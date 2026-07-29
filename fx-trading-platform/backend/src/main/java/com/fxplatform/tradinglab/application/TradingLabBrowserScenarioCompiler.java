package com.fxplatform.tradinglab.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.tradinglab.application.TradingLabValidationStartRequestFactory
    .TradingLabValidationStartSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Compiles the frozen browser scenario model into validation-client map values.
 *
 * <p>This module deliberately does not import validation runtime implementation types. The
 * validation client DTO remains the production boundary between the main control plane and the
 * isolated validation runtime.</p>
 */
public final class TradingLabBrowserScenarioCompiler {

  private static final int MAX_TICKS = 86_400;
  private static final int MAX_ACTIONS = 100_000;
  private static final int MAX_SYMBOLS_PER_PRODUCT = 128;
  private static final int MAX_TRIGGER_DEPTH = 16;
  private static final long MIN_EXPANDED_TICK_BYTES = 64L;
  private static final long MIN_EXPANDED_INSTRUMENT_BYTES = 256L;
  private static final long MIN_EXPANDED_ACTION_BYTES = 64L;
  private static final long UINT32_MASK = 0xffff_ffffL;
  private static final BigInteger UINT32_RANGE = BigInteger.ONE.shiftLeft(32);
  private static final long FNV1A_OFFSET = 0x811c9dc5L;
  private static final long FNV1A_PRIME = 0x01000193L;
  private static final int MAX_JSON_NUMBER_CHARS = 1_000;
  private static final long MULBERRY_INCREMENT = 0x6d2b79f5L;
  private static final Pattern PLAIN_DECIMAL =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
  private static final Pattern EXPECTED_ERROR_CODE =
      Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
  private static final Pattern UTC_MILLISECOND_INSTANT = Pattern.compile(
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z");
  private static final DateTimeFormatter UTC_MILLISECOND_FORMAT =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

  private static final Set<String> BROWSER_FIELDS = Set.of(
      "id",
      "name",
      "description",
      "negativeMode",
      "seed",
      "modelVersion",
      "configSnapshot",
      "configSnapshotHash",
      "executionPolicy",
      "marketPath",
      "initialBalances",
      "defaults",
      "symbols",
      "timeline");
  private static final Set<String> CONFIG_FIELDS = Set.of(
      "modelVersion",
      "symbolConfigVersion",
      "codeVersion",
      "executionPolicy",
      "instruments");
  private static final Set<String> INSTRUMENT_FIELDS = Set.of(
      "symbol",
      "productType",
      "baseAsset",
      "quoteAsset",
      "tickSize",
      "stepSize",
      "pricePrecision",
      "quantityPrecision",
      "minQty",
      "maxQty",
      "minNotional",
      "maxNotional",
      "initialMarginRate",
      "maintenanceMarginRate",
      "liquidationFeeRate",
      "fixedFundingRate",
      "fixedFundingIntervalMinutes",
      "markPriceSource",
      "contractSize",
      "maxLeverage",
      "defaultLeverage",
      "marginAsset",
      "settlementAsset",
      "riskTier");
  private static final Set<String> EXECUTION_POLICY_FIELDS = Set.of(
      "matchingMode",
      "makerFeeRate",
      "takerFeeRate",
      "liquidationFeeRate",
      "slippageRate",
      "maxFillQuantityPerTick");
  private static final Set<String> ACTION_FIELDS = Set.of(
      "id",
      "sequence",
      "type",
      "symbol",
      "productType",
      "trigger",
      "parameters",
      "overrides",
      "expectedError");
  private static final Set<String> PUBLIC_ACTIONS = Set.of(
      "PLACE_ORDER",
      "CANCEL_ORDER",
      "CANCEL_ALL",
      "SET_POSITION_MODE",
      "SET_MARGIN_MODE",
      "SET_LEVERAGE");
  private static final Set<String> ORDER_TYPES = Set.of(
      "MARKET",
      "LIMIT",
      "STOP_MARKET",
      "STOP_LIMIT",
      "TRAILING_STOP_MARKET");
  private static final Set<String> POSITION_SIDES = Set.of("BOTH", "LONG", "SHORT");
  private static final Set<String> QUANTITY_UNITS = Set.of("BASE", "QUOTE", "CONTRACTS");
  private static final Set<String> TIME_IN_FORCE_VALUES = Set.of("GTC", "IOC", "FOK");
  private static final Set<String> TRIGGER_PRICE_TYPES = Set.of("LAST_PRICE", "MARK_PRICE");
  private static final Set<String> PROTECTION_TYPES = Set.of("TAKE_PROFIT", "STOP_LOSS");
  private static final Set<String> TRIGGER_EXECUTION_TYPES = Set.of("MARKET", "LIMIT");
  private static final Set<String> PLACE_ORDER_FIELDS = Set.of(
      "side",
      "orderType",
      "lots",
      "requestedPrice",
      "stopLoss",
      "takeProfit",
      "quantity",
      "price",
      "leverage",
      "positionSide",
      "quantityUnit",
      "marginMode",
      "triggerPrice",
      "triggerPriceType",
      "reduceOnly",
      "attachedProtections",
      "timeInForce",
      "postOnly",
      "activationPrice",
      "trailingDelta",
      "trailingRate");
  private static final Set<String> PLACE_ORDER_DECIMAL_FIELDS = Set.of(
      "lots",
      "requestedPrice",
      "stopLoss",
      "takeProfit",
      "quantity",
      "price",
      "triggerPrice",
      "activationPrice",
      "trailingDelta",
      "trailingRate");
  private static final Set<String> PLACE_ORDER_BOOLEAN_FIELDS =
      Set.of("reduceOnly", "postOnly");
  private static final Set<String> PROTECTION_FIELDS = Set.of(
      "protectionType",
      "triggerPrice",
      "triggerPriceType",
      "triggerExecutionType",
      "price",
      "quantity",
      "quantityUnit");

  private final ObjectMapper canonicalJson;
  private final int maxExpandedBytes;

  public TradingLabBrowserScenarioCompiler(int maxExpandedBytes) {
    if (maxExpandedBytes < 1) {
      throw new IllegalArgumentException("maxExpandedBytes must be positive");
    }
    this.maxExpandedBytes = maxExpandedBytes;
    this.canonicalJson = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .build();
  }

  CompiledBrowserStart compile(
      TradingLabValidationStartSource source,
      long generation,
      Map<String, Object> scenario,
      Map<String, Object> frozenConfig
  ) {
    Objects.requireNonNull(source, "source");
    if (generation <= 0L) {
      throw invalid("generation");
    }
    requireExactKeys(scenario, BROWSER_FIELDS, "scenario");
    requireNonBlankText(scenario.get("id"), "id");
    requireText(scenario.get("name"), "name", 200);
    if (!(scenario.get("description") instanceof String description)
        || description.length() > 10_000) {
      throw invalid("description");
    }
    requireExactKeys(frozenConfig, CONFIG_FIELDS, "configSnapshot");
    Map<String, Object> embeddedConfig =
        requireMap(scenario.get("configSnapshot"), "configSnapshot");
    requireExactKeys(embeddedConfig, CONFIG_FIELDS, "configSnapshot");
    if (!java.util.Arrays.equals(canonicalBytes(embeddedConfig), canonicalBytes(frozenConfig))) {
      throw invalid("configSnapshot");
    }
    String frozenHash = sha256(canonicalBytes(frozenConfig));
    String scenarioHash = requireText(
        scenario.get("configSnapshotHash"), "configSnapshotHash", 64);
    if (!frozenHash.equals(source.configSnapshotHash())
        || !frozenHash.equals(scenarioHash)
        || !scenarioHash.matches("[0-9a-f]{64}")) {
      throw invalid("configSnapshotHash");
    }

    String seed = requireSeed(scenario.get("seed"), "seed");
    boolean negativeMode = requireBoolean(scenario.get("negativeMode"), "negativeMode");
    if (source.expectedSeed() != null && !source.expectedSeed().equals(seed)) {
      throw invalid("seed");
    }
    if (source.expectedNegativeMode() != null
        && source.expectedNegativeMode() != negativeMode) {
      throw invalid("negativeMode");
    }
    String scenarioModel = requireText(scenario.get("modelVersion"), "modelVersion", 80);
    String configModel = requireText(frozenConfig.get("modelVersion"), "modelVersion", 80);
    String configSymbols = requireText(
        frozenConfig.get("symbolConfigVersion"), "symbolConfigVersion", 256);
    String configCode = requireText(frozenConfig.get("codeVersion"), "codeVersion", 256);
    if (!source.modelVersion().equals(scenarioModel)
        || !source.modelVersion().equals(configModel)
        || !source.symbolConfigVersion().equals(configSymbols)
        || !source.codeVersion().equals(configCode)) {
      throw invalid("configMetadata");
    }

    Map<InstrumentIdentity, InstrumentAuthority> authorities =
        authorities(frozenConfig);
    Set<InstrumentIdentity> selected = selectedIdentities(scenario, authorities);
    Map<String, Object> executionPolicy = executionPolicy(scenario);
    BigDecimal maxFillQuantity =
        (BigDecimal) executionPolicy.get("maxFillQuantityPerTick");
    Map<String, Object> requestedMarketPath =
        requireMap(scenario.get("marketPath"), "marketPath");
    List<?> requestedActions = requireList(scenario.get("timeline"), "timeline");
    CompiledMarket market = compileMarket(
        source.runId(),
        generation,
        requestedMarketPath,
        selected,
        authorities,
        maxFillQuantity,
        requestedActions.size());
    List<Map<String, Object>> actions = compileActions(
        source.runId(),
        generation,
        negativeMode,
        requestedActions,
        selected,
        authorities,
        market.ticks());

    Map<String, BigDecimal> initialBalances = balances(
        requireMap(scenario.get("initialBalances"), "initialBalances"));
    Map<String, Object> defaults = requireMap(scenario.get("defaults"), "defaults");
    requireExactKeys(defaults, Set.of("positionMode", "marginMode", "leverage"), "defaults");
    String positionMode = requireEnum(
        defaults.get("positionMode"), Set.of("ONE_WAY", "HEDGE"), "positionMode");
    String marginMode = requireEnum(
        defaults.get("marginMode"), Set.of("CROSS", "ISOLATED"), "marginMode");
    int defaultLeverageLimit = selected.stream()
        .filter(identity -> "LINEAR_PERP".equals(identity.productType()))
        .mapToInt(identity -> authorities.get(identity).maxLeverage())
        .min()
        .orElse(1);
    int leverage = requireInteger(
        defaults.get("leverage"),
        1,
        defaultLeverageLimit,
        "leverage");
    Map<String, Object> accountSettings = Map.of(
        "positionMode", positionMode,
        "marginMode", marginMode,
        "leverage", leverage,
        "quantityUnit", "BASE");

    return new CompiledBrowserStart(
        seed,
        market.virtualStart(),
        executionPolicy,
        initialBalances,
        accountSettings,
        market.wireTicks(),
        actions,
        new BigDecimal("1.000000"));
  }

  private Map<InstrumentIdentity, InstrumentAuthority> authorities(
      Map<String, Object> config
  ) {
    List<?> values = requireList(config.get("instruments"), "configSnapshot.instruments");
    if (values.isEmpty()) {
      throw invalid("configSnapshot.instruments");
    }
    Map<InstrumentIdentity, InstrumentAuthority> result = new HashMap<>();
    for (Object value : values) {
      Map<String, Object> row = requireMap(value, "configSnapshot.instruments");
      requireExactKeys(row, INSTRUMENT_FIELDS, "configSnapshot.instruments");
      String productType = productType(row.get("productType"));
      String symbol = requireText(row.get("symbol"), "symbol", 256);
      if (!symbol.equals(SymbolNormalizer.normalize(symbol))) {
        throw invalid("symbol");
      }
      InstrumentIdentity identity = new InstrumentIdentity(productType, symbol);
      InstrumentAuthority authority = new InstrumentAuthority(
          identity,
          requirePositiveDecimal(row.get("tickSize"), "tickSize"),
          requirePositiveDecimal(row.get("stepSize"), "stepSize"),
          requirePositiveDecimal(row.get("minQty"), "minQty"),
          requirePositiveDecimal(row.get("maxQty"), "maxQty"),
          optionalPositiveDecimal(row.get("minNotional"), "minNotional"),
          optionalPositiveDecimal(row.get("maxNotional"), "maxNotional"),
          requireInteger(row.get("maxLeverage"), 1, 125, "maxLeverage"));
      if (authority.minQuantity().compareTo(authority.maxQuantity()) > 0
          || (authority.minNotional() != null
              && authority.maxNotional() != null
              && authority.minNotional().compareTo(authority.maxNotional()) > 0)) {
        throw invalid("configSnapshot.instruments");
      }
      if (result.put(identity, authority) != null) {
        throw invalid("configSnapshot.instruments");
      }
    }
    return Map.copyOf(result);
  }

  private Set<InstrumentIdentity> selectedIdentities(
      Map<String, Object> scenario,
      Map<InstrumentIdentity, InstrumentAuthority> authorities
  ) {
    List<?> values = requireList(scenario.get("symbols"), "symbols");
    if (values.isEmpty()) {
      throw invalid("symbols");
    }
    Set<InstrumentIdentity> selected = new HashSet<>();
    int spots = 0;
    int perpetuals = 0;
    for (Object value : values) {
      Map<String, Object> row = requireMap(value, "symbols");
      requireExactKeys(row, Set.of("symbol", "productType"), "symbols");
      InstrumentIdentity identity = new InstrumentIdentity(
          productType(row.get("productType")),
          requireText(row.get("symbol"), "symbol", 256));
      if (!authorities.containsKey(identity) || !selected.add(identity)) {
        throw invalid("symbols");
      }
      if ("CRYPTO_SPOT".equals(identity.productType())) {
        spots++;
      } else {
        perpetuals++;
      }
    }
    if (spots > MAX_SYMBOLS_PER_PRODUCT || perpetuals > MAX_SYMBOLS_PER_PRODUCT) {
      throw invalid("symbols");
    }
    return Set.copyOf(selected);
  }

  private Map<String, Object> executionPolicy(Map<String, Object> scenario) {
    Map<String, Object> requested =
        requireMap(scenario.get("executionPolicy"), "executionPolicy");
    requireExactKeys(requested, EXECUTION_POLICY_FIELDS, "executionPolicy");
    String matchingMode = requireEnum(
        requested.get("matchingMode"), Set.of("SIMPLE", "DEPTH"), "matchingMode");
    BigDecimal maker = requireRate(requested.get("makerFeeRate"), "makerFeeRate");
    BigDecimal taker = requireRate(requested.get("takerFeeRate"), "takerFeeRate");
    BigDecimal liquidation =
        requireRate(requested.get("liquidationFeeRate"), "liquidationFeeRate");
    BigDecimal slippage = requireRate(requested.get("slippageRate"), "slippageRate");
    BigDecimal maxFill =
        optionalPositiveDecimal(requested.get("maxFillQuantityPerTick"),
            "maxFillQuantityPerTick");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("matchingMode", matchingMode);
    result.put("makerFeeRate", maker);
    result.put("takerFeeRate", taker);
    result.put("liquidationFeeRate", liquidation);
    result.put("slippageRate", slippage);
    result.put("maxFillQuantityPerTick", maxFill);
    return Collections.unmodifiableMap(result);
  }

  private CompiledMarket compileMarket(
      UUID runId,
      long generation,
      Map<String, Object> path,
      Set<InstrumentIdentity> selected,
      Map<InstrumentIdentity, InstrumentAuthority> authorities,
      BigDecimal maxFillQuantity,
      int requestedActionCount
  ) {
    requireExactKeys(path, Set.of("virtualStart", "realistic", "instruments"), "marketPath");
    Instant virtualStart = requireInstant(path.get("virtualStart"), "virtualStart");
    boolean realistic = requireBoolean(path.get("realistic"), "realistic");
    List<?> requestedPaths = requireList(path.get("instruments"), "marketPath.instruments");
    if (requestedPaths.isEmpty()) {
      throw invalid("marketPath.instruments");
    }
    Map<InstrumentIdentity, Map<String, Object>> pathByIdentity = new HashMap<>();
    for (Object value : requestedPaths) {
      Map<String, Object> instrumentPath = requireMap(value, "marketPath.instruments");
      InstrumentIdentity identity = new InstrumentIdentity(
          productType(instrumentPath.get("productType")),
          requireText(instrumentPath.get("symbol"), "symbol", 256));
      if (!selected.contains(identity)
          || pathByIdentity.put(identity, instrumentPath) != null) {
        throw invalid("marketPath.instruments");
      }
    }
    if (!pathByIdentity.keySet().equals(selected)) {
      throw invalid("marketPath.instruments");
    }
    ExpansionBudget expansionBudget = preflightExpandedSize(
        pathByIdentity,
        authorities,
        maxFillQuantity,
        requestedActionCount);

    List<CompiledInstrument> instruments = pathByIdentity.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> compileInstrument(
            entry.getValue(),
            authorities.get(entry.getKey()),
            realistic,
            expansionBudget))
        .toList();
    int duration = instruments.getFirst().ticks().size();
    if (duration < 1
        || duration > MAX_TICKS
        || instruments.stream().anyMatch(instrument -> instrument.ticks().size() != duration)) {
      throw invalid("marketPath.duration");
    }
    try {
      virtualStart.plusSeconds(duration);
    } catch (DateTimeException exception) {
      throw invalid("virtualStart");
    }

    List<CompactTick> compact = new ArrayList<>(duration);
    List<Map<String, Object>> wire = new ArrayList<>(duration);
    for (int index = 0; index < duration; index++) {
      long sequence = index + 1L;
      Instant virtualTime = virtualStart.plusSeconds(sequence);
      List<InstrumentTick> compactInstruments = new ArrayList<>(instruments.size());
      for (CompiledInstrument instrument : instruments) {
        compactInstruments.add(instrument.ticks().get(index));
      }
      CompactTick compactTick =
          new CompactTick(sequence, virtualTime, List.copyOf(compactInstruments));
      compact.add(compactTick);
      wire.add(enrichTick(
          runId,
          generation,
          compactTick,
          instruments,
          maxFillQuantity));
    }
    return new CompiledMarket(
        virtualStart,
        List.copyOf(compact),
        List.copyOf(wire));
  }

