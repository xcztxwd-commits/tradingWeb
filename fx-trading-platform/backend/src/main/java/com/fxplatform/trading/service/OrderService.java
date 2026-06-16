package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@Service
@RequiredArgsConstructor
public class OrderService {

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final RiskCheckService riskCheckService;
  private final ExecutionAdapter executionAdapter;
  private final OrderFillService orderFillService;
  private final LedgerService ledgerService;
  private final WalletService walletService;
  private final OrderEventService orderEventService;
  private final OrderCommandFactory orderCommandFactory;
  private final OrderEntityFactory orderEntityFactory;
  private final OrderResponseMapper orderResponseMapper;
  private final OrderStatusPolicy orderStatusPolicy;

  @Transactional
  public OrderResponse createOrder(UserPrincipal principal, CreateOrderRequest request) {
    OrderCommand command = orderCommandFactory.from(principal, request);
    return findExistingOrder(command)
        .map(orderResponseMapper::toResponse)
        .orElseGet(() -> createNewOrderOrReturnExisting(command));
  }

  public List<OrderResponse> orders(UserPrincipal principal) {
    return orderRepository.findByUserIdOrderByCreatedAtDesc(principal.id())
        .stream()
        .map(orderResponseMapper::toResponse)
        .toList();
  }

  public List<OrderEventResponse> orderEvents(UserPrincipal principal, UUID orderId) {
    requireOwnedOrder(principal.id(), orderId);
    return orderEventService.events(orderId);
  }

  @Transactional
  public OrderResponse cancelOrder(UserPrincipal principal, UUID orderId) {
    OrderEntity order = requireOwnedOrder(principal.id(), orderId);
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Only pending orders can be canceled");
    }

    TradingAccountEntity account = requireOwnedAccount(principal.id(), order.getAccountId());
    BigDecimal holdAmount = orZero(order.getHoldAmount());

