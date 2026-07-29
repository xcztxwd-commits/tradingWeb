package com.fxplatform.trading.scenario;

import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.Objects;

public record ScenarioAction(
    Type type,
    PositionSide direction,
    BigDecimal quantity,
    Parameters parameters,
    String detail
) {

  public ScenarioAction {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(direction, "direction");
    if (quantity != null && quantity.signum() <= 0) {
      throw new IllegalArgumentException("quantity must be positive when present");
    }
    Objects.requireNonNull(parameters, "parameters");
    Objects.requireNonNull(detail, "detail");
  }

  public record Parameters(
      String actionId,
      String clientOrderId,
      String orderId,
      String competingOrderId,
      String protectionId,
      BigDecimal price,
      BigDecimal triggerPrice,
      Integer leverage,
      BigDecimal marginDelta,
      BigDecimal fundingRate,
      BigDecimal feeRate,
      BigDecimal makerFeeRate,
      BigDecimal takerFeeRate,
      BigDecimal worstFeeRate,
      BigDecimal slippageRate,
      BigDecimal maintenanceMarginRate,
      BigDecimal liquidationFeeRate,
      QuantityUnit quantityUnit,
      Boolean reduceOnly,
      OrderSide side,
      OrderType orderType,
      ProtectionType protectionType,
      TriggerExecutionType triggerExecutionType,
      TriggerPriceType triggerPriceType,
      String condition,
      String failureCondition
  ) {

    public Parameters {
      actionId = requireText(actionId, "actionId");
      clientOrderId = text(clientOrderId);
      orderId = text(orderId);
      competingOrderId = text(competingOrderId);
      protectionId = text(protectionId);
      condition = text(condition);
      failureCondition = text(failureCondition);
    }

    private static String requireText(String value, String field) {
      String normalized = text(value);
      if (normalized.isBlank()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
      return normalized;
    }

    private static String text(String value) {
      return value == null ? "" : value.trim();
    }
  }

  public enum Type {
    BUY,
    SELL,
    PARTIAL_SELL,
    ADD,
    PARTIAL_CLOSE,
    FULL_CLOSE,
    PLACE_ORDER,
    MODIFY,
    CANCEL,
    TRIGGER,
    CREATE_OCO,
    REPLAY,
    REVERSE,
    CHANGE_LEVERAGE,
    ADJUST_MARGIN,
    SET_PROTECTION,
    SETTLE_FUNDING,
    LIQUIDATE,
    CANCEL_ALL,
    CLOSE_ALL,
    ADMIN_FORCE_CLOSE,
    RACE,
    REVALUE,
    ROLLBACK
  }
}