  private ExpansionBudget preflightExpandedSize(
      Map<InstrumentIdentity, Map<String, Object>> pathByIdentity,
      Map<InstrumentIdentity, InstrumentAuthority> authorities,
      BigDecimal maxFillQuantity,
      int requestedActionCount
  ) {
    int duration = -1;
    for (Map.Entry<InstrumentIdentity, Map<String, Object>> entry
        : pathByIdentity.entrySet()) {
      int instrumentDuration = preflightDuration(entry.getKey(), entry.getValue());
      if (duration < 0) {
        duration = instrumentDuration;
      } else if (duration != instrumentDuration) {
        throw invalid("marketPath.duration");
      }
    }
    if (duration < 1 || duration > MAX_TICKS || requestedActionCount > MAX_ACTIONS) {
      throw invalid("validationRun");
    }
    try {
      long liquidityBytesPerTick = 0L;
      for (InstrumentIdentity identity : pathByIdentity.keySet()) {
        BigDecimal liquidity = effectiveLiquidity(
            authorities.get(identity),
            maxFillQuantity);
        liquidityBytesPerTick = Math.addExact(
            liquidityBytesPerTick,
            Math.multiplyExact(4L, plainDecimalLength(liquidity)));
      }
      long perTick = Math.addExact(
          MIN_EXPANDED_TICK_BYTES,
          Math.multiplyExact(
              pathByIdentity.size(),
              MIN_EXPANDED_INSTRUMENT_BYTES));
      perTick = Math.addExact(perTick, liquidityBytesPerTick);
      long lowerBound = Math.addExact(
          Math.multiplyExact(duration, perTick),
          Math.multiplyExact(requestedActionCount, MIN_EXPANDED_ACTION_BYTES));
      /*
       * This is intentionally a lower bound: it counts only a subset of the fixed JSON
       * field-name bytes emitted by each tick/bundle/action and ignores every value plus the
       * top-level document. Rejection is therefore safe, while the factory's canonical
       * serialization remains the exact post-compile size gate.
       */
      if (lowerBound > maxExpandedBytes) {
        throw invalid("validationRun");
      }
      return new ExpansionBudget(maxExpandedBytes - lowerBound);
    } catch (ArithmeticException exception) {
      throw invalid("validationRun");
    }
  }

