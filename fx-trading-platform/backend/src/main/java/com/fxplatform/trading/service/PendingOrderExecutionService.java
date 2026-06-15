package com.fxplatform.trading.service;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * 定时扫描仅处理仍处于 PENDING 的挂单；每笔订单在 tryExecute 内再次抢占状态。
   */
  @Scheduled(fixedDelayString = "${trading.pending-order-scan-ms:1000}")
  @Transactional
  public int executePendingOrders() {
    int filled = 0;
    for (OrderEntity order : orderRepository.findByStatus(OrderStatus.PENDING)) {
      if (tryExecute(order)) {
        filled++;
      }
    }
    return filled;
  }

  /**
   * 挂单触价后先抢占 PENDING 状态，只有抢占成功的实例可以继续成交。
   */
  private boolean tryExecute(OrderEntity order) {
    if (order.getRequestedPrice() == null) {
      return false;
    }

    QuoteResponse quote;
    try {
      quote = quoteService.freshQuote(order.getSymbol());
    } catch (BusinessException ex) {
      return false;
    }
    if (!isTriggered(order, quote)) {
      return false;
    }
    if (orderRepository.claimPending(order.getId()) != 1) {
      return false;
    }
    order.setStatus(OrderStatus.WORKING);

    TradingAccountEntity account = accountRepository.findById(order.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    CreateOrderRequest request = toRequest(order);
    BigDecimal requiredMargin = order.getHoldAmount() != null
        ? order.getHoldAmount()
        : riskCheckService.checkOrder(account, request);
    BigDecimal executionPrice = executablePrice(order, quote);

    // 挂单触价后复用统一成交写入路径，保证订单、成交、仓位、保证金流水一致。
    orderFillService.fill(order, account, executionPrice, DateUtil.date().toInstant(), requiredMargin, "Pending order margin hold");
    orderEventService.record(
        order.getId(),
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
}
