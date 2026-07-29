package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Black-box harness for the main Trading Lab control plane.
 *
 * <p>Runtime setup and test methods deliberately have no Spring application bean injection. The
 * only application seam is a JDK {@link HttpClient} connected to the main backend's random port.
 * Testcontainers provide only the main backend's PostgreSQL 16 and Redis 7 infrastructure. The
 * fixed validation relay is externally owned and must already be healthy on
 * {@code 127.0.0.1:18087}; an unavailable relay is a hard failure, never a skipped test.</p>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.task.scheduling.enabled=true",
        "trading-lab.queue.enabled=true",
        "trading-lab.queue.poll-delay=PT0.05S",
        "trading-lab.queue.lease-duration=PT15S",
        "trading-lab.report.cleanup.enabled=false",
        "trading-lab.report.chunk-bytes=16384",
        "admin.bootstrap.enabled=true",
        "admin.bootstrap.email=phase4-http-admin@trading-lab.test",
        "admin.bootstrap.password=Phase4HttpPassword123!",
        "market.demo-quotes.enabled=false",
        "market.realtime.enabled=false",
        "market.realtime.backfill-enabled=false",
        "market.realtime.dynamic-symbols-enabled=false",
        "market.provider-instrument-sync.enabled=false",
        "market.quote-broadcast-enabled=false",
        "market.test-data.enabled=false",
        "market.test-control.enabled=false",
        "execution.mode=demo",
        "trading.pending-order-execution-enabled=false",
        "trading.protective-order-execution-enabled=false",
        "trading.funding.enabled=false",
        "trading.fx-financing.enabled=false",
        "trading.liquidation.enabled=false"
    })
@ActiveProfiles("database-it")
@ResourceLock(
    value = "trading-lab-fixed-validation-runtime",
    mode = ResourceAccessMode.READ_WRITE)
abstract class TradingLabHttpIntegrationSupport {

  private static final URI VALIDATION_RELAY =
      URI.create("http://127.0.0.1:18087/actuator/health");
  private static final String VALIDATION_SECRET_ENV = "VALIDATION_INTERNAL_SECRET";
  private static final String ADMIN_EMAIL = "phase4-http-admin@trading-lab.test";
  private static final String ADMIN_PASSWORD = "Phase4HttpPassword123!";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  // One run performs both an INITIAL and a FINAL strong reset. Each reset owns the existing
  // four-minute validation deadline because it reapplies the full Flyway history.
  private static final Duration RUN_TIMEOUT = Duration.ofMinutes(9);
  private static final int MAX_API_BYTES = 4 * 1024 * 1024;
  private static final int MAX_REPORT_BYTES = 64 * 1024 * 1024;
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("success", "code", "message", "data", "timestamp");
  private static final Set<String> REPORT_FIELDS = Set.of(
      "metadata",
      "actor",
      "environment",
      "scenario",
      "modelVersion",
      "configSnapshot",
      "localCalculation",
      "lifecycle",
      "apiTrace",
      "marketTicks",
      "checkpoints",
      "actualState",
      "errors",
      "cleanup");
  private static final Set<String> API_TRACE_FIELDS = Set.of(
      "url",
      "queryParameters",
      "requestHeaders",
      "requestContentType",
      "requestBody",
      "responseHeaders",
      "responseContentType",
      "responseBody",
      "exception",
      "authentication");
  private static final Set<String> TERMINAL_STATES =
      Set.of("COMPLETED", "CANCELLED", "FAILED");
  private static final ObjectMapper JSON = new ObjectMapper()
      .findAndRegisterModules()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final HttpClient RELAY_CLIENT = newClient();

  @LocalServerPort
  private int mainPort;

  private HttpClient mainClient;
  private String accessToken;

