package com.fxplatform.account.dto;

import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.QuantityUnit;
import jakarta.validation.constraints.NotNull;

public record UpdateSymbolSettingsRequest(
    Integer leverage,
    MarginMode marginMode,
    QuantityUnit quantityUnit,
    @NotNull Long expectedVersion
) {
}
