package com.fxplatform.execution;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionResult(
    BigDecimal filledPrice,
    Instant filledAt,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    BigDecimal fee,
    String feeAsset,
    BigDecimal slippage,
    String rejectCode,
    String rejectMessage
) {

  public ExecutionResult(
      BigDecimal filledPrice,
      Instant filledAt,
      BigDecimal filledQuantity,
      BigDecimal remainingQuantity,
      BigDecimal fee,
      BigDecimal slippage,
      String rejectCode,
      String rejectMessage
  ) {
    this(filledPrice, filledAt, filledQuantity, remainingQuantity, fee, null, slippage, rejectCode, rejectMessage);
  }

  public ExecutionResult(BigDecimal filledPrice, Instant filledAt) {
    this(filledPrice, filledAt, null, null, BigDecimal.ZERO, null, BigDecimal.ZERO, null, null);
  }

  public static ExecutionResult rejected(String rejectCode, String rejectMessage) {
    return new ExecutionResult(null, Instant.now(), BigDecimal.ZERO, null, BigDecimal.ZERO, null, BigDecimal.ZERO, rejectCode, rejectMessage);
  }

  public boolean rejected() {
    return rejectCode != null && !rejectCode.isBlank();
  }
}
