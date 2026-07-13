package com.fxplatform.trading.dto.response;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OrderResponse 承载交易模块的数据结构。
 */
public record OrderResponse(
    UUID id,
    UUID accountId,
    String symbol,
    String side,
    String orderType,
    Integer leverage,
    @Schema(allowableValues = {
        "RECEIVED",
        "VALIDATING",
        "ACCEPTED",
        "WORKING",
        "PARTIALLY_FILLED",
        "PENDING_ACTIVATION",
        "PENDING",
        "FILLED",
        "CANCEL_PENDING",
        "CANCELED",
        "CANCELLED",
        "REJECTED",
        "EXPIRED",
        "FAILED"
    }) String status,
    BigDecimal lots,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal executionPrice,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    BigDecimal avgFillPrice,
    BigDecimal fee,
    BigDecimal slippage,
    BigDecimal holdAmount,
    String holdCurrency,
    String rejectCode,
    String rejectMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant filledAt,
    Instant canceledAt,
    ProductType productType,
    PositionMode positionMode,
    PositionSide positionSide,
    MarginMode marginMode,
    QuantityUnit quantityUnit,
    BigDecimal originalQuantity,
    BigDecimal baseQuantity,
    TimeInForce timeInForce,
    Boolean reduceOnly,
    OrderOrigin origin,
    String systemReason,
    String feeAsset,
    LiquidityRole liquidityRole,
    BigDecimal triggerPrice,
    TriggerPriceType triggerPriceType,
    TriggerExecutionType triggerExecutionType,
    ProtectionType protectionType,
    UUID parentOrderId,
    UUID parentPositionId,
    UUID contingencyGroupId,
    UUID holdOwnerOrderId,
    @Schema(nullable = true) Long version
) {
  public OrderResponse(
      UUID id,
      UUID accountId,
      String symbol,
      String side,
      String orderType,
      Integer leverage,
      String status,
      BigDecimal lots,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal executionPrice,
      BigDecimal filledQuantity,
      BigDecimal remainingQuantity,
      BigDecimal avgFillPrice,
      BigDecimal fee,
      BigDecimal slippage,
      BigDecimal holdAmount,
      String holdCurrency,
      String rejectCode,
      String rejectMessage,
      Instant createdAt,
      Instant updatedAt,
      Instant filledAt,
      Instant canceledAt
  ) {
    this(
        id,
        accountId,
        symbol,
        side,
        orderType,
        leverage,
        status,
        lots,
        quantity,
        price,
        executionPrice,
        filledQuantity,
        remainingQuantity,
        avgFillPrice,
        fee,
        slippage,
        holdAmount,
        holdCurrency,
        rejectCode,
        rejectMessage,
        createdAt,
        updatedAt,
        filledAt,
        canceledAt,
        null,
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        null,
        QuantityUnit.BASE,
        quantity,
        quantity,
        TimeInForce.GTC,
        false,
        OrderOrigin.USER,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
