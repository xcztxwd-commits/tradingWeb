package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 资金审核订单创建请求。
 */
public record AdminFundOrderRequest(
    @NotNull UUID userId,
    @NotNull UUID accountId,
    @NotBlank String orderType,
    @NotNull @DecimalMin("0.00000001") BigDecimal amount,
    @NotBlank String currency,
    UUID paymentMethodId,
    String note
) {
}
