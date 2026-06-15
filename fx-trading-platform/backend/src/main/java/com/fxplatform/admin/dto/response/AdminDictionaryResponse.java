package com.fxplatform.admin.dto.response;

import com.fxplatform.config.entity.SystemDictionaryEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminDictionaryResponse 是后台字典项响应 DTO。
 *
 * @param id 字典项 ID。
 * @param groupKey 字典分组键。
 * @param itemKey 字典项键。
 * @param itemValue 字典项值。
 * @param enabled 是否启用。
 * @param displayOrder 展示排序值。
 * @param description 字典项说明。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record AdminDictionaryResponse(
    UUID id,
    String groupKey,
    String itemKey,
    String itemValue,
    Boolean enabled,
    Integer displayOrder,
    String description,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将字典项实体映射为后台 DTO。
   */
  public static AdminDictionaryResponse from(SystemDictionaryEntity entity) {
    return new AdminDictionaryResponse(
        entity.getId(),
        entity.getGroupKey(),
        entity.getItemKey(),
        entity.getItemValue(),
        entity.getEnabled(),
        entity.getDisplayOrder(),
        entity.getDescription(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
