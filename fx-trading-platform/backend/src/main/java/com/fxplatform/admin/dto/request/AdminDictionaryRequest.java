package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AdminDictionaryRequest 是后台字典项请求 DTO。
 *
 * @param groupKey 字典分组键。
 * @param itemKey 字典项键。
 * @param itemValue 字典项值。
 * @param enabled 是否启用。
 * @param displayOrder 展示排序值。
 * @param description 字典项说明。
 */
public record AdminDictionaryRequest(
    @NotBlank String groupKey,
    @NotBlank String itemKey,
    @NotBlank String itemValue,
    @NotNull Boolean enabled,
    Integer displayOrder,
    String description
) {
}