    order.setStatus(OrderStatus.CANCELED);
    order.setCanceledAt(Instant.now());
    order.setRemainingQuantity(BigDecimal.ZERO);
    if (orderRepository.cancelPending(order) != 1) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Only pending orders can be canceled");
    }
    if (holdAmount.compareTo(BigDecimal.ZERO) > 0) {
      releaseOrderHold(account, order, holdAmount, "Pending order canceled", "Pending spot order canceled");
      order.setHoldAmount(BigDecimal.ZERO);
    }
    orderEventService.record(
        order.getId(),
        "ORDER_CANCELED",
        OrderStatus.PENDING,
        OrderStatus.CANCELED,
        null,
        "Pending order canceled");
    return orderResponseMapper.toResponse(order);
  }

  @Transactional
  public OrderResponse modifyOrder(UserPrincipal principal, UUID orderId, UpdateOrderRequest update) {
    OrderEntity order = requireOwnedOrder(principal.id(), orderId);
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_MODIFIABLE", "Only pending orders can be modified");
    }

    BigDecimal quantity = update.quantity() != null ? update.quantity() : currentQuantity(order);
    BigDecimal price = update.price() != null ? update.price() : currentPrice(order);
    if (price == null) {
      throw new BusinessException("ORDER_PRICE_REQUIRED", "Limit and stop orders require requested price");
    }

    TradingAccountEntity account = requireOwnedAccount(principal.id(), order.getAccountId());
    BigDecimal oldHold = orZero(order.getHoldAmount());
    boolean spotWalletHold = isSpotWalletHold(order);
    CreateOrderRequest riskRequest = new CreateOrderRequest(
        order.getAccountId(),
        order.getSymbol(),
        order.getSide(),
        order.getOrderType(),
        quantity,
        price,
        update.stopLoss() != null ? update.stopLoss() : order.getStopLoss(),
        update.takeProfit() != null ? update.takeProfit() : order.getTakeProfit(),
        order.getIdempotencyKey(),
        order.getClientOrderId(),
        quantity,
        price,
        order.getLeverage());
    BigDecimal newHold = riskCheckService.checkOrder(accountForMarginCheck(account, oldHold), riskRequest);
    String holdCurrency = spotWalletHold
        ? riskCheckService.resolveHoldCurrency(account, riskRequest)
        : account.getBaseCurrency();
    BigDecimal delta = newHold.subtract(oldHold);
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      reserveOrderHold(account, delta, holdCurrency, spotWalletHold, order.getId(),
          "Pending order margin increased", "Pending spot order wallet increased");
    } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
      releaseOrderHold(account, order, delta.abs(),
          "Pending order margin decreased", "Pending spot order wallet decreased");
    }

    order.setLots(quantity);
    order.setQuantity(quantity);
    order.setRequestedPrice(price);
    order.setPrice(price);
    order.setStopLoss(update.stopLoss() != null ? update.stopLoss() : order.getStopLoss());
    order.setTakeProfit(update.takeProfit() != null ? update.takeProfit() : order.getTakeProfit());
    order.setRemainingQuantity(quantity.subtract(orZero(order.getFilledQuantity())));
    order.setHoldAmount(newHold);
    order.setHoldCurrency(holdCurrency);
    orderRepository.save(order);
    orderEventService.record(
        order.getId(),
        "ORDER_MODIFIED",
        OrderStatus.PENDING,
        OrderStatus.PENDING,
        null,
        "Pending order modified");
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse createNewOrder(OrderCommand command) {
    CreateOrderRequest request = command.toRequest();
    TradingAccountEntity account = accountRepository.findByIdAndUserId(command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));

    if (command.orderType() != OrderType.MARKET && command.price() == null) {
      throw new BusinessException("ORDER_PRICE_REQUIRED", "Limit and stop orders require requested price");
    }

    BigDecimal requiredMargin = riskCheckService.checkOrder(account, request);
    int effectiveLeverage = riskCheckService.resolveEffectiveLeverage(account, request);
    boolean spotWalletHold = riskCheckService.isSpotSymbol(command.symbol());
    String holdCurrency = spotWalletHold
        ? riskCheckService.resolveHoldCurrency(account, request)
        : account.getBaseCurrency();
    OrderEntity order = orderEntityFactory.createReceived(command);
    order.setLeverage(effectiveLeverage);

    if (command.orderType() != OrderType.MARKET) {
      order.setStatus(orderStatusPolicy.acceptedStatus(command.orderType()));
      order.setFilledQuantity(BigDecimal.ZERO);
      order.setRemainingQuantity(command.quantity());
      order.setHoldAmount(requiredMargin);
      order.setHoldCurrency(holdCurrency);
      orderRepository.save(order);
      reserveOrderHold(account, requiredMargin, holdCurrency, spotWalletHold, order.getId(),
          "Pending order margin reserved", "Pending spot order wallet locked");
      orderEventService.record(
          order.getId(),
          "ORDER_PENDING",
          OrderStatus.ACCEPTED,
          OrderStatus.PENDING,
          null,
          "Pending order accepted and waiting");
      return orderResponseMapper.toResponse(order);
    }

    order.setStatus(orderStatusPolicy.acceptedStatus(command.orderType()));
    orderRepository.save(order);
    ExecutionResult execution = executionAdapter.execute(request);
    if (execution.rejected()) {
      return rejectOrder(order, execution);
    }

    orderFillService.fill(order, account, execution, requiredMargin, "Market order margin hold");
    orderEventService.record(
        order.getId(),
        order.getStatus() == OrderStatus.PARTIALLY_FILLED ? "ORDER_PARTIALLY_FILLED" : "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        order.getStatus(),
        null,
        order.getStatus() == OrderStatus.PARTIALLY_FILLED ? "Market order partially filled" : "Market order filled");
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse createNewOrderOrReturnExisting(OrderCommand command) {
    try {
      return createNewOrder(command);
    } catch (DataIntegrityViolationException ex) {
      return findExistingOrder(command)
          .map(orderResponseMapper::toResponse)
          .orElseThrow(() -> ex);
    }
  }

  private java.util.Optional<OrderEntity> findExistingOrder(OrderCommand command) {
    return orderRepository.findByUserIdAndAccountIdAndClientOrderId(
            command.userId(), command.accountId(), command.clientOrderId())
        .or(() -> orderRepository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()));
  }

  private OrderResponse rejectOrder(OrderEntity order, ExecutionResult execution) {
    order.setStatus(OrderStatus.REJECTED);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(currentQuantity(order));
    order.setRejectCode(execution.rejectCode());
    order.setRejectMessage(execution.rejectMessage());
    order.setFee(orZero(execution.fee()));
    order.setSlippage(orZero(execution.slippage()));
    orderRepository.save(order);
    orderEventService.record(
        order.getId(),
        "ORDER_REJECTED",
        OrderStatus.ACCEPTED,
        OrderStatus.REJECTED,
        execution.rejectCode(),
        execution.rejectMessage());
    return orderResponseMapper.toResponse(order);
  }

  private OrderEntity requireOwnedOrder(UUID userId, UUID orderId) {
    return orderRepository.findByUserIdAndId(userId, orderId)
        .orElseThrow(() -> new AuthorizationException("ORDER_NOT_FOUND", "Order not found"));
  }

  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  private void reserveOrderHold(
      TradingAccountEntity account,
      BigDecimal amount,
      String holdCurrency,
      boolean spotWalletHold,
      UUID orderId,
      String marginDescription,
      String spotDescription
  ) {
    if (spotWalletHold) {
      walletService.lockAvailableWithEntryType(
          account.getId(),
          holdCurrency,
          amount,
          "ORDER",
          orderId,
          spotDescription,
          "SPOT_ORDER_LOCK");
      return;
    }
    if (accountRepository.reserveMarginIfAvailable(account.getId(), amount) != 1) {
      throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).add(amount));
    account.setFreeMargin(accountEquity(account).subtract(account.getUsedMargin()));
    ledgerService.recordOrderHold(account, amount, orderId, marginDescription);
  }

  private void releaseOrderHold(
      TradingAccountEntity account,
      OrderEntity order,
      BigDecimal amount,
      String marginDescription,
      String spotDescription
  ) {
    if (isSpotWalletHold(order)) {
      walletService.releaseLockedWithEntryType(
          account.getId(),
          order.getHoldCurrency(),
          amount,
          "ORDER",
          order.getId(),
          spotDescription,
          "SPOT_ORDER_RELEASE");
      return;
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).subtract(amount).max(BigDecimal.ZERO));
    account.setFreeMargin(accountEquity(account).subtract(account.getUsedMargin()));
    accountRepository.save(account);
    ledgerService.recordOrderRelease(account, amount, order.getId(), marginDescription);
  }

  private boolean isSpotWalletHold(OrderEntity order) {
    return riskCheckService.isSpotSymbol(order.getSymbol());
  }

  private TradingAccountEntity accountForMarginCheck(TradingAccountEntity account, BigDecimal existingHold) {
    TradingAccountEntity copy = new TradingAccountEntity();
    copy.setId(account.getId());
    copy.setUserId(account.getUserId());
    copy.setBaseCurrency(account.getBaseCurrency());
    copy.setBalance(account.getBalance());
    copy.setEquity(accountEquity(account));
    copy.setUsedMargin(orZero(account.getUsedMargin()).subtract(existingHold).max(BigDecimal.ZERO));
    copy.setFreeMargin(orZero(account.getFreeMargin()).add(existingHold));
    copy.setLeverage(account.getLeverage());
    copy.setAccountType(account.getAccountType());
    copy.setStatus(account.getStatus());
    return copy;
  }

  private BigDecimal currentQuantity(OrderEntity order) {
    return order.getQuantity() != null ? order.getQuantity() : order.getLots();
  }

  private BigDecimal currentPrice(OrderEntity order) {
    return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
  }

}
