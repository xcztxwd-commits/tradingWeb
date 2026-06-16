package com.fxplatform.ledger.dto;

import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
    UUID id,
    UUID accountId,
    String entryType,
    BigDecimal amount,
    BigDecimal balanceAfter,
    String currency,
    String referenceType,
    UUID referenceId,
    String description,
    Instant createdAt
) {

  public static LedgerEntryResponse fromCashLedger(LedgerEntryEntity entry) {
    return new LedgerEntryResponse(
        entry.getId(),
        entry.getAccountId(),
        entry.getEntryType() == null ? null : entry.getEntryType().name(),
        entry.getAmount(),
        entry.getBalanceAfter(),
        entry.getCurrency(),
        entry.getReferenceType(),
        entry.getReferenceId(),
        entry.getDescription(),
        entry.getCreatedAt());
  }

  public static LedgerEntryResponse fromAssetLedger(AssetLedgerEntryEntity entry) {
    return new LedgerEntryResponse(
        entry.getId(),
        entry.getAccountId(),
        entry.getEntryType(),
        entry.getAmount(),
        entry.getBalanceAfter(),
        entry.getAsset(),
        entry.getReferenceType(),
        entry.getReferenceId(),
        entry.getDescription(),
        entry.getCreatedAt());
  }
}
