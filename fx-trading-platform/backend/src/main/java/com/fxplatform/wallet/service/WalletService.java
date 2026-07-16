package com.fxplatform.wallet.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.AssetLedgerEntryType;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
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

  public record WalletKey(WalletType walletType, String asset) {
  }

  private static final int MONEY_SCALE = 8;
  private static final WalletType DEFAULT_WALLET_TYPE = WalletType.SPOT;

  private final WalletBalanceRepository walletBalanceRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;

  public Optional<WalletBalanceEntity> getBalance(UUID accountId, String asset) {
    return getBalance(accountId, DEFAULT_WALLET_TYPE, asset);
  }

  public Optional<WalletBalanceEntity> getBalance(UUID accountId, WalletType walletType, String asset) {
    return walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(
        accountId,
        normalizeWalletType(walletType),
        normalizeAsset(asset));
  }

  public List<WalletBalanceEntity> balances(UUID accountId) {
    return walletBalanceRepository.findByAccountIdOrderByAssetAsc(accountId);
  }

  @Transactional
  public List<WalletBalanceEntity> lockAllBalances(UUID accountId) {
    return walletBalanceRepository.findByAccountIdForUpdate(accountId);
  }

  @Transactional
  public WalletBalanceEntity resetBalance(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal target,
      UUID requestId,
      String description
  ) {
    if (target == null || target.signum() < 0 || requestId == null) {
      throw new BusinessException("INVALID_RESET_BALANCE", "Reset balance is invalid");
    }
    BigDecimal normalizedTarget = scale(target);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, walletType, asset);
    if (findExistingOperation(
        balance,
        AssetLedgerEntryType.DEMO_RESET.name(),
        "DEMO_RESET",
        requestId) != null) {
      return balance;
    }
    BigDecimal delta = normalizedTarget.subtract(scale(balance.getTotal()));
    balance.setTotal(normalizedTarget);
    balance.setAvailable(normalizedTarget);
    balance.setLocked(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    persist(
        balance,
        delta,
        AssetLedgerEntryType.DEMO_RESET.name(),
        "DEMO_RESET",
        requestId,
        description);
    return balance;
  }

  @Transactional
  public List<WalletBalanceEntity> lockBalancesInOrder(UUID accountId, Collection<String> assets) {
    return assets.stream()
        .map(WalletService::normalizeAsset)
        .filter(StringUtils::hasText)
        .distinct()
        .sorted()
        .map(asset -> getOrCreateBalance(accountId, DEFAULT_WALLET_TYPE, asset))
        .toList();
  }

  @Transactional
  public List<WalletBalanceEntity> lockWalletsInOrder(
      UUID accountId,
      List<WalletKey> walletKeys
  ) {
    if (walletKeys == null) {
      throw new BusinessException("WALLET_KEYS_REQUIRED", "Wallet keys are required");
    }
    return walletKeys.stream()
        .map(key -> new WalletKey(
            key == null || key.walletType() == null ? DEFAULT_WALLET_TYPE : key.walletType(),
            normalizeAsset(key == null ? null : key.asset())))
        .distinct()
        .sorted(Comparator
            .comparing((WalletKey key) -> key.walletType().code())
            .thenComparing(WalletKey::asset))
        .map(key -> getOrCreateBalance(accountId, key.walletType(), key.asset()))
        .toList();
  }

  public List<AssetLedgerEntryEntity> assetLedgerEntries(
      UUID accountId,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    return assetLedgerEntries(accountId, null, asset, entryType, referenceId, from, to);
  }

  public List<AssetLedgerEntryEntity> assetLedgerEntries(
      UUID accountId,
      String walletType,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    return assetLedgerEntryRepository.findByFilters(
        accountId,
        normalizeOptionalWalletType(walletType),
        normalizeOptional(asset),
        normalizeOptional(entryType),
        referenceId,
        from,
        to);
  }

  public List<AssetLedgerEntryEntity> assetLedgerEntriesByReference(
      UUID accountId,
      String referenceType,
      UUID referenceId
  ) {
    return assetLedgerEntryRepository.findByReference(
        accountId,
        normalizeOptional(referenceType),
        referenceId);
  }

  public boolean hasBusinessOperation(
      UUID accountId,
      WalletType walletType,
      String asset,
      String referenceType,
      UUID referenceId,
      String operationType
  ) {
    return assetLedgerEntryRepository.findByBusinessOperation(
        accountId,
        normalizeWalletType(walletType),
        normalizeAsset(asset),
        normalizeOptional(referenceType),
        referenceId,
        normalizeOptional(operationType)) != null;
  }

  @Transactional
  public WalletBalanceEntity getOrCreateBalance(UUID accountId, String asset) {
    return getOrCreateBalance(accountId, DEFAULT_WALLET_TYPE, asset);
  }

  @Transactional
  public WalletBalanceEntity getOrCreateBalance(UUID accountId, WalletType walletType, String asset) {
    String normalizedAsset = normalizeAsset(asset);
    String normalizedWalletType = normalizeWalletType(walletType);
    return walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
            accountId,
            normalizedWalletType,
            normalizedAsset)
        .orElseGet(() -> walletBalanceRepository.save(newBalance(accountId, normalizedWalletType, normalizedAsset)));
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
    return creditAvailableWithEntryType(
        accountId, DEFAULT_WALLET_TYPE, asset, amount, referenceType, referenceId, description, entryType);
  }

  @Transactional
  public WalletBalanceEntity creditAvailableWithEntryType(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, walletType, asset);
    if (findExistingOperation(balance, entryType, referenceType, referenceId) != null) {
      return balance;
    }
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
    return debitAvailableWithEntryType(
        accountId, DEFAULT_WALLET_TYPE, asset, amount, referenceType, referenceId, description, entryType);
  }

  @Transactional
  public WalletBalanceEntity debitAvailable(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    return debitAvailableWithEntryType(
        accountId, walletType, asset, amount, referenceType, referenceId, description, AssetLedgerEntryType.DEBIT_AVAILABLE.code());
  }

  @Transactional
  public WalletBalanceEntity debitAvailableWithEntryType(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, walletType, asset);
    if (findExistingOperation(balance, entryType, referenceType, referenceId) != null) {
      return balance;
    }
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
    return lockAvailableWithEntryType(
        accountId, DEFAULT_WALLET_TYPE, asset, amount, referenceType, referenceId, description, entryType);
  }

  @Transactional
  public WalletBalanceEntity lockAvailableWithEntryType(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, walletType, asset);
    if (findExistingOperation(balance, entryType, referenceType, referenceId) != null) {
      return balance;
    }
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
    return debitLockedWithEntryType(
        accountId, DEFAULT_WALLET_TYPE, asset, amount, referenceType, referenceId, description, entryType);
  }

  @Transactional
  public WalletBalanceEntity debitLockedWithEntryType(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, walletType, asset);
    if (findExistingOperation(balance, entryType, referenceType, referenceId) != null) {
      return balance;
    }
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
    return releaseLockedWithEntryType(
        accountId, DEFAULT_WALLET_TYPE, asset, amount, referenceType, referenceId, description, entryType);
  }

  @Transactional
  public WalletBalanceEntity releaseLockedWithEntryType(
      UUID accountId,
      WalletType walletType,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    BigDecimal normalizedAmount = positiveAmount(amount);
    WalletBalanceEntity balance = getOrCreateBalance(accountId, walletType, asset);
    if (findExistingOperation(balance, entryType, referenceType, referenceId) != null) {
      return balance;
    }
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
    entry.setWalletType(balance.getWalletType());
    entry.setAsset(balance.getAsset());
    entry.setAmount(scale(ledgerAmount));
    entry.setBalanceAfter(scale(balance.getAvailable()));
    entry.setEntryType(entryType);
    entry.setOperationType(entryType);
    entry.setReferenceType(referenceType);
    entry.setReferenceId(referenceId);
    entry.setDescription(description);
    assetLedgerEntryRepository.save(entry);
  }

  private AssetLedgerEntryEntity findExistingOperation(
      WalletBalanceEntity balance,
      String entryType,
      String referenceType,
      UUID referenceId
  ) {
    return assetLedgerEntryRepository.findByBusinessOperation(
        balance.getAccountId(),
        balance.getWalletType(),
        balance.getAsset(),
        normalizeOptional(referenceType),
        referenceId,
        normalizeOptional(entryType));
  }

  private static WalletBalanceEntity newBalance(UUID accountId, String walletType, String asset) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setAccountId(accountId);
    balance.setWalletType(walletType);
    balance.setAsset(asset);
    balance.setTotal(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    balance.setAvailable(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    balance.setLocked(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    return balance;
  }

  private static String normalizeWalletType(WalletType walletType) {
    return (walletType == null ? DEFAULT_WALLET_TYPE : walletType).code();
  }

  private static String normalizeOptionalWalletType(String walletType) {
    if (!StringUtils.hasText(walletType)) {
      return null;
    }
    return WalletType.fromCode(walletType).code();
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
