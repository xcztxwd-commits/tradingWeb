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
 * Produces browser-model Perpetual fixtures without calling an application service, repository,
 * JDBC seam, or validation runtime.
 */
final class TradingLabPerpetualHttpScenarioFactory {

  static final String BTC_PERPETUAL = "BTCUSDT-PERP";
  static final String ETH_PERPETUAL = "ETHUSDT-PERP";

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String PRODUCT_TYPE = "LINEAR_PERP";
  private static final String VIRTUAL_START = "2026-07-25T00:00:00Z";
  private static final int ISOLATED_LEVERAGE = 8;
  private static final int CROSS_LEVERAGE = 10;
  private static final int SPREAD_STEPS = 2;

  private TradingLabPerpetualHttpScenarioFactory() {
  }

  static IsolatedScenario isolated(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    InstrumentRules bitcoin = requiredPerpetualInstrument(
        frozen.snapshot(),
        BTC_PERPETUAL,
        ISOLATED_LEVERAGE);
    List<BigDecimal> lastPrices = List.of(
        new BigDecimal("50000"),
        new BigDecimal("50100"),
        new BigDecimal("50200"));
    BigDecimal openQuantity = new BigDecimal("0.02000000");
    BigDecimal closeQuantity = new BigDecimal("0.01000000");
    requireAligned(openQuantity, bitcoin.stepSize(), "isolated open quantity");
    requireAligned(closeQuantity, bitcoin.stepSize(), "isolated close quantity");

    ObjectNode scenario = browserScenario(
        frozen,
        "phase4-http-isolated-perpetual",
        "phase4-http-isolated-perpetual-seed",
        "100000.00000000",
        "ISOLATED",
        ISOLATED_LEVERAGE);
    addSimplePath(
        scenario,
        bitcoin,
        lastPrices,
        "0.0000000000",
        "phase4-http-isolated-perpetual-path");
    addSelectedSymbol(scenario, bitcoin.symbol());
    ArrayNode timeline = scenario.putArray("timeline");
    addMarketOrder(
        timeline,
        "isolated-open",
        0,
        1,
        bitcoin.symbol(),
        "BUY",
        openQuantity,
        "LONG",
        "ISOLATED",
        ISOLATED_LEVERAGE,
        false);
    addMarketOrder(
        timeline,
        "isolated-reduce",
        1,
        2,
        bitcoin.symbol(),
        "SELL",
        closeQuantity,
        "LONG",
        "ISOLATED",
        ISOLATED_LEVERAGE,
        true);
    addMarketOrder(
        timeline,
        "isolated-close",
        2,
        3,
        bitcoin.symbol(),
        "SELL",
        closeQuantity,
        "LONG",
        "ISOLATED",
        ISOLATED_LEVERAGE,
        true);

    ObjectNode localCalculation = JSON.createObjectNode();
    localCalculation.put("fixture", "phase4-http-isolated-perpetual");
    localCalculation.putObject("inputs")
        .put("symbol", bitcoin.symbol())
        .put("initialUsdt", "100000.00000000")
        .put("openQuantity", openQuantity.toPlainString())
        .put("partialCloseQuantity", closeQuantity.toPlainString())
        .put("leverage", ISOLATED_LEVERAGE)
        .put("spreadSteps", SPREAD_STEPS)
        .put("tickSize", bitcoin.tickSize().toPlainString())
        .put("maintenanceMarginRate", bitcoin.maintenanceMarginRate().toPlainString())
        .put("slippageRate", frozen.slippageRate().toPlainString())
        .put("takerFeeRate", frozen.takerFeeRate().toPlainString());
    return new IsolatedScenario(
        writeRequest(
            "Phase 4 real HTTP isolated Perpetual",
            "Main Admin HTTP to isolated validation HTTP open, reduce, and full close",
            "phase4-http-isolated-perpetual-seed",
            frozen,
            scenario),
        localCalculation,
        bitcoin,
        new BigDecimal("100000.00000000"),
        openQuantity,
        closeQuantity,
        List.copyOf(lastPrices),
        ISOLATED_LEVERAGE,
        SPREAD_STEPS,
        frozen.slippageRate(),
        frozen.takerFeeRate());
  }

