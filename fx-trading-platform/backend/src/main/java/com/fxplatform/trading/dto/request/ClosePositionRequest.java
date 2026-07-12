package com.fxplatform.trading.dto.request;

import com.fxplatform.trading.enums.QuantityUnit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Public explicit-quantity request for one Linear Perpetual position close. */
public record ClosePositionRequest(
    @NotNull @DecimalMin("0.00000001") BigDecimal quantity,
    @NotNull QuantityUnit quantityUnit,
    @NotBlank String clientOrderId
) {
  public boolean isEmpty() {
    return quantity == null && quantityUnit == null && clientOrderId == null;
  }
}
