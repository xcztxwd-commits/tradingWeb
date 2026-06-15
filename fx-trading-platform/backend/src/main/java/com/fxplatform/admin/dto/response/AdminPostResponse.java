package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminPostEntity;
import java.util.UUID;

/**
 * 后台岗位响应。
 */
public record AdminPostResponse(
    UUID id,
    String name,
    String code,
    Boolean enabled,
    Integer sortOrder
) {

  public static AdminPostResponse from(AdminPostEntity entity) {
    return new AdminPostResponse(
        entity.getId(),
        entity.getPostName(),
        entity.getPostCode(),
        entity.getEnabled(),
        entity.getSortOrder());
  }
}
