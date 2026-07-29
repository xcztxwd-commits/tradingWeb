package com.fxplatform.validation.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence boundary for one validation account seed transaction.
 *
 * <p>The production implementation fences the current reset generation, then locks the account,
 * all existing wallets, and the authoritative Spot capability rows in that order. The callback
 * runs inside that same transaction. It must either commit account, wallets, both ledgers and the
 * receipt together, or roll them all back.</p>
 */
public interface ValidationAccountSeedGateway {

  /** Fast capability view used to reject invalid requests before opening a mutation transaction. */
  Set<String> supportedSpotAssets();

  <T> T withLockedAccount(UUID accountId, LockedAccountCallback<T> callback);

  @FunctionalInterface
  interface LockedAccountCallback<T> {
    T apply(LockedAccount account);
  }

  interface LockedAccount {

    Optional<ValidationAccountSeedReceipt> findReceipt(UUID seedId);

    /** Authoritative capability set protected for the lifetime of this transaction. */
    Set<String> supportedSpotAssets();

    PristineSnapshot snapshot();

    void writePerpetualUsdtTarget(
        UUID seedId,
        BigDecimal target,
        BigDecimal delta
    );

    void writeSpotTarget(
        UUID seedId,
        String canonicalAsset,
        BigDecimal target,
        BigDecimal delta
    );

    void saveReceipt(ValidationAccountSeedReceipt receipt);
  }

  record PristineSnapshot(
      AccountView account,
      List<WalletView> wallets,
      List<CashLedgerView> cashLedger,
      List<AssetLedgerView> assetLedger,
      long orderHistoryCount,
      long tradeHistoryCount,
      long perpetualPositionHistoryCount,
      long spotPositionHistoryCount
  ) {
  }

  record AccountView(
      UUID id,
      String accountType,
      String status,
      String baseCurrency,
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      BigDecimal marginLevel,
      int leverage,
      String positionMode,
      long demoGeneration,
      Instant resetAt
  ) {
  }

  record WalletView(
      String walletType,
      String asset,
      BigDecimal total,
      BigDecimal available,
      BigDecimal locked
  ) {
  }

  record CashLedgerView(
      String entryType,
      String operationType,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String currency,
      String referenceType,
      UUID referenceId
  ) {
  }

  record AssetLedgerView(
      String walletType,
      String asset,
      String entryType,
      String operationType,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId
  ) {
  }
}
