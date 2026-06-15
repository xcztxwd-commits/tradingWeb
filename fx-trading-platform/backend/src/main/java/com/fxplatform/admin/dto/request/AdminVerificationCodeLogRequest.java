package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 后台验证码日志补录请求，用于短信/邮箱通道联调和审计查询。
 */
public record AdminVerificationCodeLogRequest(
    @NotBlank String scene,
    @NotBlank String account,
    @NotBlank String channel,
    @NotBlank String code,
    @NotBlank String status,
    String errorMessage
) {
}
