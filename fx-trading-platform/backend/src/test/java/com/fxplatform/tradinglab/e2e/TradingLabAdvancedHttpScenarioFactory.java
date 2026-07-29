package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import java.math.BigDecimal;
import java.util.List;

/**
 * Builds the advanced HTTP fixtures without calling an application service, repository, JDBC, or
 * the validation runtime directly.
 */
final class TradingLabAdvancedHttpScenarioFactory {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String SPOT_SYMBOL = "BTCUSDT";
  private static final String PERPETUAL_SYMBOL = "BTCUSDT-PERP";
  private static final String VIRTUAL_START = "2026-07-25T03:00:00Z";
  private static final String INITIAL_USDT = "100000.00000000";
  private static final String PERPETUAL_QUANTITY = "0.01000000";
  private static final String FUNDING_RATE = "0.0010000000";

  private TradingLabAdvancedHttpScenarioFactory() {
  }

  static ProtectionScenario stopLoss(JsonNode config) {
    return protection(
        config,
        "phase4-http-stop-loss",
        "STOP_LOSS",
        "49000.00000000",
        "48000.00");
  }

  static ProtectionScenario takeProfit(JsonNode config) {
    return protection(
        config,
        "phase4-http-take-profit",
        "TAKE_PROFIT",
        "51000.00000000",
        "52000.00");
  }

  static TrailingScenario trailing(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    requireInstrument(frozen.snapshot(), PERPETUAL_SYMBOL, "LINEAR_PERP");
    String fixture = "phase4-http-trailing";
    ObjectNode scenario = browserScenario(
        frozen,
        fixture,
        fixture + "-seed",
        false,
        PERPETUAL_SYMBOL,
        "LINEAR_PERP",
        List.of(
            new Segment("50000.00", 1),
            new Segment("51000.00", 1),
            new Segment("52000.00", 1),
            new Segment("51400.00", 1)),
        "0");
    addPerpetualMarketOrder(scenario, "trailing-parent", 0, 1);
    ObjectNode trailing = action(
        scenario,
        "trailing-close",
        1,
        PERPETUAL_SYMBOL,
        "LINEAR_PERP",
        2);
    trailing.putObject("parameters")
        .put("side", "SELL")
        .put("orderType", "TRAILING_STOP_MARKET")
        .put("quantity", PERPETUAL_QUANTITY)
        .put("quantityUnit", "BASE")
        .put("positionSide", "LONG")
        .put("marginMode", "CROSS")
        .put("reduceOnly", true)
        .put("timeInForce", "GTC")
        .put("activationPrice", "50500.00000000")
        .put("trailingDelta", "500.00000000");

    ObjectNode localCalculation = calculation(fixture);
    localCalculation.putObject("invariants")
        .put("activationPrice", "50500.00000000")
        .put("extremePrice", "52000.00000000")
        .put("triggerPrice", "51500.00000000")
        .put("triggerTick", 4);
    return new TrailingScenario(
        writeRequest(frozen, scenario, fixture, false),
        localCalculation,
        new BigDecimal(PERPETUAL_QUANTITY),
        new BigDecimal("50500.00000000"),
        new BigDecimal("52000.00000000"),
        new BigDecimal("51500.00000000"),
        4);
  }

  static FundingScenario funding(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    requireInstrument(frozen.snapshot(), PERPETUAL_SYMBOL, "LINEAR_PERP");
    String fixture = "phase4-http-funding";
    ObjectNode scenario = browserScenario(
        frozen,
        fixture,
        fixture + "-seed",
        false,
        PERPETUAL_SYMBOL,
        "LINEAR_PERP",
        List.of(new Segment("50000.00", 1)),
        FUNDING_RATE);
    addPerpetualMarketOrder(scenario, "funded-long", 0, 1);

    ObjectNode localCalculation = calculation(fixture);
    localCalculation.putObject("invariants")
        .put("markPrice", "50000.00000000")
        .put("fundingRate", FUNDING_RATE)
        .put("expectedAmount", "-0.50000000");
    return new FundingScenario(
        writeRequest(frozen, scenario, fixture, false),
        localCalculation,
        new BigDecimal(FUNDING_RATE),
        new BigDecimal("-0.50000000"),
        1);
  }

