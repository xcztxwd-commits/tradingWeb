package com.fxplatform.risk.service;

import com.fxplatform.risk.model.InstrumentKind;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * MarginCalculator calculates required margin for an order.
 */
@Component
public class MarginCalculator {

  private static final BigDecimal DEFAULT_CONTRACT_SIZE = new BigDecimal("100000");
  private final TradingAlgorithmEngine tradingAlgorithmEngine;

  public MarginCalculator() {
    this(new TradingAlgorithmEngine());
  }

  public MarginCalculator(TradingAlgorithmEngine tradingAlgorithmEngine) {
    this.tradingAlgorithmEngine = tradingAlgorithmEngine;
  }

  public BigDecimal requiredMargin(BigDecimal lots, BigDecimal price, int leverage) {
    return requiredMargin(lots, price, leverage, DEFAULT_CONTRACT_SIZE);
  }

  public BigDecimal requiredMargin(BigDecimal lots, BigDecimal price, int leverage, BigDecimal contractSize) {
    return requiredMargin(InstrumentKind.FOREX, lots, price, leverage, contractSize);
  }

  public BigDecimal requiredMargin(
      InstrumentKind kind,
      BigDecimal quantity,
      BigDecimal price,
      int leverage,
      BigDecimal unitSize
  ) {
    return tradingAlgorithmEngine.requiredMargin(kind, quantity, price, unitSize, BigDecimal.valueOf(leverage));
  }
}
