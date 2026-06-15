package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminUserRoleEntity;
import java.util.UUID;

/**
 * 后台用户角色绑定响应。
 */
public record AdminUserRoleResponse(
    UUID id,
    UUID userId,
    UUID roleId
) {

  public static AdminUserRoleResponse from(AdminUserRoleEntity entity) {
    return new AdminUserRoleResponse(entity.getId(), entity.getUserId(), entity.getRoleId());
  }
}
