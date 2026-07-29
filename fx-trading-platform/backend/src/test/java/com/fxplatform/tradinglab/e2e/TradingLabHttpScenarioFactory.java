package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import java.math.BigDecimal;

/**
 * Builds strict browser-model fixtures and encodes the HTTP payload through production request
 * DTOs. It has no application service, repository, JDBC, or validation-runtime dependency.
 */
final class TradingLabHttpScenarioFactory {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String SPOT_SYMBOL = "BTCUSDT";
  private static final String VIRTUAL_START = "2026-07-25T00:00:00Z";
  private static final String SPOT_PATH_PRICE = "50000.00";
  private static final String INITIAL_USDT = "100000.00000000";
  private static final String QUOTE_BUDGET = "500.00000000";

  private TradingLabHttpScenarioFactory() {
  }

  static SpotScenario spot(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    JsonNode instrument = requiredSpotInstrument(frozen.snapshot());
    String baseAsset = requiredText(instrument, "baseAsset");
    String quoteAsset = requiredText(instrument, "quoteAsset");
    BigDecimal takerFeeRate = decimal(
        frozen.snapshot().path("executionPolicy"),
        "takerFeeRate");
    ObjectNode scenario = browserScenario(
        frozen,
        "phase4-http-spot",
        "phase4-http-spot-seed",
        3);
    ArrayNode timeline = scenario.putArray("timeline");
    ObjectNode action = timeline.addObject();
    action.put("id", "spot-buy");
    action.put("sequence", 0);
    action.put("type", "PLACE_ORDER");
    action.put("symbol", SPOT_SYMBOL);
    action.put("productType", "CRYPTO_SPOT");
    action.putObject("trigger")
        .put("type", "VIRTUAL_TIME")
        .put("atSecond", 1);
    action.putObject("parameters")
        .put("side", "BUY")
        .put("orderType", "MARKET")
        .put("quantity", QUOTE_BUDGET)
        .put("quantityUnit", "QUOTE");

    ObjectNode localCalculation = JSON.createObjectNode();
    localCalculation.put("fixture", "phase4-http-spot");
    localCalculation.putObject("invariants")
        .put("symbol", SPOT_SYMBOL)
        .put("baseAsset", baseAsset)
        .put("quoteAsset", quoteAsset)
        .put("quoteBudget", QUOTE_BUDGET)
        .put("initialQuoteBalance", INITIAL_USDT)
        .put("takerFeeRate", takerFeeRate.toPlainString());
    return new SpotScenario(
        writeRequest(
            "Phase 4 real HTTP Spot",
            "Main Admin HTTP to isolated validation HTTP Spot proof",
            "phase4-http-spot-seed",
            frozen,
            scenario),
        localCalculation,
        SPOT_SYMBOL,
        baseAsset,
        quoteAsset,
        new BigDecimal(QUOTE_BUDGET),
        new BigDecimal(INITIAL_USDT),
        takerFeeRate,
        3);
  }

  static LifecycleScenario lifecycle(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    requiredSpotInstrument(frozen.snapshot());
    int totalTicks = 40;
    ObjectNode scenario = browserScenario(
        frozen,
        "phase4-http-lifecycle",
        "phase4-http-lifecycle-seed",
        totalTicks);
    ObjectNode action = scenario.putArray("timeline").addObject();
    action.put("id", "lifecycle-spot-buy");
    action.put("sequence", 0);
    action.put("type", "PLACE_ORDER");
    action.put("symbol", SPOT_SYMBOL);
    action.put("productType", "CRYPTO_SPOT");
    action.putObject("trigger")
        .put("type", "VIRTUAL_TIME")
        .put("atSecond", 1);
    action.putObject("parameters")
        .put("side", "BUY")
        .put("orderType", "MARKET")
        .put("quantity", QUOTE_BUDGET)
        .put("quantityUnit", "QUOTE");

    ObjectNode localCalculation = JSON.createObjectNode();
    localCalculation.put("fixture", "phase4-http-lifecycle");
    localCalculation.putObject("invariants")
        .put("totalTicks", totalTicks)
        .put("pauseMustFreezeProcessedTicks", true)
        .put("cancelMustRetainPartialReport", true);
    return new LifecycleScenario(
        writeRequest(
            "Phase 4 real HTTP lifecycle",
            "Pause, resume, cancel, and partial-report proof through main Admin HTTP",
            "phase4-http-lifecycle-seed",
            frozen,
            scenario),
        localCalculation,
        totalTicks);
  }

  static LargeCoordinatorScenario largeCoordinator(
      JsonNode config,
      int actionCount
  ) {
    if (actionCount < 1 || actionCount > 512) {
      throw new IllegalArgumentException("Large coordinator action count is outside 1..512");
    }
    ConfigSnapshot frozen = config(config);
    requiredSpotInstrument(frozen.snapshot());
    ObjectNode scenario = browserScenario(
        frozen,
        "phase4-http-large-coordinator",
        "phase4-http-large-coordinator-seed",
        actionCount);
    ArrayNode timeline = scenario.putArray("timeline");
    for (int sequence = 0; sequence < actionCount; sequence++) {
      ObjectNode action = timeline.addObject();
      action.put("id", "large-spot-buy-" + sequence);
      action.put("sequence", sequence);
      action.put("type", "PLACE_ORDER");
      action.put("symbol", SPOT_SYMBOL);
      action.put("productType", "CRYPTO_SPOT");
      action.putObject("trigger")
          .put("type", "VIRTUAL_TIME")
          .put("atSecond", 1);
      action.putObject("parameters")
          .put("side", "BUY")
          .put("orderType", "MARKET")
          .put("quantity", "100.00000000")
          .put("quantityUnit", "QUOTE");
    }

    ObjectNode localCalculation = JSON.createObjectNode();
    localCalculation.put("fixture", "phase4-http-large-coordinator");
    localCalculation.putObject("invariants")
        .put("actionCount", actionCount)
        .put("totalTicks", actionCount)
        .put("directReportWritesAllowed", false);
    return new LargeCoordinatorScenario(
        writeRequest(
            "Phase 4 real coordinator large report",
            "Real Admin/coordinator/validation report larger than 50 MiB",
            "phase4-http-large-coordinator-seed",
            frozen,
            scenario),
        localCalculation,
        actionCount,
        actionCount);
  }

