package com.fxplatform.wallet.service;

import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetConversionService {

  private static final BigDecimal DEMO_USDT_USD_RATE = BigDecimal.ONE;

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
    if (!"USDT".equals(normalizedFromAsset) || !"USD".equals(normalizedToAsset)) {
      throw new BusinessException("UNSUPPORTED_CONVERSION_PAIR", "Only USDT to USD demo conversion is supported");
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
        amount,
        "ASSET_CONVERSION",
        conversionId,
        "Demo asset conversion out",
        "CONVERT_OUT");
    walletService.creditAvailableWithEntryType(
        accountId,
        toWalletType,
        normalizedToAsset,
        amount.multiply(DEMO_USDT_USD_RATE),
        "ASSET_CONVERSION",
        conversionId,
        "Demo asset conversion in",
        "CONVERT_IN");
    return new ConversionResult(
        accountId,
        fromWalletType.code(),
        normalizedFromAsset,
        toWalletType.code(),
        normalizedToAsset,
        amount,
        amount.multiply(DEMO_USDT_USD_RATE),
        DEMO_USDT_USD_RATE,
        conversionId);
  }

  private static String normalizeAsset(String asset) {
    if (asset == null || asset.isBlank()) {
      throw new BusinessException("ASSET_REQUIRED", "Asset is required");
    }
    return asset.trim().toUpperCase(Locale.ROOT);
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
