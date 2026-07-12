package com.fxplatform.risk.service;

import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Canonical BASE risk kernel for Demo USDT Linear Perpetual positions. */
@Service
@RequiredArgsConstructor
public class PerpetualRiskService {

  private static final int MONEY_SCALE = 8;
  private static final int INTERNAL_SCALE = 18;
  private static final BigDecimal CLOSE_TAKER_FEE_RATE = new BigDecimal("0.0005");

  private final PerpMarginCalculator marginCalculator;

  public PositionRisk positionRisk(
      OrderSide side,
      BigDecimal canonicalBaseQuantity,
      BigDecimal entryPrice,
      BigDecimal markPrice,
      int leverage,
      BigDecimal marginHeld,
      BigDecimal fundingPnl,
      BigDecimal maintenanceMarginRate
  ) {
    if (side == null) {
      throw new IllegalArgumentException("Position direction is required");
    }
    PerpMarginCalculator.LinearMarginResult margin = marginCalculator.calculateLinear(
        canonicalBaseQuantity,
        entryPrice,
        markPrice,
        leverage,
        maintenanceMarginRate);
    BigDecimal quantity = canonicalBaseQuantity.abs();
    BigDecimal priceMove = side == OrderSide.BUY
        ? markPrice.subtract(entryPrice)
        : entryPrice.subtract(markPrice);
    BigDecimal unrealizedPnl = priceMove
        .multiply(quantity)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal positionMargin = orZero(marginHeld);
    BigDecimal roiRatio = positionMargin.compareTo(BigDecimal.ZERO) > 0
        ? unrealizedPnl.divide(positionMargin, MONEY_SCALE, RoundingMode.HALF_UP)
        : null;
    BigDecimal closeFee = margin.markNotional()
        .multiply(CLOSE_TAKER_FEE_RATE)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal isolatedEquity = positionMargin
        .add(orZero(fundingPnl))
        .add(unrealizedPnl)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal liquidationThreshold = margin.maintenanceMargin()
        .add(closeFee)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal estimatedLiquidationPrice = estimatedLiquidationPrice(
        side,
        entryPrice,
        quantity,
        positionMargin.add(orZero(fundingPnl)),
        maintenanceMarginRate);
    return new PositionRisk(
        margin.entryNotional(),
        margin.markNotional(),
        margin.initialMargin(),
        margin.maintenanceMargin(),
        unrealizedPnl,
        roiRatio,
        closeFee,
        positionMargin,
        orZero(fundingPnl),
        isolatedEquity,
        liquidationThreshold,
        estimatedLiquidationPrice,
        isolatedEquity.compareTo(liquidationThreshold) <= 0);
  }

  private BigDecimal estimatedLiquidationPrice(
      OrderSide side,
      BigDecimal entryPrice,
      BigDecimal quantity,
      BigDecimal effectiveMargin,
      BigDecimal maintenanceMarginRate
  ) {
    BigDecimal marginPerBase = effectiveMargin.divide(
        quantity,
        INTERNAL_SCALE,
        RoundingMode.HALF_UP);
    BigDecimal numerator = side == OrderSide.BUY
        ? entryPrice.subtract(marginPerBase)
        : entryPrice.add(marginPerBase);
    BigDecimal denominator = side == OrderSide.BUY
        ? BigDecimal.ONE.subtract(maintenanceMarginRate).subtract(CLOSE_TAKER_FEE_RATE)
        : BigDecimal.ONE.add(maintenanceMarginRate).add(CLOSE_TAKER_FEE_RATE);
    if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Liquidation denominator must be positive");
    }
    return numerator
        .divide(denominator, MONEY_SCALE, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
  }

  public CrossRisk crossRisk(
      BigDecimal accountBalance,
      List<PoolPosition> positions,
      BigDecimal activeOrderHolds
  ) {
    BigDecimal isolatedPrincipal = BigDecimal.ZERO;
    BigDecimal crossUpl = BigDecimal.ZERO;
    BigDecimal crossPositionMargin = BigDecimal.ZERO;
    BigDecimal crossMaintenance = BigDecimal.ZERO;
    BigDecimal closeFees = BigDecimal.ZERO;
    for (PoolPosition position : positions) {
      if (position.marginMode() == MarginMode.ISOLATED) {
        isolatedPrincipal = isolatedPrincipal.add(position.risk().marginHeld());
        continue;
      }
      crossUpl = crossUpl.add(position.risk().unrealizedPnl());
      crossPositionMargin = crossPositionMargin.add(position.risk().marginHeld());
      crossMaintenance = crossMaintenance.add(position.risk().maintenanceMargin());
      closeFees = closeFees.add(position.risk().estimatedCloseTakerFee());
    }
    BigDecimal equity = orZero(accountBalance)
        .subtract(isolatedPrincipal)
        .add(crossUpl)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal maintenance = crossMaintenance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal estimatedCloseFees = closeFees.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal available = equity
        .subtract(crossPositionMargin)
        .subtract(orZero(activeOrderHolds))
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new CrossRisk(
        equity,
        maintenance,
        estimatedCloseFees,
        available,
        equity.compareTo(maintenance.add(estimatedCloseFees)) <= 0);
  }

