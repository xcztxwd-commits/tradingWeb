package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * AdminFundOperationRequest 是后台入金和出金的请求 DTO。
 *
 * @param amount 操作金额，入金和出金都使用正数，服务层按操作类型转为签名金额。
 * @param reason 管理员执行资金操作的业务原因。
 * @param paymentMethodId 关联的支付方式 ID，可为空。
 * @param note 财务审核备注，可为空。
 * @param idempotencyKey 前端生成的幂等键，用于后续接入命令幂等表。
 */
public record AdminFundOperationRequest(
    @NotNull @DecimalMin("0.00000001") BigDecimal amount,
    @NotBlank String reason,
    UUID paymentMethodId,
    String note,
    String idempotencyKey
) {
}
