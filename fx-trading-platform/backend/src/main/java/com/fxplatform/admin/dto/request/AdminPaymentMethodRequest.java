package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AdminPaymentMethodRequest 是后台支付方式配置请求 DTO。
 *
 * @param name 支付方式展示名称。
 * @param methodType 支付方式类型，例如 BANK_TRANSFER 或 CRYPTO。
 * @param currency 支付币种。
 * @param enabled 是否启用该支付方式。
 * @param displayOrder 前端展示排序值，越小越靠前。
 * @param instructions 支付说明或收款指引。
 */
public record AdminPaymentMethodRequest(
    @NotBlank String name,
    @NotBlank String methodType,
    @NotBlank String currency,
    @NotNull Boolean enabled,
    Integer displayOrder,
    String instructions
) {
}
