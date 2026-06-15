package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminRoleEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台角色响应。
 */
public record AdminRoleResponse(
    UUID id,
    String name,
    String code,
    Boolean enabled,
    Integer sortOrder,
    String description,
    Instant createdAt
) {

  public static AdminRoleResponse from(AdminRoleEntity entity) {
    return new AdminRoleResponse(
        entity.getId(),
        entity.getRoleName(),
        entity.getRoleCode(),
        entity.getEnabled(),
        entity.getSortOrder(),
        entity.getDescription(),
        entity.getCreatedAt());
  }
}
