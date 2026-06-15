package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * 角色菜单和按钮权限保存请求。
 */
public record AdminRoleMenuPermissionRequest(
    @NotNull UUID menuId,
    List<String> buttons
) {
}