  static NegativeScenario negative(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    requireInstrument(frozen.snapshot(), SPOT_SYMBOL, "CRYPTO_SPOT");
    String fixture = "phase4-http-negative";
    ObjectNode scenario = browserScenario(
        frozen,
        fixture,
        fixture + "-seed",
        true,
        SPOT_SYMBOL,
        "CRYPTO_SPOT",
        List.of(new Segment("50000.00", 1)),
        null);
    ObjectNode rejected = action(
        scenario,
        "rejected-zero-quantity",
        0,
        SPOT_SYMBOL,
        "CRYPTO_SPOT",
        1);
    rejected.putObject("parameters")
        .put("side", "BUY")
        .put("orderType", "MARKET")
        .put("quantity", "0.00000000")
        .put("quantityUnit", "QUOTE");
    rejected.putObject("expectedError")
        .put("status", 400)
        .put("code", "VALIDATION_ERROR");

    ObjectNode localCalculation = calculation(fixture);
    localCalculation.putObject("invariants")
        .put("expectedStatus", 400)
        .put("expectedCode", "VALIDATION_ERROR")
        .put("mutationCount", 0);
    return new NegativeScenario(
        writeRequest(frozen, scenario, fixture, true),
        localCalculation,
        400,
        "VALIDATION_ERROR",
        "quantity: must be greater than 0",
        1);
  }

  private static ProtectionScenario protection(
      JsonNode config,
      String fixture,
      String protectionType,
      String triggerPrice,
      String crossedPrice
  ) {
    ConfigSnapshot frozen = config(config);
    requireInstrument(frozen.snapshot(), PERPETUAL_SYMBOL, "LINEAR_PERP");
    ObjectNode scenario = browserScenario(
        frozen,
        fixture,
        fixture + "-seed",
        false,
        PERPETUAL_SYMBOL,
        "LINEAR_PERP",
        List.of(
            new Segment("50000.00", 1),
            new Segment(crossedPrice, 1)),
        "0");
    ObjectNode parent = addPerpetualMarketOrder(scenario, fixture + "-parent", 0, 1);
    ObjectNode protection = ((ObjectNode) parent.path("parameters"))
        .putArray("attachedProtections")
        .addObject();
    protection.put("protectionType", protectionType);
    protection.put("triggerPrice", triggerPrice);
    protection.put("triggerPriceType", "MARK_PRICE");
    protection.put("triggerExecutionType", "MARKET");

    ObjectNode localCalculation = calculation(fixture);
    localCalculation.putObject("invariants")
        .put("protectionType", protectionType)
        .put("triggerPrice", triggerPrice)
        .put("triggerTick", 2);
    return new ProtectionScenario(
        writeRequest(frozen, scenario, fixture, false),
        localCalculation,
        protectionType,
        new BigDecimal(triggerPrice),
        new BigDecimal(PERPETUAL_QUANTITY),
        2);
  }

  private static ObjectNode browserScenario(
      ConfigSnapshot frozen,
      String id,
      String seed,
      boolean negativeMode,
      String symbol,
      String productType,
      List<Segment> segments,
      String fundingRate
  ) {
    ObjectNode scenario = JSON.createObjectNode();
    scenario.put("id", id);
    scenario.put("name", id);
    scenario.put("description", "");
    scenario.put("negativeMode", negativeMode);
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
    ObjectNode instrument = marketPath.putArray("instruments").addObject();
    instrument.put("mode", "SIMPLE");
    instrument.put("productType", productType);
    instrument.put("symbol", symbol);
    instrument.put("seed", seed + "-path");
    ObjectNode last = instrument.putObject("last");
    last.put("start", "50000.00");
    ArrayNode pathSegments = last.putArray("segments");
    for (Segment segment : segments) {
      pathSegments.addObject()
          .put("target", segment.target())
          .put("durationSeconds", segment.durationSeconds())
          .put("offsetRangeSteps", 0)
          .put("volatilitySteps", 0)
          .put("maxStepPerSecond", 1_000_000);
    }
    instrument.put("spreadSteps", 2);
    instrument.put("indexOffsetSteps", 0);
    instrument.put("basisSteps", 0);
    if ("LINEAR_PERP".equals(productType)) {
      instrument.put("fundingRate", fundingRate);
    }

    scenario.putObject("initialBalances").put("USDT", INITIAL_USDT);
    ObjectNode defaults = scenario.putObject("defaults");
    defaults.put("positionMode", "LINEAR_PERP".equals(productType) ? "HEDGE" : "ONE_WAY");
    defaults.put("marginMode", "CROSS");
    defaults.put("leverage", "LINEAR_PERP".equals(productType) ? 10 : 1);
    scenario.putArray("symbols")
        .addObject()
        .put("symbol", symbol)
        .put("productType", productType);
    scenario.putArray("timeline");
    return scenario;
  }

