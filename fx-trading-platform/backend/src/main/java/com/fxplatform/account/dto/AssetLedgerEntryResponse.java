package com.fxplatform.account.dto;

import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AssetLedgerEntryResponse(
    UUID id,
    UUID accountId,
    String walletType,
    String asset,
    BigDecimal amount,
    BigDecimal balanceAfter,
    String entryType,
    String referenceType,
    UUID referenceId,
    String description,
    Instant createdAt
) {

  public static AssetLedgerEntryResponse from(AssetLedgerEntryEntity entry) {
    return new AssetLedgerEntryResponse(
        entry.getId(),
        entry.getAccountId(),
        entry.getWalletType(),
        entry.getAsset(),
        entry.getAmount(),
        entry.getBalanceAfter(),
        entry.getEntryType(),
        entry.getReferenceType(),
        entry.getReferenceId(),
        entry.getDescription(),
        entry.getCreatedAt());
  }
}
