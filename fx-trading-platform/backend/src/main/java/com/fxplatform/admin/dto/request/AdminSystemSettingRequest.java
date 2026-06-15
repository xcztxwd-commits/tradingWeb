package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AdminSystemSettingRequest 是后台系统设置请求 DTO。
 *
 * @param settingKey 设置键。
 * @param settingValue 设置值。
 * @param valueType 值类型，例如 STRING、BOOLEAN、NUMBER。
 * @param description 设置说明。
 * @param editable 是否允许后台继续编辑。
 */
public record AdminSystemSettingRequest(
    @NotBlank String settingKey,
    @NotBlank String settingValue,
    @NotBlank String valueType,
    String description,
    @NotNull Boolean editable
) {
}
