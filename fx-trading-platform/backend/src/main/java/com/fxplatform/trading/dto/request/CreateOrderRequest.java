package com.fxplatform.trading.dto.request;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CreateOrderRequest 承载交易模块的数据结构。
 */
public record CreateOrderRequest(
    @NotNull UUID accountId,
    @Schema(example = "BTCUSDT-PERP") @NotBlank String symbol,
    @NotNull OrderSide side,
    @Schema(allowableValues = {"MARKET", "LIMIT", "STOP_MARKET"}) @NotNull OrderType orderType,
    @DecimalMin(value = "0", inclusive = false) BigDecimal lots,
    BigDecimal requestedPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    String idempotencyKey,
    String clientOrderId,
    @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
    BigDecimal price,
    @Min(1) Integer leverage,
    @NotNull PositionSide positionSide,
    @NotNull QuantityUnit quantityUnit,
    @NotNull MarginMode marginMode,
    BigDecimal triggerPrice,
    TriggerPriceType triggerPriceType,
    @NotNull Boolean reduceOnly,
    @Valid List<AttachedProtectionRequest> attachedProtections
) {
  public CreateOrderRequest {
    positionSide = positionSide == null ? PositionSide.BOTH : positionSide;
    quantityUnit = quantityUnit == null ? QuantityUnit.BASE : quantityUnit;
    marginMode = marginMode == null ? MarginMode.CROSS : marginMode;
    reduceOnly = reduceOnly == null ? Boolean.FALSE : reduceOnly;
    attachedProtections = attachedProtections == null
        ? List.of()
        : List.copyOf(attachedProtections);
  }

  public CreateOrderRequest(
      UUID accountId,
      String symbol,
      OrderSide side,
      OrderType orderType,
      BigDecimal lots,
      BigDecimal requestedPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String idempotencyKey,
      String clientOrderId,
      BigDecimal quantity,
      BigDecimal price,
      Integer leverage
  ) {
    this(
        accountId,
        symbol,
        side,
        orderType,
        lots,
        requestedPrice,
        stopLoss,
        takeProfit,
        idempotencyKey,
        clientOrderId,
        quantity,
        price,
        leverage,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        null,
        null,
        false,
        List.of());
  }

  public CreateOrderRequest(
      UUID accountId,
      String symbol,
      OrderSide side,
      OrderType orderType,
      BigDecimal lots,
      BigDecimal requestedPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String idempotencyKey,
      String clientOrderId,
      BigDecimal quantity,
      BigDecimal price
  ) {
    this(
        accountId,
        symbol,
        side,
        orderType,
        lots,
        requestedPrice,
        stopLoss,
        takeProfit,
        idempotencyKey,
        clientOrderId,
        quantity,
        price,
        null);
  }

  /**
   * 执行 clientOrderId 方法逻辑。
   */
  @Override
  public String clientOrderId() {
    return hasText(clientOrderId) ? clientOrderId : idempotencyKey;
  }

  /**
   * 执行 quantity 方法逻辑。
   */
  @Override
  public BigDecimal quantity() {
    return quantity != null ? quantity : lots;
  }

  /**
   * 执行 price 方法逻辑。
   */
  @Override
  public BigDecimal price() {
    return price != null ? price : requestedPrice;
  }

  /**
   * 执行 hasClientOrderId 方法逻辑。
   */
  @AssertTrue(message = "clientOrderId or idempotencyKey is required")
  public boolean hasClientOrderId() {
    return hasText(clientOrderId());
  }

  /**
   * 执行 hasQuantity 方法逻辑。
   */
  @AssertTrue(message = "quantity or lots is required")
  public boolean hasQuantity() {
    return quantity() != null;
  }

  /**
   * 执行 hasText 方法逻辑。
   */
  private static boolean hasText(String value) {
    return StrUtil.isNotBlank(value);
  }

  public record AttachedProtectionRequest(
      @NotNull ProtectionType protectionType,
      @NotNull BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      @NotNull TriggerExecutionType triggerExecutionType,
      BigDecimal price,
      @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      QuantityUnit quantityUnit
  ) {
    public AttachedProtectionRequest(
        ProtectionType protectionType,
        BigDecimal triggerPrice,
        TriggerPriceType triggerPriceType,
        TriggerExecutionType triggerExecutionType,
        BigDecimal price
    ) {
      this(
          protectionType,
          triggerPrice,
          triggerPriceType,
          triggerExecutionType,
          price,
          null,
          null);
    }

    @AssertTrue(message = "quantity is required when quantityUnit is provided")
    public boolean hasValidQuantityContract() {
      return quantity != null || quantityUnit == null;
    }
  }
}