  private int preflightDuration(
      InstrumentIdentity identity,
      Map<String, Object> path
  ) {
    String mode = requireEnum(path.get("mode"), Set.of("SIMPLE", "ADVANCED"), "mode");
    if ("SIMPLE".equals(mode)) {
      return scalarDuration(requireMap(path.get("last"), "last"), "last");
    }
    Map<String, Object> prices = requireMap(path.get("prices"), "prices");
    Set<String> lanes = "LINEAR_PERP".equals(identity.productType())
        ? Set.of("bid", "ask", "last", "mark", "index")
        : Set.of("bid", "ask", "last");
    int duration = -1;
    for (String lane : lanes) {
      int laneDuration = scalarDuration(requireMap(prices.get(lane), lane), lane);
      if (duration < 0) {
        duration = laneDuration;
      } else if (duration != laneDuration) {
        throw invalid("marketPath.duration");
      }
    }
    return duration;
  }

  private static int scalarDuration(Map<String, Object> scalar, String lane) {
    List<?> segments = requireList(scalar.get("segments"), lane + ".segments");
    if (segments.isEmpty()) {
      throw invalid(lane + ".segments");
    }
    long duration = 0L;
    for (Object value : segments) {
      Map<String, Object> segment = requireMap(value, lane + ".segment");
      int segmentDuration = requireInteger(
          segment.get("durationSeconds"), 1, MAX_TICKS, "durationSeconds");
      try {
        duration = Math.addExact(duration, segmentDuration);
      } catch (ArithmeticException exception) {
        throw invalid("marketPath.duration");
      }
      if (duration > MAX_TICKS) {
        throw invalid("marketPath.duration");
      }
    }
    return Math.toIntExact(duration);
  }

  private CompiledInstrument compileInstrument(
      Map<String, Object> path,
      InstrumentAuthority authority,
      boolean realistic,
      ExpansionBudget expansionBudget
  ) {
    String mode = requireEnum(path.get("mode"), Set.of("SIMPLE", "ADVANCED"), "mode");
    String productType = productType(path.get("productType"));
    String symbol = requireText(path.get("symbol"), "symbol", 256);
    if (!authority.identity().equals(new InstrumentIdentity(productType, symbol))) {
      throw invalid("marketPath.instrument");
    }
    String seed = requireSeed(path.get("seed"), "marketPath.seed");
    if ("SIMPLE".equals(mode)) {
      Set<String> expected = "LINEAR_PERP".equals(productType)
          ? Set.of(
              "mode", "productType", "symbol", "seed", "last", "spreadSteps",
              "indexOffsetSteps", "basisSteps", "fundingRate")
          : Set.of(
              "mode", "productType", "symbol", "seed", "last", "spreadSteps",
              "indexOffsetSteps", "basisSteps");
      requireExactKeys(path, expected, "marketPath.instrument");
      return compileSimple(path, authority, realistic, seed, expansionBudget);
    }
    Set<String> expected = "LINEAR_PERP".equals(productType)
        ? Set.of("mode", "productType", "symbol", "seed", "prices", "fundingRate")
        : Set.of("mode", "productType", "symbol", "seed", "prices");
    requireExactKeys(path, expected, "marketPath.instrument");
    return compileAdvanced(path, authority, realistic, seed, expansionBudget);
  }

  private CompiledInstrument compileSimple(
      Map<String, Object> path,
      InstrumentAuthority authority,
      boolean realistic,
      String seed,
      ExpansionBudget expansionBudget
  ) {
    long spreadSteps = requirePositiveSafeLong(path.get("spreadSteps"), "spreadSteps");
    long indexOffset = requireSafeLong(path.get("indexOffsetSteps"), "indexOffsetSteps");
    long basis = requireSafeLong(path.get("basisSteps"), "basisSteps");
    List<GeneratedPoint> last = compileScalar(
        requireMap(path.get("last"), "last"),
        authority,
        realistic,
        seed,
        "last",
        expansionBudget);
    BigInteger spread = BigInteger.valueOf(spreadSteps);
    BigInteger halfSpread = spread.divide(BigInteger.TWO);
    List<InstrumentTick> ticks = new ArrayList<>(last.size());
    for (GeneratedPoint point : last) {
      BigInteger bid = point.steps().subtract(halfSpread);
      BigInteger ask = bid.add(spread);
      if (bid.signum() <= 0 || ask.signum() <= 0) {
        throw invalid("marketPath.price");
      }
      BigDecimal bidPrice = price(bid, authority.tickSize());
      BigDecimal askPrice = price(ask, authority.tickSize());
      BigDecimal lastPrice = price(point.steps(), authority.tickSize());
      BigDecimal lowPrice = bidPrice.min(askPrice).min(lastPrice);
      BigDecimal highPrice = bidPrice.max(askPrice).max(lastPrice);
      expansionBudget.consumeDecimal(bidPrice, 2L);
      expansionBudget.consumeDecimal(askPrice, 2L);
      expansionBudget.consumeDecimal(lastPrice, 3L);
      expansionBudget.consumeDecimal(lowPrice, 1L);
      expansionBudget.consumeDecimal(highPrice, 1L);
      if ("CRYPTO_SPOT".equals(authority.identity().productType())) {
        ticks.add(new InstrumentTick(
            authority.identity(),
            bidPrice,
            askPrice,
            lastPrice,
            null,
            null));
      } else {
        BigInteger index = point.steps().add(BigInteger.valueOf(indexOffset));
        BigInteger mark = index.add(BigInteger.valueOf(basis));
        if (index.signum() <= 0 || mark.signum() <= 0) {
          throw invalid("marketPath.price");
        }
        BigDecimal markPrice = price(mark, authority.tickSize());
        BigDecimal indexPrice = price(index, authority.tickSize());
        expansionBudget.consumeDecimal(markPrice, 1L);
        expansionBudget.consumeDecimal(indexPrice, 1L);
        ticks.add(new InstrumentTick(
            authority.identity(),
            bidPrice,
            askPrice,
            lastPrice,
            markPrice,
            indexPrice));
      }
    }
    BigDecimal funding = "LINEAR_PERP".equals(authority.identity().productType())
        ? requireFunding(path.get("fundingRate"), "fundingRate")
        : null;
    return new CompiledInstrument(authority, List.copyOf(ticks), funding);
  }

  private CompiledInstrument compileAdvanced(
      Map<String, Object> path,
      InstrumentAuthority authority,
      boolean realistic,
      String seed,
      ExpansionBudget expansionBudget
  ) {
    Map<String, Object> prices = requireMap(path.get("prices"), "prices");
    Set<String> lanes = "LINEAR_PERP".equals(authority.identity().productType())
        ? Set.of("bid", "ask", "last", "mark", "index")
        : Set.of("bid", "ask", "last");
    requireExactKeys(prices, lanes, "prices");
    Map<String, List<GeneratedPoint>> compiled = new HashMap<>();
    for (String lane : lanes) {
      compiled.put(lane, compileScalar(
          requireMap(prices.get(lane), lane),
          authority,
          realistic,
          seed,
          lane,
          expansionBudget));
    }
    int duration = compiled.get("bid").size();
    if (compiled.values().stream().anyMatch(value -> value.size() != duration)) {
      throw invalid("marketPath.duration");
    }
    List<InstrumentTick> ticks = new ArrayList<>(duration);
    for (int index = 0; index < duration; index++) {
      GeneratedPoint bidPoint = compiled.get("bid").get(index);
      GeneratedPoint askPoint = compiled.get("ask").get(index);
      BigInteger ask = askPoint.steps();
      if (bidPoint.steps().compareTo(ask) >= 0) {
        if (bidPoint.segmentFinal() || askPoint.segmentFinal()) {
          throw invalid("marketPath.bidAsk");
        }
        ask = bidPoint.steps().add(BigInteger.ONE);
      }
      GeneratedPoint last = compiled.get("last").get(index);
      BigDecimal bidPrice = price(bidPoint.steps(), authority.tickSize());
      BigDecimal askPrice = price(ask, authority.tickSize());
      BigDecimal lastPrice = price(last.steps(), authority.tickSize());
      BigDecimal lowPrice = bidPrice.min(askPrice).min(lastPrice);
      BigDecimal highPrice = bidPrice.max(askPrice).max(lastPrice);
      expansionBudget.consumeDecimal(bidPrice, 1L);
      expansionBudget.consumeDecimal(askPrice, 1L);
      expansionBudget.consumeDecimal(lastPrice, 3L);
      expansionBudget.consumeDecimal(lowPrice, 1L);
      expansionBudget.consumeDecimal(highPrice, 1L);
      if ("CRYPTO_SPOT".equals(authority.identity().productType())) {
        ticks.add(new InstrumentTick(
            authority.identity(),
            bidPrice,
            askPrice,
            lastPrice,
            null,
            null));
      } else {
        BigDecimal markPrice =
            price(compiled.get("mark").get(index).steps(), authority.tickSize());
        BigDecimal indexPrice =
            price(compiled.get("index").get(index).steps(), authority.tickSize());
        ticks.add(new InstrumentTick(
            authority.identity(),
            bidPrice,
            askPrice,
            lastPrice,
            markPrice,
            indexPrice));
      }
    }
    BigDecimal funding = "LINEAR_PERP".equals(authority.identity().productType())
        ? requireFunding(path.get("fundingRate"), "fundingRate")
        : null;
    return new CompiledInstrument(authority, List.copyOf(ticks), funding);
  }

