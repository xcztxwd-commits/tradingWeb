package com.fxplatform.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record AdjustPositionMarginRequest(
    @NotNull Action action,
    @NotNull @DecimalMin("0.00000001") @Digits(integer = 18, fraction = 8) BigDecimal amount,
    @NotNull @PositiveOrZero Long expectedVersion
) {
  public enum Action {
    ADD,
    REDUCE
  }
}
