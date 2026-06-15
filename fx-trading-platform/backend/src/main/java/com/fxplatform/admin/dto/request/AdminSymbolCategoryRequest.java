package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 后台产品分类保存请求。
 */
public record AdminSymbolCategoryRequest(
    @NotBlank String name,
    @NotBlank String code,
    Integer sortOrder,
    @NotNull Boolean enabled
) {
}
