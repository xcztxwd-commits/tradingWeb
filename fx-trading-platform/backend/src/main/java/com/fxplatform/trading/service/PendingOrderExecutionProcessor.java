package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Executes one already-resolved pending Spot candidate through the existing REQUIRES_NEW executor. */
@Service
@RequiredArgsConstructor
public class PendingOrderExecutionProcessor {

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  private final WalletService walletService;
  private final SpotPositionService spotPositionService;
  private final FullFillCoordinator fullFillCoordinator;
  private final OrderFillService orderFillService;
  private final OrderEventService orderEventService;
  private final TradingTransactionExecutor transactionExecutor;

  public boolean process(OrderEntity candidate, ExecutableMarketSnapshot snapshot) {
    if (candidate == null || snapshot == null) {
      return false;
    }
    return transactionExecutor.execute(() -> processLocked(candidate, snapshot));
  }

  private boolean processLocked(OrderEntity candidate, ExecutableMarketSnapshot snapshot) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(candidate.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    if (account.getUserId() == null
        || candidate.getUserId() == null
        || !account.getUserId().equals(candidate.getUserId())) {
      throw new com.fxplatform.common.exception.AuthorizationException(
          "ACCOUNT_NOT_FOUND",
          "Account not found");
    }
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, candidate.getSymbol());
    walletBalanceRepository.findByAccountIdForUpdate(account.getId());
    spotPositionService.lockExisting(account.getId());

    List<OrderEntity> group = candidate.getContingencyGroupId() == null
        ? List.of(orderRepository.findByIdForUpdate(candidate.getId())
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found")))
        : requireGroup(orderRepository.findByContingencyGroupIdForUpdate(
            candidate.getContingencyGroupId()));
    OrderEntity winner = group.stream()
        .filter(order -> order.getId().equals(candidate.getId()))
        .findFirst()
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pending order not found in locked group"));
    if (!account.getId().equals(winner.getAccountId())
        || !account.getUserId().equals(winner.getUserId())
        || !candidate.getUserId().equals(winner.getUserId())
        || !candidate.getAccountId().equals(winner.getAccountId())
        || !java.util.Objects.equals(candidate.getContingencyGroupId(), winner.getContingencyGroupId())
        || !com.fxplatform.common.market.SymbolNormalizer.normalize(candidate.getSymbol())
            .equals(com.fxplatform.common.market.SymbolNormalizer.normalize(winner.getSymbol()))) {
      throw new BusinessException(
          "OCO_GROUP_INCOMPLETE",
          "Locked OCO group does not match the scanned candidate");
    }
    if (winner.getStatus() != OrderStatus.PENDING || !triggered(winner, snapshot)) {
      return false;
    }
    OrderEntity peer = group.size() == 2
        ? group.stream().filter(order -> !order.getId().equals(winner.getId())).findFirst().orElseThrow()
        : null;
    if (peer != null && peer.getStatus() != OrderStatus.PENDING) {
      return false;
    }
    OrderEntity owner = peer == null ? winner : holdOwner(group);
    BigDecimal baseQuantity = canonicalQuantity(winner);
    FullFillExecutionPath path = winner.getOrderType() == OrderType.LIMIT
        ? FullFillExecutionPath.RESTING_LIMIT
        : FullFillExecutionPath.TRIGGERED_STOP_MARKET;
    FullFillResult fill = fullFillCoordinator.execute(
        new FullFillRequest(
            executionIntent(winner, baseQuantity),
            winner.getSymbol(),
            ProductType.CRYPTO_SPOT,
            winner.getSide(),
            path,
            baseQuantity,
            path == FullFillExecutionPath.RESTING_LIMIT ? currentPrice(winner) : null),
        snapshot);

    fullFillCoordinator.requireFresh(fill);
    if (orderRepository.claimPending(winner.getId()) != 1) {
      return false;
    }
    winner.setStatus(OrderStatus.WORKING);
    if (peer != null) {
      peer.setStatus(OrderStatus.CANCELED);
      peer.setCanceledAt(Instant.now());
      peer.setRemainingQuantity(BigDecimal.ZERO);
      orderRepository.save(peer);
      orderEventService.record(
          peer.getId(), "ORDER_CANCELED", OrderStatus.PENDING, OrderStatus.CANCELED,
          null, "OCO peer canceled by winning leg");
    }
    BigDecimal held = owner.getHoldAmount() == null ? BigDecimal.ZERO : owner.getHoldAmount();
    orderFillService.fill(
        winner,
        owner,
        account,
        fill,
        held,
        peer == null ? "Pending order hold" : "Pending OCO order hold");
    orderEventService.record(
        winner.getId(), "ORDER_FILLED", OrderStatus.WORKING, OrderStatus.FILLED,
        null, peer == null ? "Pending order filled" : "OCO winning leg filled");
    return true;
  }

  private boolean triggered(OrderEntity order, ExecutableMarketSnapshot snapshot) {
    if (order.getOrderType() == OrderType.LIMIT) {
      BigDecimal price = currentPrice(order);
      return price != null && (order.getSide() == OrderSide.BUY
          ? snapshot.ask().compareTo(price) <= 0
          : snapshot.bid().compareTo(price) >= 0);
    }
    if (order.getOrderType() == OrderType.STOP_MARKET) {
      BigDecimal trigger = order.getTriggerPrice();
      return trigger != null && (order.getSide() == OrderSide.BUY
          ? snapshot.last().compareTo(trigger) >= 0
          : snapshot.last().compareTo(trigger) <= 0);
    }
    return false;
  }

  private CreateOrderRequest executionIntent(OrderEntity order, BigDecimal baseQuantity) {
    return new CreateOrderRequest(
        order.getAccountId(), order.getSymbol(), order.getSide(), order.getOrderType(),
        baseQuantity, currentPrice(order), null, null,
        order.getIdempotencyKey(), order.getClientOrderId(), baseQuantity, currentPrice(order),
        1, PositionSide.BOTH, QuantityUnit.BASE, MarginMode.CASH,
        order.getTriggerPrice(), order.getOrderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE : null, false, List.of());
  }

  private List<OrderEntity> requireGroup(List<OrderEntity> group) {
    return OcoGroupValidator.requireValid(group);
  }

  private OrderEntity holdOwner(List<OrderEntity> group) {
    UUID ownerId = group.getFirst().getHoldOwnerOrderId();
    if (ownerId == null || group.stream().anyMatch(order -> !ownerId.equals(order.getHoldOwnerOrderId()))) {
      throw new BusinessException("OCO_GROUP_INCOMPLETE", "OCO hold owner is inconsistent");
    }
    return group.stream().filter(order -> ownerId.equals(order.getId())).findFirst()
        .orElseThrow(() -> new BusinessException("OCO_GROUP_INCOMPLETE", "OCO hold owner is missing"));
  }

  private BigDecimal canonicalQuantity(OrderEntity order) {
    BigDecimal baseQuantity = order.getBaseQuantity();
    if (baseQuantity == null || baseQuantity.signum() <= 0) {
      throw new BusinessException(
          "BAD_QUANTITY",
          "Pending P0 Spot order requires a positive canonical base quantity");
    }
    return baseQuantity;
  }

  private BigDecimal currentPrice(OrderEntity order) {
    return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
  }
}
