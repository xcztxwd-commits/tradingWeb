package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationMarketState.FundingRatePoint;
import com.fxplatform.validation.service.ValidationBoundedHttpExchange;
import com.fxplatform.validation.service.ValidationRunEngine.AccountSettings;
import com.fxplatform.validation.service.ValidationRunEngine.ExpectedHttpError;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Black-box harness for the fixed validation runtime.
 *
 * <p>The only runtime seam used here is JDK {@link HttpClient}. Scenario construction reuses the
 * public validation DTOs solely to encode the frozen HTTP request; no Spring service, repository,
 * JDBC connection, or in-process scenario executor is available to these tests.</p>
 */
final class ValidationHttpIntegrationSupport {

  static final String BTC_SPOT = "BTCUSDT";
  static final String BTC_PERPETUAL = "BTCUSDT-PERP";
  static final String ETH_PERPETUAL = "ETHUSDT-PERP";
  static final BigDecimal FUNDING_RATE = new BigDecimal("0.0010000000");

  private static final URI BASE_URI = URI.create("http://127.0.0.1:18087");
  private static final String INTERNAL_TOKEN_ENV = "VALIDATION_INTERNAL_SECRET";
  private static final String INTERNAL_TOKEN_HEADER = "X-Validation-Internal-Token";
  private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RESET_REQUEST_TIMEOUT = Duration.ofMinutes(4);
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(90);
  private static final Instant VIRTUAL_START = Instant.parse("2020-01-01T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper()
      .findAndRegisterModules()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final HttpClient client;
  private final String internalToken;

  ValidationHttpIntegrationSupport() {
    String token = System.getenv(INTERNAL_TOKEN_ENV);
    if (token == null
        || token.isBlank()
        || token.codePoints().anyMatch(Character::isWhitespace)
        || token.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          INTERNAL_TOKEN_ENV + " must contain at least 32 bytes");
    }
    internalToken = token;
    client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  ResetResult reset() {
    JsonNode receipt = exchange(
        "POST",
        "/internal/validation/reset",
        null,
        RESET_REQUEST_TIMEOUT);
    assertThat(receipt.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(receipt.path("errorCode").isNull()).isTrue();
    long generation = requiredPositiveLong(receipt, "memoryGeneration");
    assertThat(requiredPositiveLong(receipt, "redisGeneration")).isEqualTo(generation);
    assertThat(receipt.path("steps").isArray()).isTrue();
    assertThat(receipt.path("steps").size()).isGreaterThan(0);
    receipt.path("steps").forEach(step ->
        assertThat(step.path("status").asText()).isEqualTo("SUCCEEDED"));

    JsonNode state = state(null);
    assertCoherentGeneration(state, generation);
    assertThat(state.path("run").isNull()).isTrue();
    assertThat(state.path("clock").isNull()).isTrue();
    assertThat(state.path("marketTickSequence").isNull()).isTrue();
    assertThat(state.path("executionPolicyFrozen").asBoolean()).isFalse();
    return new ResetResult(generation, state);
  }

  RunResult startAndAwait(StartRequest request) {
    start(request);
    RunResult result = awaitTerminal(request, "COMPLETED");
    assertThat(result.finalBusinessState().isObject()).isTrue();
    return result;
  }

  void start(StartRequest request) {
    JsonNode accepted = exchange("POST", "/internal/validation/runs", request);
    assertThat(accepted.path("runId").asText()).isEqualTo(request.runId().toString());
    assertThat(accepted.path("requestFingerprint").asText())
        .isEqualTo(request.requestFingerprint());
    assertThat(accepted.path("state").asText()).isEqualTo("ACCEPTED");
  }

  RunResult awaitTerminal(StartRequest request, String expectedTerminalState) {
    assertThat(expectedTerminalState).isIn("COMPLETED", "FAILED", "CANCELLED");
    long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
    long afterSequence = 0L;
    long observedHighWatermark = 0L;
    ArrayList<JsonNode> events = new ArrayList<>();
    JsonNode latestState = null;
    while (System.nanoTime() < deadline) {
      JsonNode page = exchange(
          "GET",
          "/internal/validation/runs/" + request.runId()
              + "/events?afterSequence=" + afterSequence + "&limit=200",
          null,
          remainingRequestTimeout(deadline));
      assertThat(page.path("events").isArray()).isTrue();
      assertThat(page.path("events").size()).isLessThanOrEqualTo(200);
      long highWatermark = requiredNonNegativeLong(page, "highWatermark");
      assertThat(highWatermark).isGreaterThanOrEqualTo(afterSequence);
      assertThat(highWatermark).isGreaterThanOrEqualTo(observedHighWatermark);
      observedHighWatermark = highWatermark;
      for (JsonNode event : page.path("events")) {
        long sequence = requiredPositiveLong(event, "sequence");
        assertThat(sequence).isEqualTo(afterSequence + 1L);
        assertThat(sequence).isLessThanOrEqualTo(highWatermark);
        assertThat(event.path("runId").asText()).isEqualTo(request.runId().toString());
        events.add(event.deepCopy());
        afterSequence = sequence;
      }

      latestState = state(request.runId(), remainingRequestTimeout(deadline));
      assertCoherentGeneration(latestState, request.generation());
      JsonNode terminalNode = page.get("terminal");
      JsonNode hasMoreNode = page.get("hasMore");
      assertThat(terminalNode).isNotNull();
      assertThat(terminalNode.isBoolean()).isTrue();
      assertThat(hasMoreNode).isNotNull();
      assertThat(hasMoreNode.isBoolean()).isTrue();
      boolean terminal = terminalNode.booleanValue();
      boolean hasMore = hasMoreNode.booleanValue();
      assertThat(hasMore).isEqualTo(afterSequence < highWatermark);
      if (terminal && !hasMore && afterSequence == highWatermark) {
        JsonNode run = latestState.path("run");
        assertThat(run.isObject()).isTrue();
        assertThat(run.path("runId").asText()).isEqualTo(request.runId().toString());
        assertThat(run.path("generation").asLong()).isEqualTo(request.generation());
        assertThat(run.path("state").asText())
            .as("terminal validation state, failureCode=%s",
                run.path("failureCode").asText(""))
            .isEqualTo(expectedTerminalState);
        assertThat(run.path("terminal").asBoolean()).isTrue();
        assertThat(run.path("eventHighWatermark").asLong()).isEqualTo(highWatermark);
        if ("COMPLETED".equals(expectedTerminalState)) {
          assertThat(run.path("lastCompletedTickSequence").asLong())
              .isEqualTo(request.ticks().size());
          assertThat(latestState.path("marketTickSequence").asLong())
              .isEqualTo(request.ticks().size());
          assertThat(latestState.path("executionPolicyFrozen").asBoolean()).isTrue();
        }

        RunResult result = new RunResult(
            request,
            List.copyOf(events),
            latestState.deepCopy());
        assertThat(result.events("RUN_ACCEPTED")).hasSize(1);
        assertThat(result.events("RUN_STATE_CHANGED").stream()
            .map(event -> event.path("payload").path("state").asText())
            .toList()).contains(expectedTerminalState);
        return result;
      }
      pauseBriefly();
    }
    String observed = latestState == null
        ? "unavailable"
        : latestState.path("run").path("state").asText("missing");
    throw new AssertionError(
        "Validation run did not reach a terminal state within "
            + RUN_TIMEOUT.toSeconds() + " seconds; observed=" + observed);
  }

  JsonNode awaitRunState(
      StartRequest request,
      String expectedState,
      long minimumCompletedTick
  ) {
    long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
    JsonNode latest = null;
    while (System.nanoTime() < deadline) {
      latest = state(request.runId(), remainingRequestTimeout(deadline));
      assertCoherentGeneration(latest, request.generation());
      JsonNode run = latest.path("run");
      if (run.isObject()
          && expectedState.equals(run.path("state").asText())
          && run.path("lastCompletedTickSequence").asLong() >= minimumCompletedTick) {
        return latest;
      }
      if (run.path("terminal").asBoolean()) {
        throw new AssertionError(
            "Validation run reached " + run.path("state").asText()
                + " before expected state " + expectedState);
      }
      pauseBriefly();
    }
    String observed = latest == null
        ? "unavailable"
        : latest.path("run").path("state").asText("missing");
    throw new AssertionError(
        "Validation run did not reach " + expectedState + "; observed=" + observed);
  }

  JsonNode awaitCompletedTick(StartRequest request, long minimumCompletedTick) {
    long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
    JsonNode latest = null;
    while (System.nanoTime() < deadline) {
      latest = state(request.runId(), remainingRequestTimeout(deadline));
      assertCoherentGeneration(latest, request.generation());
      JsonNode run = latest.path("run");
      if (run.isObject()
          && !run.path("terminal").asBoolean()
          && run.path("lastCompletedTickSequence").asLong() >= minimumCompletedTick) {
        return latest;
      }
      if (run.path("terminal").asBoolean()) {
        throw new AssertionError(
            "Validation run terminated before Tick " + minimumCompletedTick);
      }
      pauseBriefly();
    }
    String observed = latest == null
        ? "unavailable"
        : latest.path("run").path("lastCompletedTickSequence").asText("missing");
    throw new AssertionError(
        "Validation run did not complete Tick " + minimumCompletedTick
            + "; observed=" + observed);
  }

  JsonNode pause(UUID runId) {
    JsonNode receipt = exchange(
        "POST", "/internal/validation/runs/" + runId + "/pause", null);
    assertThat(receipt.path("runId").asText()).isEqualTo(runId.toString());
    assertThat(receipt.path("control").asText()).isEqualTo("PAUSE_REQUESTED");
    return receipt;
  }

  JsonNode resume(UUID runId) {
    JsonNode receipt = exchange(
        "POST", "/internal/validation/runs/" + runId + "/resume", null);
    assertThat(receipt.path("runId").asText()).isEqualTo(runId.toString());
    assertThat(receipt.path("control").asText()).isEqualTo("RESUME_REQUESTED");
    return receipt;
  }

  JsonNode cancel(UUID runId) {
    JsonNode receipt = exchange(
        "POST", "/internal/validation/runs/" + runId + "/cancel", null);
    assertThat(receipt.path("runId").asText()).isEqualTo(runId.toString());
    assertThat(receipt.path("control").asText()).isEqualTo("CANCEL_REQUESTED");
    return receipt;
  }

  WireResponse rejectedStart(StartRequest request) {
    WireResponse response = rawExchange("POST", "/internal/validation/runs", request);
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.envelope().path("success").asBoolean()).isFalse();
    assertThat(response.envelope().path("data").isNull()).isTrue();
    assertThat(response.code()).isEqualTo("VALIDATION_GENERATION_FENCED");
    return response;
  }

  WireResponse rejectedEvents(UUID runId) {
    WireResponse response = rawExchange(
        "GET",
        "/internal/validation/runs/" + runId
            + "/events?afterSequence=0&limit=200",
        null);
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.envelope().path("success").asBoolean()).isFalse();
    assertThat(response.envelope().path("data").isNull()).isTrue();
    assertThat(response.code()).isEqualTo("VALIDATION_RUN_NOT_FOUND");
    return response;
  }

