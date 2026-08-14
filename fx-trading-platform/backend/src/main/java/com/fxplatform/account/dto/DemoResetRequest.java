package com.fxplatform.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record DemoResetRequest(
    @NotNull UUID requestId,
    @NotNull @PositiveOrZero Long expectedDemoGeneration
) {
}
