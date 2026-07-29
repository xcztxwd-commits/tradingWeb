package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import org.junit.jupiter.api.Test;

class TradingLabBrowserValidationWireContractTest {

  @Test
  void strictValidationRuntimeTypesAcceptTheCompiledBrowserWire() throws Exception {
    String config = TradingLabBrowserScenarioCompilerTest.configJson();
    String hash = TradingLabBrowserScenarioCompilerTest.sha256(config);
    ValidationRunStartRequest clientRequest =
        TradingLabBrowserScenarioCompilerTest.factory().compile(
            TradingLabBrowserScenarioCompilerTest.source(
                TradingLabBrowserScenarioCompilerTest.browserScenario(config, hash),
                config,
                hash),
            7L);
    ObjectMapper strict = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    StartRequest runtime = strict.readValue(
        strict.writeValueAsBytes(clientRequest),
        StartRequest.class);

    assertThat(runtime.runId()).isEqualTo(TradingLabBrowserScenarioCompilerTest.RUN_ID);
    assertThat(runtime.generation()).isEqualTo(7L);
    assertThat(runtime.ticks()).hasSize(2);
    assertThat(runtime.actions()).hasSize(2);
    assertThat(runtime.ticks().getFirst().spotBundles()).hasSize(1);
    assertThat(runtime.ticks().getFirst().perpetualBundles()).hasSize(1);
  }

  @Test
  void compiledSpotOrderCarriesTheCashMarginModeRequiredByTheOrderApi() throws Exception {
    String config = TradingLabBrowserScenarioCompilerTest.spotOnlyConfigJson();
    String hash = TradingLabBrowserScenarioCompilerTest.sha256(config);
    ValidationRunStartRequest clientRequest =
        TradingLabBrowserScenarioCompilerTest.factory().compile(
            TradingLabBrowserScenarioCompilerTest.source(
                TradingLabBrowserScenarioCompilerTest.realisticSpotScenario(config, hash),
                config,
                hash),
            3L);
    ObjectMapper strict = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    StartRequest runtime = strict.readValue(
        strict.writeValueAsBytes(clientRequest),
        StartRequest.class);

    assertThat(runtime.actions()).singleElement().satisfies(action ->
        assertThat(action.payload()).containsEntry("marginMode", "CASH"));
  }

  @Test
  void exactNegativeBusinessViolationSurvivesTheStrictRuntimeWire() throws Exception {
    String config = TradingLabBrowserScenarioCompilerTest.spotOnlyConfigJson();
    String hash = TradingLabBrowserScenarioCompilerTest.sha256(config);
    ValidationRunStartRequest clientRequest =
        TradingLabBrowserScenarioCompilerTest.factory().compile(
            TradingLabBrowserScenarioCompilerTest.source(
                TradingLabBrowserScenarioCompilerTest
                    .negativeSpotBusinessViolationScenario(config, hash),
                config,
                hash),
            5L);
    ObjectMapper strict = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    StartRequest runtime = strict.readValue(
        strict.writeValueAsBytes(clientRequest),
        StartRequest.class);

    assertThat(runtime.actions()).singleElement().satisfies(action -> {
      assertThat(action.payload())
          .containsEntry("quantityUnit", "BASE")
          .containsEntry("marginMode", "CROSS");
      assertThat(action.expectedError().status()).isEqualTo(400);
      assertThat(action.expectedError().code()).isEqualTo("INVALID_QUANTITY_UNIT");
    });
  }

  @Test
  void rejectsNonCanonicalPerpetualSymbolsBeforeTheStrictRuntimeWire() {
    String config = TradingLabBrowserScenarioCompilerTest.configJson()
        .replace("ETHUSDT-PERP", "ethusdt-perp");
    String hash = TradingLabBrowserScenarioCompilerTest.sha256(config);
    String scenario =
        TradingLabBrowserScenarioCompilerTest.browserScenario(config, hash)
            .replace("ETHUSDT-PERP", "ethusdt-perp");

    assertThatThrownBy(() ->
        TradingLabBrowserScenarioCompilerTest.factory().compile(
            TradingLabBrowserScenarioCompilerTest.source(
                scenario,
                config,
                hash),
            7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("symbol");
  }
}
