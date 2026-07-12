package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.QuantityUnit;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/** Converts the public Spot quantity contract into canonical BASE quantity. */
@Service
public class QuantityConversionService {

  private static final int WALLET_SCALE = 8;
  /** Legacy orders/trades quantity columns are NUMERIC(12,4); V46 canonical columns are wider. */
  private static final BigDecimal PERSISTED_QUANTITY_INCREMENT = new BigDecimal("0.0001");

  public Conversion convertPerpetual(
      QuantityUnit quantityUnit,
      BigDecimal originalQuantity,
      BigDecimal stepSize,
      BigDecimal contractSize,
      BigDecimal contractMultiplier,
      BigDecimal authorityMark,
      BigDecimal minNotional
  ) {
    requirePositive(originalQuantity, "BAD_QUANTITY", "Quantity must be greater than zero");
    requirePositive(stepSize, "INVALID_INSTRUMENT_RULES", "Instrument step size must be positive");
    requirePositive(authorityMark, "MARKET_BUNDLE_INCOMPLETE", "Authority mark price is required");
    if (quantityUnit == null) {
      throw new BusinessException("INVALID_QUANTITY_UNIT", "Perpetual quantity unit is required");
    }

    BigDecimal effectiveStep = storageCompatibleStep(stepSize);
    BigDecimal baseQuantity = switch (quantityUnit) {
      case BASE -> originalQuantity;
      case QUOTE -> originalQuantity.divide(authorityMark, 24, RoundingMode.DOWN)
          .divide(effectiveStep, 0, RoundingMode.DOWN)
          .multiply(effectiveStep);
      case CONTRACTS -> {
        if (originalQuantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
          throw new BusinessException(
              "CONTRACT_QUANTITY_NOT_INTEGRAL",
              "Perpetual contract quantity must be integral");
        }
        requirePositive(
            contractSize,
            "INVALID_INSTRUMENT_RULES",
            "Perpetual contract size must be positive");
        requirePositive(
            contractMultiplier,
            "INVALID_INSTRUMENT_RULES",
            "Perpetual contract multiplier must be positive");
        yield originalQuantity.multiply(contractSize).multiply(contractMultiplier);
      }
    };

    if (baseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          "QUANTITY_CONVERTS_TO_ZERO",
          "Quantity is below one effective instrument step");
    }
    if (baseQuantity.remainder(effectiveStep).compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException(
          "QUANTITY_STEP_MISMATCH",
          "Quantity cannot be represented exactly by instrument and persistence steps");
    }

    BigDecimal notional = baseQuantity.multiply(authorityMark);
    if (minNotional != null
        && minNotional.compareTo(BigDecimal.ZERO) > 0
        && notional.compareTo(minNotional) < 0) {
      throw new BusinessException(
          "ORDER_NOTIONAL_TOO_SMALL",
          "Order notional is below minimum");
    }
    return new Conversion(
        originalQuantity,
        quantityUnit,
        baseQuantity,
        notional.setScale(WALLET_SCALE, RoundingMode.HALF_UP));
  }

  public Conversion convertSpot(
      OrderSide side,
      OrderType orderType,
      QuantityUnit quantityUnit,
      BigDecimal originalQuantity,
      BigDecimal stepSize,
      FullFillPricingProjection marketPricing
  ) {
    requirePositive(originalQuantity, "BAD_QUANTITY", "Quantity must be greater than zero");
    requirePositive(stepSize, "INVALID_INSTRUMENT_RULES", "Instrument step size must be positive");
    BigDecimal effectiveStep = storageCompatibleStep(stepSize);
    if (orderType == null || side == null || quantityUnit == null || orderType == OrderType.STOP) {
      throw new BusinessException("INVALID_SPOT_ORDER_TYPE", "Unsupported P0 Spot order type");
    }

    QuantityUnit expectedUnit = orderType == OrderType.MARKET && side == OrderSide.BUY
        ? QuantityUnit.QUOTE
        : QuantityUnit.BASE;
    if (quantityUnit != expectedUnit) {
      throw new BusinessException(
          "INVALID_QUANTITY_UNIT",
          "Spot order quantity unit does not match side and order type");
    }

    if (expectedUnit == QuantityUnit.BASE) {
      if (originalQuantity.remainder(effectiveStep).compareTo(BigDecimal.ZERO) != 0) {
        throw new BusinessException(
            "QUANTITY_STEP_MISMATCH",
            "Quantity cannot be represented exactly by instrument and persistence steps");
      }
      return new Conversion(originalQuantity, quantityUnit, originalQuantity, null);
    }
    if (marketPricing == null) {
      throw new BusinessException("MARKET_BUNDLE_INCOMPLETE", "Canonical MARKET pricing is required");
    }
    requirePositive(
        marketPricing.filledPrice(),
        "MARKET_BUNDLE_INCOMPLETE",
        "Canonical MARKET execution price is required");

    BigDecimal steps = originalQuantity.divide(marketPricing.filledPrice(), 24, RoundingMode.DOWN)
        .divide(effectiveStep, 0, RoundingMode.DOWN);
    BigDecimal baseQuantity = steps.multiply(effectiveStep);
    if (baseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          "QUANTITY_CONVERTS_TO_ZERO",
          "Quote budget is below one instrument quantity step");
    }
    BigDecimal projectedSpend = baseQuantity.multiply(marketPricing.filledPrice())
        .setScale(WALLET_SCALE, RoundingMode.HALF_UP);
    while (projectedSpend.compareTo(originalQuantity) > 0) {
      baseQuantity = baseQuantity.subtract(effectiveStep);
      if (baseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException(
            "QUANTITY_CONVERTS_TO_ZERO",
            "Quote budget is below one instrument quantity step");
      }
      projectedSpend = baseQuantity.multiply(marketPricing.filledPrice())
          .setScale(WALLET_SCALE, RoundingMode.HALF_UP);
    }
    return new Conversion(originalQuantity, quantityUnit, baseQuantity, projectedSpend);
  }

  /**
   * Returns the least decimal increment that is both an instrument-step multiple and exactly
   * representable by the legacy four-decimal order/trade quantity columns.
   */
  public static BigDecimal storageCompatibleStep(BigDecimal instrumentStep) {
    requirePositive(
        instrumentStep,
        "INVALID_INSTRUMENT_RULES",
        "Instrument step size must be positive");
    int scale = Math.max(
        Math.max(instrumentStep.scale(), PERSISTED_QUANTITY_INCREMENT.scale()),
        0);
    BigInteger instrumentUnits = instrumentStep.movePointRight(scale).toBigIntegerExact().abs();
    BigInteger persistenceUnits = PERSISTED_QUANTITY_INCREMENT
        .movePointRight(scale)
        .toBigIntegerExact()
        .abs();
    BigInteger lcm = instrumentUnits.divide(instrumentUnits.gcd(persistenceUnits))
        .multiply(persistenceUnits);
    return new BigDecimal(lcm, scale).stripTrailingZeros();
  }

  private static void requirePositive(BigDecimal value, String code, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(code, message);
    }
  }

  public record Conversion(
      BigDecimal originalQuantity,
      QuantityUnit originalUnit,
      BigDecimal baseQuantity,
      BigDecimal projectedQuoteSpend
  ) {
  }
}