  static CrossScenario cross(JsonNode config) {
    ConfigSnapshot frozen = config(config);
    InstrumentRules bitcoin = requiredPerpetualInstrument(
        frozen.snapshot(),
        BTC_PERPETUAL,
        CROSS_LEVERAGE);
    InstrumentRules ether = requiredPerpetualInstrument(
        frozen.snapshot(),
        ETH_PERPETUAL,
        CROSS_LEVERAGE);
    BigDecimal bitcoinQuantity = new BigDecimal("0.01000000");
    BigDecimal etherQuantity = new BigDecimal("0.10000000");
    BigDecimal bitcoinLast = new BigDecimal("50000");
    BigDecimal etherLast = new BigDecimal("3000");
    BigDecimal fundingRate = new BigDecimal("0.0010000000");
    requireAligned(bitcoinQuantity, bitcoin.stepSize(), "Cross BTC quantity");
    requireAligned(etherQuantity, ether.stepSize(), "Cross ETH quantity");

    ObjectNode scenario = browserScenario(
        frozen,
        "phase4-http-cross-multi-symbol",
        "phase4-http-cross-multi-symbol-seed",
        "100.00000000",
        "CROSS",
        CROSS_LEVERAGE);
    addSimplePath(
        scenario,
        bitcoin,
        List.of(bitcoinLast),
        fundingRate.toPlainString(),
        "phase4-http-cross-btc-path");
    addSimplePath(
        scenario,
        ether,
        List.of(etherLast),
        fundingRate.toPlainString(),
        "phase4-http-cross-eth-path");
    addSelectedSymbol(scenario, bitcoin.symbol());
    addSelectedSymbol(scenario, ether.symbol());
    ArrayNode timeline = scenario.putArray("timeline");
    addMarketOrder(
        timeline,
        "cross-btc-long",
        0,
        1,
        bitcoin.symbol(),
        "BUY",
        bitcoinQuantity,
        "LONG",
        "CROSS",
        CROSS_LEVERAGE,
        false);
    addMarketOrder(
        timeline,
        "cross-eth-short",
        1,
        1,
        ether.symbol(),
        "SELL",
        etherQuantity,
        "SHORT",
        "CROSS",
        CROSS_LEVERAGE,
        false);

    ObjectNode localCalculation = JSON.createObjectNode();
    localCalculation.put("fixture", "phase4-http-cross-multi-symbol");
    ObjectNode inputs = localCalculation.putObject("inputs");
    inputs.put("initialUsdt", "100.00000000");
    inputs.put("fundingRate", fundingRate.toPlainString());
    inputs.put("leverage", CROSS_LEVERAGE);
    inputs.put("spreadSteps", SPREAD_STEPS);
    inputs.put("slippageRate", frozen.slippageRate().toPlainString());
    inputs.put("takerFeeRate", frozen.takerFeeRate().toPlainString());
    inputs.putObject("bitcoin")
        .put("symbol", bitcoin.symbol())
        .put("quantity", bitcoinQuantity.toPlainString())
        .put("last", bitcoinLast.toPlainString())
        .put("tickSize", bitcoin.tickSize().toPlainString())
        .put("maintenanceMarginRate", bitcoin.maintenanceMarginRate().toPlainString());
    inputs.putObject("ether")
        .put("symbol", ether.symbol())
        .put("quantity", etherQuantity.toPlainString())
        .put("last", etherLast.toPlainString())
        .put("tickSize", ether.tickSize().toPlainString())
        .put("maintenanceMarginRate", ether.maintenanceMarginRate().toPlainString());
    return new CrossScenario(
        writeRequest(
            "Phase 4 real HTTP Cross multi-symbol Perpetual",
            "BTC long and ETH short share one real USDT Cross account over public HTTP",
            "phase4-http-cross-multi-symbol-seed",
            frozen,
            scenario),
        localCalculation,
        bitcoin,
        ether,
        new BigDecimal("100.00000000"),
        bitcoinQuantity,
        etherQuantity,
        bitcoinLast,
        etherLast,
        fundingRate,
        CROSS_LEVERAGE,
        SPREAD_STEPS,
        frozen.slippageRate(),
        frozen.takerFeeRate());
  }

