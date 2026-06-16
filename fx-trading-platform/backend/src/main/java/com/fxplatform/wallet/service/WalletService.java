package com.fxplatform.wallet.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.AssetLedgerEntryType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WalletService {

  private static final int MONEY_SCALE = 8;

  private final WalletBalanceRepository walletBalanceRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;

  public Optional<WalletBalanceEntity> getBalance(UUID accountId, String asset) {
    return walletBalanceRepository.findByAccountIdAndAsset(accountId, normalizeAsset(asset));
  }

  public List<WalletBalanceEntity> balances(UUID accountId) {
    return walletBalanceRepository.findByAccountIdOrderByAssetAsc(accountId);
  }

  public List<AssetLedgerEntryEntity> assetLedgerEntries(
      UUID accountId,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    return assetLedgerEntryRepository.findByFilters(
        accountId,
        normalizeOptional(asset),
        normalizeOptional(entryType),
        referenceId,
        from,
        to);
  }

  @Transactional
  public WalletBalanceEntity getOrCreateBalance(UUID accountId, String asset) {
    String normalizedAsset = normalizeAsset(asset);
    return walletBalanceRepository.findByAccountIdAndAsset(accountId, normalizedAsset)
        .orElseGet(() -> walletBalanceRepository.save(newBalance(accountId, normalizedAsset)));
  }

  @Transactional
  public WalletBalanceEntity creditAvailable(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    return creditAvailableWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, AssetLedgerEntryType.CREDIT_AVAILABLE.code());
  }

  @Transactional
  public WalletBalanceEntity creditAvailableWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, asset);
    balance.setTotal(scale(balance.getTotal().add(normalizedAmount)));
    balance.setAvailable(scale(balance.getAvailable().add(normalizedAmount)));
    persist(balance, normalizedAmount, entryType, referenceType, referenceId, description);
    return balance;
  }

  @Transactional
  public WalletBalanceEntity creditAvailableWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      AssetLedgerEntryType entryType
  ) {
    return creditAvailableWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, entryType.code());
  }

  @Transactional
  public WalletBalanceEntity debitAvailable(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    return debitAvailableWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, AssetLedgerEntryType.DEBIT_AVAILABLE.code());
  }

  @Transactional
  public WalletBalanceEntity debitAvailableWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, asset);
    ensureEnough(balance.getAvailable(), normalizedAmount, "AVAILABLE_BALANCE_NOT_ENOUGH",
        "Available balance is not enough");
    balance.setTotal(scale(balance.getTotal().subtract(normalizedAmount)));
    balance.setAvailable(scale(balance.getAvailable().subtract(normalizedAmount)));
    persist(balance, normalizedAmount.negate(), entryType, referenceType, referenceId, description);
    return balance;
  }

  @Transactional
  public WalletBalanceEntity debitAvailableWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      AssetLedgerEntryType entryType
  ) {
    return debitAvailableWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, entryType.code());
  }

  @Transactional
  public WalletBalanceEntity lockAvailable(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    return lockAvailableWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, AssetLedgerEntryType.LOCK_AVAILABLE.code());
  }

  @Transactional
  public WalletBalanceEntity lockAvailableWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, asset);
    ensureEnough(balance.getAvailable(), normalizedAmount, "AVAILABLE_BALANCE_NOT_ENOUGH",
        "Available balance is not enough");
    balance.setAvailable(scale(balance.getAvailable().subtract(normalizedAmount)));
    balance.setLocked(scale(balance.getLocked().add(normalizedAmount)));
    persist(balance, normalizedAmount.negate(), entryType, referenceType, referenceId, description);
    return balance;
  }

  @Transactional
  public WalletBalanceEntity lockAvailableWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      AssetLedgerEntryType entryType
  ) {
    return lockAvailableWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, entryType.code());
  }

  @Transactional
  public WalletBalanceEntity releaseLocked(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    return releaseLockedWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, AssetLedgerEntryType.RELEASE_LOCKED.code());
  }

  @Transactional
  public WalletBalanceEntity debitLockedWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, asset);
    ensureEnough(balance.getLocked(), normalizedAmount, "LOCKED_BALANCE_NOT_ENOUGH",
        "Locked balance is not enough");
    balance.setTotal(scale(balance.getTotal().subtract(normalizedAmount)));
    balance.setLocked(scale(balance.getLocked().subtract(normalizedAmount)));
    persist(balance, normalizedAmount.negate(), entryType, referenceType, referenceId, description);
    return balance;
  }

  @Transactional
  public WalletBalanceEntity debitLockedWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      AssetLedgerEntryType entryType
  ) {
    return debitLockedWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, entryType.code());
  }

  @Transactional
  public WalletBalanceEntity releaseLockedWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, asset);
    ensureEnough(balance.getLocked(), normalizedAmount, "LOCKED_BALANCE_NOT_ENOUGH",
        "Locked balance is not enough");
    balance.setAvailable(scale(balance.getAvailable().add(normalizedAmount)));
    balance.setLocked(scale(balance.getLocked().subtract(normalizedAmount)));
    persist(balance, normalizedAmount, entryType, referenceType, referenceId, description);
    return balance;
  }

  @Transactional
  public WalletBalanceEntity releaseLockedWithEntryType(
      UUID accountId,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      AssetLedgerEntryType entryType
  ) {
    return releaseLockedWithEntryType(
        accountId, asset, amount, referenceType, referenceId, description, entryType.code());
  }

  private void persist(
      WalletBalanceEntity balance,
      BigDecimal ledgerAmount,
      String entryType,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    walletBalanceRepository.save(balance);
    AssetLedgerEntryEntity entry = new AssetLedgerEntryEntity();
    entry.setAccountId(balance.getAccountId());
    entry.setAsset(balance.getAsset());
    entry.setAmount(scale(ledgerAmount));
    entry.setBalanceAfter(scale(balance.getAvailable()));
    entry.setEntryType(entryType);
    entry.setReferenceType(referenceType);
    entry.setReferenceId(referenceId);
    entry.setDescription(description);
    assetLedgerEntryRepository.save(entry);
  }

  private static WalletBalanceEntity newBalance(UUID accountId, String asset) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setAccountId(accountId);
    balance.setAsset(asset);
    balance.setTotal(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    balance.setAvailable(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    balance.setLocked(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    return balance;
  }

  private static String normalizeAsset(String asset) {
    if (!StringUtils.hasText(asset)) {
      throw new BusinessException("ASSET_REQUIRED", "Asset is required");
    }
    return asset.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeOptional(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal positiveAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new BusinessException("AMOUNT_MUST_BE_POSITIVE", "Amount must be positive");
    }
    return scale(amount);
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static void ensureEnough(
      BigDecimal available,
      BigDecimal amount,
      String code,
      String message
  ) {
    if (available.compareTo(amount) < 0) {
      throw new BusinessException(code, message);
    }
  }
}
