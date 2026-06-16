package com.fxplatform.risk.service;

import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PnLCalculator 是风险控制模块的业务服务。
 */
@Component
public class PnLCalculator {

  private static final BigDecimal CONTRACT_SIZE = new BigDecimal("100000");
  private final TradingAlgorithmEngine tradingAlgorithmEngine;
  private final ForexConversionService forexConversionService;

  public PnLCalculator() {
    this(new TradingAlgorithmEngine(), new ForexConversionService());
  }

  public PnLCalculator(TradingAlgorithmEngine tradingAlgorithmEngine) {
    this(tradingAlgorithmEngine, new ForexConversionService());
  }

  @Autowired
  public PnLCalculator(
      TradingAlgorithmEngine tradingAlgorithmEngine,
      ForexConversionService forexConversionService
  ) {
    this.tradingAlgorithmEngine = tradingAlgorithmEngine;
    this.forexConversionService = forexConversionService;
  }

  public BigDecimal floatingPnl(OrderSide side, BigDecimal lots, BigDecimal openPrice, BigDecimal currentPrice) {
    return floatingPnl(InstrumentKind.FOREX, side, lots, openPrice, currentPrice, CONTRACT_SIZE);
  }

  public BigDecimal floatingPnl(
      String symbol,
      String accountCurrency,
      OrderSide side,
      BigDecimal lots,
      BigDecimal openPrice,
      BigDecimal currentPrice
  ) {
    BigDecimal grossPnlQuote = floatingPnl(side, lots, openPrice, currentPrice);
    return forexConversionService.convert(grossPnlQuote, quoteCurrency(symbol), accountCurrency);
  }

  public BigDecimal floatingPnl(
      InstrumentKind kind,
      OrderSide side,
      BigDecimal quantity,
      BigDecimal openPrice,
      BigDecimal currentPrice,
      BigDecimal unitSize
  ) {
    return tradingAlgorithmEngine.unrealizedPnl(kind, side, quantity, openPrice, currentPrice, unitSize);
  }

  private String quoteCurrency(String symbol) {
    String normalized = symbol == null ? "" : symbol.trim().toUpperCase();
    if (normalized.length() == 6) {
      return normalized.substring(3);
    }
    if (normalized.endsWith("USDT")) {
      return "USDT";
    }
    if (normalized.endsWith("USDC")) {
      return "USDC";
    }
    if (normalized.endsWith("USD")) {
      return "USD";
    }
    return "";
  }
}
