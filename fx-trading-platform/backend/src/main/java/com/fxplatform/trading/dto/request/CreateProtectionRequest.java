package com.fxplatform.trading.dto.request;

import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateProtectionRequest(
    @NotNull ProtectionType protectionType,
    @NotNull @DecimalMin("0.00000001") BigDecimal quantity,
    @NotNull QuantityUnit quantityUnit,
    @NotNull @DecimalMin("0.00000001") BigDecimal triggerPrice,
    @NotNull TriggerExecutionType triggerExecutionType,
    BigDecimal price,
    @NotBlank String clientOrderId
) {
}