  private static ObjectNode browserScenario(
      ConfigSnapshot frozen,
      String id,
      String seed,
      int durationSeconds
  ) {
    ObjectNode scenario = JSON.createObjectNode();
    scenario.put("id", id);
    scenario.put("name", id);
    scenario.put("description", "");
    scenario.put("negativeMode", false);
    scenario.put("seed", seed);
    scenario.put("modelVersion", frozen.modelVersion());
    scenario.set("configSnapshot", frozen.snapshot().deepCopy());
    scenario.put("configSnapshotHash", frozen.hash());
    scenario.set(
        "executionPolicy",
        frozen.snapshot().path("executionPolicy").deepCopy());

    ObjectNode marketPath = scenario.putObject("marketPath");
    marketPath.put("virtualStart", VIRTUAL_START);
    marketPath.put("realistic", false);
    ObjectNode path = marketPath.putArray("instruments").addObject();
    path.put("mode", "SIMPLE");
    path.put("productType", "CRYPTO_SPOT");
    path.put("symbol", SPOT_SYMBOL);
    path.put("seed", seed + "-path");
    ObjectNode last = path.putObject("last");
    last.put("start", SPOT_PATH_PRICE);
    last.putArray("segments")
        .addObject()
        .put("target", SPOT_PATH_PRICE)
        .put("durationSeconds", durationSeconds)
        .put("offsetRangeSteps", 0)
        .put("volatilitySteps", 0)
        .put("maxStepPerSecond", 1);
    path.put("spreadSteps", 2);
    path.put("indexOffsetSteps", 0);
    path.put("basisSteps", 0);

    scenario.putObject("initialBalances").put("USDT", INITIAL_USDT);
    scenario.putObject("defaults")
        .put("positionMode", "ONE_WAY")
        .put("marginMode", "CROSS")
        .put("leverage", 1);
    scenario.putArray("symbols")
        .addObject()
        .put("symbol", SPOT_SYMBOL)
        .put("productType", "CRYPTO_SPOT");
    return scenario;
  }

  private static TradingLabScenarioWriteRequest writeRequest(
      String name,
      String description,
      String seed,
      ConfigSnapshot frozen,
      JsonNode scenario
  ) {
    return new TradingLabScenarioWriteRequest(
        name,
        description,
        false,
        seed,
        frozen.modelVersion(),
        scenario,
        frozen.snapshot(),
        frozen.hash(),
        null);
  }

  private static ConfigSnapshot config(JsonNode config) {
    assertThat(config.isObject()).isTrue();
    JsonNode snapshot = config.path("configSnapshot");
    assertThat(snapshot.isObject()).isTrue();
    String hash = requiredText(config, "configSnapshotHash");
    assertThat(hash).matches("[0-9a-f]{64}");
    String modelVersion = requiredText(config, "modelVersion");
    assertThat(snapshot.path("modelVersion").asText()).isEqualTo(modelVersion);
    assertThat(snapshot.path("executionPolicy").isObject()).isTrue();
    assertThat(snapshot.path("instruments").isArray()).isTrue();
    return new ConfigSnapshot(snapshot.deepCopy(), hash, modelVersion);
  }

  private static JsonNode requiredSpotInstrument(JsonNode snapshot) {
    for (JsonNode instrument : snapshot.path("instruments")) {
      if (SPOT_SYMBOL.equals(instrument.path("symbol").asText())
          && "CRYPTO_SPOT".equals(instrument.path("productType").asText())) {
        assertThat(requiredText(instrument, "baseAsset")).isEqualTo("BTC");
        assertThat(requiredText(instrument, "quoteAsset")).isEqualTo("USDT");
        assertThat(decimal(instrument, "tickSize")).isPositive();
        assertThat(decimal(instrument, "stepSize")).isPositive();
        return instrument.deepCopy();
      }
    }
    throw new AssertionError(
        "Fresh Flyway schema must expose authoritative BTCUSDT CRYPTO_SPOT rules");
  }

  private static BigDecimal decimal(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isTextual() || value.isNumber()).as(field).isTrue();
    return value.isNumber()
        ? value.decimalValue()
        : new BigDecimal(value.textValue());
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isTextual()).as(field).isTrue();
    assertThat(value.textValue()).as(field).isNotBlank();
    return value.textValue();
  }

  record SpotScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      String symbol,
      String baseAsset,
      String quoteAsset,
      BigDecimal quoteBudget,
      BigDecimal initialQuoteBalance,
      BigDecimal takerFeeRate,
      int totalTicks
  ) {

    SpotScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return new TradingLabRunCreateRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record LifecycleScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      int totalTicks
  ) {

    LifecycleScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return new TradingLabRunCreateRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record LargeCoordinatorScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      int actionCount,
      int totalTicks
  ) {

    LargeCoordinatorScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return new TradingLabRunCreateRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  private record ConfigSnapshot(JsonNode snapshot, String hash, String modelVersion) {

    private ConfigSnapshot {
      snapshot = snapshot.deepCopy();
    }
  }
}
