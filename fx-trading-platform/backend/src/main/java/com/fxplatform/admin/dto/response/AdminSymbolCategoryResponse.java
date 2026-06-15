package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.SymbolCategoryEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台产品分类响应。
 */
public record AdminSymbolCategoryResponse(
    UUID id,
    String name,
    String code,
    Integer sortOrder,
    Boolean enabled,
    Instant updatedAt
) {

  public static AdminSymbolCategoryResponse from(SymbolCategoryEntity entity) {
    return new AdminSymbolCategoryResponse(
        entity.getId(),
        entity.getName(),
        entity.getCode(),
        entity.getSortOrder(),
        entity.getEnabled(),
        entity.getUpdatedAt());
  }
}
