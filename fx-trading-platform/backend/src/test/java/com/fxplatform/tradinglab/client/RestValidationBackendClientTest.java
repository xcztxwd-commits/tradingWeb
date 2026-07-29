package com.fxplatform.tradinglab.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizerTestFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestValidationBackendClientTest {

  private static final String TOKEN =
      "task7-validation-internal-token-0123456789-ABCDEFG";
  private static final int TRACE_LIMIT = 1_048_576;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void callsOnlyTypedFixedRoutesWithOneTokenAndPreservesEventPageMetadata() {
    UUID runId = UUID.randomUUID();
    UUID resetOperationId = UUID.randomUUID();
    Instant virtualTime = Instant.parse("2026-07-23T01:02:03Z");
    server.createContext("/", exchange -> {
      capture(exchange);
      String path = exchange.getRequestURI().getPath();
      Object data;
      if ("/internal/validation/reset".equals(path)) {
        data = Map.of(
            "status", "SUCCEEDED",
            "databaseName", "fx_validation_task7",
            "redisGeneration", 1,
            "memoryGeneration", 1,
            "startedAt", virtualTime.toString(),
            "finishedAt", virtualTime.plusSeconds(1).toString(),
            "steps", List.of(),
            "errorCode", "");
      } else if ("/internal/validation/runs".equals(path)) {
        data = Map.of(
            "runId", runId,
            "requestFingerprint", "fp-task7",
            "state", "ACCEPTED");
      } else if ("/internal/validation/state".equals(path)) {
        data = Map.ofEntries(
            Map.entry("memoryResetState", "READY"),
            Map.entry("memoryGeneration", 41),
            Map.entry("durableResetState", "READY"),
            Map.entry("durableGeneration", 41),
            Map.entry("redisGeneration", 41),
            Map.entry("observedRedisGeneration", 41),
            Map.entry("durableMemoryGeneration", 41),
            Map.entry("generationCoherent", true),
            Map.entry("run", Map.ofEntries(
                Map.entry("runId", runId),
                Map.entry("generation", 41),
                Map.entry("requestFingerprint", "fp-task7"),
                Map.entry("state", "RUNNING"),
                Map.entry("pauseRequested", true),
                Map.entry("cancelRequested", false),
                Map.entry("lastCompletedTickSequence", 7),
                Map.entry("virtualTime", virtualTime),
                Map.entry("eventHighWatermark", 12),
                Map.entry("failureCode", ""),
                Map.entry("updatedAt", virtualTime),
                Map.entry("terminal", false))),
            Map.entry("clock", Map.of(
                "runId", runId,
                "generation", 41,
                "sequence", 7,
                "virtualTime", virtualTime)),
            Map.entry("marketTickSequence", 7),
            Map.entry("executionPolicyFrozen", true));
      } else if (("/internal/validation/runs/" + runId + "/events").equals(path)) {
        data = Map.of(
            "events", List.of(Map.of(
                "runId", runId,
                "sequence", 12,
                "durableKey", "tick:12",
                "fingerprint", "event-fp-12",
                "type", "MARKET_TICK",
                "virtualTime", virtualTime.toString(),
                "correlationId", "correlation-12",
                "payload", Map.of("symbol", "BTCUSDT", "price", "65000.00"))),
            "highWatermark", 19,
            "terminal", false,
            "hasMore", true);
      } else if (("/internal/validation/runs/" + runId + "/pause").equals(path)) {
        data = control(runId, "PAUSE_REQUESTED", "RUNNING");
      } else if (("/internal/validation/runs/" + runId + "/resume").equals(path)) {
        data = control(runId, "RESUME_REQUESTED", "ACCEPTED");
      } else if (("/internal/validation/runs/" + runId + "/cancel").equals(path)) {
        data = control(runId, "CANCEL_REQUESTED", "CANCELLING");
      } else {
        throw new AssertionError("Unexpected path " + path);
      }
      json(exchange, 200, envelope(true, "OK", data));
    });

    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);
    ValidationResetRequest resetRequest = new ValidationResetRequest(
        runId,
        resetOperationId,
        ValidationResetRequest.Mode.INITIAL,
        0L);
    ValidationRunStartRequest startRequest = startRequest(runId, virtualTime);

    ValidationBackendExchange<ValidationResetReceipt> reset = client.reset(resetRequest);
    ValidationBackendExchange<ValidationRunAccepted> accepted = client.startRun(startRequest);
    ValidationBackendExchange<ValidationRunStateObservation> state = client.state(runId);
    ValidationBackendExchange<ValidationEventPage> events = client.eventsAfter(runId, 11L);
    ValidationBackendExchange<ValidationControlReceipt> paused = client.pause(runId);
    ValidationBackendExchange<ValidationControlReceipt> resumed = client.resume(runId);
    ValidationBackendExchange<ValidationControlReceipt> cancelled = client.cancel(runId);

    assertThat(List.of(reset, accepted, state, events, paused, resumed, cancelled))
        .allSatisfy(result -> {
          assertThat(result.succeeded()).isTrue();
          assertThat(result.result().status()).isEqualTo(200);
          assertThat(result.reportTrace()).isNotNull();
          assertThat(result.result().toSafeMap().toString()).doesNotContain(TOKEN);
          assertThat(result.reportTrace().toSafeMap().toString()).doesNotContain(TOKEN);
        });
    assertThat(state.data().run().pauseRequested()).isTrue();
    assertThat(state.data().run().requestFingerprint()).isEqualTo("fp-task7");
    assertThat(state.data().run().terminal()).isFalse();
    assertThat(events.data().events()).hasSize(1);
    assertThat(events.data().events().getFirst().toSafeMap())
        .containsEntry("durableKey", "tick:12")
        .containsEntry("sequence", 12L);
    assertThat(events.data().highWatermark()).isEqualTo(19L);
    assertThat(events.data().hasMore()).isTrue();
    assertThat(paused.data().control()).isEqualTo("PAUSE_REQUESTED");
    assertThat(resumed.data().state()).isEqualTo(ValidationRunState.ACCEPTED);
    assertThat(cancelled.data().state()).isEqualTo(ValidationRunState.CANCELLING);

    assertThat(requests).hasSize(7).allSatisfy(request -> {
      assertThat(request.tokenValues()).containsExactly(TOKEN);
      assertThat(request.uri().getHost()).isNull();
    });
    CapturedRequest resetCall = requests.getFirst();
    assertThat(resetCall.method()).isEqualTo("POST");
    assertThat(resetCall.uri().getPath()).isEqualTo("/internal/validation/reset");
    assertThat(resetCall.header("X-Validation-Run-Id")).containsExactly(runId.toString());
    assertThat(resetCall.header("X-Validation-Reset-Operation-Id"))
        .containsExactly(resetOperationId.toString());
    assertThat(resetCall.header("X-Validation-Reset-Mode")).containsExactly("INITIAL");
    assertThat(resetCall.header("X-Validation-Expected-Generation")).containsExactly("0");
    assertThat(requests.get(1).method()).isEqualTo("POST");
    assertThat(requests.get(1).body()).contains("\"requestFingerprint\":\"fp-task7\"");
    assertThat(requests.get(2).method()).isEqualTo("GET");
    assertThat(requests.get(3).uri().getRawQuery())
        .isEqualTo("afterSequence=11&limit=1");
  }

  @Test
  void exactStartRetryReusesTheSameBoundedBodyBytes() {
    UUID runId = UUID.randomUUID();
    List<byte[]> bodies = new ArrayList<>();
    server.createContext("/internal/validation/runs", exchange -> {
      bodies.add(exchange.getRequestBody().readAllBytes());
      json(exchange, 200, envelope(true, "OK", Map.of(
          "runId", runId,
          "requestFingerprint", "fp-task7",
          "state", "ACCEPTED")));
    });
    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);
    ValidationRunStartRequest request =
        startRequest(runId, Instant.parse("2026-07-23T01:02:03Z"));

    assertThat(client.startRun(request).succeeded()).isTrue();
    assertThat(client.startRun(request).succeeded()).isTrue();

    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(1)).containsExactly(bodies.getFirst());
  }

  @Test
  void nonZeroInitialResetSendsTheExactGenerationFenceAndAcceptsOnlyItsSuccessor() {
    UUID runId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-23T01:02:03Z");
    server.createContext("/internal/validation/reset", exchange -> {
      capture(exchange);
      json(exchange, 200, envelope(true, "OK", Map.of(
          "status", "SUCCEEDED",
          "databaseName", "fx_validation_task7",
          "redisGeneration", 42,
          "memoryGeneration", 42,
          "startedAt", now.toString(),
          "finishedAt", now.plusSeconds(1).toString(),
          "steps", List.of(),
          "errorCode", "")));
    });
    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);

    ValidationBackendExchange<ValidationResetReceipt> exchange = client.reset(
        new ValidationResetRequest(
            runId,
            operationId,
            ValidationResetRequest.Mode.INITIAL,
            41L));

    assertThat(exchange.succeeded()).isTrue();
    assertThat(exchange.data().memoryGeneration()).isEqualTo(42L);
    assertThat(requests).singleElement().satisfies(request -> {
      assertThat(request.header("X-Validation-Run-Id")).containsExactly(runId.toString());
      assertThat(request.header("X-Validation-Reset-Operation-Id"))
          .containsExactly(operationId.toString());
      assertThat(request.header("X-Validation-Reset-Mode")).containsExactly("INITIAL");
      assertThat(request.header("X-Validation-Expected-Generation"))
          .containsExactly("41");
    });
  }

  @Test
  void parsesFailedResetReceiptWithNullSkippedStepTimesAsFailureValue() {
    UUID runId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-23T01:02:03Z");
    server.createContext("/internal/validation/reset", exchange -> {
      Map<String, Object> failedStep = new java.util.LinkedHashMap<>();
      failedStep.put("step", "DATABASE");
      failedStep.put("status", "FAILED");
      failedStep.put("startedAt", now.toString());
      failedStep.put("finishedAt", now.plusMillis(1).toString());
      failedStep.put("errorCode", "DATABASE_RESET_FAILED");
      Map<String, Object> skippedStep = new java.util.LinkedHashMap<>();
      skippedStep.put("step", "REDIS");
      skippedStep.put("status", "SKIPPED");
      skippedStep.put("startedAt", null);
      skippedStep.put("finishedAt", null);
      skippedStep.put("errorCode", null);
      Map<String, Object> receipt = new java.util.LinkedHashMap<>();
      receipt.put("status", "FAILED");
      receipt.put("databaseName", "fx_validation_task7");
      receipt.put("redisGeneration", null);
      receipt.put("memoryGeneration", null);
      receipt.put("startedAt", now.toString());
      receipt.put("finishedAt", now.plusMillis(2).toString());
      receipt.put("steps", List.of(failedStep, skippedStep));
      receipt.put("errorCode", "DATABASE_RESET_FAILED");
      json(exchange, 200, envelope(true, "OK", receipt));
    });
    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);

    ValidationBackendExchange<ValidationResetReceipt> exchange = client.reset(
        new ValidationResetRequest(
            runId,
            operationId,
            ValidationResetRequest.Mode.INITIAL,
            0L));

    assertThat(exchange.succeeded()).isTrue();
    assertThat(exchange.data().status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
    assertThat(exchange.data().errorCode()).isEqualTo("DATABASE_RESET_FAILED");
    assertThat(exchange.data().steps().get(1).status())
        .isEqualTo(ValidationResetReceipt.StepStatus.SKIPPED);
    assertThat(exchange.data().steps().get(1).startedAt()).isNull();
  }

  @Test
  void non2xxAndTransportFailureReturnSealedEvidenceWithoutTokenLeakage()
      throws IOException {
    UUID runId = UUID.randomUUID();
    server.createContext("/", exchange -> json(exchange, 401, envelope(
        false,
        "VALIDATION_INTERNAL_UNAUTHORIZED",
        null,
        "rejected " + TOKEN)));
    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);

    ValidationBackendExchange<ValidationRunStateObservation> unauthorized = client.state(runId);

    assertThat(unauthorized.succeeded()).isFalse();
    assertThat(unauthorized.data()).isNull();
    assertThat(unauthorized.result().status()).isEqualTo(401);
    assertThat(unauthorized.result().exception().code())
        .isEqualTo("VALIDATION_INTERNAL_UNAUTHORIZED");
    assertThat(unauthorized.result().toSafeMap().toString()).doesNotContain(TOKEN);
    assertThat(unauthorized.reportTrace().toSafeMap().toString()).doesNotContain(TOKEN);

    int closedPort = server.getAddress().getPort();
    server.stop(0);
    server = null;
    RestValidationBackendClient disconnected = client(
        URI.create("http://127.0.0.1:" + closedPort),
        64 * 1024,
        64 * 1024,
        Duration.ofMillis(100));

    ValidationBackendExchange<ValidationRunStateObservation> transport =
        disconnected.state(runId);

    assertThat(transport.succeeded()).isFalse();
    assertThat(transport.result().status()).isZero();
    assertThat(transport.result().exception().type()).isEqualTo("TRANSPORT");
    assertThat(transport.reportTrace()).isNotNull();
    assertThat(transport.result().toSafeMap().toString()).doesNotContain(TOKEN);
    assertThat(transport.reportTrace().toSafeMap().toString()).doesNotContain(TOKEN);
  }

  @Test
  void dynamicSessionSecretCannotBecomeDurableTraceOrCorrelationIdentity()
      throws IOException {
    UUID runId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-23T01:02:03Z");
    String dynamicSecret =
        "task7-dynamic-session-secret-0123456789-ABCDEFG";
    server.createContext("/internal/validation/reset", exchange -> {
      byte[] bytes = objectMapper.writeValueAsBytes(envelope(true, "OK", Map.of(
          "status", "SUCCEEDED",
          "databaseName", "fx_validation_task7",
          "redisGeneration", 42,
          "memoryGeneration", 42,
          "startedAt", now.toString(),
          "finishedAt", now.plusSeconds(1).toString(),
          "steps", List.of(),
          "errorCode", "")));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set(
          "Set-Cookie",
          "VALIDATION_SESSION=" + dynamicSecret + "; HttpOnly; SameSite=Strict");
      exchange.getResponseHeaders().set("X-Request-Id", dynamicSecret);
      exchange.getResponseHeaders().set(
          "X-Validation-Correlation-Id",
          dynamicSecret);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);

    ValidationBackendExchange<ValidationResetReceipt> exchange = client.reset(
        new ValidationResetRequest(
            runId,
            operationId,
            ValidationResetRequest.Mode.INITIAL,
            41L));
    ValidationHttpResult result = exchange.result();
    String journalSafeJson = objectMapper.writeValueAsString(result.toSafeMap());
    ValidationHttpResult restored =
        ValidationHttpResult.fromSafeMap(result.toSafeMap());

    assertThat(journalSafeJson).doesNotContain(dynamicSecret);
    assertThat(objectMapper.writeValueAsString(restored.toSafeMap()))
        .doesNotContain(dynamicSecret);
    assertThat(objectMapper.writeValueAsString(exchange.reportTrace().toSafeMap()))
        .doesNotContain(dynamicSecret);
    assertThat(result.traceId())
        .isNotBlank()
        .isNotEqualTo(dynamicSecret);
    assertThat(result.correlationId()).isNotEqualTo(dynamicSecret);
  }

  @Test
  void sensitiveValueInsideAnOtherwiseValidLargeEventDtoFailsClosedEveryEvidenceSurface()
      throws IOException {
    UUID runId = UUID.randomUUID();
    Instant virtualTime = Instant.parse("2026-07-23T01:02:03Z");
    String dynamicSecret =
        "task7-event-payload-token-0123456789-ABCDEFG";
    server.createContext(
        "/internal/validation/runs/" + runId + "/events",
        exchange -> {
          byte[] bytes = objectMapper.writeValueAsBytes(envelope(true, "OK", Map.of(
              "events", List.of(Map.of(
                  "runId", runId,
                  "sequence", 1,
                  "durableKey", "event:1",
                  "fingerprint", "event-fingerprint-1",
                  "type", "SAFE_ECHO",
                  "virtualTime", virtualTime.toString(),
                  "correlationId", "event-correlation-1",
                  "payload", Map.of(
                      "token", dynamicSecret,
                      "padding", "x".repeat(300_000)))),
              "highWatermark", 1,
              "terminal", false,
              "hasMore", false)));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("X-Request-Id", "event-trace-1");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    RestValidationBackendClient client = client(64 * 1024, 512 * 1024);

    ValidationBackendExchange<ValidationEventPage> exchange =
        client.eventsAfter(runId, 0L);
    String journalSafeJson =
        objectMapper.writeValueAsString(exchange.result().toSafeMap());

    assertThat(exchange.succeeded()).isFalse();
    assertThat(exchange.data()).isNull();
    assertThat(exchange.result().exception()).isNotNull();
    assertThat(journalSafeJson).doesNotContain(dynamicSecret);
    assertThat(objectMapper.writeValueAsString(
        ValidationHttpResult.fromSafeMap(exchange.result().toSafeMap()).toSafeMap()))
        .doesNotContain(dynamicSecret);
    assertThat(objectMapper.writeValueAsString(exchange.reportTrace().toSafeMap()))
        .doesNotContain(dynamicSecret);
  }

  @Test
  void refusesRedirectsAndNeverCallsRedirectTarget() {
    AtomicInteger redirected = new AtomicInteger();
    server.createContext("/internal/validation/state", exchange -> {
      exchange.getResponseHeaders().set("Location", "/redirect-target");
      exchange.sendResponseHeaders(307, -1);
      exchange.close();
    });
    server.createContext("/redirect-target", exchange -> {
      redirected.incrementAndGet();
      json(exchange, 200, envelope(true, "OK", Map.of()));
    });

    ValidationBackendExchange<ValidationRunStateObservation> result =
        client(64 * 1024, 64 * 1024).state(UUID.randomUUID());

    assertThat(result.succeeded()).isFalse();
    assertThat(result.result().status()).isEqualTo(307);
    assertThat(redirected).hasValue(0);
  }

  @Test
  void rejectsDuplicateJsonAndOversizedBodiesWithSafeProtocolEvidence() {
    UUID runId = UUID.randomUUID();
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/", exchange -> {
      if (calls.getAndIncrement() == 0) {
        byte[] duplicate = ("{\"success\":true,\"success\":true,\"code\":\"OK\","
            + "\"message\":\"success\",\"data\":{},"
            + "\"timestamp\":\"2026-07-23T01:02:03Z\"}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, duplicate.length);
        exchange.getResponseBody().write(duplicate);
        exchange.close();
        return;
      }
      byte[] oversized = ("{\"success\":true,\"code\":\"OK\",\"message\":\"success\","
          + "\"data\":{\"padding\":\"" + "x".repeat(1024) + "\"},"
          + "\"timestamp\":\"2026-07-23T01:02:03Z\"}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, oversized.length);
      exchange.getResponseBody().write(oversized);
      exchange.close();
    });
    RestValidationBackendClient client = client(64 * 1024, 512);

    ValidationBackendExchange<ValidationRunStateObservation> duplicate = client.state(runId);
    ValidationBackendExchange<ValidationRunStateObservation> oversized = client.state(runId);

    assertThat(duplicate.succeeded()).isFalse();
    assertThat(duplicate.result().exception().type()).isEqualTo("PROTOCOL");
    assertThat(duplicate.reportTrace()).isNotNull();
    assertThat(oversized.succeeded()).isFalse();
    assertThat(oversized.result().exception().code())
        .isEqualTo("VALIDATION_HTTP_RESPONSE_TOO_LARGE");
    assertThat(oversized.reportTrace()).isNotNull();
  }

  @Test
  void stateSnapshotUsesIdentityInsteadOfASecondAcceptedBoolean() {
    UUID runId = UUID.randomUUID();
    ValidationRunStartRequest request =
        startRequest(runId, Instant.parse("2026-07-23T01:02:03Z"));
    ValidationRunStateSnapshot running = new ValidationRunStateSnapshot(
        runId,
        41L,
        "fp-task7",
        ValidationRunState.RUNNING,
        false,
        false,
        4L,
        Instant.parse("2026-07-23T01:02:07Z"),
        8L,
        null,
        Instant.parse("2026-07-23T01:02:08Z"),
        false);
    ValidationRunStateSnapshot completed = new ValidationRunStateSnapshot(
        runId,
        41L,
        "fp-task7",
        ValidationRunState.COMPLETED,
        false,
        false,
        4L,
        Instant.parse("2026-07-23T01:02:07Z"),
        9L,
        null,
        Instant.parse("2026-07-23T01:02:09Z"),
        true);

    assertThat(running.matchesAcceptedIdentity(request)).isTrue();
    assertThat(running.terminal()).isFalse();
    assertThat(completed.terminal()).isTrue();
  }

  @Test
  void coherentNullRunIsDefiniteAbsenceButAnIncoherentGenerationIsNot() {
    UUID runId = UUID.randomUUID();
    ValidationRunStartRequest request =
        startRequest(runId, Instant.parse("2026-07-23T01:02:03Z"));
    ValidationRunStateObservation absent = new ValidationRunStateObservation(
        "READY",
        41L,
        "READY",
        41L,
        41L,
        41L,
        41L,
        true,
        null,
        null,
        null,
        false);
    ValidationRunStateObservation resetting = new ValidationRunStateObservation(
        "RESETTING",
        41L,
        "RESETTING",
        41L,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        false);

    assertThat(absent.definitelyAbsent(request)).isTrue();
    assertThat(resetting.definitelyAbsent(request)).isFalse();
  }

  @Test
  void productionAdapterIsExactLoopbackAndOnlyExistsForEnabledNonValidationQueue() {
    assertThat(RestValidationBackendClient.PRODUCTION_BASE_URI)
        .isEqualTo(URI.create("http://127.0.0.1:18087"));
    assertThat(RestValidationBackendClient.class.getAnnotation(Profile.class).value())
        .containsExactly("!validation");
    ConditionalOnProperty condition =
        RestValidationBackendClient.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition.name()).containsExactly("trading-lab.queue.enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
  }

  @Test
  void journalSafeResultRoundTripsAndRebuildsACompleteSealedTrace() {
    UUID runId = UUID.randomUUID();
    server.createContext("/internal/validation/state", exchange -> json(
        exchange,
        503,
        envelope(false, "VALIDATION_TEMPORARILY_UNAVAILABLE", null)));
    RestValidationBackendClient client = client(64 * 1024, 64 * 1024);

    ValidationHttpResult sequenced = client.state(runId).result().withSequence(77L);
    ValidationHttpResult restored =
        ValidationHttpResult.fromSafeMap(sequenced.toSafeMap());
    TradingLabHttpTraceSanitizer sanitizer =
        TradingLabHttpTraceSanitizerTestFactory.create(List.of(TOKEN), TRACE_LIMIT);
    String rebuilt = restored.rebuildReportTrace(sanitizer).toSafeMap().toString();

    assertThat(restored).isEqualTo(sequenced);
    assertThat(rebuilt)
        .contains("sequence=77")
        .contains("environment=validation")
        .contains("method=GET")
        .contains("status=503")
        .contains("VALIDATION_TEMPORARILY_UNAVAILABLE")
        .doesNotContain(TOKEN);

    Map<String, Object> extra = new java.util.LinkedHashMap<>(sequenced.toSafeMap());
    extra.put("rawThrowable", TOKEN);
    assertThatThrownBy(() -> ValidationHttpResult.fromSafeMap(extra))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Validation HTTP evidence shape is invalid");
  }

  @Test
  void oversizedRequestFailsBeforeTheSocketAndStillReturnsEvidence() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/", exchange -> {
      calls.incrementAndGet();
      json(exchange, 200, envelope(true, "OK", Map.of()));
    });
    UUID runId = UUID.randomUUID();
    ValidationRunStartRequest base =
        startRequest(runId, Instant.parse("2026-07-23T01:02:03Z"));
    Map<String, Object> oversizedPolicy = new java.util.LinkedHashMap<>(
        base.executionPolicy());
    oversizedPolicy.put("padding", "x".repeat(2_048));
    ValidationRunStartRequest oversized = new ValidationRunStartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        oversizedPolicy,
        base.initialBalances(),
        base.accountSettings(),
        base.ticks(),
        base.actions(),
        base.speedMultiplier());
    RestValidationBackendClient client = client(512, 64 * 1024);

    ValidationBackendExchange<ValidationRunAccepted> result = client.startRun(oversized);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.result().status()).isZero();
    assertThat(result.result().exception().type()).isEqualTo("REQUEST");
    assertThat(result.result().exception().code())
        .isEqualTo("VALIDATION_HTTP_REQUEST_TOO_LARGE");
    assertThat(result.reportTrace()).isNotNull();
    assertThat(calls).hasValue(0);
  }

  private RestValidationBackendClient client(int requestLimit, int responseLimit) {
    return client(baseUri(), requestLimit, responseLimit, Duration.ofSeconds(2));
  }

  private RestValidationBackendClient client(
      URI baseUri,
      int requestLimit,
      int responseLimit,
      Duration readTimeout
  ) {
    TradingLabHttpTraceSanitizer sanitizer =
        TradingLabHttpTraceSanitizerTestFactory.create(List.of(TOKEN), TRACE_LIMIT);
    return new RestValidationBackendClient(
        baseUri,
        TOKEN,
        objectMapper,
        sanitizer,
        Clock.systemUTC(),
        Duration.ofSeconds(1),
        readTimeout,
        requestLimit,
        responseLimit);
  }

  private URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private ValidationRunStartRequest startRequest(UUID runId, Instant virtualTime) {
    return new ValidationRunStartRequest(
        runId,
        41L,
        "fp-task7",
        "seed-task7",
        virtualTime,
        Map.of(
            "matchingMode", "SIMPLE",
            "makerFeeRate", new BigDecimal("0.0002"),
            "takerFeeRate", new BigDecimal("0.0005"),
            "liquidationFeeRate", new BigDecimal("0.001"),
            "slippageRate", new BigDecimal("0.0001"),
            "bids", List.of(),
            "asks", List.of()),
        Map.of("USDT", new BigDecimal("50000.00000000")),
        Map.of(
            "positionMode", "ONE_WAY",
            "marginMode", "CROSS",
            "leverage", 20,
            "quantityUnit", "BASE"),
        List.of(Map.of(
            "runId", runId,
            "generation", 41L,
            "sequence", 1L,
            "virtualTime", virtualTime.plusSeconds(1),
            "fingerprint", "tick-fp-1",
            "spotBundles", List.of(),
            "perpetualBundles", List.of())),
        List.of(),
        BigDecimal.ONE.setScale(6));
  }

  private void capture(HttpExchange exchange) throws IOException {
    requests.add(new CapturedRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI(),
        Map.copyOf(exchange.getRequestHeaders()),
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private void json(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = objectMapper.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("X-Request-Id", "trace-task7");
    exchange.getResponseHeaders().set(
        "X-Validation-Correlation-Id", "correlation-task7");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static Map<String, Object> envelope(boolean success, String code, Object data) {
    return envelope(success, code, data, success ? "success" : "failure");
  }

  private static Map<String, Object> envelope(
      boolean success,
      String code,
      Object data,
      String message
  ) {
    Map<String, Object> envelope = new java.util.LinkedHashMap<>();
    envelope.put("success", success);
    envelope.put("code", code);
    envelope.put("message", message);
    envelope.put("data", data);
    envelope.put("timestamp", "2026-07-23T01:02:03Z");
    return envelope;
  }

  private static Map<String, Object> control(UUID runId, String control, String state) {
    return Map.of("runId", runId, "control", control, "state", state);
  }

  private record CapturedRequest(
      String method,
      URI uri,
      Map<String, List<String>> headers,
      String body
  ) {

    private List<String> tokenValues() {
      return header(RestValidationBackendClient.INTERNAL_TOKEN_HEADER);
    }

    private List<String> header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(List.of());
    }
  }
}
