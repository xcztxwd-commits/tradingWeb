package com.fxplatform.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountSnapshot(
    UUID accountId,
    BigDecimal balance,
    BigDecimal openFloatingPnl,
    BigDecimal equity,
    BigDecimal usedMargin,
    BigDecimal maintenanceMargin,
    BigDecimal freeMargin,
    BigDecimal marginLevel,
    String baseCurrency,
    BigDecimal positionValue,
    BigDecimal marginAvailable,
    Instant lastSnapshotAt,
    String warning
) {
  public AccountSnapshot(
      UUID accountId,
      BigDecimal balance,
      BigDecimal openFloatingPnl,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal maintenanceMargin,
      BigDecimal freeMargin,
      BigDecimal marginLevel,
      String baseCurrency
  ) {
    this(
        accountId,
        balance,
        openFloatingPnl,
        equity,
        usedMargin,
        maintenanceMargin,
        freeMargin,
        marginLevel,
        baseCurrency,
        BigDecimal.ZERO,
        freeMargin,
        Instant.now(),
        null);
  }
}
