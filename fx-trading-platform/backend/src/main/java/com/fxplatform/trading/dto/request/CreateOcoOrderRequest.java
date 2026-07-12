package com.fxplatform.trading.dto.request;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Public P0 Spot OCO request: one LIMIT leg and one STOP_MARKET leg. */
public record CreateOcoOrderRequest(
    @NotNull UUID accountId,
    @NotBlank String symbol,
    @NotNull OrderSide side,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
    @NotNull QuantityUnit quantityUnit,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal limitPrice,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal stopTriggerPrice,
    TriggerPriceType triggerPriceType,
    @Size(max = 128) String idempotencyKey,
    @Size(max = 128) String clientOrderId
) {

  public CreateOcoOrderRequest {
    triggerPriceType = triggerPriceType == null ? TriggerPriceType.LAST_PRICE : triggerPriceType;
  }

  @Override
  public String clientOrderId() {
    return StrUtil.isNotBlank(clientOrderId) ? clientOrderId : idempotencyKey;
  }

  @AssertTrue(message = "clientOrderId or idempotencyKey is required")
  public boolean hasClientOrderId() {
    return StrUtil.isNotBlank(clientOrderId());
  }
}
