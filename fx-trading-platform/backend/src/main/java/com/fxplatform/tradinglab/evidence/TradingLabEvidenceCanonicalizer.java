package com.fxplatform.tradinglab.evidence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import com.fxplatform.tradinglab.report.TradingLabFixedValidationSecretProvider;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class TradingLabEvidenceCanonicalizer {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

  private final TradingLabCredentialSanitizer sanitizer;
  private final ObjectMapper json;
  private final int maxBytes;
  private final List<String> fixedSecrets;

  TradingLabEvidenceCanonicalizer(
      TradingLabCredentialSanitizer sanitizer,
      TradingLabFixedValidationSecretProvider fixedSecrets,
      TradingLabReportProperties properties
  ) {
    this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
    this.maxBytes = Objects.requireNonNull(properties, "properties").maxLogicalValueBytes();
    this.fixedSecrets = Objects.requireNonNull(fixedSecrets, "fixedSecrets").secrets();
    JsonFactory factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(64)
            .maxStringLength(maxBytes)
            .maxNumberLength(1_000)
            .build())
        .build();
    this.json = JsonMapper.builder(factory)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
  }

  CanonicalPayload canonicalize(Map<String, Object> raw) {
    List<String> discoveredSecrets = new ArrayList<>();
    Object sanitized = sanitizer.sanitizeJsonValue(
        raw == null ? Map.of() : raw,
        discoveredSecrets::add);
    List<String> knownSecrets = new ArrayList<>(fixedSecrets);
    knownSecrets.addAll(discoveredSecrets);
    sanitized = sanitizer.redactKnownSecrets(sanitized, boundedSecrets(knownSecrets));
    if (!(sanitized instanceof Map<?, ?> map)) {
      throw unsafe();
    }
    try {
      byte[] bytes = json.writeValueAsBytes(map);
      if (bytes.length > maxBytes) {
        throw unsafe();
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> frozen = json.readValue(bytes, MAP);
      return new CanonicalPayload(new String(bytes, StandardCharsets.UTF_8), frozen);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof IllegalArgumentException illegal) {
        throw illegal;
      }
      throw unsafe();
    }
  }

  CanonicalPayload parse(String canonicalJson) {
    if (canonicalJson == null
        || canonicalJson.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw unsafe();
    }
    try {
      Map<String, Object> value = json.readValue(canonicalJson, MAP);
      return canonicalize(value);
    } catch (IOException exception) {
      throw unsafe();
    }
  }

  /**
   * Parses already-persisted evidence without applying another sanitization pass.
   *
   * <p>Recovery callers use this only to prove that the durable JSON still has the exact shape
   * and fingerprint originally written. Re-sanitizing first would erase a newly injected
   * sensitive-looking key and could make a corrupt row appear valid.
   */
  CanonicalPayload parsePersistedExact(String persistedJson) {
    if (persistedJson == null
        || persistedJson.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw unsafe();
    }
    try {
      Map<String, Object> value = json.readValue(persistedJson, MAP);
      return canonicalizePersistedExact(value);
    } catch (IOException exception) {
      throw unsafe();
    }
  }

  CanonicalPayload canonicalizePersistedExact(Map<String, Object> value) {
    try {
      byte[] bytes = json.writeValueAsBytes(
          value == null ? Map.of() : value);
      if (bytes.length > maxBytes) {
        throw unsafe();
      }
      Map<String, Object> frozen = json.readValue(bytes, MAP);
      return new CanonicalPayload(new String(bytes, StandardCharsets.UTF_8), frozen);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof IllegalArgumentException illegal) {
        throw illegal;
      }
      throw unsafe();
    }
  }

  private Collection<String> boundedSecrets(List<String> secrets) {
    if (secrets.size() > 256) {
      throw unsafe();
    }
    int bytes = 0;
    List<String> bounded = new ArrayList<>();
    for (String secret : secrets) {
      if (secret == null || secret.isEmpty()) {
        continue;
      }
      bytes = Math.addExact(bytes, secret.getBytes(StandardCharsets.UTF_8).length);
      if (bytes > maxBytes) {
        throw unsafe();
      }
      bounded.add(secret);
    }
    return List.copyOf(bounded);
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("Unsafe Trading Lab coordinator evidence");
  }

  record CanonicalPayload(String json, Map<String, Object> value) {
    CanonicalPayload {
      Objects.requireNonNull(json, "json");
      value = Collections.unmodifiableMap(
          new LinkedHashMap<>(Objects.requireNonNull(value, "value")));
    }
  }
}
