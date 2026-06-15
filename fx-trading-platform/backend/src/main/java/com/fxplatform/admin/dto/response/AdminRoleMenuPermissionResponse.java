package com.fxplatform.admin.dto.response;

import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import java.util.List;
import java.util.UUID;

/**
 * 角色菜单按钮权限响应。
 */
public record AdminRoleMenuPermissionResponse(
    UUID id,
    UUID roleId,
    UUID menuId,
    List<String> buttons,
    Boolean enabled
) {

  public static AdminRoleMenuPermissionResponse from(AdminRoleMenuPermissionEntity entity) {
    return new AdminRoleMenuPermissionResponse(
        entity.getId(),
        entity.getRoleId(),
        entity.getMenuId(),
        JSONUtil.toList(entity.getButtons(), String.class),
        entity.getEnabled());
  }
}
