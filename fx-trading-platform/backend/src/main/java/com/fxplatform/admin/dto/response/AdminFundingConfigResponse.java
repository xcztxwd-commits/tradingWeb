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
    String fallbackReason,
    String sourceMode,
    Instant asOf,
    Instant nextFundingTime
) {

  public static AdminFundingConfigResponse from(SymbolEntity symbol, FundingRateEntity latest) {
    List<String> priority = symbol.getFundingSourcePriority() == null
        ? List.of()
        : List.copyOf(symbol.getFundingSourcePriority());
    String selectedSource = latest == null ? null : actualSource(latest.getProviderCode());
    return new AdminFundingConfigResponse(
        symbol.getId(),
        symbol.getSymbol(),
        priority,
        symbol.getFixedFundingRate(),
        symbol.getFixedFundingIntervalMinutes(),
        symbol.getFundingStaleSeconds(),
        selectedSource,
        fallbackReason(priority, selectedSource),
        latest == null ? null : latest.getSourceMode(),
        latest == null ? null : latest.getAsOf(),
        latest == null ? null : latest.getNextFundingTime());
  }

  public AdminFundingConfigResponse(
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
    this(
        symbolId,
        symbol,
        fundingSourcePriority,
        fixedFundingRate,
        fixedFundingIntervalMinutes,
        fundingStaleSeconds,
        actualSource,
        null,
        sourceMode,
        asOf,
        nextFundingTime);
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

  private static String fallbackReason(List<String> priority, String actualSource) {
    if (actualSource == null || priority == null || priority.isEmpty()) {
      return null;
    }
    List<String> normalized = priority.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(value -> value.trim().toUpperCase(Locale.ROOT))
        .toList();
    int selectedIndex = normalized.indexOf(actualSource);
    if (selectedIndex <= 0) {
      return null;
    }
    List<String> skipped = normalized.subList(0, selectedIndex);
    return String.join("_", skipped) + "_UNAVAILABLE_OR_STALE";
  }
}