  /**
   * Solves the shared Cross liquidation boundary for every leg of one symbol.
   * The fixed terms contain account balance less Isolated principal plus the UPL and
   * maintenance/close-fee threshold of all other Cross symbols at their cached marks.
   */
  public BigDecimal estimatedCrossLiquidationPrice(
      BigDecimal fixedCrossEquity,
      BigDecimal fixedMaintenanceAndCloseFees,
      List<CrossLiquidationLeg> symbolLegs
  ) {
    if (symbolLegs == null || symbolLegs.isEmpty()) {
      return null;
    }
    BigDecimal fixedThreshold = orZero(fixedMaintenanceAndCloseFees);
    if (fixedThreshold.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("Fixed Cross threshold cannot be negative");
    }

    BigDecimal equityAtZero = orZero(fixedCrossEquity);
    BigDecimal equitySlope = BigDecimal.ZERO;
    BigDecimal thresholdSlope = BigDecimal.ZERO;
    for (CrossLiquidationLeg leg : symbolLegs) {
      validateCrossLiquidationLeg(leg);
      BigDecimal quantity = leg.quantity();
      BigDecimal entryNotional = quantity.multiply(leg.entryPrice());
      if (leg.side() == OrderSide.BUY) {
        equityAtZero = equityAtZero.subtract(entryNotional);
        equitySlope = equitySlope.add(quantity);
      } else {
        equityAtZero = equityAtZero.add(entryNotional);
        equitySlope = equitySlope.subtract(quantity);
      }
      thresholdSlope = thresholdSlope.add(quantity.multiply(
          leg.maintenanceMarginRate().add(CLOSE_TAKER_FEE_RATE)));
    }

    BigDecimal gapAtZero = equityAtZero.subtract(fixedThreshold);
    BigDecimal gapSlope = equitySlope.subtract(thresholdSlope);
    if (gapSlope.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    return gapSlope.compareTo(BigDecimal.ZERO) > 0
        ? estimatedCrossLongLiquidationPrice(gapAtZero, gapSlope)
        : estimatedCrossShortLiquidationPrice(gapAtZero, gapSlope.negate());
  }

  /** Reverse-solves a boundary whose Cross equity-minus-threshold gap rises with price. */
  public BigDecimal estimatedCrossLongLiquidationPrice(
      BigDecimal gapAtZero,
      BigDecimal positiveGapSlope
  ) {
    if (positiveGapSlope == null || positiveGapSlope.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    if (positiveGapSlope.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("Long Cross gap slope must be positive");
    }
    return nonNegativePrice(orZero(gapAtZero).negate().divide(
        positiveGapSlope,
        INTERNAL_SCALE,
        RoundingMode.HALF_UP));
  }

  /** Reverse-solves a boundary whose Cross equity-minus-threshold gap falls with price. */
  public BigDecimal estimatedCrossShortLiquidationPrice(
      BigDecimal gapAtZero,
      BigDecimal positiveAdverseGapSlope
  ) {
    if (positiveAdverseGapSlope == null
        || positiveAdverseGapSlope.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    if (positiveAdverseGapSlope.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("Short Cross adverse gap slope must be positive");
    }
    return nonNegativePrice(orZero(gapAtZero).divide(
        positiveAdverseGapSlope,
        INTERNAL_SCALE,
        RoundingMode.HALF_UP));
  }

  private void validateCrossLiquidationLeg(CrossLiquidationLeg leg) {
    if (leg == null
        || leg.side() == null
        || leg.quantity() == null
        || leg.quantity().compareTo(BigDecimal.ZERO) <= 0
        || leg.entryPrice() == null
        || leg.entryPrice().compareTo(BigDecimal.ZERO) <= 0
        || leg.maintenanceMarginRate() == null
        || leg.maintenanceMarginRate().compareTo(BigDecimal.ZERO) < 0
        || leg.maintenanceMarginRate().compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException("Cross liquidation leg is invalid");
    }
  }

  private BigDecimal nonNegativePrice(BigDecimal price) {
    return price.max(BigDecimal.ZERO)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  public record PoolPosition(MarginMode marginMode, PositionRisk risk) {
  }

  public record CrossLiquidationLeg(
      OrderSide side,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal maintenanceMarginRate
  ) {
  }

  public record PositionRisk(
      BigDecimal entryNotional,
      BigDecimal markNotional,
      BigDecimal initialMargin,
      BigDecimal maintenanceMargin,
      BigDecimal unrealizedPnl,
      BigDecimal roiRatio,
      BigDecimal estimatedCloseTakerFee,
      BigDecimal marginHeld,
      BigDecimal fundingPnl,
      BigDecimal isolatedEquity,
      BigDecimal liquidationThreshold,
      BigDecimal estimatedLiquidationPrice,
      boolean liquidatable
  ) {
  }

  public record CrossRisk(
      BigDecimal crossEquity,
      BigDecimal crossMaintenance,
      BigDecimal estimatedCloseTakerFees,
      BigDecimal crossAvailable,
      boolean liquidatable
  ) {
  }
}
