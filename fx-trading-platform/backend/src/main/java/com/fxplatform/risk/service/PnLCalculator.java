package com.fxplatform.risk.service;

import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * PnLCalculator 是风险控制模块的业务服务。
 */
@Component
public class PnLCalculator {

  private static final BigDecimal CONTRACT_SIZE = new BigDecimal("100000");

  public BigDecimal floatingPnl(OrderSide side, BigDecimal lots, BigDecimal openPrice, BigDecimal currentPrice) {
    BigDecimal diff = side == OrderSide.BUY
        ? currentPrice.subtract(openPrice)
        : openPrice.subtract(currentPrice);
    return diff.multiply(lots).multiply(CONTRACT_SIZE);
  }
}
