package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 后台角色创建或更新请求。
 */
public record AdminRoleRequest(
    @NotBlank String name,
    @NotBlank String code,
    @NotNull Boolean enabled,
    Integer sortOrder,
    String description
) {
}
