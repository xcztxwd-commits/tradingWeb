package com.fxplatform.finance.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * FundOrderRequest carries a user recharge or withdrawal application.
 */
public record FundOrderRequest(
    @NotNull UUID accountId,
    @NotBlank String orderType,
    @NotNull @DecimalMin("0.00000001") BigDecimal amount,
    @NotBlank String currency,
    UUID paymentMethodId,
    String note
) {
}
