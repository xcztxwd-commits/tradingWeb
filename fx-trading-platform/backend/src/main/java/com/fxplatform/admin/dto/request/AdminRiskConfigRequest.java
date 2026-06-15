package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 后台风控配置保存请求。
 */
public record AdminRiskConfigRequest(
    @NotBlank String symbol,
    @NotNull @Min(1) Integer maxLeverage,
    @NotNull @DecimalMin("0.0001") BigDecimal maxLots,
    @NotNull @DecimalMin("0.0001") BigDecimal marginCallLevel,
    @NotNull @DecimalMin("0.0001") BigDecimal stopOutLevel,
    @NotNull Boolean enabled,
    @NotBlank String reason
) {
}
