package com.fxplatform.tradinglab.admin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TradingLabScenarioCanonicalizer {

  private static final Set<String> TRANSIENT_KEYS = Set.of(
      "transient",
      "uistate",
      "validationerrors",
      "selected",
      "expanded",
      "dragging");
  private static final Set<String> CREDENTIAL_KEYS = Set.of(
      "authorization",
      "cookie",
      "token",
      "password",
      "secret",
      "apikey",
      "accesskey",
      "authcode",
      "credential",
      "privatekey",
      "encryptionkey");
  private static final Set<String> NON_FINITE_NUMERIC_TEXT = Set.of(
      "nan",
      "+nan",
      "-nan",
      "inf",
      "+inf",
      "-inf",
      "infinity",
      "+infinity",
      "-infinity");
  private static final Pattern NUMERIC_EXPONENT = Pattern.compile(
      "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)[eE][+-]?\\d+");
  private static final Pattern PLAIN_DECIMAL = Pattern.compile(
      "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");
  private static final Set<String> OPAQUE_NUMERIC_SUFFIXES = Set.of(
      "id",
      "ids",
      "code",
      "version",
      "hash",
      "fingerprint",
      "symbol",
      "symbols",
      "asset",
      "assets",
      "type",
      "mode",
      "state",
      "status",
      "name",
      "description",
      "url",
      "path",
      "time",
      "date",
      "timestamp",
      "key",
      "label",
      "seed");
  private static final Set<String> DECIMAL_SUFFIXES = Set.of(
      "price",
      "prices",
      "quantity",
      "quantities",
      "qty",
      "amount",
      "balance",
      "notional",
      "rate",
      "ratio",
      "size",
      "pnl",
      "fee",
      "margin",
      "cost",
      "proceeds",
      "equity",
      "value",
      "volume",
      "budget",
      "hold",
      "multiplier",
      "offset",
      "volatility",
      "spread",
      "basis");
  private static final Set<String> DECIMAL_KEYS = Set.of(
      "bid",
      "ask",
      "last",
      "mark",
      "index",
      "maxfillquantitypertick");
  private static final Set<String> DECIMAL_OBJECT_KEYS = Set.of(
      "initialbalances");

  private final JsonMapper json;
  private final int maxBytes;

  @Autowired
  public TradingLabScenarioCanonicalizer(
      ObjectMapper ignored,
      TradingLabReportProperties properties
  ) {
    this(ignored, properties.maxLogicalValueBytes());
  }

  TradingLabScenarioCanonicalizer(ObjectMapper ignored, int maxBytes) {
    if (maxBytes < 1) {
      throw new IllegalArgumentException("Trading Lab canonical JSON budget must be positive");
    }
    JsonFactory factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(64)
            .maxStringLength(maxBytes)
            .maxNumberLength(1_000)
            .build())
        .build();
    this.json = JsonMapper.builder(factory)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    this.maxBytes = maxBytes;
  }

  public TradingLabCanonicalDocument canonicalize(String source) {
    if (source == null || utf8Length(source) > maxBytes) {
      throw invalid();
    }
    try {
      return canonicalizeNode(json.readTree(source));
    } catch (RuntimeException | java.io.IOException exception) {
      throw invalid();
    }
  }

  public TradingLabCanonicalDocument canonicalize(JsonNode source) {
    if (source == null) {
      throw invalid();
    }
    try {
      if (json.writeValueAsBytes(source).length > maxBytes) {
        throw invalid();
      }
      return canonicalizeNode(source);
    } catch (RuntimeException | java.io.IOException exception) {
      throw invalid();
    }
  }

  private TradingLabCanonicalDocument canonicalizeNode(JsonNode source) {
    JsonNode normalized = normalize(source, 0, null);
    if (!normalized.isObject()) {
      throw invalid();
    }
    try {
      String canonical = json.writeValueAsString(normalized);
      if (utf8Length(canonical) > maxBytes) {
        throw invalid();
      }
      return new TradingLabCanonicalDocument(
          canonical,
          sha256(canonical.getBytes(StandardCharsets.UTF_8)),
          normalized.deepCopy());
    } catch (java.io.IOException exception) {
      throw invalid();
    }
  }

  private JsonNode normalize(JsonNode value, int depth, String contextKey) {
    if (value == null || value.isMissingNode() || depth > 64) {
      throw invalid();
    }
    if (value.isObject()) {
      ObjectNode result = json.createObjectNode();
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String key = field.getKey();
        if (key == null || key.isBlank() || containsControl(key)) {
          throw invalid();
        }
        String normalizedKey = normalizedKey(key);
        if (TRANSIENT_KEYS.contains(normalizedKey)) {
          throw invalid();
        }
        if (isCredentialKey(normalizedKey) || sorted.put(key, field.getValue()) != null) {
          throw invalid();
        }
      }
      sorted.forEach((key, child) -> result.set(
          key,
          normalize(
              child,
              depth + 1,
              isDecimalObjectContext(contextKey)
                  ? contextKey
                  : normalizedKey(key))));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = json.createArrayNode();
      value.forEach(child -> result.add(normalize(child, depth + 1, contextKey)));
      return result;
    }
    if (value.isFloatingPointNumber()) {
      throw invalid();
    }
    if (value.isIntegralNumber()) {
      if (isDecimalContext(contextKey)) {
        throw invalid();
      }
      return value.deepCopy();
    }
    if (value.isBoolean() || value.isNull()) {
      return value.deepCopy();
    }
    if (value.isTextual()) {
      String text = value.textValue();
      if (text == null
          || containsControl(text)
          || NON_FINITE_NUMERIC_TEXT.contains(text.toLowerCase(Locale.ROOT))
          || NUMERIC_EXPONENT.matcher(text).matches()) {
        throw invalid();
      }
      if (PLAIN_DECIMAL.matcher(text).matches() && !isOpaqueNumericString(contextKey)) {
        BigDecimal decimal = new BigDecimal(text);
        BigDecimal normalized =
            decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
        return TextNode.valueOf(normalized.toPlainString());
      }
      return TextNode.valueOf(text);
    }
    throw invalid();
  }

  private static boolean isCredentialKey(String key) {
    return CREDENTIAL_KEYS.stream().anyMatch(key::contains);
  }

  private static boolean isOpaqueNumericString(String contextKey) {
    return contextKey != null
        && OPAQUE_NUMERIC_SUFFIXES.stream().anyMatch(contextKey::endsWith);
  }

  private static boolean isDecimalContext(String contextKey) {
    return contextKey != null
        && (DECIMAL_KEYS.contains(contextKey)
            || DECIMAL_OBJECT_KEYS.contains(contextKey)
            || DECIMAL_SUFFIXES.stream().anyMatch(contextKey::endsWith));
  }

  private static boolean isDecimalObjectContext(String contextKey) {
    return contextKey != null && DECIMAL_OBJECT_KEYS.contains(contextKey);
  }

  private static String normalizedKey(String key) {
    StringBuilder normalized = new StringBuilder(key.length());
    key.toLowerCase(Locale.ROOT).codePoints()
        .filter(Character::isLetterOrDigit)
        .forEach(normalized::appendCodePoint);
    return normalized.toString();
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f);
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Trading Lab scenario JSON is invalid");
  }
}
