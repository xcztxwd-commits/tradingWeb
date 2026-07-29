package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.execution.ExecutionProperties;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DefaultTradingLabConfigProviderTest {

  private final ObjectMapper json = new ObjectMapper();
  private final TradingLabScenarioCanonicalizer canonicalizer =
      new TradingLabScenarioCanonicalizer(json, 1024 * 1024);
  private final SymbolRepository symbols = mock(SymbolRepository.class);
  private final InstrumentRulesEngine rules = mock(InstrumentRulesEngine.class);

  @Test
  void returnsOnlyTradableSpotAndPerpetualRulesWithCanonicalStringDecimals() {
    SymbolEntity spot = symbol("XBT-USDT-LAB", ProductType.CRYPTO_SPOT, true);
    SymbolEntity perpetual = symbol("ETHUSDT", ProductType.LINEAR_PERP, true);
    SymbolEntity blocked = symbol("XRPUSDT", ProductType.CRYPTO_SPOT, false);
    when(symbols.findByEnabledTrueOrderBySymbolAsc())
        .thenReturn(List.of(spot, perpetual, blocked));
    when(rules.rules(spot)).thenReturn(rules(spot));
    when(rules.rules(perpetual)).thenReturn(rules(perpetual));

    ExecutionProperties execution = new ExecutionProperties();
    execution.getBroker().setApiKey("must-not-leak");
    MockEnvironment environment = new MockEnvironment()
        .withProperty("trading-lab.model-version", "model-v7")
        .withProperty("TRADING_LAB_CODE_VERSION", "build-abc123");
    DefaultTradingLabConfigProvider provider = new DefaultTradingLabConfigProvider(
        symbols,
        rules,
        execution,
        canonicalizer,
        json,
        environment);

    var result = provider.current();

    assertThat(result.modelVersion()).isEqualTo("model-v7");
    assertThat(result.codeVersion()).isEqualTo("build-abc123");
    assertThat(result.configSnapshotHash())
        .isEqualTo(canonicalizer.canonicalize(result.configSnapshot()).sha256())
        .matches("[0-9a-f]{64}");
    assertThat(result.configSnapshot().path("instruments")).hasSize(2);
    assertThat(result.configSnapshot().path("instruments").path(0).path("symbol").asText())
        .isEqualTo("XBT-USDT-LAB");
    assertThat(result.configSnapshot().path("instruments").path(0).path("baseAsset").asText())
        .isEqualTo("XBT");
    assertThat(result.configSnapshot().path("instruments").path(0).path("quoteAsset").asText())
        .isEqualTo("USDT");
    assertThat(result.configSnapshot().path("instruments").path(1).path("productType").asText())
        .isEqualTo("LINEAR_PERP");
    assertThat(result.configSnapshot().path("instruments").path(0).path("tickSize").asText())
        .isEqualTo("0.01");
    assertThat(result.configSnapshot().path("instruments").path(0).path("stepSize").asText())
        .isEqualTo("0.001");
    assertThat(result.configSnapshot().path("instruments").path(0)
        .path("initialMarginRate").asText()).isEqualTo("0.01");
    assertThat(result.configSnapshot().path("executionPolicy").path("makerFeeRate").isTextual())
        .isTrue();
    assertThat(result.configSnapshot().toString())
        .doesNotContain(
            "must-not-leak",
            "\"apiKey\"",
            "\"broker\"",
            "\"fix\"",
            "\"lp\"");

    ObjectNode symbolDocument = json.createObjectNode();
    symbolDocument.set(
        "instruments",
        result.configSnapshot().path("instruments").deepCopy());
    assertThat(result.symbolConfigVersion())
        .isEqualTo(canonicalizer.canonicalize(symbolDocument).sha256());
    verify(rules, never()).rules(blocked);
  }

  @Test
  void localCodeVersionIsExplicitlyMarkedAsWorkingTree() {
    when(symbols.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of());
    DefaultTradingLabConfigProvider provider = new DefaultTradingLabConfigProvider(
        symbols,
        rules,
        new ExecutionProperties(),
        canonicalizer,
        json,
        new MockEnvironment());

    assertThat(provider.current().codeVersion())
        .isEqualTo("local+working-tree")
        .contains("working-tree");
  }

  @Test
  void rejectsAnInconsistentInstrumentRulesSnapshot() {
    SymbolEntity spot = symbol("BTCUSDT", ProductType.CRYPTO_SPOT, true);
    when(symbols.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(spot));
    when(rules.rules(spot)).thenReturn(InstrumentRules.missing("BTCUSDT"));
    DefaultTradingLabConfigProvider provider = new DefaultTradingLabConfigProvider(
        symbols,
        rules,
        new ExecutionProperties(),
        canonicalizer,
        json,
        new MockEnvironment());

    assertThatThrownBy(provider::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incomplete or inconsistent");
  }

  @Test
  void rejectsMissingAuthoritativeBaseOrQuoteAssetWithoutInferringFromSymbol() {
    SymbolEntity spot = symbol("XBT-USDT-LAB", ProductType.CRYPTO_SPOT, true);
    spot.setBaseCurrency(" ");
    when(symbols.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(spot));
    when(rules.rules(spot)).thenReturn(rules(spot));
    DefaultTradingLabConfigProvider provider = new DefaultTradingLabConfigProvider(
        symbols,
        rules,
        new ExecutionProperties(),
        canonicalizer,
        json,
        new MockEnvironment());

    assertThatThrownBy(provider::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incomplete or inconsistent");
  }

  private static SymbolEntity symbol(
      String code,
      ProductType productType,
      boolean tradable
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(code);
    symbol.setProductType(productType);
    symbol.setBaseCurrency(code.startsWith("XBT") ? "XBT" : "ETH");
    symbol.setQuoteCurrency("USDT");
    symbol.setEnabled(true);
    symbol.setTradable(tradable);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005000"));
    symbol.setLiquidationFeeRate(new BigDecimal("0.002000"));
    symbol.setFixedFundingRate(new BigDecimal("0.000100"));
    symbol.setFixedFundingIntervalMinutes(480);
    symbol.setMarkPriceSource(productType == ProductType.CRYPTO_SPOT
        ? "quote_mid"
        : "provider_mark");
    return symbol;
  }

  private static InstrumentRules rules(SymbolEntity symbol) {
    return new InstrumentRules(
        symbol.getSymbol(),
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        symbol.getProductType(),
        new BigDecimal("0.0100"),
        new BigDecimal("0.0010"),
        new BigDecimal("0.0010"),
        new BigDecimal("100.000"),
        new BigDecimal("10.00"),
        new BigDecimal("1000000.00"),
        new BigDecimal("0.0010"),
        new BigDecimal("100.000"),
        100,
        symbol.getProductType() == ProductType.CRYPTO_SPOT ? 1 : 20,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        "TIER_1",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }
}
