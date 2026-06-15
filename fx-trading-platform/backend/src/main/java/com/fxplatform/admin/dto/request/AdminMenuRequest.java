package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 后台菜单和按钮权限入口配置请求。
 */
public record AdminMenuRequest(
    UUID parentId,
    @NotBlank String name,
    @NotBlank String permissionKey,
    String path,
    String component,
    @NotBlank String menuType,
    @NotNull Boolean enabled,
    Integer sortOrder
) {
}