  private List<GeneratedPoint> compileScalar(
      Map<String, Object> scalar,
      InstrumentAuthority authority,
      boolean realistic,
      String seed,
      String lane,
      ExpansionBudget expansionBudget
  ) {
    requireExactKeys(scalar, Set.of("start", "segments"), lane);
    List<?> segments = requireList(scalar.get("segments"), lane + ".segments");
    if (segments.isEmpty()) {
      throw invalid(lane + ".segments");
    }
    BigInteger segmentStart = priceSteps(scalar.get("start"), authority.tickSize(), lane);
    List<GeneratedPoint> points = new ArrayList<>();
    for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
      Map<String, Object> segment = requireMap(segments.get(segmentIndex), lane + ".segment");
      requireExactKeys(
          segment,
          Set.of(
              "target",
              "durationSeconds",
              "offsetRangeSteps",
              "volatilitySteps",
              "maxStepPerSecond"),
          lane + ".segment");
      int duration = requireInteger(
          segment.get("durationSeconds"), 1, MAX_TICKS, "durationSeconds");
      long offsetRange = requireNonNegativeLong(
          segment.get("offsetRangeSteps"), "offsetRangeSteps");
      long volatility = requireNonNegativeLong(
          segment.get("volatilitySteps"), "volatilitySteps");
      long maxStep = requirePositiveSafeLong(
          segment.get("maxStepPerSecond"), "maxStepPerSecond");
      if ((long) points.size() + duration > MAX_TICKS) {
        throw invalid("marketPath.duration");
      }
      BigInteger target = priceSteps(segment.get("target"), authority.tickSize(), lane);
      BigInteger delta = target.subtract(segmentStart);
      BigInteger distance = delta.abs();
      BigInteger required = distance
          .add(BigInteger.valueOf(duration - 1L))
          .divide(BigInteger.valueOf(duration));
      if (required.compareTo(BigInteger.valueOf(maxStep)) > 0) {
        throw invalid("marketPath.maxStepPerSecond");
      }
      Mulberry32 random = realistic
          ? new Mulberry32(seed
              + "\0" + authority.identity().productType()
              + "\0" + authority.identity().symbol()
              + "\0" + lane
              + "\0" + segmentIndex)
          : null;
      BigInteger segmentOffset = random == null
          ? BigInteger.ZERO
          : randomSignedSteps(random.nextUnsigned(), offsetRange);
      BigInteger direction = delta.signum() < 0
          ? BigInteger.ONE.negate()
          : BigInteger.ONE;
      for (int second = 1; second <= duration; second++) {
        boolean finalPoint = second == duration;
        BigInteger generated = segmentStart.add(direction.multiply(
            distance.multiply(BigInteger.valueOf(second))
                .divide(BigInteger.valueOf(duration))));
        if (random != null && !finalPoint) {
          generated = generated.add(
              segmentOffset.multiply(BigInteger.valueOf(duration - second))
                  .divide(BigInteger.valueOf(duration)));
          generated = generated.add(randomSignedSteps(random.nextUnsigned(), volatility));
          if (generated.signum() <= 0) {
            generated = BigInteger.ONE;
          }
        }
        if (finalPoint) {
          generated = target;
        }
        expansionBudget.consumePrice(generated, authority.tickSize());
        points.add(new GeneratedPoint(generated, finalPoint));
      }
      segmentStart = target;
    }
    return List.copyOf(points);
  }

  private Map<String, Object> enrichTick(
      UUID runId,
      long generation,
      CompactTick tick,
      List<CompiledInstrument> instruments,
      BigDecimal maxFillQuantity
  ) {
    List<Map<String, Object>> spots = new ArrayList<>();
    List<Map<String, Object>> perpetuals = new ArrayList<>();
    List<Map<String, Object>> funding = new ArrayList<>();
    for (int index = 0; index < instruments.size(); index++) {
      CompiledInstrument instrument = instruments.get(index);
      InstrumentTick quote = tick.instruments().get(index);
      BigDecimal liquidity =
          effectiveLiquidity(instrument.authority(), maxFillQuantity);
      Map<String, Object> bundle = bundle(
          runId,
          generation,
          tick,
          quote,
          liquidity);
      if ("CRYPTO_SPOT".equals(quote.identity().productType())) {
        spots.add(bundle);
      } else {
        perpetuals.add(bundle);
        funding.add(Map.of(
            "platformSymbol", quote.identity().symbol(),
            "fundingRate", instrument.fundingRate()));
      }
    }
    Comparator<Map<String, Object>> bySymbol =
        Comparator.comparing(value -> value.get("platformSymbol").toString());
    spots.sort(bySymbol);
    perpetuals.sort(bySymbol);
    funding.sort(bySymbol);
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("runId", runId.toString());
    material.put("generation", generation);
    material.put("sequence", tick.sequence());
    material.put("virtualTime", tick.virtualTime().toString());
    material.put("spotBundles", spots);
    material.put("perpetualBundles", perpetuals);
    material.put("fundingRates", funding);
    String fingerprint = canonicalHash(material);
    Map<String, Object> wire = new LinkedHashMap<>(material);
    wire.put("fingerprint", fingerprint);
    return Collections.unmodifiableMap(wire);
  }

  private Map<String, Object> bundle(
      UUID runId,
      long generation,
      CompactTick tick,
      InstrumentTick quote,
      BigDecimal liquidity
  ) {
    String symbol = quote.identity().symbol();
    Instant time = tick.virtualTime();
    Instant expiresAt = time.plusSeconds(60L);
    long timestamp = time.toEpochMilli();
    Map<String, Object> depth = new LinkedHashMap<>();
    depth.put("symbol", symbol);
    depth.put("timestamp", timestamp);
    depth.put("bids", List.of(Map.of("price", quote.bid(), "amount", liquidity)));
    depth.put("asks", List.of(Map.of("price", quote.ask(), "amount", liquidity)));
    depth.put("providerCode", "validation");
    depth.put("providerSymbol", symbol);
    depth.put("sourceMode", "LOCAL_SIMULATED");
    depth.put("asOf", time.toString());
    depth.put("expiresAt", expiresAt.toString());
    depth.put("stale", false);

    UUID tradeId = UUID.nameUUIDFromBytes((
        runId + ":" + generation + ":" + quote.identity().productType()
            + ":" + symbol + ":" + tick.sequence() + ":trade")
        .getBytes(StandardCharsets.UTF_8));
    Map<String, Object> trade = new LinkedHashMap<>();
    trade.put("id", tradeId.toString());
    trade.put("symbol", symbol);
    trade.put("price", quote.last());
    trade.put("amount", liquidity);
    trade.put("side", "BUY");
    trade.put("timestamp", timestamp);
    trade.put("providerCode", "validation");
    trade.put("providerSymbol", symbol);
    trade.put("sourceMode", "LOCAL_SIMULATED");
    trade.put("asOf", time.toString());
    trade.put("expiresAt", expiresAt.toString());
    trade.put("stale", false);

    BigDecimal low = quote.bid().min(quote.ask()).min(quote.last());
    BigDecimal high = quote.bid().max(quote.ask()).max(quote.last());
    Map<String, Object> candle = new LinkedHashMap<>();
    candle.put("timestamp", timestamp);
    candle.put("open", quote.last());
    candle.put("high", high);
    candle.put("low", low);
    candle.put("close", quote.last());
    candle.put("volume", liquidity);
    candle.put("providerCode", "validation");
    candle.put("providerSymbol", symbol);
    candle.put("sourceMode", "LOCAL_SIMULATED");
    candle.put("asOf", time.toString());
    candle.put("expiresAt", expiresAt.toString());
    candle.put("stale", false);

    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("platformSymbol", symbol);
    bundle.put("providerSymbol", symbol);
    bundle.put("providerCode", "validation");
    bundle.put("sourceMode", "LOCAL_SIMULATED");
    bundle.put("bid", quote.bid());
    bundle.put("ask", quote.ask());
    bundle.put("last", quote.last());
    if ("LINEAR_PERP".equals(quote.identity().productType())) {
      bundle.put("mark", Objects.requireNonNull(quote.mark()));
      bundle.put("index", Objects.requireNonNull(quote.index()));
    }
    bundle.put("orderBook", depth);
    bundle.put("recentTrades", List.of(trade));
    bundle.put("candles", List.of(candle));
    bundle.put("asOf", time.toString());
    bundle.put("expiresAt", expiresAt.toString());
    return Collections.unmodifiableMap(bundle);
  }

  private List<Map<String, Object>> compileActions(
      UUID runId,
      long generation,
      boolean negativeMode,
      List<?> requested,
      Set<InstrumentIdentity> selected,
      Map<InstrumentIdentity, InstrumentAuthority> authorities,
      List<CompactTick> ticks
  ) {
    if (requested.size() > MAX_ACTIONS) {
      throw invalid("timeline");
    }
    List<Map<String, Object>> result = new ArrayList<>(requested.size());
    Map<String, ResolvedAction> resolved = new LinkedHashMap<>();
    long previousSequence = -1L;
    Long previousTick = null;
    int expectedErrors = 0;
    for (Object value : requested) {
      Map<String, Object> action = requireMap(value, "action");
      if (!ACTION_FIELDS.containsAll(action.keySet())
          || !action.keySet().containsAll(Set.of(
              "id", "sequence", "type", "symbol", "productType", "trigger", "parameters"))) {
        throw invalid("action");
      }
      if (action.containsKey("overrides")) {
        throw invalid("action.overrides");
      }
      String scenarioActionId = requireNonEmptyText(action.get("id"), "action.id");
      if (resolved.containsKey(scenarioActionId)) {
        throw invalid("action.id");
      }
      long sequence = requireNonNegativeLong(action.get("sequence"), "action.sequence");
      if (sequence <= previousSequence) {
        throw invalid("action.sequence");
      }
      previousSequence = sequence;
      String type = requireText(action.get("type"), "action.type", 80);
      if (!PUBLIC_ACTIONS.contains(type)) {
        throw invalid("action.type");
      }
      InstrumentIdentity identity = new InstrumentIdentity(
          productType(action.get("productType")),
          requireText(action.get("symbol"), "action.symbol", 256));
      if (!selected.contains(identity)) {
        throw invalid("action.symbol");
      }
      long tickSequence = resolveTrigger(
          requireMap(action.get("trigger"), "action.trigger"),
          identity,
          ticks,
          resolved);
      if (previousTick != null && tickSequence < previousTick) {
        throw invalid("action.trigger");
      }
      previousTick = tickSequence;
      UUID actionUuid = UUID.nameUUIDFromBytes((
          runId + ":" + generation + ":" + scenarioActionId)
          .getBytes(StandardCharsets.UTF_8));
      Map<String, Object> expected = null;
      if (action.containsKey("expectedError")) {
        expected = expectedError(action.get("expectedError"));
        expectedErrors++;
        if (!negativeMode) {
          throw invalid("action.expectedError");
        }
      }
      Map<String, Object> parameters =
          requireMap(action.get("parameters"), "action.parameters");
      Map<String, Object> payload = actionPayload(
          type,
          identity,
          authorities.get(identity),
          parameters,
          resolved,
          runId,
          expected != null);
      Map<String, Object> fingerprintValue = new LinkedHashMap<>();
      fingerprintValue.put("runId", runId.toString());
      fingerprintValue.put("generation", generation);
      fingerprintValue.put("scenarioActionId", scenarioActionId);
      fingerprintValue.put("tickSequence", tickSequence);
      fingerprintValue.put("sequence", sequence);
      fingerprintValue.put("type", type);
      fingerprintValue.put("payload", payload);
      fingerprintValue.put("expectedError", expected);
      String fingerprint = canonicalHash(fingerprintValue);

      Map<String, Object> wire = new LinkedHashMap<>();
      wire.put("actionId", actionUuid.toString());
      wire.put("tickSequence", tickSequence);
      wire.put("sequence", sequence);
      wire.put("type", type);
      wire.put("requestFingerprint", fingerprint);
      wire.put("payload", payload);
      if (expected != null) {
        wire.put("expectedError", expected);
      }
      result.add(Collections.unmodifiableMap(wire));
      resolved.put(
          scenarioActionId,
          new ResolvedAction(type, tickSequence, actionUuid));
    }
    if (negativeMode && expectedErrors == 0) {
      throw invalid("expectedError");
    }
    return List.copyOf(result);
  }

  private long resolveTrigger(
      Map<String, Object> trigger,
      InstrumentIdentity identity,
      List<CompactTick> ticks,
      Map<String, ResolvedAction> resolved
  ) {
    validateTriggerReferences(trigger, resolved, 0);
    boolean unavailable = false;
    for (CompactTick tick : ticks) {
      Match match = triggerMatch(trigger, identity, tick, resolved, 0);
      if (match == Match.UNAVAILABLE) {
        unavailable = true;
        break;
      }
      if (match == Match.MATCH) {
        return tick.sequence();
      }
    }
    throw invalid(unavailable ? "action.trigger.priceType" : "action.trigger");
  }

  private void validateTriggerReferences(
      Map<String, Object> trigger,
      Map<String, ResolvedAction> resolved,
      int depth
  ) {
    if (depth > MAX_TRIGGER_DEPTH) {
      throw invalid("action.trigger");
    }
    String type = requireText(trigger.get("type"), "action.trigger.type", 40);
    switch (type) {
      case "VIRTUAL_TIME" ->
          requireExactKeys(trigger, Set.of("type", "atSecond"), "action.trigger");
      case "PRICE" ->
          requireExactKeys(
              trigger,
              Set.of("type", "priceType", "operator", "value"),
              "action.trigger");
      case "AFTER_ACTION" -> {
        requireExactKeys(
            trigger,
            Set.of("type", "actionId", "delaySeconds"),
            "action.trigger");
        String actionId =
            requireNonEmptyText(trigger.get("actionId"), "action.trigger.actionId");
        if (!resolved.containsKey(actionId)) {
          throw invalid("action.trigger.actionId");
        }
      }
      case "GROUP" -> {
        requireExactKeys(
            trigger,
            Set.of("type", "operator", "items"),
            "action.trigger");
        requireEnum(trigger.get("operator"), Set.of("ALL", "ANY"), "action.trigger.operator");
        List<?> items = requireList(trigger.get("items"), "action.trigger.items");
        if (items.isEmpty()) {
          throw invalid("action.trigger.items");
        }
        for (Object child : items) {
          validateTriggerReferences(
              requireMap(child, "action.trigger.items"),
              resolved,
              depth + 1);
        }
      }
      default -> throw invalid("action.trigger.type");
    }
  }

  private Match triggerMatch(
      Map<String, Object> trigger,
      InstrumentIdentity identity,
      CompactTick tick,
      Map<String, ResolvedAction> resolved,
      int depth
  ) {
    if (depth > MAX_TRIGGER_DEPTH) {
      throw invalid("action.trigger");
    }
    return switch (requireText(trigger.get("type"), "action.trigger.type", 40)) {
      case "VIRTUAL_TIME" -> tick.sequence()
          >= requireInteger(trigger.get("atSecond"), 1, MAX_TICKS, "atSecond")
              ? Match.MATCH : Match.NO_MATCH;
      case "PRICE" -> priceMatch(trigger, identity, tick);
      case "AFTER_ACTION" -> {
        ResolvedAction prior = resolved.get(
            requireNonEmptyText(trigger.get("actionId"), "actionId"));
        long delay = requireNonNegativeLong(trigger.get("delaySeconds"), "delaySeconds");
        long threshold;
        try {
          threshold = Math.addExact(prior.tickSequence(), delay);
        } catch (ArithmeticException exception) {
          throw invalid("delaySeconds");
        }
        yield tick.sequence() >= threshold ? Match.MATCH : Match.NO_MATCH;
      }
      case "GROUP" -> {
        List<?> items = requireList(trigger.get("items"), "items");
        List<Match> matches = new ArrayList<>(items.size());
        for (Object child : items) {
          matches.add(triggerMatch(
              requireMap(child, "trigger"),
              identity,
              tick,
              resolved,
              depth + 1));
        }
        if (matches.contains(Match.UNAVAILABLE)) {
          yield Match.UNAVAILABLE;
        }
        boolean all = "ALL".equals(trigger.get("operator"));
        boolean matched = all
            ? matches.stream().allMatch(value -> value == Match.MATCH)
            : matches.stream().anyMatch(value -> value == Match.MATCH);
        yield matched ? Match.MATCH : Match.NO_MATCH;
      }
      default -> throw invalid("action.trigger.type");
    };
  }

  private Match priceMatch(
      Map<String, Object> trigger,
      InstrumentIdentity identity,
      CompactTick tick
  ) {
    String priceType = requireEnum(
        trigger.get("priceType"),
        Set.of("BID", "ASK", "LAST", "MARK", "INDEX"),
        "priceType");
    String operator = requireEnum(
        trigger.get("operator"), Set.of("GTE", "LTE"), "operator");
    BigDecimal target = requirePositiveDecimal(trigger.get("value"), "value");
    InstrumentTick instrument = tick.instruments().stream()
        .filter(value -> value.identity().equals(identity))
        .findFirst()
        .orElseThrow(() -> invalid("action.symbol"));
    BigDecimal current = switch (priceType) {
      case "BID" -> instrument.bid();
      case "ASK" -> instrument.ask();
      case "LAST" -> instrument.last();
      case "MARK" -> instrument.mark();
      case "INDEX" -> instrument.index();
      default -> throw invalid("priceType");
    };
    if (current == null) {
      return Match.UNAVAILABLE;
    }
    int comparison = current.compareTo(target);
    return ("GTE".equals(operator) ? comparison >= 0 : comparison <= 0)
        ? Match.MATCH
        : Match.NO_MATCH;
  }

  private Map<String, Object> actionPayload(
      String type,
      InstrumentIdentity identity,
      InstrumentAuthority authority,
      Map<String, Object> parameters,
      Map<String, ResolvedAction> resolved,
      UUID runId,
      boolean allowBusinessViolation
  ) {
    return switch (type) {
      case "PLACE_ORDER" ->
          placeOrderPayload(identity, authority, parameters, allowBusinessViolation);
      case "CANCEL_ORDER" -> {
        requireExactKeys(parameters, Set.of("clientOrderId"), "action.parameters");
        String referenced =
            requireNonBlankText(parameters.get("clientOrderId"), "clientOrderId");
        ResolvedAction target = resolved.get(referenced);
        if (target == null || !"PLACE_ORDER".equals(target.type())) {
          throw invalid("action.parameters.clientOrderId");
        }
        yield Map.of(
            "clientOrderId",
            runId + ":" + target.actionUuid() + ":0");
      }
      case "CANCEL_ALL" -> {
        requireExactKeys(parameters, Set.of(), "action.parameters");
        yield Map.of();
      }
      case "SET_POSITION_MODE" -> {
        requireExactKeys(parameters, Set.of("positionMode"), "action.parameters");
        String positionMode =
            requireNonBlankText(parameters.get("positionMode"), "positionMode");
        if (!allowBusinessViolation
            && !Set.of("ONE_WAY", "HEDGE").contains(positionMode)) {
          throw invalid("positionMode");
        }
        yield Map.of(
            "positionMode",
            positionMode);
      }
      case "SET_MARGIN_MODE" -> {
        requireExactKeys(parameters, Set.of("marginMode"), "action.parameters");
        String marginMode =
            requireNonBlankText(parameters.get("marginMode"), "marginMode");
        if (!allowBusinessViolation) {
          requirePerpetual(identity, "marginMode");
          if (!Set.of("CROSS", "ISOLATED").contains(marginMode)) {
            throw invalid("marginMode");
          }
        }
        yield Map.of(
            "symbol", identity.symbol(),
            "marginMode", marginMode);
      }
      case "SET_LEVERAGE" -> {
        requireExactKeys(parameters, Set.of("leverage"), "action.parameters");
        long leverage = requirePositiveSafeLong(
            parameters.get("leverage"), "leverage");
        if (!allowBusinessViolation) {
          requirePerpetual(identity, "leverage");
          if (leverage > authority.maxLeverage()) {
            throw invalid("leverage");
          }
        }
        yield Map.of(
            "symbol", identity.symbol(),
            "leverage", leverage);
      }
      default -> throw invalid("action.type");
    };
  }

  private Map<String, Object> placeOrderPayload(
      InstrumentIdentity identity,
      InstrumentAuthority authority,
      Map<String, Object> parameters,
      boolean allowBusinessViolation
  ) {
    if (!PLACE_ORDER_FIELDS.containsAll(parameters.keySet())) {
      throw invalid("action.parameters");
    }
    String side = requireNonBlankText(parameters.get("side"), "side");
    String orderType = requireNonBlankText(parameters.get("orderType"), "orderType");
    BigDecimal quantity = requireDecimal(parameters.get("quantity"), "quantity");
    for (String field : PLACE_ORDER_DECIMAL_FIELDS) {
      if (parameters.containsKey(field)) {
        requireDecimal(parameters.get(field), field);
      }
    }
    String positionSide = optionalText(parameters, "positionSide");
    String quantityUnit = optionalText(parameters, "quantityUnit");
    String marginMode = optionalText(parameters, "marginMode");
    String triggerPriceType = optionalText(parameters, "triggerPriceType");
    String timeInForce =
        Objects.requireNonNullElse(
            optionalText(parameters, "timeInForce"),
            "GTC");
    for (String field : PLACE_ORDER_BOOLEAN_FIELDS) {
      if (parameters.containsKey(field)
          && !(parameters.get(field) instanceof Boolean)) {
        throw invalid(field);
      }
    }
    Long leverage = null;
    if (parameters.containsKey("leverage")) {
      leverage = requirePositiveSafeLong(parameters.get("leverage"), "leverage");
    }
    int protectionCount = 0;
    if (parameters.containsKey("attachedProtections")) {
      protectionCount = validateProtections(
          parameters.get("attachedProtections"),
          authority,
          !allowBusinessViolation);
    }
    String effectiveSpotQuantityUnit = null;
    if ("CRYPTO_SPOT".equals(identity.productType())
        && Set.of("BUY", "SELL").contains(side)
        && ORDER_TYPES.contains(orderType)) {
      effectiveSpotQuantityUnit =
          "MARKET".equals(orderType) && "BUY".equals(side) ? "QUOTE" : "BASE";
    }
    if (allowBusinessViolation) {
      return placeOrderWirePayload(
          identity,
          parameters,
          effectiveSpotQuantityUnit);
    }

    if (!Set.of("BUY", "SELL").contains(side)) {
      throw invalid("side");
    }
    if (!ORDER_TYPES.contains(orderType)) {
      throw invalid("orderType");
    }
    requireAllowed(positionSide, POSITION_SIDES, "positionSide");
    requireAllowed(quantityUnit, QUANTITY_UNITS, "quantityUnit");
    requireAllowed(marginMode, Set.of("CROSS", "ISOLATED"), "marginMode");
    requireAllowed(triggerPriceType, TRIGGER_PRICE_TYPES, "triggerPriceType");
    requireAllowed(timeInForce, TIME_IN_FORCE_VALUES, "timeInForce");
    for (String field : PLACE_ORDER_DECIMAL_FIELDS) {
      if (parameters.containsKey(field)
          && requireDecimal(parameters.get(field), field).signum() <= 0) {
        throw invalid(field);
      }
    }
    if (leverage != null && leverage > authority.maxLeverage()) {
      throw invalid("leverage");
    }
    if (parameters.containsKey("stopLoss") || parameters.containsKey("takeProfit")) {
      throw invalid("action.parameters.protection");
    }
    boolean postOnly = Boolean.TRUE.equals(parameters.get("postOnly"));
    boolean reduceOnly = Boolean.TRUE.equals(parameters.get("reduceOnly"));
    boolean hasTrailing = parameters.containsKey("activationPrice")
        || parameters.containsKey("trailingDelta")
        || parameters.containsKey("trailingRate");
    if ("MARKET".equals(orderType)) {
      rejectPresent(
          parameters,
          "price",
          "requestedPrice",
          "triggerPrice",
          "triggerPriceType");
    } else if ("LIMIT".equals(orderType)) {
      requirePositiveDecimal(parameters.get("price"), "price");
      rejectPresent(parameters, "triggerPrice", "triggerPriceType");
    } else if ("STOP_MARKET".equals(orderType)
        || "STOP_LIMIT".equals(orderType)) {
      requirePositiveDecimal(parameters.get("triggerPrice"), "triggerPrice");
      if ("STOP_LIMIT".equals(orderType)) {
        requirePositiveDecimal(parameters.get("price"), "price");
      } else {
        rejectPresent(parameters, "price", "requestedPrice");
      }
      String expectedTrigger = "LINEAR_PERP".equals(identity.productType())
          ? "MARK_PRICE" : "LAST_PRICE";
      if (!"GTC".equals(timeInForce)
          || postOnly
          || (triggerPriceType != null && !expectedTrigger.equals(triggerPriceType))) {
        throw invalid("action.parameters.stopOrder");
      }
    } else {
      if (!"LINEAR_PERP".equals(identity.productType())
          || !reduceOnly
          || !"GTC".equals(timeInForce)
          || postOnly
          || protectionCount > 0) {
        throw invalid("action.parameters.trailingOrder");
      }
      rejectPresent(
          parameters,
          "price",
          "requestedPrice",
          "triggerPrice",
          "triggerPriceType");
      boolean delta = parameters.containsKey("trailingDelta");
      boolean rate = parameters.containsKey("trailingRate");
      if (delta == rate) {
        throw invalid("action.parameters.trailingOrder");
      }
      if (rate
          && requirePositiveDecimal(parameters.get("trailingRate"), "trailingRate")
              .compareTo(BigDecimal.ONE) >= 0) {
        throw invalid("trailingRate");
      }
    }
    if (!"TRAILING_STOP_MARKET".equals(orderType) && hasTrailing) {
      throw invalid("action.parameters.trailingOrder");
    }
    if (postOnly && (!"LIMIT".equals(orderType) || !"GTC".equals(timeInForce))) {
      throw invalid("action.parameters.postOnly");
    }
    if (reduceOnly && !"LINEAR_PERP".equals(identity.productType())) {
      throw invalid("action.parameters.reduceOnly");
    }
    if (reduceOnly && protectionCount > 0) {
      throw invalid("action.parameters.attachedProtections");
    }
    if ("CRYPTO_SPOT".equals(identity.productType())) {
      boolean invalidQuantityUnit = quantityUnit != null
          && !Objects.equals(effectiveSpotQuantityUnit, quantityUnit);
      if ((positionSide != null && !"BOTH".equals(positionSide))
          || invalidQuantityUnit
          || leverage != null
          || parameters.containsKey("marginMode")
          || protectionCount > 0) {
        throw invalid("action.parameters.spot");
      }
    } else if ("QUOTE".equals(quantityUnit)) {
      throw invalid("action.parameters.quantityUnit");
    }
    validateOrderNumbers(
        identity,
        authority,
        parameters,
        side,
        orderType,
        quantity,
        quantityUnit == null ? effectiveSpotQuantityUnit : quantityUnit);
    return placeOrderWirePayload(
        identity,
        parameters,
        effectiveSpotQuantityUnit);
  }

  private static Map<String, Object> placeOrderWirePayload(
      InstrumentIdentity identity,
      Map<String, Object> parameters,
      String effectiveSpotQuantityUnit
  ) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("symbol", identity.symbol());
    result.putAll(parameters);
    if ("CRYPTO_SPOT".equals(identity.productType())) {
      result.putIfAbsent("marginMode", "CASH");
      if (effectiveSpotQuantityUnit != null) {
        result.putIfAbsent("quantityUnit", effectiveSpotQuantityUnit);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private static void requirePerpetual(
      InstrumentIdentity identity,
      String field
  ) {
    if (!"LINEAR_PERP".equals(identity.productType())) {
      throw invalid("action.parameters." + field);
    }
  }

  private static void validateOrderNumbers(
      InstrumentIdentity identity,
      InstrumentAuthority authority,
      Map<String, Object> parameters,
      String side,
      String orderType,
      BigDecimal quantity,
      String quantityUnit
  ) {
    boolean quoteBudget = "CRYPTO_SPOT".equals(identity.productType())
        && "MARKET".equals(orderType)
        && "BUY".equals(side)
        && "QUOTE".equals(quantityUnit);
    boolean contracts = "LINEAR_PERP".equals(identity.productType())
        && "CONTRACTS".equals(quantityUnit);
    if (contracts) {
      if (quantity.scale() != 0) {
        throw invalid("quantity");
      }
    } else if (!quoteBudget) {
      requireAligned(quantity, authority.stepSize(), "quantity");
      requireRange(
          quantity,
          authority.minQuantity(),
          authority.maxQuantity(),
          "quantity");
    } else {
      requireRange(
          quantity,
          authority.minNotional(),
          authority.maxNotional(),
          "quantity");
    }

    BigDecimal price = positiveIfPresent(parameters, "price");
    BigDecimal triggerPrice = positiveIfPresent(parameters, "triggerPrice");
    BigDecimal activationPrice = positiveIfPresent(parameters, "activationPrice");
    BigDecimal trailingDelta = positiveIfPresent(parameters, "trailingDelta");
    requireOptionalAligned(price, authority.tickSize(), "price");
    requireOptionalAligned(triggerPrice, authority.tickSize(), "triggerPrice");
    requireOptionalAligned(activationPrice, authority.tickSize(), "activationPrice");
    requireOptionalAligned(trailingDelta, authority.tickSize(), "trailingDelta");
    if (price != null && !contracts) {
      requireRange(
          quantity.multiply(price),
          authority.minNotional(),
          authority.maxNotional(),
          "notional");
    }
  }

  private static BigDecimal positiveIfPresent(
      Map<String, Object> values,
      String field
  ) {
    return values.containsKey(field)
        ? requirePositiveDecimal(values.get(field), field)
        : null;
  }

  private static void requireAligned(
      BigDecimal value,
      BigDecimal step,
      String field
  ) {
    if (value.divideAndRemainder(step)[1].signum() != 0) {
      throw invalid(field);
    }
  }

  private static void requireOptionalAligned(
      BigDecimal value,
      BigDecimal step,
      String field
  ) {
    if (value != null) {
      requireAligned(value, step, field);
    }
  }

  private static void requireRange(
      BigDecimal value,
      BigDecimal minimum,
      BigDecimal maximum,
      String field
  ) {
    if ((minimum != null && value.compareTo(minimum) < 0)
        || (maximum != null && value.compareTo(maximum) > 0)) {
      throw invalid(field);
    }
  }

  private static BigDecimal effectiveLiquidity(
      InstrumentAuthority authority,
      BigDecimal maxFillQuantity
  ) {
    return maxFillQuantity == null
        ? authority.maxQuantity()
        : authority.maxQuantity().min(maxFillQuantity);
  }

  private int validateProtections(
      Object value,
      InstrumentAuthority authority,
      boolean enforceBusinessRules
  ) {
    List<?> protections = requireList(value, "attachedProtections");
    if (protections.size() > 10) {
      throw invalid("attachedProtections");
    }
    for (Object item : protections) {
      Map<String, Object> protection = requireMap(item, "attachedProtections");
      if (!PROTECTION_FIELDS.containsAll(protection.keySet())) {
        throw invalid("attachedProtections");
      }
      String protectionType =
          requireNonBlankText(protection.get("protectionType"), "protectionType");
      BigDecimal triggerPrice =
          requireDecimal(protection.get("triggerPrice"), "triggerPrice");
      String execution = requireNonBlankText(
          protection.get("triggerExecutionType"),
          "triggerExecutionType");
      String triggerType = optionalText(protection, "triggerPriceType");
      String quantityUnit = optionalText(protection, "quantityUnit");
      if (quantityUnit != null && !protection.containsKey("quantity")) {
        throw invalid("attachedProtections.quantity");
      }
      BigDecimal quantity = protection.containsKey("quantity")
          ? requireDecimal(protection.get("quantity"), "quantity")
          : null;
      BigDecimal price = protection.containsKey("price")
          ? requireDecimal(protection.get("price"), "price")
          : null;
      if (!enforceBusinessRules) {
        continue;
      }
      requireAllowed(protectionType, PROTECTION_TYPES, "protectionType");
      requireAllowed(execution, TRIGGER_EXECUTION_TYPES, "triggerExecutionType");
      requireAllowed(triggerType, TRIGGER_PRICE_TYPES, "triggerPriceType");
      requireAllowed(quantityUnit, QUANTITY_UNITS, "quantityUnit");
      if (triggerPrice.signum() <= 0) {
        throw invalid("triggerPrice");
      }
      requireAligned(triggerPrice, authority.tickSize(), "triggerPrice");
      if (triggerType != null && !"MARK_PRICE".equals(triggerType)) {
        throw invalid("triggerPriceType");
      }
      if (protection.containsKey("quantity")) {
        if (Objects.requireNonNull(quantity).signum() <= 0) {
          throw invalid("quantity");
        }
        if ("CONTRACTS".equals(quantityUnit) && quantity.scale() != 0) {
          throw invalid("attachedProtections.quantity");
        }
      }
      if ("LIMIT".equals(execution)) {
        if (price == null || price.signum() <= 0) {
          throw invalid("price");
        }
        requireAligned(price, authority.tickSize(), "price");
      } else if (protection.containsKey("price")) {
        throw invalid("attachedProtections.price");
      }
    }
    return protections.size();
  }

  private static String optionalText(
      Map<String, Object> values,
      String field
  ) {
    return values.containsKey(field)
        ? requireNonBlankText(values.get(field), field)
        : null;
  }

  private static void requireAllowed(
      String value,
      Set<String> allowed,
      String field
  ) {
    if (value != null && !allowed.contains(value)) {
      throw invalid(field);
    }
  }

  private static void rejectPresent(
      Map<String, Object> values,
      String... fields
  ) {
    for (String field : fields) {
      if (values.containsKey(field)) {
        throw invalid("action.parameters." + field);
      }
    }
  }

  private Map<String, Object> expectedError(Object value) {
    Map<String, Object> expected = requireMap(value, "expectedError");
    requireExactKeys(expected, Set.of("status", "code"), "expectedError");
    int status = requireInteger(expected.get("status"), 400, 499, "expectedError.status");
    String code = requireText(expected.get("code"), "expectedError.code", 80);
    if (!EXPECTED_ERROR_CODE.matcher(code).matches()) {
      throw invalid("expectedError.code");
    }
    return Map.of("status", status, "code", code);
  }

  private Map<String, BigDecimal> balances(Map<String, Object> requested) {
    if (requested.isEmpty()) {
      throw invalid("initialBalances");
    }
    TreeMap<String, BigDecimal> sorted = new TreeMap<>();
    requested.forEach((asset, value) -> {
      if (asset == null || !asset.matches("[A-Z0-9]{2,20}")) {
        throw invalid("initialBalances");
      }
      BigDecimal amount = requireDecimal(value, "initialBalances");
      if (amount.signum() < 0) {
        throw invalid("initialBalances");
      }
      try {
        amount = amount.setScale(8, RoundingMode.UNNECESSARY);
        if (amount.precision() > 24 || sorted.put(asset, amount) != null) {
          throw invalid("initialBalances");
        }
      } catch (ArithmeticException exception) {
        throw invalid("initialBalances");
      }
    });
    if (!sorted.containsKey("USDT")) {
      throw invalid("initialBalances.USDT");
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
  }

  private static BigInteger randomSignedSteps(long unsigned, long maximum) {
    BigInteger max = BigInteger.valueOf(maximum);
    BigInteger span = max.multiply(BigInteger.TWO).add(BigInteger.ONE);
    return BigInteger.valueOf(unsigned)
        .multiply(span)
        .divide(UINT32_RANGE)
        .subtract(max);
  }

  private static BigInteger priceSteps(
      Object value,
      BigDecimal tickSize,
      String field
  ) {
    BigDecimal price = requirePositiveDecimal(value, field);
    BigDecimal[] division = price.divideAndRemainder(tickSize);
    if (division[1].signum() != 0) {
      throw invalid(field);
    }
    try {
      return division[0].toBigIntegerExact();
    } catch (ArithmeticException exception) {
      throw invalid(field);
    }
  }

  private static BigDecimal price(BigInteger steps, BigDecimal tickSize) {
    if (steps.signum() <= 0) {
      throw invalid("marketPath.price");
    }
    BigDecimal price = new BigDecimal(steps).multiply(tickSize).stripTrailingZeros();
    BigDecimal normalized = price.scale() < 0 ? price.setScale(0) : price;
    if (plainDecimalLength(normalized) > MAX_JSON_NUMBER_CHARS) {
      throw invalid("validationRun");
    }
    return normalized;
  }

  private static long plainDecimalLength(BigDecimal value) {
    long precision = value.precision();
    long scale = value.scale();
    long integerDigits = precision - scale;
    long length = scale <= 0L
        ? integerDigits
        : integerDigits > 0L
            ? precision + 1L
            : 2L - integerDigits + precision;
    return value.signum() < 0 ? Math.addExact(length, 1L) : length;
  }

  private static final class ExpansionBudget {

    private long remainingBytes;

    private ExpansionBudget(long remainingBytes) {
      this.remainingBytes = remainingBytes;
    }

    private void consumePrice(BigInteger steps, BigDecimal tickSize) {
      consume(plainDecimalLength(price(steps, tickSize)));
    }

    private void consumeDecimal(BigDecimal value, long occurrences) {
      try {
        consume(Math.multiplyExact(plainDecimalLength(value), occurrences));
      } catch (ArithmeticException exception) {
        throw invalid("validationRun");
      }
    }

    private void consume(long bytes) {
      if (bytes < 0L || bytes > remainingBytes) {
        throw invalid("validationRun");
      }
      remainingBytes -= bytes;
    }
  }

  private static BigDecimal requireFunding(Object value, String field) {
    try {
      BigDecimal rate = requireDecimal(value, field).setScale(10, RoundingMode.UNNECESSARY);
      if (rate.precision() > 18) {
        throw invalid(field);
      }
      return rate;
    } catch (ArithmeticException exception) {
      throw invalid(field);
    }
  }

  private static BigDecimal requireRate(Object value, String field) {
    BigDecimal rate = requireDecimal(value, field);
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
      throw invalid(field);
    }
    return rate;
  }

  private static BigDecimal requirePositiveDecimal(Object value, String field) {
    BigDecimal decimal = requireDecimal(value, field);
    if (decimal.signum() <= 0) {
      throw invalid(field);
    }
    return decimal;
  }

  private static BigDecimal optionalPositiveDecimal(Object value, String field) {
    return value == null ? null : requirePositiveDecimal(value, field);
  }

  private static BigDecimal requireDecimal(Object value, String field) {
    if (!(value instanceof String text) || !PLAIN_DECIMAL.matcher(text).matches()) {
      throw invalid(field);
    }
    if (text.length() > MAX_JSON_NUMBER_CHARS) {
      throw invalid("validationRun");
    }
    try {
      BigDecimal decimal = new BigDecimal(text);
      if (plainDecimalLength(decimal) > MAX_JSON_NUMBER_CHARS) {
        throw invalid("validationRun");
      }
      return decimal;
    } catch (NumberFormatException exception) {
      throw invalid(field);
    }
  }

  private static Instant requireInstant(Object value, String field) {
    String text = requireText(value, field, 64);
    if (!UTC_MILLISECOND_INSTANT.matcher(text).matches()) {
      throw invalid(field);
    }
    try {
      Instant instant = Instant.parse(text);
      String withoutZulu = text.substring(0, text.length() - 1);
      int dot = withoutZulu.lastIndexOf('.');
      String normalized = dot < 0
          ? withoutZulu + ".000Z"
          : withoutZulu + "0".repeat(3 - (withoutZulu.length() - dot - 1)) + "Z";
      if (!UTC_MILLISECOND_FORMAT.format(instant).equals(normalized)) {
        throw invalid(field);
      }
      return instant;
    } catch (DateTimeException exception) {
      throw invalid(field);
    }
  }

  private static String requireSeed(Object value, String field) {
    String seed = requireText(value, field, 256);
    if (seed.codePoints().allMatch(TradingLabBrowserScenarioCompiler::ecmaWhitespace)) {
      throw invalid(field);
    }
    for (int index = 0; index < seed.length(); index++) {
      char unit = seed.charAt(index);
      if (Character.isHighSurrogate(unit)) {
        if (index + 1 >= seed.length()
            || !Character.isLowSurrogate(seed.charAt(++index))) {
          throw invalid(field);
        }
      } else if (Character.isLowSurrogate(unit)) {
        throw invalid(field);
      }
    }
    return seed;
  }

  private static boolean ecmaWhitespace(int codePoint) {
    return (codePoint >= 0x0009 && codePoint <= 0x000d)
        || codePoint == 0x0020
        || codePoint == 0x00a0
        || codePoint == 0x1680
        || (codePoint >= 0x2000 && codePoint <= 0x200a)
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0x202f
        || codePoint == 0x205f
        || codePoint == 0x3000
        || codePoint == 0xfeff;
  }

  private static String productType(Object value) {
    return requireEnum(
        value, Set.of("CRYPTO_SPOT", "LINEAR_PERP"), "productType");
  }

  private static String requireEnum(
      Object value,
      Set<String> allowed,
      String field
  ) {
    String text = requireText(value, field, 256);
    if (!allowed.contains(text)) {
      throw invalid(field);
    }
    return text;
  }

  private static String requireText(Object value, String field, int maxLength) {
    if (!(value instanceof String text)
        || isEcmaBlank(text)
        || text.length() > maxLength) {
      throw invalid(field);
    }
    return text;
  }

  private static String requireNonBlankText(Object value, String field) {
    if (!(value instanceof String text) || isEcmaBlank(text)) {
      throw invalid(field);
    }
    return text;
  }

  private static String requireNonEmptyText(Object value, String field) {
    if (!(value instanceof String text) || text.isEmpty()) {
      throw invalid(field);
    }
    return text;
  }

  private static boolean isEcmaBlank(String value) {
    return value.codePoints().allMatch(TradingLabBrowserScenarioCompiler::ecmaWhitespace);
  }

  private static boolean requireBoolean(Object value, String field) {
    if (!(value instanceof Boolean result)) {
      throw invalid(field);
    }
    return result;
  }

  private static int requireInteger(
      Object value,
      int minimum,
      int maximum,
      String field
  ) {
    long result = requireSafeLong(value, field);
    if (result < minimum || result > maximum) {
      throw invalid(field);
    }
    return Math.toIntExact(result);
  }

  private static long requireNonNegativeLong(Object value, String field) {
    long result = requireSafeLong(value, field);
    if (result < 0L) {
      throw invalid(field);
    }
    return result;
  }

  private static long requirePositiveSafeLong(Object value, String field) {
    long result = requireSafeLong(value, field);
    if (result <= 0L) {
      throw invalid(field);
    }
    return result;
  }

  private static long requireSafeLong(Object value, String field) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger)) {
      throw invalid(field);
    }
    try {
      long result = value instanceof BigInteger integer
          ? integer.longValueExact()
          : ((Number) value).longValue();
      if (result < -9_007_199_254_740_991L
          || result > 9_007_199_254_740_991L) {
        throw invalid(field);
      }
      return result;
    } catch (ArithmeticException exception) {
      throw invalid(field);
    }
  }

  private static Map<String, Object> requireMap(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) {
      throw invalid(field);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || result.put(key, entry.getValue()) != null) {
        throw invalid(field);
      }
    }
    return result;
  }

  private static List<?> requireList(Object value, String field) {
    if (!(value instanceof List<?> list)) {
      throw invalid(field);
    }
    return list;
  }

  private static void requireExactKeys(
      Map<String, Object> value,
      Set<String> expected,
      String field
  ) {
    if (!value.keySet().equals(expected)) {
      throw invalid(field);
    }
  }

  private byte[] canonicalBytes(Object value) {
    ByteArrayOutputStream bytes =
        new ByteArrayOutputStream(Math.min(maxExpandedBytes, 8_192));
    try {
      canonicalJson.writeValue(
          new CappedOutputStream(bytes, maxExpandedBytes),
          value);
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw invalid("canonical");
    }
  }

  private String canonicalHash(Object value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      canonicalJson.writeValue(
          new CappedOutputStream(
              new DigestOutputStream(OutputStream.nullOutputStream(), digest),
              maxExpandedBytes),
          value);
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException exception) {
      throw invalid("validationRun");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static final class CappedOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final long maximumBytes;
    private long written;

    private CappedOutputStream(OutputStream delegate, long maximumBytes) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.maximumBytes = maximumBytes;
    }

    @Override
    public void write(int value) throws IOException {
      requireCapacity(1L);
      delegate.write(value);
      written++;
    }

    @Override
    public void write(byte[] value, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, value.length);
      requireCapacity(length);
      delegate.write(value, offset, length);
      written += length;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    private void requireCapacity(long additionalBytes) throws IOException {
      if (additionalBytes < 0L
          || written > maximumBytes - additionalBytes) {
        throw new IOException("JSON value exceeds the configured byte limit");
      }
    }
  }

  static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException(
        "Invalid Trading Lab browser validation field: " + field);
  }

  record CompiledBrowserStart(
      String seed,
      Instant virtualStart,
      Map<String, Object> executionPolicy,
      Map<String, BigDecimal> initialBalances,
      Map<String, Object> accountSettings,
      List<Map<String, Object>> ticks,
      List<Map<String, Object>> actions,
      BigDecimal speedMultiplier
  ) {
  }

  private record InstrumentIdentity(String productType, String symbol)
      implements Comparable<InstrumentIdentity> {

    @Override
    public int compareTo(InstrumentIdentity other) {
      int product = productType.compareTo(other.productType);
      return product != 0 ? product : symbol.compareTo(other.symbol);
    }
  }

  private record InstrumentAuthority(
      InstrumentIdentity identity,
      BigDecimal tickSize,
      BigDecimal stepSize,
      BigDecimal minQuantity,
      BigDecimal maxQuantity,
      BigDecimal minNotional,
      BigDecimal maxNotional,
      int maxLeverage
  ) {
  }

  private record GeneratedPoint(BigInteger steps, boolean segmentFinal) {
  }

  private record InstrumentTick(
      InstrumentIdentity identity,
      BigDecimal bid,
      BigDecimal ask,
      BigDecimal last,
      BigDecimal mark,
      BigDecimal index
  ) {
  }

  private record CompiledInstrument(
      InstrumentAuthority authority,
      List<InstrumentTick> ticks,
      BigDecimal fundingRate
  ) {
  }

  private record CompactTick(
      long sequence,
      Instant virtualTime,
      List<InstrumentTick> instruments
  ) {
  }

  private record CompiledMarket(
      Instant virtualStart,
      List<CompactTick> ticks,
      List<Map<String, Object>> wireTicks
  ) {
  }

  private record ResolvedAction(String type, long tickSequence, UUID actionUuid) {
  }

  private enum Match {
    MATCH,
    NO_MATCH,
    UNAVAILABLE
  }

  private static final class Mulberry32 {

    private long state;

    private Mulberry32(String seed) {
      long hash = FNV1A_OFFSET;
      for (byte value : seed.getBytes(StandardCharsets.UTF_8)) {
        hash ^= value & 0xffL;
        hash = (hash * FNV1A_PRIME) & UINT32_MASK;
      }
      this.state = hash;
    }

    private long nextUnsigned() {
      state = (state + MULBERRY_INCREMENT) & UINT32_MASK;
      long value = state;
      value = multiply32(value ^ (value >>> 15), value | 1L);
      value ^= (value + multiply32(value ^ (value >>> 7), value | 61L))
          & UINT32_MASK;
      return (value ^ (value >>> 14)) & UINT32_MASK;
    }

    private static long multiply32(long left, long right) {
      return (left * right) & UINT32_MASK;
    }
  }
}
