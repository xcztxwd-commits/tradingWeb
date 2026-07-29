package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.money.ExactNumeric;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import java.math.BigDecimal;

/** Persistence-shaped numeric boundary for one public create-order request. */
final class CreateOrderNumericBoundary {

  private static final int QUANTITY_PRECISION = 24;
  private static final int QUANTITY_SCALE = 8;
  private static final int PRICE_PRECISION = 24;
  private static final int PRICE_SCALE = 10;
  private static final int ADVANCED_PRICE_PRECISION = 30;
  private static final int ADVANCED_PRICE_SCALE = 12;
  private static final int TRAILING_RATE_PRECISION = 18;
  private static final int TRAILING_RATE_SCALE = 10;

  private CreateOrderNumericBoundary() {
  }

  static void requireSafe(CreateOrderRequest request) {
    requireOptionalQuantity(
        request.lots(),
        request.quantityUnit(),
        "Order quantity exceeds the supported numeric range");
    requireOptionalQuantity(
        request.quantity(),
        request.quantityUnit(),
        "Order quantity exceeds the supported numeric range");

    requireOptionalPrice(request.requestedPrice());
    requireOptionalPrice(request.price());
    requireOptionalPrice(request.stopLoss());
    requireOptionalPrice(request.takeProfit());
    requireOptionalTriggerPrice(request.triggerPrice());
    requireOptionalAdvancedPrice(request.activationPrice());
    requireOptionalAdvancedPrice(request.trailingDelta());
    requireOptional(
        request.trailingRate(),
        TRAILING_RATE_PRECISION,
        TRAILING_RATE_SCALE,
        ErrorCode.ORDER_PRICE_REQUIRED,
        "Order trailing rate exceeds the supported numeric range");

    for (CreateOrderRequest.AttachedProtectionRequest protection
        : request.attachedProtections()) {
      requireOptionalTriggerPrice(protection.triggerPrice());
      requireOptionalPrice(protection.price());
      requireOptionalQuantity(
          protection.quantity(),
          protection.quantityUnit(),
          "Attached protection quantity exceeds the supported numeric range");
    }
  }

  private static void requireOptionalQuantity(
      BigDecimal value,
      com.fxplatform.trading.enums.QuantityUnit quantityUnit,
      String message
  ) {
    if (value == null || ExactNumeric.fits(value, QUANTITY_PRECISION, QUANTITY_SCALE)) {
      return;
    }
    String code = "BAD_QUANTITY";
    if (ExactNumeric.exceedsScaleOnly(value, QUANTITY_PRECISION, QUANTITY_SCALE)) {
      if (quantityUnit == com.fxplatform.trading.enums.QuantityUnit.BASE) {
        code = ErrorCode.QUANTITY_STEP_MISMATCH;
      } else if (quantityUnit == com.fxplatform.trading.enums.QuantityUnit.CONTRACTS) {
        code = ErrorCode.CONTRACT_QUANTITY_NOT_INTEGRAL;
      }
    }
    throw new BusinessException(code, message);
  }

  private static void requireOptionalPrice(BigDecimal value) {
    requireOptional(
        value,
        PRICE_PRECISION,
        PRICE_SCALE,
        ErrorCode.ORDER_PRICE_REQUIRED,
        "Order price exceeds the supported numeric range");
  }

  private static void requireOptionalTriggerPrice(BigDecimal value) {
    requireOptional(
        value,
        PRICE_PRECISION,
        PRICE_SCALE,
        ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED,
        "Order trigger price exceeds the supported numeric range");
  }

  private static void requireOptionalAdvancedPrice(BigDecimal value) {
    requireOptional(
        value,
        ADVANCED_PRICE_PRECISION,
        ADVANCED_PRICE_SCALE,
        ErrorCode.ORDER_PRICE_REQUIRED,
        "Order advanced price exceeds the supported numeric range");
  }

  private static void requireOptional(
      BigDecimal value,
      int precision,
      int scale,
      String code,
      String message
  ) {
    if (value != null && !ExactNumeric.fits(value, precision, scale)) {
      throw new BusinessException(code, message);
    }
  }
}
