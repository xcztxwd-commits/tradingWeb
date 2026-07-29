package com.fxplatform.tradinglab.report;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TradingLabReportCanonicalizer {

  private static final int MAX_DEPTH = 64;
  private static final int MAX_SUMMARY_BYTES = 2048;

  private final JsonMapper objectMapper;
  private final TradingLabBoundedCredentialSanitizer boundedCredentialSanitizer;
  private final int maxLogicalValueBytes;

  @Autowired
  public TradingLabReportCanonicalizer(
      TradingLabCredentialSanitizer sanitizer,
      TradingLabReportProperties properties
  ) {
    this(sanitizer, properties.maxLogicalValueBytes());
  }

  TradingLabReportCanonicalizer(
      ObjectMapper objectMapper,
      TradingLabCredentialSanitizer sanitizer,
      int maxLogicalValueBytes
  ) {
    this(sanitizer, maxLogicalValueBytes);
    Objects.requireNonNull(objectMapper, "objectMapper");
  }

  TradingLabReportCanonicalizer(
      TradingLabCredentialSanitizer sanitizer,
      int maxLogicalValueBytes
  ) {
    if (maxLogicalValueBytes < 1) {
      throw new IllegalArgumentException("Trading Lab report value limit must be positive");
    }
    this.objectMapper = deterministicJsonMapper(maxLogicalValueBytes);
    this.boundedCredentialSanitizer = new TradingLabBoundedCredentialSanitizer(
        Objects.requireNonNull(sanitizer, "sanitizer"), maxLogicalValueBytes);
    this.maxLogicalValueBytes = maxLogicalValueBytes;
  }

  private static JsonMapper deterministicJsonMapper(int maxLogicalValueBytes) {
    SimpleModule canonicalNumbers = new SimpleModule();
    canonicalNumbers.addSerializer(
        BigDecimal.class, new PlainBigDecimalSerializer(maxLogicalValueBytes));
    return JsonMapper.builder()
        .addModule(canonicalNumbers)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
  }

  public TradingLabCanonicalValue canonicalize(
      TradingLabReportSection section,
      Object value
  ) {
    return canonicalize(section, value, new TradingLabReportSecretRegistry());
  }

  TradingLabCanonicalValue canonicalize(
      TradingLabReportSection section,
      Object value,
      TradingLabReportSecretRegistry secrets
  ) {
    Objects.requireNonNull(section, "section");
    Objects.requireNonNull(secrets, "secrets");
    if (section == TradingLabReportSection.API_TRACE
        && !(value instanceof SafeTradingLabHttpTrace)) {
      throw error(
          "TRADING_LAB_REPORT_UNSAFE_TRACE",
          "Trading Lab API trace must use the sealed safe trace type");
    }

    TraversalBudget budget = new TraversalBudget(maxLogicalValueBytes);
    Object safeValue = section == TradingLabReportSection.API_TRACE
        ? sanitizeTrustedTrace((SafeTradingLabHttpTrace) value, secrets, budget)
        : sanitize(
            adapt(value, new IdentityHashMap<>(), 0, budget), secrets);
    requireSectionShape(section, safeValue);

    int jsonLimit = section.isArray()
        ? maxLogicalValueBytes - 1
        : maxLogicalValueBytes;
    if (jsonLimit < 1) {
      throw tooLarge();
    }

    CountingOutput counter = new CountingOutput(jsonLimit);
    writeCanonicalJson(counter, safeValue);
    int jsonLength = counter.byteLength();
    byte[] canonical = new byte[jsonLength + (section.isArray() ? 1 : 0)];
    ExactOutput output = new ExactOutput(canonical, jsonLength);
    writeCanonicalJson(output, safeValue);
    output.requireComplete();
    if (section.isArray()) {
      canonical[jsonLength] = '\n';
    }
    requireNoCanary(canonical, secrets.values());
    return new TradingLabCanonicalValue(canonical, checksum(canonical));
  }

  private void writeCanonicalJson(OutputStream output, Object safeValue) {
    try {
      objectMapper.writeValue(output, safeValue);
    } catch (IOException exception) {
      if (containsTooLarge(exception)) {
        throw tooLarge();
      }
      if (containsLengthMismatch(exception)) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
  }

  public String sanitizeSummary(String summary) {
    return sanitizeSummary(summary, new TradingLabReportSecretRegistry());
  }

  String sanitizeSummary(
      String summary,
      TradingLabReportSecretRegistry secrets
  ) {
    if (summary == null) {
      return null;
    }
    Objects.requireNonNull(secrets, "secrets");
    TraversalBudget budget = new TraversalBudget(MAX_SUMMARY_BYTES + 64);
    Object adapted = adapt(
        Map.of("summary", summary), new IdentityHashMap<>(), 0, budget);
    Object safe = sanitize(adapted, secrets);
    Object value = ((Map<?, ?>) safe).get("summary");
    if (!(value instanceof String text)
        || text.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
      throw new IllegalArgumentException("Unsafe Trading Lab report summary");
    }
    requireNoCanary(text.getBytes(StandardCharsets.UTF_8), secrets.values());
    return text;
  }

  private Object sanitizeTrustedTrace(
      SafeTradingLabHttpTrace trace,
      TradingLabReportSecretRegistry secrets,
      TraversalBudget budget
  ) {
    Map<String, Object> traceValue = new LinkedHashMap<>(trace.toSafeMap());
    Object authentication = traceValue.remove("authentication");
    Object sanitized = sanitize(
        adapt(traceValue, new IdentityHashMap<>(), 0, budget), secrets);
    Map<String, Object> result = new LinkedHashMap<>();
    ((Map<?, ?>) sanitized).forEach((key, child) -> result.put((String) key, child));
    if (authentication != null) {
      result.put(
          "authentication", requireTrustedAuthentication(authentication, budget));
    } else {
      result.put("authentication", null);
    }
    return result;
  }

  private Map<String, Object> requireTrustedAuthentication(
      Object authentication,
      TraversalBudget budget
  ) {
    if (!(authentication instanceof Map<?, ?> map)
        || !map.keySet().equals(
            java.util.Set.of(
                "credentialType", "actorId", "scopes", "expiresAt", "fingerprint"))) {
      throw error(
          "TRADING_LAB_REPORT_UNSAFE_TRACE",
          "Trading Lab authentication metadata is unsafe");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> copied = (Map<String, Object>) adapt(
        map, new IdentityHashMap<>(), 0, budget);
    return copied;
  }

  private Object sanitize(
      Object value,
      TradingLabReportSecretRegistry secrets
  ) {
    TradingLabReportSecretRegistry staged = secrets.stagingCopy();
    Object sanitized = boundedCredentialSanitizer.sanitizeAndRedact(
        value, staged::register, staged::values);
    secrets.registerAll(staged.values());
    return sanitized;
  }

  private Object adapt(
      Object value,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth,
      TraversalBudget budget
  ) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
    budget.consumeNode();
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      budget.consumeText(text);
      return text;
    }
    if (value instanceof Boolean) {
      return value;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      budget.consumeCanonicalNumber(value);
      return value;
    }
    if (value instanceof Float number) {
      if (!Float.isFinite(number)) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      budget.consumeCanonicalNumber(number);
      return number;
    }
    if (value instanceof Double number) {
      if (!Double.isFinite(number)) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      budget.consumeCanonicalNumber(number);
      return number;
    }
    if (value instanceof BigInteger number) {
      if (number.getClass() != BigInteger.class) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      budget.requireTextCapacity(integerTextUpperBound(number));
      budget.consumeCanonicalNumber(number);
      return number;
    }
    if (value instanceof BigDecimal number) {
      if (number.getClass() != BigDecimal.class) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      preflightPlainDecimal(number, budget);
      budget.consumeCanonicalNumber(number);
      return number;
    }
    if (value instanceof UUID || value instanceof Instant) {
      String adapted = value.toString();
      budget.consumeText(adapted);
      return adapted;
    }
    if (value instanceof Map<?, ?> map) {
      enter(map, ancestors);
      try {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key) || copy.containsKey(key)) {
            throw new IllegalArgumentException("Unsafe Trading Lab report value");
          }
          budget.consumeText(key);
          copy.put(
              key, adapt(entry.getValue(), ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(map);
      }
    }
    if (value instanceof List<?> list) {
      enter(list, ancestors);
      try {
        List<Object> copy = new ArrayList<>();
        for (Object child : list) {
          copy.add(adapt(child, ancestors, depth + 1, budget));
        }
        return copy;
      } finally {
        ancestors.remove(list);
      }
    }
    if (value.getClass().isArray()) {
      enter(value, ancestors);
      try {
        int length = java.lang.reflect.Array.getLength(value);
        budget.requireContainerSize(length);
        List<Object> copy = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
          copy.add(adapt(
              java.lang.reflect.Array.get(value, index),
              ancestors,
              depth + 1,
              budget));
        }
        return copy;
      } finally {
        ancestors.remove(value);
      }
    }
    throw new IllegalArgumentException("Unsafe Trading Lab report value");
  }

  private static long integerTextUpperBound(BigInteger value) {
    long digits = decimalDigitUpperBound(value.bitLength());
    return digits + (value.signum() < 0 ? 1L : 0L);
  }

  private static long decimalDigitUpperBound(int bitLength) {
    if (bitLength <= 0) {
      return 1L;
    }
    // 30103 / 100000 is a strict upper bound for log10(2).
    return Math.ceilDiv((long) bitLength * 30_103L, 100_000L);
  }

  private static void preflightPlainDecimal(
      BigDecimal value,
      TraversalBudget budget
  ) {
    int scale = value.scale();
    boolean negative = value.signum() < 0;

    // Reject scale-driven expansion before inspecting or formatting the unscaled value.
    budget.requireTextCapacity(plainDecimalLengthUpperBound(1L, scale, negative));

    BigInteger unscaled = value.unscaledValue();
    long digitUpperBound = decimalDigitUpperBound(unscaled.bitLength());
    budget.requireTextCapacity(
        plainDecimalLengthUpperBound(digitUpperBound, scale, negative));
  }

  private static long plainDecimalLengthUpperBound(
      long precision,
      int scale,
      boolean negative
  ) {
    long sign = negative ? 1L : 0L;
    if (scale == 0) {
      return sign + precision;
    }
    if (scale < 0) {
      return sign + precision - (long) scale;
    }
    return sign + (precision > scale ? precision + 1L : (long) scale + 2L);
  }

  private void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
    if (ancestors.put(value, Boolean.TRUE) != null) {
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
  }

  private void requireSectionShape(TradingLabReportSection section, Object value) {
    boolean valid = section.isArray()
        || (section.isString() && value instanceof String)
        || (!section.isArray() && !section.isString() && value instanceof Map<?, ?>);
    if (!valid) {
      throw new IllegalArgumentException("Trading Lab report section has the wrong value shape");
    }
  }

  private void requireNoCanary(byte[] canonical, List<String> secrets) {
    if (secrets.isEmpty()) {
      return;
    }
    TradingLabCanonicalCanaryScanner.BytePatternMatcher matcher = canaryMatcher(secrets);
    if (matcher.contains(canonical)) {
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
  }

  TradingLabCanonicalCanaryScanner.BytePatternMatcher canaryMatcher(
      TradingLabReportSecretRegistry secrets
  ) {
    Objects.requireNonNull(secrets, "secrets");
    return canaryMatcher(secrets.values());
  }

  private TradingLabCanonicalCanaryScanner.BytePatternMatcher canaryMatcher(
      List<String> secrets
  ) {
    try {
      return TradingLabCanonicalCanaryScanner.canaryMatcher(secrets);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
  }

  void requireNoCanary(
      List<TradingLabLogicalAppend> appends,
      TradingLabReportSecretRegistry secrets
  ) {
    if (appends.isEmpty() || secrets.values().isEmpty()) {
      return;
    }
    try {
      long canonicalBytes = 0L;
      for (TradingLabLogicalAppend append : appends) {
        canonicalBytes = Math.addExact(canonicalBytes, append.canonicalByteCount());
      }
      if (canonicalBytes > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      TradingLabCanonicalCanaryScanner scanner = new TradingLabCanonicalCanaryScanner(
          canaryMatcher(secrets), Math.max(1, (int) canonicalBytes));
      for (TradingLabLogicalAppend append : appends) {
        scanner.scan(append.internalBytes());
      }
    } catch (ArithmeticException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
  }

  void requireNoCanary(
      Object value,
      TradingLabReportSecretRegistry secrets
  ) {
    Objects.requireNonNull(secrets, "secrets");
    new TradingLabCanonicalCanaryScanner(secrets.values(), maxLogicalValueBytes)
        .scan(value);
  }

  void requireNoCanary(
      byte[] prefix,
      byte[] canonical,
      TradingLabReportSecretRegistry secrets
  ) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(canonical, "canonical");
    Objects.requireNonNull(secrets, "secrets");
    if (prefix.length == 0 || secrets.values().isEmpty()) {
      return;
    }
    try {
      int canonicalBytes = Math.addExact(prefix.length, canonical.length);
      TradingLabCanonicalCanaryScanner scanner = new TradingLabCanonicalCanaryScanner(
          canaryMatcher(secrets), Math.max(1, canonicalBytes));
      scanner.scan(prefix);
      scanner.scan(canonical);
    } catch (ArithmeticException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsafe Trading Lab report value");
    }
  }

  private static String checksum(byte[] value) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static boolean containsTooLarge(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ValueTooLargeIOException) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsLengthMismatch(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof CanonicalLengthMismatchIOException) {
        return true;
      }
    }
    return false;
  }

  private static TradingLabReportException tooLarge() {
    return error(
        "TRADING_LAB_REPORT_VALUE_TOO_LARGE",
        "Trading Lab report value exceeds the configured byte limit");
  }

  private static TradingLabReportException error(String code, String message) {
    return new TradingLabReportException(code, message);
  }

  private static final class PlainBigDecimalSerializer
      extends JsonSerializer<BigDecimal> {
    private final int maxBytes;

    private PlainBigDecimalSerializer(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public void serialize(
        BigDecimal value,
        JsonGenerator generator,
        SerializerProvider serializers
    ) throws IOException {
      // Advance Jackson's value context, then stream the already-preflighted number raw.
      generator.writeRawValue("");
      try {
        TradingLabCanonicalNumberWriter.write(value, maxBytes, next -> {
          try {
            generator.writeRaw((char) next);
          } catch (IOException exception) {
            throw new CanonicalWriteFailure(exception);
          }
        });
      } catch (CanonicalWriteFailure failure) {
        throw failure.ioException();
      }
    }
  }

  private static final class CanonicalWriteFailure extends RuntimeException {
    private final IOException ioException;

    private CanonicalWriteFailure(IOException ioException) {
      super(ioException);
      this.ioException = ioException;
    }

    private IOException ioException() {
      return ioException;
    }
  }

  private static final class CountingOutput extends OutputStream {
    private final int limit;
    private int count;

    private CountingOutput(int limit) {
      this.limit = limit;
    }

    @Override
    public void write(int value) throws ValueTooLargeIOException {
      requireCapacity(1);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length)
        throws ValueTooLargeIOException {
      requireCapacity(length);
      count += length;
    }

    private void requireCapacity(int length) throws ValueTooLargeIOException {
      if (length < 0 || count > limit - length) {
        throw new ValueTooLargeIOException();
      }
    }

    private int byteLength() {
      return count;
    }
  }

  private static final class ExactOutput extends OutputStream {
    private final byte[] target;
    private final int limit;
    private int position;

    private ExactOutput(byte[] target, int limit) {
      this.target = target;
      this.limit = limit;
    }

    @Override
    public void write(int value) throws CanonicalLengthMismatchIOException {
      requireCapacity(1);
      target[position++] = (byte) value;
    }

    @Override
    public void write(byte[] bytes, int offset, int length)
        throws CanonicalLengthMismatchIOException {
      requireCapacity(length);
      System.arraycopy(bytes, offset, target, position, length);
      position += length;
    }

    private void requireCapacity(int length)
        throws CanonicalLengthMismatchIOException {
      if (length < 0 || position > limit - length) {
        throw new CanonicalLengthMismatchIOException();
      }
    }

    private void requireComplete() {
      if (position != limit) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
    }
  }

  private static final class ValueTooLargeIOException extends IOException {
    private ValueTooLargeIOException() {
      super("Trading Lab report value limit exceeded");
    }
  }

  private static final class CanonicalLengthMismatchIOException extends IOException {
    private CanonicalLengthMismatchIOException() {
      super("Trading Lab canonical length changed between deterministic passes");
    }
  }

  private static final class TraversalBudget {
    private long remainingBytes;
    private long remainingNodes;

    private TraversalBudget(int byteLimit) {
      this.remainingBytes = byteLimit;
      this.remainingNodes = Math.max(64L, Math.ceilDiv((long) byteLimit, 4L));
    }

    private void consumeNode() {
      if (--remainingNodes < 0L) {
        throw tooLarge();
      }
    }

    private void requireContainerSize(int size) {
      if (size < 0) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      if (size > remainingNodes) {
        throw tooLarge();
      }
    }

    private void requireTextCapacity(long bytes) {
      if (bytes < 0L || bytes > remainingBytes) {
        throw tooLarge();
      }
    }

    private void consumeCanonicalNumber(Object value) {
      long canonicalBytes;
      try {
        canonicalBytes = TradingLabJsonNumberBounds.requireCanonicalLength(
            value, remainingBytes);
      } catch (IllegalArgumentException exception) {
        throw tooLarge();
      }
      consumeAscii(canonicalBytes);
    }

    private void consumeAscii(long bytes) {
      requireTextCapacity(bytes);
      remainingBytes -= bytes;
    }

    private void consumeText(String value) {
      long bytes = 0L;
      for (int index = 0; index < value.length(); index++) {
        char current = value.charAt(index);
        if (current <= 0x7f) {
          bytes++;
        } else if (current <= 0x7ff) {
          bytes += 2L;
        } else if (Character.isHighSurrogate(current)
            && index + 1 < value.length()
            && Character.isLowSurrogate(value.charAt(index + 1))) {
          bytes += 4L;
          index++;
        } else {
          bytes += 3L;
        }
        if (bytes > remainingBytes) {
          throw tooLarge();
        }
      }
      remainingBytes -= bytes;
    }
  }
}
