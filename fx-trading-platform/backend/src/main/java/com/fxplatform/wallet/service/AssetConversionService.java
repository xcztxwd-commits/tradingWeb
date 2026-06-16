package com.fxplatform.wallet.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssetConversionService {

  private static final BigDecimal DEMO_USDT_USD_RATE = BigDecimal.ONE;

  private final WalletService walletService;

  @Transactional
  public ConversionResult convert(
      UUID accountId,
      WalletType fromWalletType,
      String fromAsset,
      WalletType toWalletType,
      String toAsset,
      BigDecimal amount,
      UUID conversionId
  ) {
    String normalizedFromAsset = normalizeAsset(fromAsset);
    String normalizedToAsset = normalizeAsset(toAsset);
    if (!"USDT".equals(normalizedFromAsset) || !"USD".equals(normalizedToAsset)) {
      throw new BusinessException("UNSUPPORTED_CONVERSION_PAIR", "Only USDT to USD demo conversion is supported");
    }
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
