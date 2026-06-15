package com.fxplatform.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * UpdateOrderRequest carries editable fields for a pending order.
 */
public record UpdateOrderRequest(
    @DecimalMin("0.01") BigDecimal quantity,
    BigDecimal price,
    BigDecimal stopLoss,
    BigDecimal takeProfit
) {
}
