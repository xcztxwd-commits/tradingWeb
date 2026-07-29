package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradingLabBrowserScenarioCompilerTest {

  static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void compilesAHighLevelBrowserScenarioIntoCompleteGenerationScopedWire() {
    String config = configJson();
    String hash = sha256(config);
    var source = source(browserScenario(config, hash), config, hash);
    var factory = factory();

    ValidationRunStartRequest first = factory.compile(source, 7L);
    ValidationRunStartRequest replay = factory.compile(source, 7L);
    ValidationRunStartRequest nextGeneration = factory.compile(source, 8L);

    assertThat(replay).isEqualTo(first);
    assertThat(first.runId()).isEqualTo(RUN_ID);
    assertThat(first.generation()).isEqualTo(7L);
    assertThat(first.seed()).isEqualTo("seed-\u4e2d\u6587");
    assertThat(first.virtualStart().toString()).isEqualTo("2026-07-25T00:00:00Z");
    assertThat(first.speedMultiplier()).isEqualByComparingTo("1.000000");
    assertThat(first.initialBalances()).containsEntry(
        "USDT", new BigDecimal("100000.00000000"));
    assertThat(first.accountSettings()).containsExactlyInAnyOrderEntriesOf(Map.of(
        "positionMode", "HEDGE",
        "marginMode", "CROSS",
        "leverage", 10,
        "quantityUnit", "BASE"));
    assertThat(first.requestFingerprint()).matches("[0-9a-f]{64}");
    assertThat(first.ticks()).hasSize(2);
    assertCompleteFirstTick(first.ticks().getFirst());

    assertThat(first.actions()).hasSize(2);
    Map<String, Object> place = first.actions().getFirst();
    Map<String, Object> cancel = first.actions().get(1);
    assertThat(place).containsEntry("type", "PLACE_ORDER")
        .containsEntry("tickSequence", 1L)
        .containsEntry("sequence", 0L);
    @SuppressWarnings("unchecked")
    Map<String, Object> placePayload = (Map<String, Object>) place.get("payload");
    assertThat(placePayload).containsEntry("symbol", "ETHUSDT-PERP")
        .containsEntry("orderType", "LIMIT");
    String placeActionUuid = place.get("actionId").toString();
    assertThat(cancel).containsEntry("type", "CANCEL_ORDER")
        .containsEntry("tickSequence", 2L)
        .containsEntry("sequence", 1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> cancelPayload = (Map<String, Object>) cancel.get("payload");
    assertThat(cancelPayload).containsOnlyKeys("clientOrderId")
        .containsEntry("clientOrderId", RUN_ID + ":" + placeActionUuid + ":0");

    assertThat(nextGeneration.requestFingerprint())
        .isNotEqualTo(first.requestFingerprint());
    assertThat(nextGeneration.ticks().getFirst().get("fingerprint"))
        .isNotEqualTo(first.ticks().getFirst().get("fingerprint"));
    assertThat(nextGeneration.actions().getFirst().get("actionId"))
        .isNotEqualTo(place.get("actionId"));
    @SuppressWarnings("unchecked")
    Map<String, Object> nextCancelPayload =
        (Map<String, Object>) nextGeneration.actions().get(1).get("payload");
    assertThat(nextCancelPayload.get("clientOrderId"))
        .isNotEqualTo(cancelPayload.get("clientOrderId"));
  }

  @Test
  void matchesTheFrozenNonAsciiRealisticTypeScriptVector() {
    String config = spotOnlyConfigJson();
    String hash = sha256(config);
    ValidationRunStartRequest request = factory().compile(
        source(realisticSpotScenario(config, hash), config, hash),
        3L);

    @SuppressWarnings("unchecked")
    Map<String, Object> spotPayload =
        (Map<String, Object>) request.actions().getFirst().get("payload");
    assertThat(spotPayload).containsEntry("marginMode", "CASH");
    assertThat(request.ticks()).extracting(tick -> {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> bundles =
          (List<Map<String, Object>>) tick.get("spotBundles");
      return bundles.getFirst().get("last");
    }).containsExactly(
        new BigDecimal("12"),
        new BigDecimal("12"),
        new BigDecimal("10"),
        new BigDecimal("10"),
        new BigDecimal("10"),
        new BigDecimal("10"));
  }

  @Test
  void acceptsAuthoritativeInstrumentConfigWithoutNotionalBounds()
      throws Exception {
    ObjectNode configNode = (ObjectNode) JSON.readTree(spotOnlyConfigJson());
    ObjectNode instrument = (ObjectNode) configNode.withArray("instruments").get(0);
    instrument.putNull("minNotional");
    instrument.putNull("maxNotional");
    String config = JSON.writeValueAsString(configNode);
    String hash = sha256(config);

    ValidationRunStartRequest request = factory().compile(
        source(realisticSpotScenario(config, hash), config, hash),
        3L);

    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) request.actions().getFirst().get("payload");
    assertThat(payload)
        .containsEntry("quantity", "10")
        .containsEntry("quantityUnit", "QUOTE");

    instrument.put("maxNotional", "9");
    String maximumOnlyConfig = JSON.writeValueAsString(configNode);
    String maximumOnlyHash = sha256(maximumOnlyConfig);
    assertThatThrownBy(() -> factory().compile(
        source(
            realisticSpotScenario(maximumOnlyConfig, maximumOnlyHash),
            maximumOnlyConfig,
            maximumOnlyHash),
        3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");

    instrument.put("minNotional", "11");
    instrument.putNull("maxNotional");
    String minimumOnlyConfig = JSON.writeValueAsString(configNode);
    String minimumOnlyHash = sha256(minimumOnlyConfig);
    assertThatThrownBy(() -> factory().compile(
        source(
            realisticSpotScenario(minimumOnlyConfig, minimumOnlyHash),
            minimumOnlyConfig,
            minimumOnlyHash),
        3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void acceptsAuthoritativeExecutionPolicyWithoutPerTickFillCap()
      throws Exception {
    ObjectNode configNode = (ObjectNode) JSON.readTree(spotOnlyConfigJson());
    ((ObjectNode) configNode.get("executionPolicy"))
        .putNull("maxFillQuantityPerTick");
    String config = JSON.writeValueAsString(configNode);
    String hash = sha256(config);
    ObjectNode scenario = scenarioNode(realisticSpotScenario(config, hash));
    ((ObjectNode) scenario.get("executionPolicy"))
        .putNull("maxFillQuantityPerTick");

    ValidationRunStartRequest request = factory().compile(
        source(JSON.writeValueAsString(scenario), config, hash),
        3L);

    assertThat(request.executionPolicy())
        .containsEntry("maxFillQuantityPerTick", null);
    assertThat(orderBookAmount(request.ticks().getFirst(), "spotBundles"))
        .isEqualByComparingTo("100");
  }

  @Test
  void rejectsMixedModesLocalOnlyActionsAndNormalModeExpectedErrors() {
    String config = configJson();
    String hash = sha256(config);
    String valid = browserScenario(config, hash);

    assertThatThrownBy(() -> factory().compile(
        source(valid.replaceFirst("\\{", "{\"validationRun\":{},"), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mode");

    assertThatThrownBy(() -> factory().compile(
        source(valid.replace("\"type\":\"CANCEL_ORDER\"", "\"type\":\"ADD_MARGIN\""),
            config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");

    String override = valid.replace(
        "\"parameters\":{\"clientOrderId\":\"place-1\"}",
        "\"parameters\":{\"clientOrderId\":\"place-1\"},\"overrides\":{}");
    assertThatThrownBy(() -> factory().compile(source(override, config, hash), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overrides");

    String expected = valid.replace(
        "\"parameters\":{\"clientOrderId\":\"place-1\"}",
        "\"parameters\":{\"clientOrderId\":\"place-1\"},"
            + "\"expectedError\":{\"status\":409,\"code\":\"ORDER_NOT_OPEN\"}");
    assertThatThrownBy(() -> factory().compile(source(expected, config, hash), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedError");
  }

  @Test
  void resolvesPriceAndGroupTriggersAndRejectsUnavailableOrBackwardTriggers()
      throws Exception {
    String config = configJson();
    String hash = sha256(config);
    ObjectNode scenario = scenarioNode(browserScenario(config, hash));
    ArrayNode timeline = (ArrayNode) scenario.get("timeline");
    appendSetLeverage(
        timeline,
        "price-action",
        2,
        "ETHUSDT-PERP",
        "LINEAR_PERP",
        """
            {"type":"PRICE","priceType":"LAST","operator":"GTE","value":"201"}
            """);
    appendSetLeverage(
        timeline,
        "group-action",
        3,
        "ETHUSDT-PERP",
        "LINEAR_PERP",
        """
            {
              "type":"GROUP",
              "operator":"ALL",
              "items":[
                {"type":"VIRTUAL_TIME","atSecond":2},
                {"type":"PRICE","priceType":"ASK","operator":"GTE","value":"201"}
              ]
            }
            """);

    ValidationRunStartRequest compiled = factory().compile(
        source(JSON.writeValueAsString(scenario), config, hash),
        1L);

    assertThat(compiled.actions()).extracting(action -> action.get("tickSequence"))
        .containsExactly(1L, 2L, 2L, 2L);

    ObjectNode unavailable = scenarioNode(browserScenario(config, hash));
    appendSetLeverage(
        (ArrayNode) unavailable.get("timeline"),
        "unavailable",
        2,
        "BTCUSDT",
        "CRYPTO_SPOT",
        """
            {"type":"PRICE","priceType":"MARK","operator":"GTE","value":"1"}
            """);
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(unavailable), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceType");

    ObjectNode backward = scenarioNode(browserScenario(config, hash));
    appendSetLeverage(
        (ArrayNode) backward.get("timeline"),
        "backward",
        2,
        "ETHUSDT-PERP",
        "LINEAR_PERP",
        """
            {"type":"VIRTUAL_TIME","atSecond":1}
            """);
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(backward), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trigger");
  }

  @Test
  void enforcesNegativeModeAndFrozenMetadata() throws Exception {
    String config = configJson();
    String hash = sha256(config);
    ObjectNode negative = scenarioNode(browserScenario(config, hash));
    negative.put("negativeMode", true);

    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(negative), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedError");

    ObjectNode cancel = (ObjectNode) negative.withArray("timeline").get(1);
    cancel.putObject("expectedError")
        .put("status", 409)
        .put("code", "ORDER_NOT_OPEN");
    ValidationRunStartRequest compiled = factory().compile(
        source(JSON.writeValueAsString(negative), config, hash),
        1L);
    assertThat(compiled.actions().get(1).get("expectedError"))
        .isEqualTo(Map.of("status", 409, "code", "ORDER_NOT_OPEN"));

    var mismatchedMetadata =
        new TradingLabValidationStartRequestFactory.TradingLabValidationStartSource(
            RUN_ID,
            browserScenario(config, hash),
            config,
            hash,
            "wrong-model",
            "symbols-v1",
            "code-v1");
    assertThatThrownBy(() -> factory().compile(mismatchedMetadata, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configMetadata");
  }

  @Test
  void rejectsTypeScriptParityLeapSecondsAndEcmaWhitespaceSeeds() {
    String config = configJson();
    String hash = sha256(config);
    String valid = browserScenario(config, hash);

    assertThatThrownBy(() -> factory().compile(
        source(
            valid.replace(
                "2026-07-25T00:00:00Z",
                "2016-12-31T23:59:60Z"),
            config,
            hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("virtualStart");

    assertThatThrownBy(() -> factory().compile(
        source(valid.replace("seed-\u4e2d\u6587", "\u00a0"), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seed");

    assertThatThrownBy(() -> factory().compile(
        source(valid.replace("perpetual-seed", "\ufeff"), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seed");
  }

  @Test
  void rejectsHostileActionEnumsAndDependenciesBeforeFreeze() throws Exception {
    String config = configJson();
    String hash = sha256(config);
    String valid = browserScenario(config, hash);

    ObjectNode invalidSide = scenarioNode(valid);
    firstActionParameters(invalidSide).put("side", "HACK");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(invalidSide), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("side");

    ObjectNode missingLimitPrice = scenarioNode(valid);
    firstActionParameters(missingLimitPrice).remove("price");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(missingLimitPrice), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("price");

    ObjectNode marketWithLimitFields = scenarioNode(valid);
    firstActionParameters(marketWithLimitFields).put("orderType", "MARKET");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(marketWithLimitFields), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("price");

    ObjectNode excessiveLeverage = scenarioNode(valid);
    firstActionParameters(excessiveLeverage).put("leverage", 101);
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(excessiveLeverage), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leverage");

    ObjectNode misalignedQuantity = scenarioNode(valid);
    firstActionParameters(misalignedQuantity).put("quantity", "0.6");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(misalignedQuantity), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");

    ObjectNode excessiveQuantity = scenarioNode(valid);
    firstActionParameters(excessiveQuantity).put("quantity", "101");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(excessiveQuantity), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");

    ObjectNode misalignedPrice = scenarioNode(valid);
    firstActionParameters(misalignedPrice).put("price", "200.1");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(misalignedPrice), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("price");

    ObjectNode invalidProtection = scenarioNode(valid);
    firstActionParameters(invalidProtection)
        .putArray("attachedProtections")
        .addObject()
        .put("protectionType", "HACK")
        .put("triggerPrice", "200")
        .put("triggerExecutionType", "MARKET");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(invalidProtection), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("protectionType");

    String spotConfig = spotOnlyConfigJson();
    String spotHash = sha256(spotConfig);
    ObjectNode spotLeverage =
        scenarioNode(realisticSpotScenario(spotConfig, spotHash));
    firstActionParameters(spotLeverage).put("leverage", 1);
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(spotLeverage), spotConfig, spotHash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spot");

    ObjectNode missingSpotQuoteUnit =
        scenarioNode(realisticSpotScenario(spotConfig, spotHash));
    firstActionParameters(missingSpotQuoteUnit).remove("quantityUnit");
    ValidationRunStartRequest injectedSpotDefaults = factory().compile(
        source(JSON.writeValueAsString(missingSpotQuoteUnit), spotConfig, spotHash),
        1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> injectedSpotPayload =
        (Map<String, Object>) injectedSpotDefaults.actions().getFirst().get("payload");
    assertThat(injectedSpotPayload)
        .containsEntry("quantityUnit", "QUOTE")
        .containsEntry("marginMode", "CASH");

    ObjectNode perpetualQuoteUnit = scenarioNode(valid);
    firstActionParameters(perpetualQuoteUnit).put("quantityUnit", "QUOTE");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(perpetualQuoteUnit), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantityUnit");

    ObjectNode spotDefaults =
        scenarioNode(realisticSpotScenario(spotConfig, spotHash));
    ((ObjectNode) spotDefaults.get("defaults")).put("leverage", 2);
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(spotDefaults), spotConfig, spotHash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leverage");

    ObjectNode spotSetLeverage = scenarioNode(valid);
    appendSetLeverage(
        (ArrayNode) spotSetLeverage.get("timeline"),
        "spot-leverage",
        2,
        "BTCUSDT",
        "CRYPTO_SPOT",
        """
            {"type":"VIRTUAL_TIME","atSecond":2}
            """);
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(spotSetLeverage), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leverage");
  }

  @Test
  void preservesExactNegativeBusinessViolationsForRealHttpJudgment() throws Exception {
    String config = spotOnlyConfigJson();
    String hash = sha256(config);
    ObjectNode normal = scenarioNode(realisticSpotScenario(config, hash));
    firstActionParameters(normal)
        .put("quantityUnit", "BASE")
        .put("marginMode", "CROSS");

    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(normal), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spot");

    ValidationRunStartRequest compiled = factory().compile(
        source(negativeSpotBusinessViolationScenario(config, hash), config, hash),
        1L);

    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) compiled.actions().getFirst().get("payload");
    assertThat(payload)
        .containsEntry("quantityUnit", "BASE")
        .containsEntry("marginMode", "CROSS");
    assertThat(compiled.actions().getFirst().get("expectedError"))
        .isEqualTo(Map.of("status", 400, "code", "INVALID_QUANTITY_UNIT"));
  }

  @Test
  void matchesFrontendNegativeActionTextStructure() throws Exception {
    String config = spotOnlyConfigJson();
    String hash = sha256(config);

    ObjectNode blank = scenarioNode(
        negativeSpotBusinessViolationScenario(config, hash));
    firstActionParameters(blank).put("side", "\u00a0");
    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(blank), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("side");

    ObjectNode longInvalid = scenarioNode(
        negativeSpotBusinessViolationScenario(config, hash));
    String longSide = "X".repeat(300);
    ((ObjectNode) longInvalid.withArray("timeline").get(0))
        .put("id", "action-".repeat(250));
    firstActionParameters(longInvalid).put("side", longSide);
    ValidationRunStartRequest compiled = factory().compile(
        source(JSON.writeValueAsString(longInvalid), config, hash),
        1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) compiled.actions().getFirst().get("payload");
    assertThat(payload).containsEntry("side", longSide);

    String fullConfig = configJson();
    String fullHash = sha256(fullConfig);
    ObjectNode whitespaceIdentity =
        scenarioNode(browserScenario(fullConfig, fullHash));
    ArrayNode actions = whitespaceIdentity.withArray("timeline");
    ((ObjectNode) actions.get(0)).put("id", "\u00a0");
    ObjectNode after = (ObjectNode) actions.get(1);
    after.put("type", "SET_LEVERAGE");
    ((ObjectNode) after.get("trigger")).put("actionId", "\u00a0");
    after.putObject("parameters").put("leverage", 5);

    ValidationRunStartRequest whitespaceCompiled = factory().compile(
        source(JSON.writeValueAsString(whitespaceIdentity), fullConfig, fullHash),
        1L);
    assertThat(whitespaceCompiled.actions()).hasSize(2);
  }

  @Test
  void usesPerInstrumentLiquidityAndMatchesAdvancedSpotPerpetualFinalVectors()
      throws Exception {
    String config = configJson().replaceFirst(
        "\"maxQty\":\"100\"",
        "\"maxQty\":\"2\"");
    String hash = sha256(config);
    ObjectNode scenario = scenarioNode(browserScenario(config, hash));
    ObjectNode marketPath = (ObjectNode) scenario.get("marketPath");
    marketPath.put("realistic", true);
    ArrayNode instruments = marketPath.putArray("instruments");
    instruments.add(advancedSpotPath());
    instruments.add(advancedPerpetualPath());

    ValidationRunStartRequest compiled = factory().compile(
        source(JSON.writeValueAsString(scenario), config, hash),
        4L);

    assertThat(compiled.ticks()).hasSize(20);
    Map<String, Object> first = compiled.ticks().getFirst();
    assertThat(orderBookAmount(first, "spotBundles")).isEqualTo(new BigDecimal("2"));
    assertThat(orderBookAmount(first, "perpetualBundles")).isEqualTo(new BigDecimal("10"));
    assertQuote(first, "spotBundles", "BTCUSDT", "17", "18", "12", null, null);
    assertQuote(
        first,
        "perpetualBundles",
        "ETHUSDT-PERP",
        "13.5",
        "19",
        "20.5",
        "18",
        "17.5");
    Map<String, Object> last = compiled.ticks().getLast();
    assertQuote(last, "spotBundles", "BTCUSDT", "9", "10", "9", null, null);
    assertQuote(
        last,
        "perpetualBundles",
        "ETHUSDT-PERP",
        "19",
        "20",
        "19",
        "18",
        "19");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> funding =
        (List<Map<String, Object>>) last.get("fundingRates");
    assertThat(funding).singleElement().satisfies(rate -> assertThat(rate)
        .containsEntry("platformSymbol", "ETHUSDT-PERP")
        .containsEntry("fundingRate", new BigDecimal("-0.0001000000")));
  }

  @Test
  void sameProductInstrumentPermutationKeepsCompleteTickAndFingerprintOrder()
      throws Exception {
    ObjectNode configNode = (ObjectNode) JSON.readTree(spotOnlyConfigJson());
    ArrayNode configInstruments = configNode.withArray("instruments");
    ObjectNode secondAuthority = ((ObjectNode) configInstruments.get(0)).deepCopy();
    secondAuthority.put("baseAsset", "ETH");
    secondAuthority.put("symbol", "ETHUSDT");
    configInstruments.add(secondAuthority);
    String config = JSON.writeValueAsString(configNode);
    String hash = sha256(config);

    ObjectNode orderedScenario =
        scenarioNode(realisticSpotScenario(config, hash));
    ArrayNode paths =
        ((ObjectNode) orderedScenario.get("marketPath")).withArray("instruments");
    ObjectNode firstPath = ((ObjectNode) paths.get(0)).deepCopy();
    ObjectNode secondPath = firstPath.deepCopy();
    secondPath.put("seed", "second-spot-seed");
    secondPath.put("symbol", "ETHUSDT");
    ((ObjectNode) secondPath.get("last")).put("start", "20");
    ((ObjectNode) secondPath.get("last")).withArray("segments")
        .forEach(segment -> ((ObjectNode) segment).put("target", "20"));
    paths.add(secondPath);
    orderedScenario.withArray("symbols")
        .addObject()
        .put("symbol", "ETHUSDT")
        .put("productType", "CRYPTO_SPOT");

    ValidationRunStartRequest ordered = factory().compile(
        source(JSON.writeValueAsString(orderedScenario), config, hash),
        5L);

    ObjectNode permutedScenario = orderedScenario.deepCopy();
    ArrayNode permutedPaths =
        ((ObjectNode) permutedScenario.get("marketPath")).withArray("instruments");
    ObjectNode permutedFirst = ((ObjectNode) permutedPaths.get(0)).deepCopy();
    ObjectNode permutedSecond = ((ObjectNode) permutedPaths.get(1)).deepCopy();
    permutedPaths.removeAll();
    permutedPaths.add(permutedSecond);
    permutedPaths.add(permutedFirst);

    ValidationRunStartRequest permuted = factory().compile(
        source(JSON.writeValueAsString(permutedScenario), config, hash),
        5L);

    assertThat(permuted.ticks()).isEqualTo(ordered.ticks());
    assertThat(permuted.requestFingerprint())
        .isEqualTo(ordered.requestFingerprint());
    assertThat(ordered.ticks()).allSatisfy(tick -> {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> bundles =
          (List<Map<String, Object>>) tick.get("spotBundles");
      assertThat(bundles)
          .extracting(bundle -> bundle.get("platformSymbol"))
          .containsExactly("BTCUSDT", "ETHUSDT");
    });
  }

  @Test
  void rejectsNumericTokensBeyondTheValidationHttpLimitBeforeFreeze()
      throws Exception {
    String config = spotOnlyConfigJson();
    String hash = sha256(config);
    ObjectNode scenario = scenarioNode(realisticSpotScenario(config, hash));
    String oversizedPrice = "1".repeat(1_001);
    ObjectNode last = (ObjectNode) ((ObjectNode) ((ObjectNode) scenario
        .get("marketPath")).withArray("instruments").get(0)).get("last");
    last.put("start", oversizedPrice);
    ((ObjectNode) last.withArray("segments").get(0))
        .put("target", oversizedPrice);

    assertThatThrownBy(() -> factory().compile(
        source(JSON.writeValueAsString(scenario), config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validationRun");
  }

  @Test
  void rejectsAnExpandedStartWireAboveTheLogicalValueBound() {
    String config = configJson();
    String hash = sha256(config);
    String scenario = browserScenario(config, hash)
        .replace("\"durationSeconds\":2", "\"durationSeconds\":20");
    int inputBytes = Math.max(
        config.getBytes(StandardCharsets.UTF_8).length,
        scenario.getBytes(StandardCharsets.UTF_8).length);
    TradingLabReportProperties properties = new TradingLabReportProperties();
    properties.setMaxLogicalValueBytes(inputBytes + 64);
    SnapshotTradingLabValidationStartRequestFactory bounded =
        new SnapshotTradingLabValidationStartRequestFactory(
            new TradingLabCredentialSanitizer(),
            properties);

    assertThatThrownBy(() -> bounded.compile(
        source(scenario, config, hash),
        1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validationRun");
  }

  @Test
  void rejectsHugeExpansionBeforeEnteringTheMarketGenerator() {
    String config = configJson();
    String hash = sha256(config);
    String hostile = browserScenario(config, hash)
        .replace("\"durationSeconds\":2", "\"durationSeconds\":86400")
        .replace("\"target\":\"102\"", "\"target\":\"999999\"");

    assertTimeout(Duration.ofSeconds(2), () ->
        assertThatThrownBy(() -> factory().compile(
            source(hostile, config, hash),
            1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("validationRun"));
  }

  @Test
  void boundsHugePriceAndLiquidityDecimalsBeforeBulkTickMaterialization()
      throws Exception {
    String priceConfig = spotOnlyConfigJson();
    String priceHash = sha256(priceConfig);
    ObjectNode hugePriceScenario =
        scenarioNode(realisticSpotScenario(priceConfig, priceHash));
    ((ObjectNode) hugePriceScenario.get("marketPath")).put("realistic", false);
    ObjectNode last = (ObjectNode) hugePriceScenario
        .path("marketPath")
        .path("instruments")
        .get(0)
        .path("last");
    String hugePrice = "9".repeat(250_000);
    last.put("start", hugePrice);
    ((ObjectNode) last.withArray("segments").get(0))
        .put("target", hugePrice)
        .put("durationSeconds", 1_000);
    String priceSource = JSON.writeValueAsString(hugePriceScenario);
    assertThat(priceSource.getBytes(StandardCharsets.UTF_8).length)
        .isLessThan(new TradingLabReportProperties().maxLogicalValueBytes());

    assertTimeout(Duration.ofSeconds(5), () ->
        assertThatThrownBy(() -> factory().compile(
            source(priceSource, priceConfig, priceHash),
            1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("validationRun"));

    String hugeLiquidity = "9".repeat(100_000);
    String liquidityConfig = spotOnlyConfigJson()
        .replace(
            "\"maxFillQuantityPerTick\":\"10\"",
            "\"maxFillQuantityPerTick\":\"" + hugeLiquidity + "\"")
        .replace(
            "\"maxQty\":\"100\"",
            "\"maxQty\":\"" + hugeLiquidity + "\"");
    String liquidityHash = sha256(liquidityConfig);
    ObjectNode hugeLiquidityScenario =
        scenarioNode(realisticSpotScenario(liquidityConfig, liquidityHash));
    ((ObjectNode) hugeLiquidityScenario.get("executionPolicy"))
        .put("maxFillQuantityPerTick", hugeLiquidity);
    ObjectNode liquiditySegment = (ObjectNode) hugeLiquidityScenario
        .path("marketPath")
        .path("instruments")
        .get(0)
        .path("last")
        .path("segments")
        .get(0);
    liquiditySegment.put("durationSeconds", 1_000);
    String liquiditySource = JSON.writeValueAsString(hugeLiquidityScenario);
    assertThat(liquiditySource.getBytes(StandardCharsets.UTF_8).length)
        .isLessThan(new TradingLabReportProperties().maxLogicalValueBytes());

    assertTimeout(Duration.ofSeconds(5), () ->
        assertThatThrownBy(() -> factory().compile(
            source(liquiditySource, liquidityConfig, liquidityHash),
            1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("validationRun"));
  }

  @Test
  void expandedSizeProofUsesTheWorstPositiveLongGenerationWidth()
      throws Exception {
    String config = configJson();
    String hash = sha256(config);
    String scenario = browserScenario(config, hash)
        .replace("\"durationSeconds\":2", "\"durationSeconds\":20");
    var source = source(scenario, config, hash);
    ValidationRunStartRequest generationOne = factory().compile(source, 1L);
    ValidationRunStartRequest maximumGeneration =
        factory().compile(source, Long.MAX_VALUE);
    ObjectMapper wireJson = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    int generationOneBytes = wireJson.writeValueAsBytes(generationOne).length;
    int maximumGenerationBytes =
        wireJson.writeValueAsBytes(maximumGeneration).length;
    assertThat(maximumGenerationBytes - generationOneBytes)
        .isEqualTo(18 * (generationOne.ticks().size() + 1));
    assertThat(generationOneBytes)
        .isGreaterThan(Math.max(
            config.getBytes(StandardCharsets.UTF_8).length,
            scenario.getBytes(StandardCharsets.UTF_8).length));

    TradingLabReportProperties properties = new TradingLabReportProperties();
    properties.setMaxLogicalValueBytes(
        generationOneBytes + (maximumGenerationBytes - generationOneBytes) / 2);
    SnapshotTradingLabValidationStartRequestFactory bounded =
        new SnapshotTradingLabValidationStartRequestFactory(
            new TradingLabCredentialSanitizer(),
            properties);

    assertThatThrownBy(() -> bounded.compile(source, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validationRun");
    assertThatThrownBy(() -> bounded.compile(source, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validationRun");
  }

  private static void assertCompleteFirstTick(Map<String, Object> tick) {
    assertThat(tick).containsEntry("runId", RUN_ID.toString())
        .containsEntry("generation", 7L)
        .containsEntry("sequence", 1L)
        .containsEntry("virtualTime", "2026-07-25T00:00:01Z");
    assertThat(tick.get("fingerprint").toString()).matches("[0-9a-f]{64}");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> spots =
        (List<Map<String, Object>>) tick.get("spotBundles");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> perpetuals =
        (List<Map<String, Object>>) tick.get("perpetualBundles");
    assertThat(spots).singleElement().satisfies(bundle -> {
      assertThat(bundle).containsEntry("platformSymbol", "BTCUSDT")
          .containsEntry("providerSymbol", "BTCUSDT")
          .containsEntry("providerCode", "validation")
          .containsEntry("sourceMode", "LOCAL_SIMULATED")
          .containsEntry("bid", new BigDecimal("100"))
          .containsEntry("ask", new BigDecimal("102"))
          .containsEntry("last", new BigDecimal("101"))
          .containsEntry("asOf", "2026-07-25T00:00:01Z")
          .containsEntry("expiresAt", "2026-07-25T00:01:01Z");
      assertThat(bundle).containsKeys("orderBook", "recentTrades", "candles");
    });
    assertThat(perpetuals).singleElement().satisfies(bundle -> {
      assertThat(bundle).containsEntry("platformSymbol", "ETHUSDT-PERP")
          .containsEntry("bid", new BigDecimal("200"))
          .containsEntry("ask", new BigDecimal("201"))
          .containsEntry("last", new BigDecimal("200.5"))
          .containsEntry("mark", new BigDecimal("200.5"))
          .containsEntry("index", new BigDecimal("201"));
    });
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> funding =
        (List<Map<String, Object>>) tick.get("fundingRates");
    assertThat(funding).singleElement().satisfies(rate -> assertThat(rate)
        .containsEntry("platformSymbol", "ETHUSDT-PERP")
        .containsEntry("fundingRate", new BigDecimal("0.0002500000")));
  }

  private static ObjectNode scenarioNode(String scenario) throws Exception {
    return (ObjectNode) JSON.readTree(scenario);
  }

  private static ObjectNode firstActionParameters(ObjectNode scenario) {
    ObjectNode action = (ObjectNode) scenario.withArray("timeline").get(0);
    return (ObjectNode) action.get("parameters");
  }

  private static void appendSetLeverage(
      ArrayNode timeline,
      String id,
      int sequence,
      String symbol,
      String productType,
      String triggerJson
  ) throws Exception {
    ObjectNode action = timeline.addObject();
    action.put("id", id);
    action.put("sequence", sequence);
    action.put("type", "SET_LEVERAGE");
    action.put("symbol", symbol);
    action.put("productType", productType);
    action.set("trigger", JSON.readTree(triggerJson));
    action.putObject("parameters").put("leverage", 5);
  }

  private static ObjectNode advancedSpotPath() {
    ObjectNode path = JSON.createObjectNode();
    path.put("mode", "ADVANCED");
    path.put("productType", "CRYPTO_SPOT");
    path.put("symbol", "BTCUSDT");
    path.put("seed", "advanced-spot");
    ObjectNode prices = path.putObject("prices");
    prices.set("bid", scalar("9", "9", 12));
    prices.set("ask", scalar("10", "10", 12));
    prices.set("last", scalar("9", "9", 3));
    return path;
  }

  private static ObjectNode advancedPerpetualPath() {
    ObjectNode path = JSON.createObjectNode();
    path.put("mode", "ADVANCED");
    path.put("productType", "LINEAR_PERP");
    path.put("symbol", "ETHUSDT-PERP");
    path.put("seed", "advanced-perpetual");
    path.put("fundingRate", "-0.0001");
    ObjectNode prices = path.putObject("prices");
    prices.set("bid", scalar("19", "19", 12));
    prices.set("ask", scalar("20", "20", 12));
    prices.set("last", scalar("19", "19", 3));
    prices.set("mark", scalar("18", "18", 3));
    prices.set("index", scalar("19", "19", 3));
    return path;
  }

  private static ObjectNode scalar(
      String start,
      String target,
      int volatilitySteps
  ) {
    ObjectNode scalar = JSON.createObjectNode();
    scalar.put("start", start);
    scalar.putArray("segments")
        .addObject()
        .put("target", target)
        .put("durationSeconds", 20)
        .put("offsetRangeSteps", 0)
        .put("volatilitySteps", volatilitySteps)
        .put("maxStepPerSecond", 1);
    return scalar;
  }

  private static BigDecimal orderBookAmount(
      Map<String, Object> tick,
      String bundleField
  ) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bundles =
        (List<Map<String, Object>>) tick.get(bundleField);
    @SuppressWarnings("unchecked")
    Map<String, Object> book =
        (Map<String, Object>) bundles.getFirst().get("orderBook");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bids =
        (List<Map<String, Object>>) book.get("bids");
    return (BigDecimal) bids.getFirst().get("amount");
  }

  private static void assertQuote(
      Map<String, Object> tick,
      String bundleField,
      String symbol,
      String bid,
      String ask,
      String last,
      String mark,
      String index
  ) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bundles =
        (List<Map<String, Object>>) tick.get(bundleField);
    assertThat(bundles).singleElement().satisfies(bundle -> {
      assertThat(bundle.get("platformSymbol")).isEqualTo(symbol);
      assertThat((BigDecimal) bundle.get("bid")).isEqualByComparingTo(bid);
      assertThat((BigDecimal) bundle.get("ask")).isEqualByComparingTo(ask);
      assertThat((BigDecimal) bundle.get("last")).isEqualByComparingTo(last);
      if (mark != null) {
        assertThat((BigDecimal) bundle.get("mark")).isEqualByComparingTo(mark);
        assertThat((BigDecimal) bundle.get("index")).isEqualByComparingTo(index);
      }
    });
  }

  static SnapshotTradingLabValidationStartRequestFactory factory() {
    return new SnapshotTradingLabValidationStartRequestFactory(
        new TradingLabCredentialSanitizer(),
        new TradingLabReportProperties());
  }

  static TradingLabValidationStartRequestFactory.TradingLabValidationStartSource source(
      String scenario,
      String config,
      String hash
  ) {
    return new TradingLabValidationStartRequestFactory.TradingLabValidationStartSource(
        RUN_ID,
        scenario,
        config,
        hash,
        "model-v1",
        "symbols-v1",
        "code-v1");
  }

  static String browserScenario(String config, String hash) {
    return """
        {
          "id":"browser-scenario",
          "name":"Browser scenario",
          "description":"compiler contract",
          "negativeMode":false,
          "seed":"seed-\u4e2d\u6587",
          "modelVersion":"model-v1",
          "configSnapshot":%s,
          "configSnapshotHash":"%s",
          "executionPolicy":{
            "matchingMode":"SIMPLE",
            "makerFeeRate":"0.0002",
            "takerFeeRate":"0.0005",
            "liquidationFeeRate":"0.001",
            "slippageRate":"0.0001",
            "maxFillQuantityPerTick":"10"
          },
          "marketPath":{
            "virtualStart":"2026-07-25T00:00:00Z",
            "realistic":false,
            "instruments":[
              {
                "mode":"SIMPLE",
                "productType":"LINEAR_PERP",
                "symbol":"ETHUSDT-PERP",
                "seed":"perpetual-seed",
                "last":{
                  "start":"200",
                  "segments":[{
                    "target":"201",
                    "durationSeconds":2,
                    "offsetRangeSteps":0,
                    "volatilitySteps":0,
                    "maxStepPerSecond":1
                  }]
                },
                "spreadSteps":2,
                "indexOffsetSteps":1,
                "basisSteps":-1,
                "fundingRate":"0.00025"
              },
              {
                "mode":"SIMPLE",
                "productType":"CRYPTO_SPOT",
                "symbol":"BTCUSDT",
                "seed":"spot-seed",
                "last":{
                  "start":"100",
                  "segments":[{
                    "target":"102",
                    "durationSeconds":2,
                    "offsetRangeSteps":0,
                    "volatilitySteps":0,
                    "maxStepPerSecond":1
                  }]
                },
                "spreadSteps":2,
                "indexOffsetSteps":0,
                "basisSteps":0
              }
            ]
          },
          "initialBalances":{"BTC":"0","USDT":"100000"},
          "defaults":{"positionMode":"HEDGE","marginMode":"CROSS","leverage":10},
          "symbols":[
            {"symbol":"BTCUSDT","productType":"CRYPTO_SPOT"},
            {"symbol":"ETHUSDT-PERP","productType":"LINEAR_PERP"}
          ],
          "timeline":[
            {
              "id":"place-1",
              "sequence":0,
              "type":"PLACE_ORDER",
              "symbol":"ETHUSDT-PERP",
              "productType":"LINEAR_PERP",
              "trigger":{"type":"VIRTUAL_TIME","atSecond":1},
              "parameters":{
                "side":"BUY",
                "orderType":"LIMIT",
                "quantity":"1",
                "price":"200.5",
                "quantityUnit":"BASE",
                "positionSide":"LONG",
                "marginMode":"CROSS",
                "leverage":10,
                "timeInForce":"GTC",
                "reduceOnly":false
              }
            },
            {
              "id":"cancel-1",
              "sequence":1,
              "type":"CANCEL_ORDER",
              "symbol":"ETHUSDT-PERP",
              "productType":"LINEAR_PERP",
              "trigger":{"type":"AFTER_ACTION","actionId":"place-1","delaySeconds":1},
              "parameters":{"clientOrderId":"place-1"}
            }
          ]
        }
        """.formatted(config, hash);
  }

  static String realisticSpotScenario(String config, String hash) {
    return """
        {
          "id":"realistic-vector",
          "name":"Realistic vector",
          "description":"",
          "negativeMode":false,
          "seed":"seed-\u4e2d\u6587",
          "modelVersion":"model-v1",
          "configSnapshot":%s,
          "configSnapshotHash":"%s",
          "executionPolicy":{
            "matchingMode":"SIMPLE",
            "makerFeeRate":"0.0002",
            "takerFeeRate":"0.0005",
            "liquidationFeeRate":"0.001",
            "slippageRate":"0.0001",
            "maxFillQuantityPerTick":"10"
          },
          "marketPath":{
            "virtualStart":"2026-07-25T00:00:00Z",
            "realistic":true,
            "instruments":[{
              "mode":"SIMPLE",
              "productType":"CRYPTO_SPOT",
              "symbol":"BTCUSDT",
              "seed":"seed-\u4e2d\u6587",
              "last":{
                "start":"10",
                "segments":[{
                  "target":"10",
                  "durationSeconds":6,
                  "offsetRangeSteps":2,
                  "volatilitySteps":2,
                  "maxStepPerSecond":1
                }]
              },
              "spreadSteps":2,
              "indexOffsetSteps":0,
              "basisSteps":0
            }]
          },
          "initialBalances":{"USDT":"100000"},
          "defaults":{"positionMode":"ONE_WAY","marginMode":"CROSS","leverage":1},
          "symbols":[{"symbol":"BTCUSDT","productType":"CRYPTO_SPOT"}],
          "timeline":[{
            "id":"place-1",
            "sequence":0,
            "type":"PLACE_ORDER",
            "symbol":"BTCUSDT",
            "productType":"CRYPTO_SPOT",
            "trigger":{"type":"VIRTUAL_TIME","atSecond":1},
            "parameters":{
              "side":"BUY",
              "orderType":"MARKET",
              "quantity":"10",
              "quantityUnit":"QUOTE"
            }
          }]
        }
        """.formatted(config, hash);
  }

  static String negativeSpotBusinessViolationScenario(
      String config,
      String hash
  ) throws Exception {
    ObjectNode scenario = scenarioNode(realisticSpotScenario(config, hash));
    scenario.put("negativeMode", true);
    ObjectNode action = (ObjectNode) scenario.withArray("timeline").get(0);
    ((ObjectNode) action.get("parameters"))
        .put("quantityUnit", "BASE")
        .put("marginMode", "CROSS");
    action.putObject("expectedError")
        .put("status", 400)
        .put("code", "INVALID_QUANTITY_UNIT");
    return JSON.writeValueAsString(scenario);
  }

  static String configJson() {
    return """
        {"codeVersion":"code-v1","executionPolicy":{"liquidationFeeRate":"0.001","makerFeeRate":"0.0002","matchingMode":"SIMPLE","maxFillQuantityPerTick":"10","slippageRate":"0.0001","takerFeeRate":"0.0005"},"instruments":[{"baseAsset":"BTC","contractSize":"1","defaultLeverage":1,"fixedFundingIntervalMinutes":480,"fixedFundingRate":"0","initialMarginRate":"1","liquidationFeeRate":"0.001","maintenanceMarginRate":"0","marginAsset":"USDT","markPriceSource":"LAST","maxLeverage":1,"maxNotional":"1000000","maxQty":"100","minNotional":"1","minQty":"1","pricePrecision":0,"productType":"CRYPTO_SPOT","quantityPrecision":0,"quoteAsset":"USDT","riskTier":"T1","settlementAsset":"USDT","stepSize":"1","symbol":"BTCUSDT","tickSize":"1"},{"baseAsset":"ETH","contractSize":"1","defaultLeverage":10,"fixedFundingIntervalMinutes":480,"fixedFundingRate":"0.00025","initialMarginRate":"0.01","liquidationFeeRate":"0.001","maintenanceMarginRate":"0.005","marginAsset":"USDT","markPriceSource":"INDEX","maxLeverage":100,"maxNotional":"1000000","maxQty":"100","minNotional":"1","minQty":"0.5","pricePrecision":1,"productType":"LINEAR_PERP","quantityPrecision":1,"quoteAsset":"USDT","riskTier":"T1","settlementAsset":"USDT","stepSize":"0.5","symbol":"ETHUSDT-PERP","tickSize":"0.5"}],"modelVersion":"model-v1","symbolConfigVersion":"symbols-v1"}
        """.strip();
  }

  static String spotOnlyConfigJson() {
    return """
        {"codeVersion":"code-v1","executionPolicy":{"liquidationFeeRate":"0.001","makerFeeRate":"0.0002","matchingMode":"SIMPLE","maxFillQuantityPerTick":"10","slippageRate":"0.0001","takerFeeRate":"0.0005"},"instruments":[{"baseAsset":"BTC","contractSize":"1","defaultLeverage":1,"fixedFundingIntervalMinutes":480,"fixedFundingRate":"0","initialMarginRate":"1","liquidationFeeRate":"0.001","maintenanceMarginRate":"0","marginAsset":"USDT","markPriceSource":"LAST","maxLeverage":1,"maxNotional":"1000000","maxQty":"100","minNotional":"1","minQty":"1","pricePrecision":0,"productType":"CRYPTO_SPOT","quantityPrecision":0,"quoteAsset":"USDT","riskTier":"T1","settlementAsset":"USDT","stepSize":"1","symbol":"BTCUSDT","tickSize":"1"}],"modelVersion":"model-v1","symbolConfigVersion":"symbols-v1"}
        """.strip();
  }

  static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
