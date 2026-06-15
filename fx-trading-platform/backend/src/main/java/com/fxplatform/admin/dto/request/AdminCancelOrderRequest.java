package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AdminCancelOrderRequest 是后台取消订单的请求 DTO。
 *
 * @param reason 管理员取消订单的业务原因，用于审计和客服追踪。
 * @param idempotencyKey 前端生成的幂等键，用于后续接入命令幂等表。
 */
public record AdminCancelOrderRequest(
    @NotBlank String reason,
    String idempotencyKey
) {
}
