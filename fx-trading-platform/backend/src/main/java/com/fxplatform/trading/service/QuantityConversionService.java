package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.money.ExactNumeric;
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
  private static final int PUBLIC_QUANTITY_PRECISION = 24;
  private static final int PUBLIC_QUANTITY_SCALE = 8;
  private static final int LEGACY_QUANTITY_PRECISION = 12;
  private static final int LEGACY_QUANTITY_SCALE = 4;
  private static final int PRICE_PRECISION = 24;
  private static final int PRICE_SCALE = 10;
  private static final int INSTRUMENT_VALUE_PRECISION = 24;
  private static final int INSTRUMENT_VALUE_SCALE = 8;
  private static final int RATE_PRECISION = 18;
  private static final int RATE_SCALE = 10;
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
    requirePublicQuantity(originalQuantity, quantityUnit);
    requirePositive(originalQuantity, "BAD_QUANTITY", "Quantity must be greater than zero");
    requireNumeric(
        stepSize,
        INSTRUMENT_VALUE_PRECISION,
        INSTRUMENT_VALUE_SCALE,
        "INVALID_INSTRUMENT_RULES",
        "Instrument step size exceeds the supported numeric range");
    requirePositive(stepSize, "INVALID_INSTRUMENT_RULES", "Instrument step size must be positive");
    requireNumeric(
        authorityMark,
        PRICE_PRECISION,
        PRICE_SCALE,
        ErrorCode.MARKET_BUNDLE_INCOMPLETE,
        "Authority mark price exceeds the supported numeric range");
    requirePositive(authorityMark, "MARKET_BUNDLE_INCOMPLETE", "Authority mark price is required");
    if (quantityUnit == null) {
      throw new BusinessException("INVALID_QUANTITY_UNIT", "Perpetual quantity unit is required");
    }
    requireOptionalNumeric(
        minNotional,
        INSTRUMENT_VALUE_PRECISION,
        INSTRUMENT_VALUE_SCALE,
        "INVALID_INSTRUMENT_RULES",
        "Minimum notional exceeds the supported numeric range");
    if (quantityUnit == QuantityUnit.CONTRACTS) {
      requireNumeric(
          contractSize,
          INSTRUMENT_VALUE_PRECISION,
          INSTRUMENT_VALUE_SCALE,
          "INVALID_INSTRUMENT_RULES",
          "Perpetual contract size exceeds the supported numeric range");
      requirePositive(
          contractSize,
          "INVALID_INSTRUMENT_RULES",
          "Perpetual contract size must be positive");
      requireNumeric(
          contractMultiplier,
          INSTRUMENT_VALUE_PRECISION,
          INSTRUMENT_VALUE_SCALE,
          "INVALID_INSTRUMENT_RULES",
          "Perpetual contract multiplier exceeds the supported numeric range");
      requirePositive(
          contractMultiplier,
          "INVALID_INSTRUMENT_RULES",
          "Perpetual contract multiplier must be positive");
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
        yield originalQuantity.multiply(contractSize).multiply(contractMultiplier);
      }
    };

    if (baseQuantity.compareTo(effectiveStep) < 0) {
      throw new BusinessException(
          "QUANTITY_CONVERTS_TO_ZERO",
          "Quantity is below one effective instrument step");
    }
    if (baseQuantity.remainder(effectiveStep).compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException(
          "QUANTITY_STEP_MISMATCH",
          "Quantity cannot be represented exactly by instrument and persistence steps");
    }
    requireNumeric(
        baseQuantity,
        LEGACY_QUANTITY_PRECISION,
        LEGACY_QUANTITY_SCALE,
        "BAD_QUANTITY",
        "Canonical BASE quantity exceeds the persistence numeric range");

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
    requirePublicQuantity(originalQuantity, quantityUnit);
    requirePositive(originalQuantity, "BAD_QUANTITY", "Quantity must be greater than zero");
    requireNumeric(
        stepSize,
        INSTRUMENT_VALUE_PRECISION,
        INSTRUMENT_VALUE_SCALE,
        "INVALID_INSTRUMENT_RULES",
        "Instrument step size exceeds the supported numeric range");
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
      requireNumeric(
          originalQuantity,
          LEGACY_QUANTITY_PRECISION,
          LEGACY_QUANTITY_SCALE,
          "BAD_QUANTITY",
          "Canonical BASE quantity exceeds the persistence numeric range");
      return new Conversion(originalQuantity, quantityUnit, originalQuantity, null);
    }
    if (marketPricing == null) {
      throw new BusinessException("MARKET_BUNDLE_INCOMPLETE", "Canonical MARKET pricing is required");
    }
    requireNumeric(
        marketPricing.filledPrice(),
        PRICE_PRECISION,
        PRICE_SCALE,
        ErrorCode.MARKET_BUNDLE_INCOMPLETE,
        "Canonical MARKET execution price exceeds the supported numeric range");
    requirePositive(
        marketPricing.filledPrice(),
        "MARKET_BUNDLE_INCOMPLETE",
        "Canonical MARKET execution price is required");
    requireNumeric(
        marketPricing.feeRate(),
        RATE_PRECISION,
        RATE_SCALE,
        ErrorCode.MARKET_BUNDLE_INCOMPLETE,
        "Canonical MARKET fee rate exceeds the supported numeric range");
    if (marketPricing.feeRate().signum() < 0
        || marketPricing.feeRate().compareTo(BigDecimal.ONE) >= 0) {
      throw new BusinessException(
          "MARKET_BUNDLE_INCOMPLETE",
          "Canonical MARKET fee rate is invalid");
    }

    BigDecimal unitSpend = marketPricing.filledPrice()
        .multiply(BigDecimal.ONE.add(marketPricing.feeRate()));
    BigDecimal steps = originalQuantity.divide(unitSpend, 24, RoundingMode.DOWN)
        .divide(effectiveStep, 0, RoundingMode.DOWN);
    BigDecimal baseQuantity = steps.multiply(effectiveStep);
    if (baseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          "QUANTITY_CONVERTS_TO_ZERO",
          "Quote budget is below one instrument quantity step");
    }
    requireNumeric(
        baseQuantity,
        LEGACY_QUANTITY_PRECISION,
        LEGACY_QUANTITY_SCALE,
        "BAD_QUANTITY",
        "Canonical BASE quantity exceeds the persistence numeric range");
    BigDecimal projectedSpend = projectedSpotBuySpend(baseQuantity, marketPricing);
    while (projectedSpend.compareTo(originalQuantity) > 0) {
      baseQuantity = baseQuantity.subtract(effectiveStep);
      if (baseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException(
            "QUANTITY_CONVERTS_TO_ZERO",
            "Quote budget is below one instrument quantity step");
      }
      projectedSpend = projectedSpotBuySpend(baseQuantity, marketPricing);
    }
    return new Conversion(originalQuantity, quantityUnit, baseQuantity, projectedSpend);
  }

  private static BigDecimal projectedSpotBuySpend(
      BigDecimal baseQuantity,
      FullFillPricingProjection marketPricing
  ) {
    BigDecimal grossQuote = baseQuantity.multiply(marketPricing.filledPrice())
        .setScale(WALLET_SCALE, RoundingMode.HALF_UP);
    BigDecimal feeQuote = baseQuantity.multiply(marketPricing.filledPrice())
        .multiply(marketPricing.feeRate())
        .setScale(WALLET_SCALE, RoundingMode.HALF_UP);
    return grossQuote.add(feeQuote).setScale(WALLET_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Returns the least decimal increment that is both an instrument-step multiple and exactly
   * representable by the legacy four-decimal order/trade quantity columns.
   */
  public static BigDecimal storageCompatibleStep(BigDecimal instrumentStep) {
    requireNumeric(
        instrumentStep,
        INSTRUMENT_VALUE_PRECISION,
        INSTRUMENT_VALUE_SCALE,
        "INVALID_INSTRUMENT_RULES",
        "Instrument step size exceeds the supported numeric range");
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
    BigDecimal effectiveStep = new BigDecimal(lcm, scale).stripTrailingZeros();
    requireNumeric(
        effectiveStep,
        LEGACY_QUANTITY_PRECISION,
        LEGACY_QUANTITY_SCALE,
        "INVALID_INSTRUMENT_RULES",
        "Effective step exceeds the persistence numeric range");
    return effectiveStep;
  }

  private static void requireNumeric(
      BigDecimal value,
      int precision,
      int scale,
      String code,
      String message
  ) {
    if (!ExactNumeric.fits(value, precision, scale)) {
      throw new BusinessException(code, message);
    }
  }

  private static void requirePublicQuantity(
      BigDecimal value,
      QuantityUnit quantityUnit
  ) {
    if (ExactNumeric.fits(value, PUBLIC_QUANTITY_PRECISION, PUBLIC_QUANTITY_SCALE)) {
      return;
    }
    String code = "BAD_QUANTITY";
    if (ExactNumeric.exceedsScaleOnly(
        value,
        PUBLIC_QUANTITY_PRECISION,
        PUBLIC_QUANTITY_SCALE)) {
      if (quantityUnit == QuantityUnit.BASE) {
        code = ErrorCode.QUANTITY_STEP_MISMATCH;
      } else if (quantityUnit == QuantityUnit.CONTRACTS) {
        code = ErrorCode.CONTRACT_QUANTITY_NOT_INTEGRAL;
      }
    }
    throw new BusinessException(code, "Quantity exceeds the supported numeric range");
  }

  private static void requireOptionalNumeric(
      BigDecimal value,
      int precision,
      int scale,
      String code,
      String message
  ) {
    if (value != null) {
      requireNumeric(value, precision, scale, code, message);
    }
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
