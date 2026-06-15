package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminDepartmentEntity;
import java.util.UUID;

/**
 * 后台部门响应。
 */
public record AdminDepartmentResponse(
    UUID id,
    String name,
    UUID parentId,
    String leader,
    String phone,
    Boolean enabled,
    Integer sortOrder
) {

  public static AdminDepartmentResponse from(AdminDepartmentEntity entity) {
    return new AdminDepartmentResponse(
        entity.getId(),
        entity.getDepartmentName(),
        entity.getParentId(),
        entity.getLeader(),
        entity.getPhone(),
        entity.getEnabled(),
        entity.getSortOrder());
  }
}
