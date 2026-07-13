package com.fxplatform.admin.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.trading.entity.FundingRateEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminFundingConfigResponseTest {

  @Test
  void noLatestRateOrSelectedPrimaryHasNoFallbackReason() {
    SymbolEntity symbol = symbol(List.of("BINANCE", "OKX", "FIXED"));

    assertThat(AdminFundingConfigResponse.from(symbol, null).fallbackReason()).isNull();
    assertThat(AdminFundingConfigResponse.from(symbol, rate("binance-usdm")).fallbackReason()).isNull();
  }

  @Test
  void configuredPrimaryStillHasNoFallbackReasonWhenPriorityIsReordered() {
    SymbolEntity symbol = symbol(List.of("OKX", "BINANCE", "FIXED"));

    assertThat(AdminFundingConfigResponse.from(symbol, rate("okx-swap")).fallbackReason()).isNull();
  }

  @Test
  void okxAfterBinanceReportsBinanceUnavailableOrStale() {
    SymbolEntity symbol = symbol(List.of("BINANCE", "OKX", "FIXED"));

    assertThat(AdminFundingConfigResponse.from(symbol, rate("okx-swap")).fallbackReason())
        .isEqualTo("BINANCE_UNAVAILABLE_OR_STALE");
  }

  @Test
  void fixedAfterPublicSourcesReportsBothUnavailableOrStale() {
    SymbolEntity symbol = symbol(List.of("BINANCE", "OKX", "FIXED"));

    assertThat(AdminFundingConfigResponse.from(symbol, rate("fixed")).fallbackReason())
        .isEqualTo("BINANCE_OKX_UNAVAILABLE_OR_STALE");
  }

  @Test
  void fallbackReasonFollowsConfiguredPriorityInsteadOfAssumingCanonicalOrder() {
    assertThat(AdminFundingConfigResponse.from(symbol(List.of("OKX", "FIXED")), rate("fixed")).fallbackReason())
        .isEqualTo("OKX_UNAVAILABLE_OR_STALE");
    assertThat(AdminFundingConfigResponse.from(symbol(List.of("BINANCE", "FIXED")), rate("fixed")).fallbackReason())
        .isEqualTo("BINANCE_UNAVAILABLE_OR_STALE");
  }

  private SymbolEntity symbol(List<String> priority) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol("BTCUSDT-PERP");
    symbol.setFundingSourcePriority(priority);
    symbol.setFixedFundingRate(new BigDecimal("0.0001"));
    symbol.setFixedFundingIntervalMinutes(480);
    symbol.setFundingStaleSeconds(900);
    return symbol;
  }

  private FundingRateEntity rate(String providerCode) {
    FundingRateEntity rate = new FundingRateEntity();
    rate.setProviderCode(providerCode);
    return rate;
  }
}
