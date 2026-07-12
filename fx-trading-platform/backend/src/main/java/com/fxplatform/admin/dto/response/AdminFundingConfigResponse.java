package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.trading.entity.FundingRateEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record AdminFundingConfigResponse(
    UUID symbolId,
    String symbol,
    List<String> fundingSourcePriority,
    BigDecimal fixedFundingRate,
    Integer fixedFundingIntervalMinutes,
    Integer fundingStaleSeconds,
    String actualSource,
    String sourceMode,
    Instant asOf,
    Instant nextFundingTime
) {

  public static AdminFundingConfigResponse from(SymbolEntity symbol, FundingRateEntity latest) {
    return new AdminFundingConfigResponse(
        symbol.getId(),
        symbol.getSymbol(),
        symbol.getFundingSourcePriority() == null
            ? List.of()
            : List.copyOf(symbol.getFundingSourcePriority()),
        symbol.getFixedFundingRate(),
        symbol.getFixedFundingIntervalMinutes(),
        symbol.getFundingStaleSeconds(),
        latest == null ? null : actualSource(latest.getProviderCode()),
        latest == null ? null : latest.getSourceMode(),
        latest == null ? null : latest.getAsOf(),
        latest == null ? null : latest.getNextFundingTime());
  }

  private static String actualSource(String providerCode) {
    if (providerCode == null || providerCode.isBlank()) {
      return null;
    }
    return switch (providerCode.trim().toLowerCase(Locale.ROOT)) {
      case "binance", "binance-usdm" -> "BINANCE";
      case "okx", "okx-swap" -> "OKX";
      case "fixed" -> "FIXED";
      default -> providerCode.trim().toUpperCase(Locale.ROOT);
    };
  }
}
