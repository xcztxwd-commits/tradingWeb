package com.fxplatform.tradinglab.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Frozen validation start wire document without importing validation execution implementation
 * types into the main control plane.
 */
public record ValidationRunStartRequest(
    UUID runId,
    long generation,
    String requestFingerprint,
    String seed,
    Instant virtualStart,
    Map<String, Object> executionPolicy,
    Map<String, BigDecimal> initialBalances,
    Map<String, Object> accountSettings,
    List<Map<String, Object>> ticks,
    List<Map<String, Object>> actions,
    BigDecimal speedMultiplier
) {

  public ValidationRunStartRequest {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(virtualStart, "virtualStart");
    Objects.requireNonNull(speedMultiplier, "speedMultiplier");
    if (generation <= 0L
        || requestFingerprint == null
        || requestFingerprint.isBlank()
        || requestFingerprint.length() > 128
        || seed.isBlank()
        || seed.length() > 256
        || speedMultiplier.signum() <= 0) {
      throw new IllegalArgumentException("Validation start identity is invalid");
    }
    executionPolicy = ValidationClientSafeValues.freezeMap(executionPolicy);
    accountSettings = ValidationClientSafeValues.freezeMap(accountSettings);
    ticks = ValidationClientSafeValues.freezeMapList(ticks);
    actions = ValidationClientSafeValues.freezeMapList(actions);
    if (executionPolicy.isEmpty() || accountSettings.isEmpty() || ticks.isEmpty()) {
      throw new IllegalArgumentException("Validation start document is incomplete");
    }
    TreeMap<String, BigDecimal> sortedBalances = new TreeMap<>();
    Map<String, BigDecimal> requestedBalances =
        initialBalances == null ? Map.of() : initialBalances;
    requestedBalances.forEach((asset, amount) -> {
      if (asset == null || asset.isBlank() || amount == null || amount.signum() < 0
          || sortedBalances.put(asset, amount) != null) {
        throw new IllegalArgumentException("Validation initial balance is invalid");
      }
    });
    if (!sortedBalances.containsKey("USDT")) {
      throw new IllegalArgumentException("Validation initial balances require USDT");
    }
    initialBalances = Collections.unmodifiableMap(new LinkedHashMap<>(sortedBalances));
  }
}
