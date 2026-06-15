package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/**
 * 角色数据权限范围保存请求。
 */
public record AdminRoleDataScopeRequest(
    @NotBlank String scopeType,
    List<UUID> departmentIds
) {
}
