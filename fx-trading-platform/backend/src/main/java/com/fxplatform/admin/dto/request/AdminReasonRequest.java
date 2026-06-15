package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AdminReasonRequest 是后台高风险写操作的基础原因请求。
 *
 * @param reason 管理员执行本次操作的业务原因，用于审计和事后追踪。
 */
public record AdminReasonRequest(
    @NotBlank String reason
) {
}