  private static ObjectNode browserScenario(
      ConfigSnapshot frozen,
      String id,
      String seed,
      String initialUsdt,
      String marginMode,
      int leverage
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
    scenario.putObject("marketPath")
        .put("virtualStart", VIRTUAL_START)
        .put("realistic", false)
        .putArray("instruments");
    scenario.putObject("initialBalances").put("USDT", initialUsdt);
    scenario.putObject("defaults")
        .put("positionMode", "HEDGE")
        .put("marginMode", marginMode)
        .put("leverage", leverage);
    scenario.putArray("symbols");
    return scenario;
  }

  private static void addSimplePath(
      ObjectNode scenario,
      InstrumentRules rules,
      List<BigDecimal> lastPrices,
      String fundingRate,
      String seed
  ) {
    assertThat(lastPrices).isNotEmpty();
    ArrayNode instruments =
        (ArrayNode) scenario.path("marketPath").path("instruments");
    ObjectNode path = instruments.addObject();
    path.put("mode", "SIMPLE");
    path.put("productType", PRODUCT_TYPE);
    path.put("symbol", rules.symbol());
    path.put("seed", seed);
    ObjectNode last = path.putObject("last");
    last.put("start", lastPrices.getFirst().toPlainString());
    ArrayNode segments = last.putArray("segments");
    for (BigDecimal price : lastPrices) {
      requireAligned(price, rules.tickSize(), rules.symbol() + " path price");
      segments.addObject()
          .put("target", price.toPlainString())
          .put("durationSeconds", 1)
          .put("offsetRangeSteps", 0)
          .put("volatilitySteps", 0)
          .put("maxStepPerSecond", 1_000_000);
    }
    path.put("spreadSteps", SPREAD_STEPS);
    path.put("indexOffsetSteps", 0);
    path.put("basisSteps", 0);
    path.put("fundingRate", fundingRate);
  }

  private static void addSelectedSymbol(ObjectNode scenario, String symbol) {
    ((ArrayNode) scenario.path("symbols"))
        .addObject()
        .put("symbol", symbol)
        .put("productType", PRODUCT_TYPE);
  }

  private static void addMarketOrder(
      ArrayNode timeline,
      String id,
      int sequence,
      int atSecond,
      String symbol,
      String side,
      BigDecimal quantity,
      String positionSide,
      String marginMode,
      int leverage,
      boolean reduceOnly
  ) {
    ObjectNode action = timeline.addObject();
    action.put("id", id);
    action.put("sequence", sequence);
    action.put("type", "PLACE_ORDER");
    action.put("symbol", symbol);
    action.put("productType", PRODUCT_TYPE);
    action.putObject("trigger")
        .put("type", "VIRTUAL_TIME")
        .put("atSecond", atSecond);
    action.putObject("parameters")
        .put("side", side)
        .put("orderType", "MARKET")
        .put("quantity", quantity.toPlainString())
        .put("leverage", leverage)
        .put("positionSide", positionSide)
        .put("quantityUnit", "BASE")
        .put("marginMode", marginMode)
        .put("reduceOnly", reduceOnly);
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
    JsonNode policy = snapshot.path("executionPolicy");
    assertThat(policy.isObject()).isTrue();
    BigDecimal slippageRate = decimal(policy, "slippageRate");
    BigDecimal takerFeeRate = decimal(policy, "takerFeeRate");
    assertRate(slippageRate, "slippageRate");
    assertRate(takerFeeRate, "takerFeeRate");
    assertThat(snapshot.path("instruments").isArray()).isTrue();
    return new ConfigSnapshot(
        snapshot.deepCopy(),
        hash,
        modelVersion,
        slippageRate,
        takerFeeRate);
  }

