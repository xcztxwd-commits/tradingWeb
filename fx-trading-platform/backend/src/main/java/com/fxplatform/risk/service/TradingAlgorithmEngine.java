package com.fxplatform.risk.service;

import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class TradingAlgorithmEngine {

  private static final int MONEY_SCALE = 8;
  private static final int PRICE_SCALE = 8;
  private static final int INTERNAL_SCALE = 18;

  public SpotBuyResult spotBuyWithQuoteBudget(BigDecimal quoteBudget, BigDecimal price, BigDecimal feeRate) {
    BigDecimal grossBase = divide(quoteBudget, price);
    BigDecimal feeBase = grossBase.multiply(feeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal netBase = grossBase.subtract(feeBase).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal averageCost = divide(quoteBudget, netBase);
    return new SpotBuyResult(grossBase, feeBase, netBase, averageCost);
  }

  public SpotSellResult spotSell(BigDecimal baseQuantity, BigDecimal price, BigDecimal feeRate, BigDecimal costBasis) {
    BigDecimal grossQuote = baseQuantity.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal feeQuote = grossQuote.multiply(feeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal netQuote = grossQuote.subtract(feeQuote).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal netPnl = netQuote.subtract(costBasis).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new SpotSellResult(grossQuote, feeQuote, netQuote, netPnl);
  }

  public BigDecimal requiredMargin(
      InstrumentKind kind,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal unitSize,
      BigDecimal leverage
  ) {
    if (kind == InstrumentKind.INVERSE_PERPETUAL) {
      return abs(quantity)
          .multiply(unitSize)
          .divide(price.multiply(leverage), MONEY_SCALE, RoundingMode.HALF_UP);
    }
    BigDecimal notional = abs(quantity).multiply(unitSize).multiply(price);
    if (kind == InstrumentKind.SPOT) {
      return notional.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return notional.divide(leverage, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal unrealizedPnl(
      InstrumentKind kind,
      OrderSide side,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal closeoutPrice,
      BigDecimal unitSize
  ) {
    if (kind == InstrumentKind.INVERSE_PERPETUAL) {
      return inversePnl(side, inverseUsdNotional(quantity, unitSize, BigDecimal.ONE), entryPrice, closeoutPrice);
    }
    BigDecimal diff = side == OrderSide.BUY
        ? closeoutPrice.subtract(entryPrice)
        : entryPrice.subtract(closeoutPrice);
    return diff.multiply(abs(quantity)).multiply(unitSize).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal liquidationPrice(
      InstrumentKind kind,
      OrderSide side,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal marginHeld,
      BigDecimal unitSize
  ) {
    if (kind == InstrumentKind.SPOT || !positive(quantity) || !positive(entryPrice) || !positive(marginHeld) || !positive(unitSize)) {
      return null;
    }
    if (kind == InstrumentKind.INVERSE_PERPETUAL) {
      return inverseLiquidationPrice(side, quantity, entryPrice, marginHeld, unitSize);
    }
    BigDecimal exposureUnits = abs(quantity).multiply(unitSize);
    if (exposureUnits.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal priceMoveToZero = marginHeld.divide(exposureUnits, PRICE_SCALE, RoundingMode.HALF_UP);
    BigDecimal price = side == OrderSide.BUY
        ? entryPrice.subtract(priceMoveToZero)
        : entryPrice.add(priceMoveToZero);
    return positive(price) ? price.setScale(PRICE_SCALE, RoundingMode.HALF_UP) : null;
  }

  public BigDecimal linearFee(BigDecimal quantity, BigDecimal price, BigDecimal feeRate, BigDecimal unitSize) {
    return abs(quantity).multiply(unitSize).multiply(price).multiply(feeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal inverseUsdNotional(BigDecimal contracts, BigDecimal contractSize, BigDecimal multiplier) {
    return abs(contracts).multiply(contractSize).multiply(multiplier).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal inversePnl(OrderSide side, BigDecimal usdNotional, BigDecimal entryPrice, BigDecimal closeoutPrice) {
    BigDecimal entryInverse = divideInternal(BigDecimal.ONE, entryPrice);
    BigDecimal closeInverse = divideInternal(BigDecimal.ONE, closeoutPrice);
    BigDecimal diff = side == OrderSide.BUY
        ? entryInverse.subtract(closeInverse)
        : closeInverse.subtract(entryInverse);
    return usdNotional.multiply(diff).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal inverseFee(BigDecimal usdNotional, BigDecimal price, BigDecimal feeRate) {
    return usdNotional.divide(price, MONEY_SCALE, RoundingMode.HALF_UP)
        .multiply(feeRate)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal netPnl(BigDecimal grossPnl, BigDecimal openFee, BigDecimal closeFee, BigDecimal funding) {
    return grossPnl
        .subtract(openFee)
        .subtract(closeFee)
        .add(funding)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public BigDecimal roi(BigDecimal netPnl, BigDecimal initialMargin) {
    if (initialMargin == null || initialMargin.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return netPnl.divide(initialMargin, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    return numerator.divide(denominator, PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal divideInternal(BigDecimal numerator, BigDecimal denominator) {
    return numerator.divide(denominator, INTERNAL_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal inverseLiquidationPrice(
      OrderSide side,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal marginHeld,
      BigDecimal unitSize
  ) {
    BigDecimal usdNotional = inverseUsdNotional(quantity, unitSize, BigDecimal.ONE);
    if (usdNotional.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal entryInverse = divideInternal(BigDecimal.ONE, entryPrice);
    BigDecimal marginPerUsd = marginHeld.divide(usdNotional, INTERNAL_SCALE, RoundingMode.HALF_UP);
    BigDecimal liquidationInverse = side == OrderSide.BUY
        ? entryInverse.add(marginPerUsd)
        : entryInverse.subtract(marginPerUsd);
    return positive(liquidationInverse)
        ? BigDecimal.ONE.divide(liquidationInverse, PRICE_SCALE, RoundingMode.HALF_UP)
        : null;
  }

  private BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  public record SpotBuyResult(
      BigDecimal grossBase,
      BigDecimal feeBase,
      BigDecimal netBase,
      BigDecimal averageCost
  ) {
  }

  public record SpotSellResult(
      BigDecimal grossQuote,
      BigDecimal feeQuote,
      BigDecimal netQuote,
      BigDecimal netPnl
  ) {
  }
}