  WireResponse rejectedStaleReset(long staleGeneration) {
    UUID runId = runId("stale-reset", staleGeneration);
    UUID operationId = UUID.nameUUIDFromBytes(
        (runId + ":initial-reset").getBytes(StandardCharsets.UTF_8));
    WireResponse response = rawExchange(
        "POST",
        "/internal/validation/reset",
        null,
        Map.of(
            "X-Validation-Run-Id", runId.toString(),
            "X-Validation-Reset-Operation-Id", operationId.toString(),
            "X-Validation-Reset-Mode", "INITIAL",
            "X-Validation-Expected-Generation", Long.toString(staleGeneration)));
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.envelope().path("success").asBoolean()).isFalse();
    assertThat(response.code()).isEqualTo("VALIDATION_RESET_STALE_GENERATION");
    return response;
  }

  JsonNode observeState(UUID runId) {
    return state(runId);
  }

  void assertSanitized(JsonNode evidence) {
    assertThat(evidence.toString().contains(internalToken)).isFalse();
    assertNoSensitiveKeys(evidence);
    assertNoSensitiveValues(evidence);
  }

  private JsonNode state(UUID runId) {
    return state(runId, REQUEST_TIMEOUT);
  }

  private JsonNode state(UUID runId, Duration requestTimeout) {
    String suffix = runId == null ? "" : "?runId=" + runId;
    return exchange("GET", "/internal/validation/state" + suffix, null, requestTimeout);
  }

  private JsonNode exchange(String method, String path, Object body) {
    return exchange(method, path, body, REQUEST_TIMEOUT);
  }

  private JsonNode exchange(
      String method,
      String path,
      Object body,
      Duration requestTimeout
  ) {
    WireResponse response = rawExchange(method, path, body, Map.of(), requestTimeout);
    assertThat(response.status())
        .as("%s %s HTTP status", method, path)
        .isEqualTo(200);
    JsonNode envelope = response.envelope();
    assertThat(envelope.path("success").asBoolean()).isTrue();
    assertThat(envelope.path("code").asText()).isEqualTo("OK");
    JsonNode data = envelope.get("data");
    assertThat(data).isNotNull();
    assertThat(data.isNull()).isFalse();
    return data;
  }

  private WireResponse rawExchange(String method, String path, Object body) {
    return rawExchange(method, path, body, Map.of());
  }

  private WireResponse rawExchange(
      String method,
      String path,
      Object body,
      Map<String, String> extraHeaders
  ) {
    return rawExchange(method, path, body, extraHeaders, REQUEST_TIMEOUT);
  }

  private WireResponse rawExchange(
      String method,
      String path,
      Object body,
      Map<String, String> extraHeaders,
      Duration requestTimeout
  ) {
    if (!path.startsWith("/internal/validation")
        || path.contains("://")
        || path.contains("\\")) {
      throw new IllegalArgumentException("Validation HTTP path must remain fixed and internal");
    }
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(RESET_REQUEST_TIMEOUT) > 0) {
      throw new IllegalArgumentException("Validation HTTP timeout is outside the fixed bound");
    }
    long requestDeadline = System.nanoTime() + requestTimeout.toNanos();
    HttpRequest.Builder builder = HttpRequest.newBuilder(BASE_URI.resolve(path))
        .timeout(requestTimeout)
        .header("Accept", "application/json")
        .header(INTERNAL_TOKEN_HEADER, internalToken);
    extraHeaders.forEach((name, value) -> {
      if (!Set.of(
          "X-Validation-Run-Id",
          "X-Validation-Reset-Operation-Id",
          "X-Validation-Reset-Mode",
          "X-Validation-Expected-Generation").contains(name)) {
        throw new IllegalArgumentException("Unsupported validation test header");
      }
      builder.header(name, value);
    });
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      byte[] encodedBody = json(body).getBytes(StandardCharsets.UTF_8);
      assertThat(encodedBody.length).isLessThanOrEqualTo(MAX_REQUEST_BYTES);
      builder.method(method, HttpRequest.BodyPublishers.ofByteArray(encodedBody));
    }

    ValidationBoundedHttpExchange.Result response;
    try {
      long remainingNanos = requestDeadline - System.nanoTime();
      if (remainingNanos <= 0L) {
        throw new AssertionError("Validation HTTP response exceeded request deadline");
      }
      response = ValidationBoundedHttpExchange.send(
          client,
          builder.build(),
          Duration.ofNanos(remainingNanos),
          MAX_RESPONSE_BYTES);
    } catch (HttpTimeoutException exception) {
      throw new AssertionError(
          "Validation HTTP response exceeded request deadline",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Validation HTTP request was interrupted", exception);
    } catch (IOException exception) {
      throw new AssertionError("Validation HTTP endpoint is unavailable", exception);
    }
    assertThat(response.bodyTooLarge())
        .as("%s %s HTTP response exceeds %s bytes", method, path, MAX_RESPONSE_BYTES)
        .isFalse();
    byte[] encoded = response.body();
    assertThat(encoded.length).isLessThanOrEqualTo(MAX_RESPONSE_BYTES);
    String contentType = response.headers()
        .firstValue("Content-Type")
        .orElseThrow(() -> new AssertionError("Validation HTTP response has no Content-Type"));
    assertThat(contentType).matches(
        "(?i)application/json(?:\\s*;\\s*charset=utf-8)?");

    JsonNode envelope;
    try {
      envelope = JSON.readTree(encoded);
    } catch (JsonProcessingException invalidJson) {
      throw new AssertionError("Validation HTTP response is not one JSON document");
    } catch (IOException invalidJson) {
      throw new AssertionError("Validation HTTP response is not one JSON document");
    }
    assertThat(envelope.isObject()).isTrue();
    ArrayList<String> fields = new ArrayList<>();
    envelope.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder(
        "success", "code", "message", "data", "timestamp");
    assertThat(envelope.path("success").isBoolean()).isTrue();
    assertThat(envelope.path("code").isTextual()).isTrue();
    assertThat(envelope.path("message").isTextual()).isTrue();
    assertThat(envelope.has("data")).isTrue();
    assertThat(envelope.path("timestamp").isTextual()).isTrue();
    try {
      Instant.parse(envelope.path("timestamp").textValue());
    } catch (java.time.format.DateTimeParseException invalidTimestamp) {
      throw new AssertionError("Validation HTTP response timestamp is invalid");
    }
    return new WireResponse(response.statusCode(), envelope);
  }

  private static void assertCoherentGeneration(JsonNode state, long generation) {
    assertThat(state.path("memoryResetState").asText()).isEqualTo("READY");
    assertThat(state.path("durableResetState").asText()).isEqualTo("READY");
    assertThat(state.path("memoryGeneration").asLong()).isEqualTo(generation);
    assertThat(state.path("durableGeneration").asLong()).isEqualTo(generation);
    assertThat(state.path("redisGeneration").asLong()).isEqualTo(generation);
    assertThat(state.path("observedRedisGeneration").asLong()).isEqualTo(generation);
    assertThat(state.path("durableMemoryGeneration").asLong()).isEqualTo(generation);
    assertThat(state.path("generationCoherent").asBoolean()).isTrue();
  }

  private static void assertNoSensitiveKeys(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> {
        String canonical = entry.getKey()
            .toLowerCase(java.util.Locale.ROOT)
            .replace("-", "")
            .replace("_", "");
        assertThat(Set.of(
            "authorization",
            "cookie",
            "password",
            "secret",
            "token",
            "credential",
            "apikey").stream().noneMatch(canonical::contains)).isTrue();
        assertNoSensitiveKeys(entry.getValue());
      });
      return;
    }
    if (node.isArray()) {
      node.forEach(ValidationHttpIntegrationSupport::assertNoSensitiveKeys);
    }
  }

  private static void assertNoSensitiveValues(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isTextual()) {
      String value = node.textValue().toLowerCase(java.util.Locale.ROOT);
      assertThat(value).doesNotContain("bearer ");
      assertThat(value).doesNotMatch(
          ".*(?:password|secret|token|cookie|credential|api[-_]?key)\\s*[=:]\\s*\\S+.*");
      return;
    }
    if (node.isContainerNode()) {
      node.forEach(ValidationHttpIntegrationSupport::assertNoSensitiveValues);
    }
  }

  private static long requiredPositiveLong(JsonNode object, String field) {
    long value = requiredNonNegativeLong(object, field);
    assertThat(value).isPositive();
    return value;
  }

  private static long requiredNonNegativeLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isIntegralNumber()).as(field).isTrue();
    assertThat(value.canConvertToLong()).as(field).isTrue();
    long result = value.asLong();
    assertThat(result).as(field).isNotNegative();
    return result;
  }

  private static void pauseBriefly() {
    try {
      Thread.sleep(25L);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Validation HTTP polling was interrupted", exception);
    }
  }

  private static Duration remainingRequestTimeout(long deadline) {
    long remainingNanos = deadline - System.nanoTime();
    if (remainingNanos <= 0L) {
      throw new AssertionError("Validation HTTP polling deadline expired");
    }
    return Duration.ofNanos(Math.min(REQUEST_TIMEOUT.toNanos(), remainingNanos));
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Validation request is not serializable", exception);
    }
  }

  static StartRequest resetProbe(long generation) {
    String caseId = "reset-probe";
    UUID runId = runId(caseId, generation);
    return request(
        caseId,
        runId,
        generation,
        AccountSettings.defaults(),
        List.of(tick(
            caseId,
            runId,
            generation,
            1L,
            List.of(spot(BTC_SPOT, decimal("50000"), 1L)),
            List.of(),
            List.of())),
        List.of());
  }

  static StartRequest spotReplay(long generation) {
    String caseId = "spot-replay";
    UUID runId = runId(caseId, generation);
    PublicAction buy = action(
        runId,
        "spot-buy",
        1L,
        0L,
        marketOrder(
            BTC_SPOT,
            "BUY",
            decimal("500.00000000"),
            "BOTH",
            "CASH",
            List.of()),
        null);
    PublicAction sell = action(
        runId,
        "spot-sell",
        2L,
        0L,
        marketOrder(
            BTC_SPOT,
            "SELL",
            decimal("0.00500000"),
            "BOTH",
            "CASH",
            List.of()),
        null);
    return request(
        caseId,
        runId,
        generation,
        AccountSettings.defaults(),
        List.of(
            tick(
                caseId,
                runId,
                generation,
                1L,
                List.of(spot(BTC_SPOT, decimal("50000"), 1L)),
                List.of(),
                List.of()),
            tick(
                caseId,
                runId,
                generation,
                2L,
                List.of(spot(BTC_SPOT, decimal("51000"), 2L)),
                List.of(),
                List.of())),
        List.of(buy, sell));
  }

  static StartRequest isolatedPerpetualReplay(long generation) {
    String caseId = "isolated-perpetual";
    UUID runId = runId(caseId, generation);
    PublicAction buy = action(
        runId,
        "isolated-open",
        1L,
        0L,
        marketOrder(
            BTC_PERPETUAL,
            "BUY",
            decimal("0.02000000"),
            "LONG",
            "ISOLATED",
            List.of()),
        null);
    PublicAction reduce = action(
        runId,
        "isolated-reduce",
        2L,
        0L,
        marketOrder(
            BTC_PERPETUAL,
            "SELL",
            decimal("0.01000000"),
            "LONG",
            "ISOLATED",
            List.of(),
            true),
        null);
    PublicAction close = action(
        runId,
        "isolated-close",
        3L,
        0L,
        marketOrder(
            BTC_PERPETUAL,
            "SELL",
            decimal("0.01000000"),
            "LONG",
            "ISOLATED",
            List.of(),
            true),
        null);
    return request(
        caseId,
        runId,
        generation,
        new AccountSettings("HEDGE", "ISOLATED", 10, "BASE"),
        List.of(
            tick(
                caseId,
                runId,
                generation,
                1L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("50000"), 1L)),
                List.of()),
            tick(
                caseId,
                runId,
                generation,
                2L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("50100"), 2L)),
                List.of()),
            tick(
                caseId,
                runId,
                generation,
                3L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("50200"), 3L)),
                List.of()),
            tick(
                caseId,
                runId,
                generation,
                4L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("50300"), 4L)),
                List.of()),
            tick(
                caseId,
                runId,
                generation,
                5L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("50400"), 5L)),
                List.of())),
        List.of(buy, reduce, close),
        decimal("0.250000"));
  }

  static StartRequest crossMultiSymbolReplay(long generation) {
    String caseId = "cross-multi-symbol";
    UUID runId = runId(caseId, generation);
    PublicAction bitcoinLong = action(
        runId,
        "btc-long",
        1L,
        0L,
        marketOrder(
            BTC_PERPETUAL,
            "BUY",
            decimal("0.01000000"),
            "LONG",
            "CROSS",
            List.of()),
        null);
    PublicAction etherShort = action(
        runId,
        "eth-short",
        1L,
        1L,
        marketOrder(
            ETH_PERPETUAL,
            "SELL",
            decimal("0.10000000"),
            "SHORT",
            "CROSS",
            List.of()),
        null);
    return request(
        caseId,
        runId,
        generation,
        new AccountSettings("HEDGE", "CROSS", 10, "BASE"),
        List.of(
            crossTick(caseId, runId, generation, 1L, "50000", "3000"),
            crossTick(caseId, runId, generation, 2L, "50100", "3010"),
            crossTick(caseId, runId, generation, 3L, "50200", "3020"),
            crossTick(caseId, runId, generation, 4L, "50300", "3030")),
        List.of(bitcoinLong, etherShort),
        decimal("0.250000"));
  }

  static StartRequest protectionReplay(long generation) {
    String caseId = "protection-replay";
    UUID runId = runId(caseId, generation);
    Map<String, Object> stopLoss = Map.of(
        "protectionType", "STOP_LOSS",
        "triggerPrice", decimal("49000.00000000"),
        "triggerPriceType", "MARK_PRICE",
        "triggerExecutionType", "MARKET");
    PublicAction protectedLong = action(
        runId,
        "protected-long",
        1L,
        0L,
        marketOrder(
            BTC_PERPETUAL,
            "BUY",
            decimal("0.01000000"),
            "LONG",
            "CROSS",
            List.of(stopLoss)),
        null);
    return request(
        caseId,
        runId,
        generation,
        new AccountSettings("HEDGE", "CROSS", 10, "BASE"),
        List.of(
            tick(
                caseId,
                runId,
                generation,
                1L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("50000"), 1L)),
                List.of()),
            tick(
                caseId,
                runId,
                generation,
                2L,
                List.of(),
                List.of(perpetual(BTC_PERPETUAL, decimal("48000"), 2L)),
                List.of())),
        List.of(protectedLong));
  }

  static StartRequest fundingReplay(long generation) {
    String caseId = "funding-replay";
    UUID runId = runId(caseId, generation);
    PublicAction longPosition = action(
        runId,
        "funded-long",
        1L,
        0L,
        marketOrder(
            BTC_PERPETUAL,
            "BUY",
            decimal("0.01000000"),
            "LONG",
            "CROSS",
            List.of()),
        null);
    return request(
        caseId,
        runId,
        generation,
        new AccountSettings("HEDGE", "CROSS", 10, "BASE"),
        List.of(tick(
            caseId,
            runId,
            generation,
            1L,
            List.of(),
            List.of(perpetual(BTC_PERPETUAL, decimal("50000"), 1L)),
            List.of(new FundingRatePoint(BTC_PERPETUAL, FUNDING_RATE)))),
        List.of(longPosition));
  }

  static StartRequest negativeReplay(long generation) {
    return negativeRun(
        "negative-replay",
        generation,
        decimal("0.00000000"),
        new ExpectedHttpError(400, "VALIDATION_ERROR"));
  }

  static StartRequest negativeWithoutExpectation(long generation) {
    return negativeRun(
        "negative-no-expectation",
        generation,
        decimal("0.00000000"),
        null);
  }

  static StartRequest negativeMismatch(long generation) {
    return negativeRun(
        "negative-mismatch",
        generation,
        decimal("0.00000000"),
        new ExpectedHttpError(409, "INSUFFICIENT_BALANCE"));
  }

  static StartRequest negativeStatusMismatch(long generation) {
    return negativeRun(
        "negative-status-mismatch",
        generation,
        decimal("0.00000000"),
        new ExpectedHttpError(409, "VALIDATION_ERROR"));
  }

  static StartRequest negativeCodeMismatch(long generation) {
    return negativeRun(
        "negative-code-mismatch",
        generation,
        decimal("0.00000000"),
        new ExpectedHttpError(400, "INSUFFICIENT_BALANCE"));
  }

  static StartRequest negativeUnexpectedSuccess(long generation) {
    return negativeRun(
        "negative-unexpected-success",
        generation,
        decimal("500.00000000"),
        new ExpectedHttpError(400, "VALIDATION_ERROR"));
  }

  private static StartRequest negativeRun(
      String caseId,
      long generation,
      BigDecimal quantity,
      ExpectedHttpError expectedError
  ) {
    UUID runId = runId(caseId, generation);
    PublicAction rejected = action(
        runId,
        "negative-action",
        1L,
        0L,
        marketOrder(
            BTC_SPOT,
            "BUY",
            quantity,
            "BOTH",
            "CASH",
            List.of()),
        expectedError);
    return request(
        caseId,
        runId,
        generation,
        AccountSettings.defaults(),
        List.of(tick(
            caseId,
            runId,
            generation,
            1L,
            List.of(spot(BTC_SPOT, decimal("50000"), 1L)),
            List.of(),
            List.of())),
        List.of(rejected));
  }

  private static StartRequest request(
      String caseId,
      UUID runId,
      long generation,
      AccountSettings accountSettings,
      List<CompositeTick> ticks,
      List<PublicAction> actions
  ) {
    return request(
        caseId,
        runId,
        generation,
        accountSettings,
        ticks,
        actions,
        decimal("1000000.000000"));
  }

  private static StartRequest request(
      String caseId,
      UUID runId,
      long generation,
      AccountSettings accountSettings,
      List<CompositeTick> ticks,
      List<PublicAction> actions,
      BigDecimal speedMultiplier
  ) {
    return new StartRequest(
        runId,
        generation,
        "http-" + caseId + "-generation-" + generation,
        "seed-" + caseId,
        VIRTUAL_START,
        DemoExecutionPolicy.defaults(),
        Map.of("USDT", decimal("100000.00000000")),
        accountSettings,
        ticks,
        actions,
        speedMultiplier);
  }

  private static CompositeTick crossTick(
      String caseId,
      UUID runId,
      long generation,
      long sequence,
      String bitcoinPrice,
      String etherPrice
  ) {
    return tick(
        caseId,
        runId,
        generation,
        sequence,
        List.of(),
        List.of(
            perpetual(BTC_PERPETUAL, decimal(bitcoinPrice), sequence),
            perpetual(ETH_PERPETUAL, decimal(etherPrice), sequence)),
        List.of());
  }

  private static CompositeTick tick(
      String caseId,
      UUID runId,
      long generation,
      long sequence,
      List<SpotMarketBundle> spots,
      List<PerpetualMarketBundle> perpetuals,
      List<FundingRatePoint> fundingRates
  ) {
    return new CompositeTick(
        runId,
        generation,
        sequence,
        VIRTUAL_START.plusSeconds(sequence),
        "http-" + caseId + "-tick-" + sequence,
        spots,
        perpetuals,
        fundingRates);
  }

  private static SpotMarketBundle spot(String symbol, BigDecimal price, long sequence) {
    Instant time = VIRTUAL_START.plusSeconds(sequence);
    Instant expiresAt = time.plusSeconds(60L);
    BigDecimal bid = price.subtract(BigDecimal.ONE);
    BigDecimal ask = price.add(BigDecimal.ONE);
    return new SpotMarketBundle(
        symbol,
        symbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        bid,
        ask,
        price,
        depth(symbol, bid, ask, time, expiresAt),
        List.of(trade(symbol, price, sequence, time, expiresAt)),
        List.of(candle(symbol, price, time, expiresAt)),
        time,
        expiresAt);
  }

  private static PerpetualMarketBundle perpetual(
      String symbol,
      BigDecimal price,
      long sequence
  ) {
    Instant time = VIRTUAL_START.plusSeconds(sequence);
    Instant expiresAt = time.plusSeconds(60L);
    BigDecimal bid = price.subtract(BigDecimal.ONE);
    BigDecimal ask = price.add(BigDecimal.ONE);
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        bid,
        ask,
        price,
        price,
        price,
        depth(symbol, bid, ask, time, expiresAt),
        List.of(trade(symbol, price, sequence, time, expiresAt)),
        List.of(candle(symbol, price, time, expiresAt)),
        time,
        expiresAt);
  }

  private static MarketDepthResponse depth(
      String symbol,
      BigDecimal bid,
      BigDecimal ask,
      Instant time,
      Instant expiresAt
  ) {
    return new MarketDepthResponse(
        symbol,
        time.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(bid, decimal("10.00000000"))),
        List.of(new MarketDepthLevelResponse(ask, decimal("10.00000000"))),
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        time,
        expiresAt,
        false);
  }

  private static RecentTradeResponse trade(
      String symbol,
      BigDecimal price,
      long sequence,
      Instant time,
      Instant expiresAt
  ) {
    return new RecentTradeResponse(
        symbol + "-trade-" + sequence,
        symbol,
        price,
        decimal("1.00000000"),
        "BUY",
        time.toEpochMilli(),
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        time,
        expiresAt,
        false);
  }

  private static CandleResponse candle(
      String symbol,
      BigDecimal price,
      Instant time,
      Instant expiresAt
  ) {
    return new CandleResponse(
        time.toEpochMilli(),
        price,
        price.add(BigDecimal.ONE),
        price.subtract(BigDecimal.ONE),
        price,
        decimal("100.00000000"),
        "validation",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        time,
        expiresAt,
        false);
  }

  private static PublicAction action(
      UUID runId,
      String name,
      long tickSequence,
      long sequence,
      Map<String, Object> payload,
      ExpectedHttpError expected
  ) {
    return new PublicAction(
        UUID.nameUUIDFromBytes((runId + ":" + name).getBytes(StandardCharsets.UTF_8)),
        tickSequence,
        sequence,
        PublicActionType.PLACE_ORDER,
        "http-action-" + name,
        payload,
        expected);
  }

  private static Map<String, Object> marketOrder(
      String symbol,
      String side,
      BigDecimal quantity,
      String positionSide,
      String marginMode,
      List<Map<String, Object>> attachedProtections
  ) {
    return marketOrder(
        symbol,
        side,
        quantity,
        positionSide,
        marginMode,
        attachedProtections,
        false);
  }

  private static Map<String, Object> marketOrder(
      String symbol,
      String side,
      BigDecimal quantity,
      String positionSide,
      String marginMode,
      List<Map<String, Object>> attachedProtections,
      boolean reduceOnly
  ) {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("symbol", symbol);
    payload.put("side", side);
    payload.put("orderType", "MARKET");
    payload.put("quantity", quantity);
    boolean perpetual = symbol.endsWith("-PERP");
    payload.put("leverage", perpetual ? 10 : 1);
    payload.put("positionSide", positionSide);
    payload.put("quantityUnit", perpetual || "SELL".equals(side) ? "BASE" : "QUOTE");
    payload.put("marginMode", marginMode);
    payload.put("reduceOnly", reduceOnly);
    payload.put("attachedProtections", attachedProtections);
    payload.put("timeInForce", "GTC");
    payload.put("postOnly", false);
    return payload;
  }

  private static UUID runId(String caseId, long generation) {
    return UUID.nameUUIDFromBytes(
        ("validation-http:" + caseId + ":" + generation).getBytes(StandardCharsets.UTF_8));
  }

  static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  static BigDecimal decimalField(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isNumber() || value.isTextual()).as(field).isTrue();
    return value.isNumber()
        ? value.decimalValue()
        : new BigDecimal(value.textValue());
  }

  record ResetResult(long generation, JsonNode state) {

    ResetResult {
      state = state.deepCopy();
    }
  }

  record RunResult(StartRequest request, List<JsonNode> allEvents, JsonNode controlState) {

    RunResult {
      allEvents = List.copyOf(allEvents);
      controlState = controlState.deepCopy();
    }

    List<JsonNode> events(String type) {
      return allEvents.stream()
          .filter(event -> type.equals(event.path("type").asText()))
          .toList();
    }

    List<JsonNode> apiTraces(String operation) {
      return events("API_TRACE").stream()
          .filter(event -> operation.equals(event.path("payload").path("operation").asText()))
          .toList();
    }

    JsonNode finalBusinessState() {
      List<JsonNode> snapshots = events("STATE_SNAPSHOT");
      assertThat(snapshots).isNotEmpty();
      JsonNode state = snapshots.getLast().path("payload").path("state");
      assertThat(state.isObject()).isTrue();
      return state;
    }

    List<JsonNode> items(String collection) {
      return stateItems(finalBusinessState(), collection);
    }

    List<JsonNode> businessStates() {
      return events("STATE_SNAPSHOT").stream()
          .map(event -> event.path("payload").path("state"))
          .filter(JsonNode::isObject)
          .toList();
    }

    String terminalState() {
      return controlState.path("run").path("state").asText();
    }

    String failureCode() {
      return controlState.path("run").path("failureCode").asText();
    }
  }

  static List<JsonNode> stateItems(JsonNode businessState, String collection) {
    JsonNode collectionNode = businessState.path(collection);
    JsonNode values;
    if (collectionNode.isArray()) {
      values = collectionNode;
    } else {
      assertThat(collectionNode.isObject()).as(collection).isTrue();
      assertThat(collectionNode.path("complete").asBoolean()).as(collection).isTrue();
      assertThat(requiredNonNegativeLong(collectionNode, "page")).isZero();
      long size = requiredPositiveLong(collectionNode, "size");
      long total = requiredNonNegativeLong(collectionNode, "total");
      long totalPages = requiredNonNegativeLong(collectionNode, "totalPages");
      long expectedPages = total == 0L ? 0L : 1L + (total - 1L) / size;
      assertThat(totalPages).as(collection + ".totalPages").isEqualTo(expectedPages);
      values = collectionNode.path("items");
      assertThat(values.isArray()).as(collection + ".items").isTrue();
      assertThat((long) values.size()).as(collection + ".total").isEqualTo(total);
    }
    assertThat(values.isArray()).as(collection).isTrue();
    ArrayList<JsonNode> items = new ArrayList<>();
    values.forEach(item -> items.add(item));
    return List.copyOf(items);
  }

  record WireResponse(int status, JsonNode envelope) {

    WireResponse {
      envelope = envelope.deepCopy();
    }

    String code() {
      return envelope.path("code").asText();
    }
  }
}
