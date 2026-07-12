package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record AdminFundingConfigRequest(
    @NotEmpty List<String> fundingSourcePriority,
    @NotNull BigDecimal fixedFundingRate,
    @NotNull Integer fixedFundingIntervalMinutes,
    @NotNull Integer fundingStaleSeconds,
    @NotBlank String reason
) {
}
