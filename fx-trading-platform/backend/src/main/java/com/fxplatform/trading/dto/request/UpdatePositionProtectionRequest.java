package com.fxplatform.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Carries editable protection prices for an open position.
 */
public record UpdatePositionProtectionRequest(
    @DecimalMin("0.00000001") BigDecimal stopLoss,
    @DecimalMin("0.00000001") BigDecimal takeProfit,
    Boolean allowImmediateTrigger
) {
  public UpdatePositionProtectionRequest(BigDecimal stopLoss, BigDecimal takeProfit) {
    this(stopLoss, takeProfit, false);
  }

  public boolean allowsImmediateTrigger() {
    return Boolean.TRUE.equals(allowImmediateTrigger);
  }
}
