package com.fxplatform.admin.dto.response;

import com.fxplatform.config.entity.SystemSettingEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminSystemSettingResponse 是后台系统设置响应 DTO。
 *
 * @param id 设置 ID。
 * @param settingKey 设置键。
 * @param settingValue 设置值。
 * @param valueType 值类型。
 * @param description 设置说明。
 * @param editable 是否允许后台继续编辑。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record AdminSystemSettingResponse(
    UUID id,
    String settingKey,
    String settingValue,
    String valueType,
    String description,
    Boolean editable,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将系统设置实体映射为后台 DTO。
   */
  public static AdminSystemSettingResponse from(SystemSettingEntity entity) {
    return new AdminSystemSettingResponse(
        entity.getId(),
        entity.getSettingKey(),
        entity.getSettingValue(),
        entity.getValueType(),
        entity.getDescription(),
        entity.getEditable(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
