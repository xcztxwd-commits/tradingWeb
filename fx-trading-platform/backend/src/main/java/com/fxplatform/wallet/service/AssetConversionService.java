package com.fxplatform.wallet.service;

import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetConversionService {

  private static final int MONEY_SCALE = 8;
  private static final BigDecimal DEMO_USDT_USD_RATE = BigDecimal.ONE;
  private static final String REFERENCE_TYPE = "ASSET_CONVERSION";
  private static final String CONVERT_OUT = "CONVERT_OUT";
  private static final String CONVERT_IN = "CONVERT_IN";

  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final DemoExecutionGuard demoExecutionGuard;

  public AssetConversionService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this.accountRepository = accountRepository;
    this.walletService = walletService;
    this.demoExecutionGuard = demoExecutionGuard;
  }

  @Transactional
  public ConversionResult convert(
      UUID userId,
      UUID accountId,
      WalletType fromWalletType,
      String fromAsset,
      WalletType toWalletType,
      String toAsset,
      BigDecimal amount,
      UUID conversionId
  ) {
    var account = accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemoAccount(account);

    String normalizedFromAsset = normalizeAsset(fromAsset);
    String normalizedToAsset = normalizeAsset(toAsset);
    BigDecimal normalizedAmount = normalizeAmount(amount);
    if (conversionId == null) {
      throw new BusinessException(
          "ASSET_CONVERSION_ID_REQUIRED", "Asset conversion id is required");
    }
    if (fromWalletType == null
        || toWalletType == null
        || fromWalletType == WalletType.USDT_PERP
        || toWalletType == WalletType.USDT_PERP
        || !"USDT".equals(normalizedFromAsset)
        || !"USD".equals(normalizedToAsset)) {
      throw new BusinessException("UNSUPPORTED_CONVERSION_PAIR", "Only USDT to USD demo conversion is supported");
    }
    ConversionResult replay = replayIfExact(
        accountId,
        fromWalletType,
        normalizedFromAsset,
        toWalletType,
        normalizedToAsset,
        normalizedAmount,
        conversionId);
    if (replay != null) {
      return replay;
    }
    List<WalletService.WalletKey> walletKeys = List.of(
            new WalletService.WalletKey(fromWalletType, normalizedFromAsset),
            new WalletService.WalletKey(toWalletType, normalizedToAsset))
        .stream()
        .sorted(Comparator
            .comparing((WalletService.WalletKey key) -> key.walletType().code())
            .thenComparing(WalletService.WalletKey::asset))
        .toList();
    walletService.lockWalletsInOrder(accountId, walletKeys);
    walletService.debitAvailableWithEntryType(
        accountId,
        fromWalletType,
        normalizedFromAsset,
        normalizedAmount,
        REFERENCE_TYPE,
        conversionId,
        "Demo asset conversion out",
        CONVERT_OUT);
    walletService.creditAvailableWithEntryType(
        accountId,
        toWalletType,
        normalizedToAsset,
        normalizedAmount.multiply(DEMO_USDT_USD_RATE),
        REFERENCE_TYPE,
        conversionId,
        "Demo asset conversion in",
        CONVERT_IN);
    return new ConversionResult(
        accountId,
        fromWalletType.code(),
        normalizedFromAsset,
        toWalletType.code(),
        normalizedToAsset,
        normalizedAmount,
        normalizedAmount.multiply(DEMO_USDT_USD_RATE),
        DEMO_USDT_USD_RATE,
        conversionId);
  }

  private ConversionResult replayIfExact(
      UUID accountId,
      WalletType fromWalletType,
      String fromAsset,
      WalletType toWalletType,
      String toAsset,
      BigDecimal amount,
      UUID conversionId
  ) {
    List<AssetLedgerEntryEntity> entries = walletService.assetLedgerEntriesByReference(
        accountId, REFERENCE_TYPE, conversionId);
    if (entries == null || entries.isEmpty()) {
      return null;
    }
    boolean exact = entries.size() == 2
        && entries.stream().anyMatch(entry -> matches(
            entry, fromWalletType, fromAsset, CONVERT_OUT, amount.negate()))
        && entries.stream().anyMatch(entry -> matches(
            entry, toWalletType, toAsset, CONVERT_IN, amount));
    if (!exact) {
      throw new BusinessException(
          "ASSET_CONVERSION_REQUEST_CONFLICT",
          "Asset conversion id was already used with a different request");
    }
    return new ConversionResult(
        accountId,
        fromWalletType.code(),
        fromAsset,
        toWalletType.code(),
        toAsset,
        amount,
        amount.multiply(DEMO_USDT_USD_RATE),
        DEMO_USDT_USD_RATE,
        conversionId);
  }

  private static boolean matches(
      AssetLedgerEntryEntity entry,
      WalletType walletType,
      String asset,
      String operationType,
      BigDecimal amount
  ) {
    return entry != null
        && walletType.code().equals(entry.getWalletType())
        && asset.equals(entry.getAsset())
        && operationType.equals(entry.getOperationType())
        && entry.getAmount() != null
        && amount.compareTo(entry.getAmount()) == 0;
  }

  private static String normalizeAsset(String asset) {
    if (asset == null || asset.isBlank()) {
      throw new BusinessException("ASSET_REQUIRED", "Asset is required");
    }
    return asset.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal normalizeAmount(BigDecimal amount) {
    if (amount == null) {
      throw new BusinessException("AMOUNT_MUST_BE_POSITIVE", "Amount must be positive");
    }
    BigDecimal normalized = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (normalized.signum() <= 0) {
      throw new BusinessException("AMOUNT_MUST_BE_POSITIVE", "Amount must be positive");
    }
    return normalized;
  }

  public record ConversionResult(
      UUID accountId,
      String fromWalletType,
      String fromAsset,
      String toWalletType,
      String toAsset,
      BigDecimal fromAmount,
      BigDecimal toAmount,
      BigDecimal rate,
      UUID conversionId
  ) {
  }
}