  private static ObjectNode addPerpetualMarketOrder(
      ObjectNode scenario,
      String id,
      long sequence,
      int atSecond
  ) {
    ObjectNode placed = action(
        scenario,
        id,
        sequence,
        PERPETUAL_SYMBOL,
        "LINEAR_PERP",
        atSecond);
    placed.putObject("parameters")
        .put("side", "BUY")
        .put("orderType", "MARKET")
        .put("quantity", PERPETUAL_QUANTITY)
        .put("quantityUnit", "BASE")
        .put("positionSide", "LONG")
        .put("marginMode", "CROSS")
        .put("leverage", 10);
    return placed;
  }

  private static ObjectNode action(
      ObjectNode scenario,
      String id,
      long sequence,
      String symbol,
      String productType,
      int atSecond
  ) {
    ObjectNode action = scenario.withArray("timeline").addObject();
    action.put("id", id);
    action.put("sequence", sequence);
    action.put("type", "PLACE_ORDER");
    action.put("symbol", symbol);
    action.put("productType", productType);
    action.putObject("trigger")
        .put("type", "VIRTUAL_TIME")
        .put("atSecond", atSecond);
    return action;
  }

  private static TradingLabScenarioWriteRequest writeRequest(
      ConfigSnapshot frozen,
      JsonNode scenario,
      String fixture,
      boolean negativeMode
  ) {
    return new TradingLabScenarioWriteRequest(
        "Phase 4 real HTTP " + fixture,
        "Main Admin HTTP to isolated validation HTTP proof for " + fixture,
        negativeMode,
        fixture + "-seed",
        frozen.modelVersion(),
        scenario,
        frozen.snapshot(),
        frozen.hash(),
        null);
  }

  private static ObjectNode calculation(String fixture) {
    ObjectNode calculation = JSON.createObjectNode();
    calculation.put("fixture", fixture);
    return calculation;
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

  private static JsonNode requireInstrument(
      JsonNode snapshot,
      String symbol,
      String productType
  ) {
    for (JsonNode instrument : snapshot.path("instruments")) {
      if (symbol.equals(instrument.path("symbol").asText())
          && productType.equals(instrument.path("productType").asText())) {
        assertThat(requiredText(instrument, "baseAsset")).isEqualTo("BTC");
        assertThat(requiredText(instrument, "quoteAsset")).isEqualTo("USDT");
        assertThat(decimal(instrument, "tickSize")).isPositive();
        assertThat(decimal(instrument, "stepSize")).isPositive();
        if ("LINEAR_PERP".equals(productType)) {
          assertThat(instrument.path("maxLeverage").asInt()).isGreaterThanOrEqualTo(10);
        }
        return instrument.deepCopy();
      }
    }
    throw new AssertionError(
        "Fresh Flyway schema must expose authoritative " + symbol + " " + productType + " rules");
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

  private static TradingLabRunCreateRequest runRequest(
      long scenarioVersion,
      String configSnapshotHash,
      JsonNode localCalculation
  ) {
    return new TradingLabRunCreateRequest(
        scenarioVersion,
        configSnapshotHash,
        localCalculation);
  }

  record ProtectionScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      String protectionType,
      BigDecimal triggerPrice,
      BigDecimal quantity,
      int totalTicks
  ) {

    ProtectionScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return TradingLabAdvancedHttpScenarioFactory.runRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record TrailingScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      BigDecimal quantity,
      BigDecimal activationPrice,
      BigDecimal extremePrice,
      BigDecimal triggerPrice,
      int totalTicks
  ) {

    TrailingScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return TradingLabAdvancedHttpScenarioFactory.runRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record FundingScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      BigDecimal fundingRate,
      BigDecimal expectedAmount,
      int totalTicks
  ) {

    FundingScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return TradingLabAdvancedHttpScenarioFactory.runRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record NegativeScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      int expectedStatus,
      String expectedCode,
      String expectedMessage,
      int totalTicks
  ) {

    NegativeScenario {
      localCalculation = localCalculation.deepCopy();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return TradingLabAdvancedHttpScenarioFactory.runRequest(
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

  private record Segment(String target, int durationSeconds) {
  }
}
