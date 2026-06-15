package com.fxplatform.risk.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * MarginCalculator calculates required margin for an order.
 */
@Component
public class MarginCalculator {

  private static final BigDecimal DEFAULT_CONTRACT_SIZE = new BigDecimal("100000");

  public BigDecimal requiredMargin(BigDecimal lots, BigDecimal price, int leverage) {
    return requiredMargin(lots, price, leverage, DEFAULT_CONTRACT_SIZE);
  }

  public BigDecimal requiredMargin(BigDecimal lots, BigDecimal price, int leverage, BigDecimal contractSize) {
    return lots.multiply(contractSize)
        .multiply(price)
        .divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
  }
}