  private static InstrumentRules requiredPerpetualInstrument(
      JsonNode snapshot,
      String symbol,
      int requiredLeverage
  ) {
    for (JsonNode instrument : snapshot.path("instruments")) {
      if (symbol.equals(instrument.path("symbol").asText())
          && PRODUCT_TYPE.equals(instrument.path("productType").asText())) {
        assertThat(requiredText(instrument, "quoteAsset")).isEqualTo("USDT");
        assertThat(requiredText(instrument, "settlementAsset")).isEqualTo("USDT");
        assertThat(requiredText(instrument, "marginAsset")).isEqualTo("USDT");
        BigDecimal tickSize = decimal(instrument, "tickSize");
        BigDecimal stepSize = decimal(instrument, "stepSize");
        BigDecimal maintenanceMarginRate =
            decimal(instrument, "maintenanceMarginRate");
        assertThat(tickSize).isPositive();
        assertThat(stepSize).isPositive();
        assertThat(maintenanceMarginRate).isPositive().isLessThan(BigDecimal.ONE);
        assertThat(instrument.path("maxLeverage").isIntegralNumber()).isTrue();
        assertThat(instrument.path("maxLeverage").asInt())
            .isGreaterThanOrEqualTo(requiredLeverage);
        return new InstrumentRules(
            symbol,
            tickSize,
            stepSize,
            maintenanceMarginRate);
      }
    }
    throw new AssertionError(
        "Fresh Flyway schema must expose authoritative " + symbol + " LINEAR_PERP rules");
  }

  private static void requireAligned(
      BigDecimal value,
      BigDecimal increment,
      String description
  ) {
    assertThat(value).as(description).isPositive();
    assertThat(value.remainder(increment))
        .as(description + " alignment")
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  private static void assertRate(BigDecimal value, String field) {
    assertThat(value).as(field).isNotNegative().isLessThan(BigDecimal.ONE);
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

  record IsolatedScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      InstrumentRules instrument,
      BigDecimal initialUsdt,
      BigDecimal openQuantity,
      BigDecimal closeQuantity,
      List<BigDecimal> lastPrices,
      int leverage,
      int spreadSteps,
      BigDecimal slippageRate,
      BigDecimal takerFeeRate
  ) {

    IsolatedScenario {
      localCalculation = localCalculation.deepCopy();
      lastPrices = List.copyOf(lastPrices);
    }

    int totalTicks() {
      return lastPrices.size();
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return new TradingLabRunCreateRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record CrossScenario(
      TradingLabScenarioWriteRequest scenarioRequest,
      JsonNode localCalculation,
      InstrumentRules bitcoin,
      InstrumentRules ether,
      BigDecimal initialUsdt,
      BigDecimal bitcoinQuantity,
      BigDecimal etherQuantity,
      BigDecimal bitcoinLast,
      BigDecimal etherLast,
      BigDecimal fundingRate,
      int leverage,
      int spreadSteps,
      BigDecimal slippageRate,
      BigDecimal takerFeeRate
  ) {

    CrossScenario {
      localCalculation = localCalculation.deepCopy();
    }

    int totalTicks() {
      return 1;
    }

    TradingLabRunCreateRequest runRequest(long scenarioVersion) {
      return new TradingLabRunCreateRequest(
          scenarioVersion,
          scenarioRequest.configSnapshotHash(),
          localCalculation);
    }
  }

  record InstrumentRules(
      String symbol,
      BigDecimal tickSize,
      BigDecimal stepSize,
      BigDecimal maintenanceMarginRate
  ) {
  }

  private record ConfigSnapshot(
      JsonNode snapshot,
      String hash,
      String modelVersion,
      BigDecimal slippageRate,
      BigDecimal takerFeeRate
  ) {

    private ConfigSnapshot {
      snapshot = snapshot.deepCopy();
    }
  }
}
