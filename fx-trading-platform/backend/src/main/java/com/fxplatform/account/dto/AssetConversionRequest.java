package com.fxplatform.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AssetConversionRequest(
    @NotBlank String fromWalletType,
    @NotBlank String fromAsset,
    @NotBlank String toWalletType,
    @NotBlank String toAsset,
    @NotNull @DecimalMin("0.00000001") BigDecimal amount,
    UUID conversionId
) {
}
