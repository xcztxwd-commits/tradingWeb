package com.fxplatform.trading.service;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * PendingOrderExecutionService 是交易模块的业务服务。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading", name = "pending-order-execution-enabled", havingValue = "true")
public class PendingOrderExecutionService {

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final QuoteService quoteService;
  private final RiskCheckService riskCheckService;
  private final OrderFillService orderFillService;
  private final OrderEventService orderEventService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  private final WalletService walletService;
  private final SpotPositionService spotPositionService;
  private final PositionRepository positionRepository;
  private final TradingTransactionExecutor transactionExecutor;

  /**
   * 定时扫描仅处理仍处于 PENDING 的挂单；每笔订单在 tryExecute 内再次抢占状态。
   */
  @Scheduled(fixedDelayString = "${trading.pending-order-scan-ms:1000}")
  public int executePendingOrders() {
    List<PendingCandidate> candidates = new ArrayList<>();
    for (OrderEntity order : orderRepository.findByStatus(OrderStatus.PENDING)) {
      if (order.getRequestedPrice() == null) {
        continue;
      }
      try {
        QuoteResponse quote = quoteService.freshQuote(order.getSymbol());
        if (isTriggered(order, quote)) {
          ProductType productType = requestedProduct(order.getSymbol());
          TradingAccountEntity accountSnapshot = accountRepository.findById(order.getAccountId())
              .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
          demoExecutionGuard.requireDemo(accountSnapshot, productType, order.getSymbol());
          CreateOrderRequest request = toRequest(order);
          BigDecimal requiredMargin = order.getHoldAmount() != null
              ? order.getHoldAmount()
              : riskCheckService.checkOrder(accountSnapshot, request);
          candidates.add(new PendingCandidate(
              order,
              quote,
              productType,
              requiredMargin,
              executablePrice(order, quote)));
        }
      } catch (BusinessException ignored) {
        // A stale or unavailable public quote leaves the order pending for the next scan.
      }
    }

    int filled = 0;
    for (PendingCandidate candidate : candidates) {
      if (transactionExecutor.execute(() -> tryExecute(candidate))) {
        filled++;
      }
    }
    return filled;
  }

  /**
   * 挂单触价后先抢占 PENDING 状态，只有抢占成功的实例可以继续成交。
   */
  private boolean tryExecute(PendingCandidate candidate) {
    OrderEntity order = candidate.order();
    TradingAccountEntity account = accountRepository.findByIdForUpdate(order.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, candidate.productType(), order.getSymbol());
    lockMutationState(account.getId(), candidate.productType(), order.getSymbol());
    OrderEntity lockedOrder = orderRepository.findByIdForUpdate(order.getId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    if (lockedOrder.getStatus() != OrderStatus.PENDING || !isTriggered(lockedOrder, candidate.quote())) {
      return false;
    }
    if (orderRepository.claimPending(lockedOrder.getId()) != 1) {
      return false;
    }
    lockedOrder.setStatus(OrderStatus.WORKING);

    // 挂单触价后复用统一成交写入路径，保证订单、成交、仓位、保证金流水一致。
    orderFillService.fill(
        lockedOrder,
        account,
        candidate.executionPrice(),
        DateUtil.date().toInstant(),
        candidate.requiredMargin(),
        "Pending order margin hold");
    orderEventService.record(
        lockedOrder.getId(),
        "ORDER_FILLED",
        OrderStatus.WORKING,
        OrderStatus.FILLED,
        null,
        "Pending order filled");
    return true;
  }

  /**
   * LIMIT 和 STOP 的触价方向相反，这里只判断价格条件，不修改订单状态。
   */
  private boolean isTriggered(OrderEntity order, QuoteResponse quote) {
    BigDecimal price = executablePrice(order, quote);
    BigDecimal requestedPrice = order.getRequestedPrice();

    if (order.getOrderType() == OrderType.LIMIT) {
      return order.getSide() == OrderSide.BUY
          ? price.compareTo(requestedPrice) <= 0
          : price.compareTo(requestedPrice) >= 0;
    }
    if (order.getOrderType() == OrderType.STOP) {
      return order.getSide() == OrderSide.BUY
          ? price.compareTo(requestedPrice) >= 0
          : price.compareTo(requestedPrice) <= 0;
    }
    return false;
  }

  /**
   * 买单按卖价成交、卖单按买价成交，保持与即时单成交口径一致。
   */
  private BigDecimal executablePrice(OrderEntity order, QuoteResponse quote) {
    return order.getSide() == OrderSide.BUY ? quote.ask() : quote.bid();
  }

  /**
   * 将已接收的挂单字段还原为风控请求，复用统一保证金校验。
   */
  private CreateOrderRequest toRequest(OrderEntity order) {
    return new CreateOrderRequest(
        order.getAccountId(),
        order.getSymbol(),
        order.getSide(),
        order.getOrderType(),
        order.getLots(),
        order.getRequestedPrice(),
        order.getStopLoss(),
        order.getTakeProfit(),
        order.getIdempotencyKey(),
        order.getClientOrderId(),
        order.getQuantity(),
        order.getPrice(),
        order.getLeverage());
  }

  private ProductType requestedProduct(String canonicalSymbol) {
    return canonicalSymbol.endsWith("-PERP") ? ProductType.LINEAR_PERP : ProductType.CRYPTO_SPOT;
  }

  private void lockMutationState(UUID accountId, ProductType productType, String canonicalSymbol) {
    if (productType == ProductType.CRYPTO_SPOT) {
      if (canonicalSymbol != null && canonicalSymbol.endsWith("USDT") && canonicalSymbol.length() > 4) {
        String baseAsset = canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
        walletService.lockBalancesInOrder(
            accountId,
            List.of(baseAsset, "USDT"));
        spotPositionService.lockOrCreate(accountId, baseAsset, "USDT");
      } else {
        walletBalanceRepository.findByAccountIdForUpdate(accountId);
      }
      return;
    }
    positionRepository.findOpenByAccountIdForUpdate(accountId);
  }

  private record PendingCandidate(
      OrderEntity order,
      QuoteResponse quote,
      ProductType productType,
      BigDecimal requiredMargin,
      BigDecimal executionPrice) {
  }
}
