package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 后台岗位保存请求。
 */
public record AdminPostRequest(
    @NotBlank String name,
    @NotBlank String code,
    Boolean enabled,
    Integer sortOrder
) {
}
