package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AdminRiskLevelRequest 是后台修改用户风险等级的请求。
 *
 * @param riskLevel 新的风险等级。
 * @param reason 修改风险等级的原因。
 */
public record AdminRiskLevelRequest(
    @NotBlank String riskLevel,
    @NotBlank String reason
) {
}
