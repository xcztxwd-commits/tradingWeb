package com.fxplatform.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountTransferRequest(
    @NotNull Direction direction,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
    @NotNull UUID requestId
) {
  public enum Direction {
    SPOT_TO_PERP,
    PERP_TO_SPOT
  }
}
