package com.fxplatform.tradinglab.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SnapshotTradingLabValidationStartRequestFactory
    implements TradingLabValidationStartRequestFactory {

  private static final TypeReference<Map<String, Object>> OBJECT_MAP =
      new TypeReference<>() { };
  private static final Set<String> BROWSER_MARKERS = Set.of(
      "marketPath",
      "timeline",
      "defaults",
      "symbols",
      "executionPolicy",
      "initialBalances");
  private static final Set<String> ROOT_LEGACY_MARKERS = Set.of(
      "ticks",
      "actions",
      "virtualStart",
      "speedMultiplier");
  private static final int MAX_TICKS = 86_400;
  private static final int MAX_ACTIONS = 100_000;
  private static final int MAX_JSON_DEPTH = 64;
  private static final int MAX_JSON_NUMBER_CHARS = 1_000;

  private final TradingLabCredentialSanitizer credentialSanitizer;
  private final TradingLabBrowserScenarioCompiler browserCompiler;
  private final ObjectMapper json;
  private final int maxBytes;

  public SnapshotTradingLabValidationStartRequestFactory(
      TradingLabCredentialSanitizer credentialSanitizer,
      TradingLabReportProperties properties
  ) {
    this.credentialSanitizer = Objects.requireNonNull(
        credentialSanitizer, "credentialSanitizer");
    this.maxBytes = Objects.requireNonNull(properties, "properties").maxLogicalValueBytes();
    this.browserCompiler = new TradingLabBrowserScenarioCompiler(maxBytes);
    JsonFactory factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_JSON_DEPTH)
            .maxStringLength(maxBytes)
            .maxNumberLength(MAX_JSON_NUMBER_CHARS)
            .build())
        .streamWriteConstraints(StreamWriteConstraints.builder()
            .maxNestingDepth(MAX_JSON_DEPTH)
            .build())
        .build();
    this.json = JsonMapper.builder(factory)
        .addModule(new JavaTimeModule())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  @Override
  public ValidationRunStartRequest compile(
      TradingLabValidationStartSource source,
      long generation
  ) {
    Objects.requireNonNull(source, "source");
    if (generation <= 0L) {
      throw new IllegalArgumentException("Validation generation must be positive");
    }
    Map<String, Object> root = requireCredentialFree(
        parse(source.scenarioSnapshotJson(), "scenarioSnapshotJson"),
        "validationRun");
    Map<String, Object> frozenConfig = requireCredentialFree(
        parse(source.configSnapshotJson(), "configSnapshotJson"),
        "configSnapshotJson");

    StartDocument start = switch (mode(root)) {
      case BROWSER -> browserStart(source, generation, root, frozenConfig);
      case LEGACY -> legacyStart(source, generation, root);
    };

    Map<String, Object> fingerprintValue = new LinkedHashMap<>();
    fingerprintValue.put("runId", source.runId().toString());
    fingerprintValue.put("generation", generation);
    fingerprintValue.put("seed", start.seed());
    fingerprintValue.put("virtualStart", start.virtualStart().toString());
    fingerprintValue.put("executionPolicy", start.executionPolicy());
    fingerprintValue.put("initialBalances", start.initialBalances());
    fingerprintValue.put("accountSettings", start.accountSettings());
    fingerprintValue.put("ticks", start.ticks());
    fingerprintValue.put("actions", start.actions());
    fingerprintValue.put("speedMultiplier", start.speedMultiplier());
    String fingerprint = hashBounded(fingerprintValue);

    ValidationRunStartRequest request = new ValidationRunStartRequest(
        source.runId(),
        generation,
        fingerprint,
        start.seed(),
        start.virtualStart(),
        start.executionPolicy(),
        start.initialBalances(),
        start.accountSettings(),
        start.ticks(),
        start.actions(),
        start.speedMultiplier());
    writeBounded(
        request,
        generationIndependentMaximum(generation, request.ticks().size()));
    return request;
  }

  private StartDocument browserStart(
      TradingLabValidationStartSource source,
      long generation,
      Map<String, Object> scenario,
      Map<String, Object> frozenConfig
  ) {
    TradingLabBrowserScenarioCompiler.CompiledBrowserStart compiled =
        browserCompiler.compile(source, generation, scenario, frozenConfig);
    return new StartDocument(
        compiled.seed(),
        compiled.virtualStart(),
        compiled.executionPolicy(),
        compiled.initialBalances(),
        compiled.accountSettings(),
        compiled.ticks(),
        compiled.actions(),
        compiled.speedMultiplier());
  }

  private StartDocument legacyStart(
      TradingLabValidationStartSource source,
      long generation,
      Map<String, Object> root
  ) {
    String seed = requireText(root.get("seed"), "seed");
    validateSeed(seed);
    Object nested = root.get("validationRun");
    Map<String, Object> template = nested == null
        ? root
        : requireMap(nested, "validationRun");
    Instant virtualStart = parseInstant(
        template.get("virtualStart"),
        source.legacyVirtualStartFallback(),
        "virtualStart");
    BigDecimal speedMultiplier = scaledDecimal(
        template.get("speedMultiplier"),
        source.legacySpeedMultiplierFallback(),
        6,
        "speedMultiplier");
    if (speedMultiplier.signum() <= 0 || speedMultiplier.precision() > 18) {
      throw invalid("speedMultiplier");
    }
    Map<String, Object> executionPolicy = requireMap(
        template.get("executionPolicy"), "executionPolicy");
    Map<String, BigDecimal> balances = decimals(
        requireMap(template.get("initialBalances"), "initialBalances"));
    Map<String, Object> accountSettings = requireMap(
        template.get("accountSettings"), "accountSettings");
    List<?> rawTicks = requireList(template.get("ticks"), "ticks");
    List<?> rawActions = requireList(template.get("actions"), "actions");
    if (rawTicks.isEmpty() || rawTicks.size() > MAX_TICKS) {
      throw invalid("ticks");
    }
    if (rawActions.size() > MAX_ACTIONS) {
      throw invalid("actions");
    }
    List<Map<String, Object>> ticks = rewriteTicks(
        rawTicks, source.runId().toString(), generation);
    List<Map<String, Object>> actions = maps(rawActions, "actions");
    return new StartDocument(
        seed,
        virtualStart,
        executionPolicy,
        balances,
        accountSettings,
        ticks,
        actions,
        speedMultiplier);
  }

  private Mode mode(Map<String, Object> root) {
    long browserMarkers = BROWSER_MARKERS.stream().filter(root::containsKey).count();
    long legacyMarkers = ROOT_LEGACY_MARKERS.stream().filter(root::containsKey).count();
    boolean nestedLegacy = root.containsKey("validationRun");
    if (browserMarkers == BROWSER_MARKERS.size()
        && !nestedLegacy
        && !root.containsKey("ticks")
        && !root.containsKey("actions")) {
      return Mode.BROWSER;
    }
    boolean browserExclusiveMarker = root.containsKey("marketPath")
        || root.containsKey("timeline")
        || root.containsKey("defaults")
        || root.containsKey("symbols");
    if (nestedLegacy && (browserMarkers > 0L || legacyMarkers > 0L)) {
      throw invalid("mode");
    }
    if (nestedLegacy
        || (!browserExclusiveMarker
            && legacyMarkers == ROOT_LEGACY_MARKERS.size())) {
      return Mode.LEGACY;
    }
    throw invalid("mode");
  }

  private Map<String, Object> parse(String source, String field) {
    if (source == null || source.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw invalid(field);
    }
    try {
      return json.readValue(source, OBJECT_MAP);
    } catch (IOException exception) {
      throw invalid(field);
    }
  }

  private Map<String, Object> requireCredentialFree(
      Map<String, Object> source,
      String field
  ) {
    Object sanitized = credentialSanitizer.sanitizeJsonValue(source);
    if (!(sanitized instanceof Map<?, ?> map) || !source.equals(map)) {
      throw invalid(field);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> safe = (Map<String, Object>) map;
    return safe;
  }

  private List<Map<String, Object>> rewriteTicks(
      List<?> values,
      String runId,
      long generation
  ) {
    List<Map<String, Object>> ticks = maps(values, "ticks");
    List<Map<String, Object>> rewritten = new ArrayList<>(ticks.size());
    for (Map<String, Object> tick : ticks) {
      Map<String, Object> value = new LinkedHashMap<>(tick);
      value.put("runId", runId);
      value.put("generation", generation);
      rewritten.add(Collections.unmodifiableMap(value));
    }
    return List.copyOf(rewritten);
  }

  private static List<Map<String, Object>> maps(List<?> values, String field) {
    List<Map<String, Object>> result = new ArrayList<>(values.size());
    for (Object value : values) {
      result.add(Collections.unmodifiableMap(requireMap(value, field)));
    }
    return List.copyOf(result);
  }

  private static Map<String, BigDecimal> decimals(Map<String, Object> source) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (key == null || key.isBlank() || value == null) {
        throw invalid("initialBalances");
      }
      try {
        BigDecimal amount = decimal(value, "initialBalances")
            .setScale(8, RoundingMode.UNNECESSARY);
        if (amount.signum() < 0
            || amount.precision() > 24
            || result.put(key, amount) != null) {
          throw invalid("initialBalances");
        }
      } catch (ArithmeticException exception) {
        throw invalid("initialBalances");
      }
    });
    if (!result.containsKey("USDT")) {
      throw invalid("initialBalances");
    }
    return Map.copyOf(result);
  }

  private static BigDecimal scaledDecimal(
      Object value,
      BigDecimal fallback,
      int scale,
      String field
  ) {
    if (value == null && fallback != null) {
      value = fallback;
    }
    try {
      return decimal(value, field).setScale(scale, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException exception) {
      throw invalid(field);
    }
  }

  private static BigDecimal decimal(Object value, String field) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number || value instanceof String) {
      try {
        return new BigDecimal(value.toString());
      } catch (NumberFormatException exception) {
        throw invalid(field);
      }
    }
    throw invalid(field);
  }

  private static Map<String, Object> requireMap(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) {
      throw invalid(field);
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, child) -> {
      if (!(key instanceof String text) || copy.containsKey(text)) {
        throw invalid(field);
      }
      copy.put(text, child);
    });
    return copy;
  }

  private static List<?> requireList(Object value, String field) {
    if (!(value instanceof List<?> list)) {
      throw invalid(field);
    }
    return list;
  }

  private static String requireText(Object value, String field) {
    if (!(value instanceof String text)
        || text.isBlank()
        || text.length() > 256) {
      throw invalid(field);
    }
    return text;
  }

  private static void validateSeed(String seed) {
    if (seed.codePoints()
        .allMatch(SnapshotTradingLabValidationStartRequestFactory::ecmaWhitespace)) {
      throw invalid("seed");
    }
    for (int index = 0; index < seed.length(); index++) {
      char value = seed.charAt(index);
      if (Character.isHighSurrogate(value)) {
        if (index + 1 >= seed.length()
            || !Character.isLowSurrogate(seed.charAt(index + 1))) {
          throw invalid("seed");
        }
        index++;
      } else if (Character.isLowSurrogate(value)) {
        throw invalid("seed");
      }
    }
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

  private static Instant parseInstant(Object value, Instant fallback, String field) {
    if (value == null && fallback != null) {
      return fallback;
    }
    if (!(value instanceof String text)) {
      throw invalid(field);
    }
    try {
      return Instant.parse(text);
    } catch (RuntimeException exception) {
      throw invalid(field);
    }
  }

  private String hashBounded(Object value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      writeBounded(value, new DigestOutputStream(OutputStream.nullOutputStream(), digest));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private void writeBounded(Object value) {
    writeBounded(value, OutputStream.nullOutputStream());
  }

  private void writeBounded(Object value, long maximumBytes) {
    writeBounded(value, OutputStream.nullOutputStream(), maximumBytes);
  }

  private void writeBounded(Object value, OutputStream target) {
    writeBounded(value, target, maxBytes);
  }

  private void writeBounded(
      Object value,
      OutputStream target,
      long maximumBytes
  ) {
    try {
      json.writeValue(new CappedOutputStream(target, maximumBytes), value);
    } catch (IOException exception) {
      throw invalid("validationRun");
    }
  }

  private long generationIndependentMaximum(long generation, int tickCount) {
    try {
      long missingDigits = 19L - Long.toString(generation).length();
      long occurrences = Math.addExact((long) tickCount, 1L);
      long reservedBytes = Math.multiplyExact(missingDigits, occurrences);
      long maximum = Math.subtractExact((long) maxBytes, reservedBytes);
      if (maximum < 0L) {
        throw invalid("validationRun");
      }
      return maximum;
    } catch (ArithmeticException exception) {
      throw invalid("validationRun");
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

  private static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("Invalid Trading Lab validation start field: " + field);
  }

  private enum Mode {
    LEGACY,
    BROWSER
  }

  private record StartDocument(
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
}
