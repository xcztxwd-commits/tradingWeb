package com.fxplatform.admin.dto.response;

import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.entity.AdminRoleDataScopeEntity;
import java.util.List;
import java.util.UUID;

/**
 * 角色数据权限响应。
 */
public record AdminRoleDataScopeResponse(
    UUID id,
    UUID roleId,
    String scopeType,
    List<UUID> departmentIds
) {

  public static AdminRoleDataScopeResponse from(AdminRoleDataScopeEntity entity) {
    return new AdminRoleDataScopeResponse(
        entity.getId(),
        entity.getRoleId(),
        entity.getScopeType(),
        JSONUtil.toList(entity.getDepartmentIds(), UUID.class));
  }
}
