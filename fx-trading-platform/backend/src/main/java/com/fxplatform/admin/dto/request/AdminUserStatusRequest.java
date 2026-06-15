package com.fxplatform.admin.dto.request;

import com.fxplatform.auth.enums.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AdminUserStatusRequest 是后台修改用户状态的请求。
 *
 * @param status 目标用户状态。
 * @param reason 修改状态的原因，用于审计。
 */
public record AdminUserStatusRequest(
    @NotNull UserStatus status,
    @NotBlank String reason
) {
}
