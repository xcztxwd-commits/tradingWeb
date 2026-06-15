package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * ProtectiveOrderExecutionService 是交易模块的业务服务。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading", name = "protective-order-execution-enabled", havingValue = "true")
public class ProtectiveOrderExecutionService {

  private final PositionRepository positionRepository;
  private final QuoteService quoteService;
  private final PositionService positionService;

  /**
   * 定时扫描只读取 OPEN 持仓；实际平仓仍委托 PositionService 保证结算一致性。
   */
  @Scheduled(fixedDelayString = "${trading.protective-order-scan-ms:1000}")
  public int executeProtectiveOrders() {
    int closed = 0;
    for (PositionEntity position : positionRepository.findByStatus(PositionStatus.OPEN)) {
      QuoteResponse quote;
      try {
        quote = quoteService.freshQuote(position.getSymbol());
      } catch (BusinessException ex) {
        continue;
      }
      if (shouldClose(position, quote)) {
        // 止盈止损只负责判断触发条件，真正平仓复用 PositionService 的保证金和流水逻辑。
        try {
          positionService.closeSystemPosition(position.getAccountId(), position.getId());
          closed++;
        } catch (BusinessException ex) {
          if (!"POSITION_NOT_OPEN".equals(ex.getCode())) {
            throw ex;
          }
        }
      }
    }
    return closed;
  }

  /**
   * 止盈止损只扫描 OPEN 持仓；并发重复触发交给 PositionService 的 OPEN 条件更新兜底。
   */
  private boolean shouldClose(PositionEntity position, QuoteResponse quote) {
    BigDecimal price = closingPrice(position, quote);
    return isStopLossTriggered(position, price) || isTakeProfitTriggered(position, price);
  }

  /**
   * 多单止损看 bid 下穿，空单止损看 ask 上穿。
   */
  private boolean isStopLossTriggered(PositionEntity position, BigDecimal price) {
    BigDecimal stopLoss = position.getStopLoss();
    if (stopLoss == null) {
      return false;
    }
    return position.getSide() == OrderSide.BUY
        ? price.compareTo(stopLoss) <= 0
        : price.compareTo(stopLoss) >= 0;
  }

  /**
   * 多单止盈看 bid 上穿，空单止盈看 ask 下穿。
   */
  private boolean isTakeProfitTriggered(PositionEntity position, BigDecimal price) {
    BigDecimal takeProfit = position.getTakeProfit();
    if (takeProfit == null) {
      return false;
    }
    return position.getSide() == OrderSide.BUY
        ? price.compareTo(takeProfit) >= 0
        : price.compareTo(takeProfit) <= 0;
  }

  /**
   * 保护单判断使用真实可平仓价，而不是 mid 价。
   */
  private BigDecimal closingPrice(PositionEntity position, QuoteResponse quote) {
    return position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
  }
}
