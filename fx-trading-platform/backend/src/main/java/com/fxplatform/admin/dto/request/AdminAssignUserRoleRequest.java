package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 后台用户角色绑定请求。
 */
public record AdminAssignUserRoleRequest(
    @NotNull UUID userId,
    @NotNull UUID roleId
) {
}
