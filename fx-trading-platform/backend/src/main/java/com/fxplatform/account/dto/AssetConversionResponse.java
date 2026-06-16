package com.fxplatform.account.dto;

import com.fxplatform.wallet.service.AssetConversionService;
import java.math.BigDecimal;
import java.util.UUID;

public record AssetConversionResponse(
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

  public static AssetConversionResponse from(AssetConversionService.ConversionResult result) {
    return new AssetConversionResponse(
        result.accountId(),
        result.fromWalletType(),
        result.fromAsset(),
        result.toWalletType(),
        result.toAsset(),
        result.fromAmount(),
        result.toAmount(),
        result.rate(),
        result.conversionId());
  }
}
