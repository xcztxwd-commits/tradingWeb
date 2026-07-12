package com.fxplatform.trading.dto.request;

import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record UpdateProtectionRequest(
    BigDecimal quantity,
    QuantityUnit quantityUnit,
    BigDecimal triggerPrice,
    TriggerExecutionType triggerExecutionType,
    BigDecimal price,
    @NotNull @PositiveOrZero Long expectedVersion
) {
}