  @DynamicPropertySource
  static void mainInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MainInfrastructure.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", MainInfrastructure.POSTGRES::getUsername);
    registry.add("spring.datasource.password", MainInfrastructure.POSTGRES::getPassword);
    registry.add("spring.flyway.clean-disabled", () -> true);
    registry.add("spring.flyway.baseline-on-migrate", () -> false);
    registry.add("spring.data.redis.host", MainInfrastructure.REDIS::getHost);
    registry.add(
        "spring.data.redis.port",
        () -> MainInfrastructure.REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    registry.add(
        "trading-lab.validation.internal-token",
        TradingLabHttpIntegrationSupport::requiredValidationSecret);
    registry.add("trading-lab.code-version", () -> "phase4-http-it+working-tree");
  }

  @BeforeAll
  static void requireExternallyOwnedValidationRelay() {
    HttpRequest request = HttpRequest.newBuilder(VALIDATION_RELAY)
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json")
        .GET()
        .build();
    try {
      BoundedResponse response =
          sendBounded(RELAY_CLIENT, request, 64 * 1024, Duration.ofSeconds(5));
      assertThat(response.status())
          .as("fixed validation relay health HTTP status")
          .isEqualTo(200);
      assertJsonUtf8(response.contentType(), "validation relay health");
      JsonNode health = strictJson(response.body(), "validation relay health");
      assertThat(health.path("status").asText())
          .as("fixed validation relay health")
          .isEqualTo("UP");
    } catch (RuntimeException | AssertionError failure) {
      throw new AssertionError(
          "BLOCKED: external runner must provide a healthy fixed validation relay at "
              + VALIDATION_RELAY,
          failure);
    }
  }

  @BeforeEach
  void authenticateThroughMainHttp() {
    mainClient = newClient();
    JsonNode login = apiData(
        "POST",
        "/api/auth/login",
        Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
        false,
        200);
    accessToken = requiredText(login, "accessToken");
    assertThat(login.path("email").asText()).isEqualTo(ADMIN_EMAIL);
  }

  final JsonNode currentConfig() {
    JsonNode config = apiData(
        "GET",
        "/api/admin/trading-lab/config",
        null,
        true,
        200);
    assertThat(config.path("configSnapshot").isObject()).isTrue();
    assertThat(requiredText(config, "configSnapshotHash")).matches("[0-9a-f]{64}");
    assertThat(requiredText(config, "modelVersion")).isNotBlank();
    return config.deepCopy();
  }

  final JsonNode createScenario(TradingLabScenarioWriteRequest request) {
    JsonNode scenario = apiData(
        "POST",
        "/api/admin/trading-lab/scenarios",
        request,
        true,
        200);
    requiredUuid(scenario, "id");
    assertThat(scenario.path("status").asText()).isEqualTo("DRAFT");
    assertThat(requiredNonNegativeLong(scenario, "version")).isZero();
    assertThat(scenario.path("configSnapshotHash").asText())
        .isEqualTo(request.configSnapshotHash());
    return scenario.deepCopy();
  }

  final RunView startRun(
      UUID scenarioId,
      TradingLabRunCreateRequest request
  ) {
    JsonNode run = apiData(
        "POST",
        "/api/admin/trading-lab/scenarios/" + scenarioId + "/runs",
        request,
        true,
        200);
    RunView accepted = runView(run);
    assertThat(accepted.scenarioId()).isEqualTo(scenarioId);
    assertThat(accepted.reportId()).isNotNull();
    assertThat(accepted.state()).isEqualTo("QUEUED");
    assertThat(accepted.totalTicks()).isPositive();
    return accepted;
  }

  final RunView run(UUID runId) {
    return runView(apiData(
        "GET",
        "/api/admin/trading-lab/runs/" + runId,
        null,
        true,
        200));
  }

  final RunView awaitRun(
      UUID runId,
      Predicate<RunView> condition,
      Duration timeout,
      String description
  ) {
    long deadline = System.nanoTime() + timeout.toNanos();
    RunView latest = null;
    while (System.nanoTime() < deadline) {
      latest = run(runId);
      if (condition.test(latest)) {
        return latest;
      }
      if (TERMINAL_STATES.contains(latest.state())) {
        throw new AssertionError(
            "Trading Lab run reached terminal state " + latest.state()
                + " before " + description
                + "; processedTicks=" + latest.processedTicks()
                + ", totalTicks=" + latest.totalTicks());
      }
      pause(Duration.ofMillis(75));
    }
    throw new AssertionError(
        "Trading Lab run did not reach " + description + " within "
            + timeout.toSeconds() + " seconds; latest=" + latest);
  }

