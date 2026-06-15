package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * 后台部门保存请求。
 */
public record AdminDepartmentRequest(
    @NotBlank String name,
    UUID parentId,
    String leader,
    String phone,
    Boolean enabled,
    Integer sortOrder
) {
}