  final RunView awaitTerminal(UUID runId) {
    return awaitTerminal(runId, RUN_TIMEOUT);
  }

  final RunView awaitTerminal(UUID runId, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    RunView latest = null;
    while (System.nanoTime() < deadline) {
      latest = run(runId);
      if (TERMINAL_STATES.contains(latest.state())) {
        return latest;
      }
      pause(Duration.ofMillis(75));
    }
    throw new AssertionError(
        "Trading Lab run did not terminate within " + timeout.toSeconds()
            + " seconds; latest=" + latest);
  }

  final JsonNode pauseRun(UUID runId) {
    JsonNode control = control(runId, "pause");
    assertThat(control.path("pauseRequested").asBoolean()).isTrue();
    return control;
  }

  final JsonNode resumeRun(UUID runId) {
    JsonNode control = control(runId, "resume");
    assertThat(control.path("pauseRequested").asBoolean()).isFalse();
    return control;
  }

  final JsonNode cancelRun(UUID runId) {
    JsonNode control = control(runId, "cancel");
    assertThat(control.path("cancelRequested").asBoolean()).isTrue();
    return control;
  }

  final void cancelAndRetainIfActive(UUID runId) {
    RunView current = run(runId);
    if (!TERMINAL_STATES.contains(current.state())
        && !"CLEANING".equals(current.state())) {
      MainResponse response = api(
          "POST",
          "/api/admin/trading-lab/runs/" + runId + "/cancel",
          null,
          true);
      if (response.status() != 200 && response.status() != 409) {
        throw new AssertionError(
            "Best-effort Trading Lab cancellation returned HTTP " + response.status()
                + ": " + response.envelope());
      }
    }
    awaitTerminal(runId);
  }

  final RawReport downloadAndValidate(UUID runId) {
    RunView terminal = run(runId);
    assertThat(terminal.state()).isIn(TERMINAL_STATES);
    UUID reportId = terminal.reportId();
    JsonNode detail = apiData(
        "GET",
        "/api/admin/trading-lab/reports/" + reportId,
        null,
        true,
        200);
    assertThat(requiredUuid(detail, "id")).isEqualTo(reportId);
    assertThat(requiredUuid(detail, "runId")).isEqualTo(runId);
    assertThat(requiredNonNegativeLong(detail, "uncompressedBytes")).isPositive();

    HttpRequest request = request(
        "GET",
        "/api/admin/trading-lab/reports/" + reportId + "/download",
        null,
        true);
    BoundedResponse response =
        sendBounded(mainClient, request, MAX_REPORT_BYTES, REQUEST_TIMEOUT);
    assertThat(response.status()).as("report download HTTP status").isEqualTo(200);
    assertJsonUtf8(response.contentType(), "Trading Lab report");
    byte[] bytes = response.body();
    assertThat(bytes).isNotEmpty();
    assertThat(bytes[0]).as("report starts at the JSON object").isEqualTo((byte) '{');
    assertThat(bytes[bytes.length - 1])
        .as("report ends cleanly at EOF without trailing bytes")
        .isEqualTo((byte) '}');
    String text = strictUtf8(bytes, "Trading Lab report");
    JsonNode root = strictJson(text, "Trading Lab report");
    assertThat(root.isObject()).isTrue();
    assertThat(fieldSet(root)).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
    assertThat(fieldSet(root)).hasSize(14);
    assertThat(root.path("apiTrace").isArray()).isTrue();
    root.path("apiTrace").forEach(trace -> {
      assertThat(trace.isObject()).isTrue();
      assertThat(fieldSet(trace))
          .containsExactlyInAnyOrderElementsOf(API_TRACE_FIELDS);
    });
    return new RawReport(runId, reportId, bytes, root);
  }

  final JsonNode reportDetail(UUID reportId) {
    JsonNode detail = apiData(
        "GET",
        "/api/admin/trading-lab/reports/" + reportId,
        null,
        true,
        200);
    assertThat(requiredUuid(detail, "id")).isEqualTo(reportId);
    requiredNonNegativeLong(detail, "uncompressedBytes");
    requiredNonNegativeLong(detail, "compressedBytes");
    requiredNonNegativeLong(detail, "chunkCount");
    return detail.deepCopy();
  }

  final ReportDownload openReportDownload(UUID reportId) {
    return openReportDownload(reportId, REQUEST_TIMEOUT);
  }

  final ReportDownload openReportDownload(UUID reportId, Duration timeout) {
    HttpRequest request = request(
        "GET",
        "/api/admin/trading-lab/reports/" + reportId + "/download",
        null,
        true,
        timeout);
    try {
      HttpResponse<InputStream> response =
          mainClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        try (InputStream body = response.body()) {
          byte[] error = readToCleanEof(body, 64 * 1024);
          throw new AssertionError(
              "Report download returned HTTP " + response.statusCode() + ": "
                  + strictUtf8(error, "report download error"));
        }
      }
      assertJsonUtf8(
          response.headers().firstValue("Content-Type").orElse(null),
          "Trading Lab report");
      return new ReportDownload(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Report download was interrupted", exception);
    } catch (IOException exception) {
      throw new AssertionError("Report download is unavailable", exception);
    }
  }

  final <T> T queryMainDatabase(MainDatabaseQuery<T> query) throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MainInfrastructure.POSTGRES.getJdbcUrl(),
        MainInfrastructure.POSTGRES.getUsername(),
        MainInfrastructure.POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      return query.execute(connection);
    }
  }

  final JsonNode control(UUID runId, String action) {
    assertThat(action).isIn("pause", "resume", "cancel");
    JsonNode result = apiData(
        "POST",
        "/api/admin/trading-lab/runs/" + runId + "/" + action,
        null,
        true,
        200);
    assertThat(requiredUuid(result, "runId")).isEqualTo(runId);
    requiredNonNegativeLong(result, "runVersion");
    return result;
  }

  static List<JsonNode> stateItems(JsonNode businessState, String collection) {
    JsonNode collectionNode = businessState.path(collection);
    JsonNode items;
    if (collectionNode.isArray()) {
      items = collectionNode;
    } else {
      assertThat(collectionNode.isObject()).as(collection).isTrue();
      assertThat(collectionNode.path("complete").asBoolean()).as(collection).isTrue();
      items = collectionNode.path("items");
      assertThat(items.isArray()).as(collection + ".items").isTrue();
      assertThat(requiredNonNegativeLong(collectionNode, "total"))
          .as(collection + ".total")
          .isEqualTo(items.size());
    }
    ArrayList<JsonNode> values = new ArrayList<>();
    items.forEach(item -> values.add(item.deepCopy()));
    return List.copyOf(values);
  }

  static BigDecimal decimal(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isNumber() || value.isTextual()).as(field).isTrue();
    return value.isNumber()
        ? value.decimalValue()
        : new BigDecimal(value.textValue());
  }

  static JsonNode item(
      List<JsonNode> items,
      Predicate<JsonNode> predicate,
      String description
  ) {
    return items.stream()
        .filter(predicate)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + description));
  }

  static List<String> apiTraceUrls(JsonNode report) {
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    report.path("apiTrace").forEach(trace -> {
      addText(urls, trace.path("url"));
      addText(urls, trace.path("requestBody").path("url"));
    });
    return List.copyOf(urls);
  }

  static boolean terminal(String state) {
    return TERMINAL_STATES.contains(state);
  }

  private JsonNode apiData(
      String method,
      String path,
      Object body,
      boolean authenticated,
      int expectedStatus
  ) {
    MainResponse response = api(method, path, body, authenticated);
    assertThat(response.status())
        .as("%s %s HTTP status; envelope=%s", method, path, response.envelope())
        .isEqualTo(expectedStatus);
    assertThat(response.envelope().path("success").asBoolean()).isTrue();
    assertThat(response.envelope().path("code").asText()).isEqualTo("OK");
    JsonNode data = response.envelope().get("data");
    assertThat(data).as(method + " " + path + " data").isNotNull();
    assertThat(data.isNull()).as(method + " " + path + " data").isFalse();
    return data.deepCopy();
  }

  private MainResponse api(
      String method,
      String path,
      Object body,
      boolean authenticated
  ) {
    HttpRequest request = request(method, path, body, authenticated);
    BoundedResponse response =
        sendBounded(mainClient, request, MAX_API_BYTES, REQUEST_TIMEOUT);
    assertJsonUtf8(response.contentType(), method + " " + path);
    JsonNode envelope = strictJson(response.body(), method + " " + path);
    assertThat(envelope.isObject()).isTrue();
    assertThat(fieldSet(envelope))
        .containsExactlyInAnyOrderElementsOf(ENVELOPE_FIELDS);
    return new MainResponse(response.status(), envelope);
  }

  private HttpRequest request(
      String method,
      String path,
      Object body,
      boolean authenticated
  ) {
    return request(method, path, body, authenticated, REQUEST_TIMEOUT);
  }

  private HttpRequest request(
      String method,
      String path,
      Object body,
      boolean authenticated,
      Duration timeout
  ) {
    if (!path.startsWith("/api/")
        || path.contains("://")
        || path.contains("\\")
        || path.contains("..")) {
      throw new IllegalArgumentException("Main HTTP path is outside the fixed API boundary");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Main HTTP timeout must be positive");
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + mainPort + path))
        .timeout(timeout)
        .header("Accept", "application/json");
    if (authenticated) {
      assertThat(accessToken).as("main Admin access token").isNotBlank();
      builder.header("Authorization", "Bearer " + accessToken);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      byte[] encoded = encode(body);
      assertThat(encoded.length).isLessThanOrEqualTo(MAX_API_BYTES);
      builder.header("Content-Type", "application/json; charset=UTF-8");
      builder.method(method, HttpRequest.BodyPublishers.ofByteArray(encoded));
    }
    return builder.build();
  }

  private static RunView runView(JsonNode run) {
    return new RunView(
        requiredUuid(run, "id"),
        requiredUuid(run, "scenarioId"),
        requiredUuid(run, "reportId"),
        requiredText(run, "state"),
        run.path("pauseRequested").asBoolean(),
        run.path("cancelRequested").asBoolean(),
        requiredNonNegativeLong(run, "processedTicks"),
        requiredPositiveLong(run, "totalTicks"),
        requiredNonNegativeLong(run, "currentStep"),
        run.path("failureCode").isNull() ? null : run.path("failureCode").asText(),
        requiredNonNegativeLong(run, "version"));
  }

  private static BoundedResponse sendBounded(
      HttpClient client,
      HttpRequest request,
      int maxBytes,
      Duration timeout
  ) {
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream input = response.body()) {
        body = readToCleanEof(input, maxBytes);
      }
      return new BoundedResponse(
          response.statusCode(),
          response.headers().firstValue("Content-Type").orElse(null),
          body);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("HTTP request was interrupted", exception);
    } catch (IOException exception) {
      throw new AssertionError(
          "HTTP endpoint is unavailable within " + timeout.toSeconds() + " seconds",
          exception);
    }
  }

  private static byte[] readToCleanEof(InputStream input, int maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      if (total > maxBytes - read) {
        throw new AssertionError("HTTP response exceeded the fixed " + maxBytes + "-byte bound");
      }
      output.write(buffer, 0, read);
      total += read;
    }
    return output.toByteArray();
  }

  private static byte[] encode(Object body) {
    try {
      return JSON.writeValueAsBytes(body);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("HTTP request DTO is not JSON serializable", exception);
    }
  }

  private static JsonNode strictJson(byte[] bytes, String description) {
    return strictJson(strictUtf8(bytes, description), description);
  }

  private static JsonNode strictJson(String text, String description) {
    try {
      JsonNode value = JSON.readTree(text);
      if (value == null) {
        throw new AssertionError(description + " is empty");
      }
      return value;
    } catch (JsonProcessingException exception) {
      throw new AssertionError(description + " is not exactly one JSON document", exception);
    }
  }

  private static String strictUtf8(byte[] bytes, String description) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new AssertionError(description + " is not valid UTF-8", exception);
    }
  }

  private static void assertJsonUtf8(String contentType, String description) {
    assertThat(contentType).as(description + " Content-Type").isNotBlank();
    String normalized = contentType.toLowerCase(java.util.Locale.ROOT)
        .replace("\"", "")
        .replace(" ", "");
    assertThat(normalized).as(description + " Content-Type")
        .startsWith("application/json");
    if (normalized.contains("charset=")) {
      assertThat(normalized).as(description + " charset").contains("charset=utf-8");
    }
  }

  private static Set<String> fieldSet(JsonNode object) {
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    object.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private static String requiredValidationSecret() {
    String token = System.getenv(VALIDATION_SECRET_ENV);
    if (token == null
        || token.isBlank()
        || token.codePoints().anyMatch(Character::isWhitespace)
        || token.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "BLOCKED: " + VALIDATION_SECRET_ENV
              + " must match the externally owned validation runtime and contain at least "
              + "32 non-whitespace bytes");
    }
    return token;
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isTextual()).as(field).isTrue();
    assertThat(value.textValue()).as(field).isNotBlank();
    return value.textValue();
  }

  private static UUID requiredUuid(JsonNode object, String field) {
    String text = requiredText(object, field);
    try {
      UUID value = UUID.fromString(text);
      assertThat(value.toString()).as(field + " canonical UUID").isEqualTo(text);
      return value;
    } catch (IllegalArgumentException exception) {
      throw new AssertionError(field + " is not a UUID", exception);
    }
  }

  private static long requiredPositiveLong(JsonNode object, String field) {
    long value = requiredNonNegativeLong(object, field);
    assertThat(value).as(field).isPositive();
    return value;
  }

  private static long requiredNonNegativeLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as(field).isNotNull();
    assertThat(value.isIntegralNumber()).as(field).isTrue();
    assertThat(value.canConvertToLong()).as(field).isTrue();
    long result = value.longValue();
    assertThat(result).as(field).isNotNegative();
    return result;
  }

  private static void addText(Set<String> target, JsonNode value) {
    if (value.isTextual() && !value.textValue().isBlank()) {
      target.add(value.textValue());
    }
  }

  private static HttpClient newClient() {
    return HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  static void pause(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Trading Lab HTTP polling was interrupted", exception);
    }
  }

  record RunView(
      UUID id,
      UUID scenarioId,
      UUID reportId,
      String state,
      boolean pauseRequested,
      boolean cancelRequested,
      long processedTicks,
      long totalTicks,
      long currentStep,
      String failureCode,
      long version
  ) {
  }

  record RawReport(UUID runId, UUID reportId, byte[] encoded, JsonNode root) {

    RawReport {
      encoded = encoded.clone();
      root = root.deepCopy();
    }

    @Override
    public byte[] encoded() {
      return encoded.clone();
    }

    @Override
    public JsonNode root() {
      return root.deepCopy();
    }
  }

  record ReportDownload(InputStream body) implements AutoCloseable {

    ReportDownload {
      java.util.Objects.requireNonNull(body, "body");
    }

    @Override
    public void close() throws IOException {
      body.close();
    }
  }

  @FunctionalInterface
  interface MainDatabaseQuery<T> {

    T execute(Connection connection) throws Exception;
  }

  private record MainResponse(int status, JsonNode envelope) {

    private MainResponse {
      envelope = envelope.deepCopy();
    }
  }

  private record BoundedResponse(int status, String contentType, byte[] body) {

    private BoundedResponse {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  private static final class MainInfrastructure {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
      try {
        Startables.deepStart(Stream.of(POSTGRES, REDIS)).join();
      } catch (RuntimeException failure) {
        throw new ExceptionInInitializerError(new IllegalStateException(
            "BLOCKED: Docker with PostgreSQL 16 and Redis 7 is required for "
                + "Trading Lab main HTTP integration tests",
            failure));
      }
    }

    private MainInfrastructure() {
    }
  }
}
