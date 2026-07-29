package com.fxplatform.tradinglab.e2e;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabReportChunkCodec;
import com.fxplatform.tradinglab.report.TradingLabReportCleanupScheduler;
import com.fxplatform.tradinglab.report.TradingLabReportReadTicket;
import com.fxplatform.tradinglab.report.TradingLabReportRetentionService;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.report.TradingLabReportStreamer;
import com.fxplatform.tradinglab.report.TradingLabReportWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "trading-lab.queue.enabled=false",
        "trading-lab.report.chunk-bytes=65536",
        "admin.bootstrap.enabled=true",
        "admin.bootstrap.email=large-report-admin@large-report-it.test",
        "admin.bootstrap.password=LargeReportPassword123!",
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
@ActiveProfiles({"database-it", "trading-lab-large-report-it"})
@Testcontainers
class TradingLabLargeReportIT {

  private static final String ADMIN_EMAIL =
      "large-report-admin@large-report-it.test";
  private static final String ADMIN_PASSWORD = "LargeReportPassword123!";
  private static final long FIFTY_MIB = 50L * 1024L * 1024L;
  private static final long CHECKER_MAX_HEAP_DELTA = 32L * 1024L * 1024L;
  private static final long CHECKER_MAX_RSS_DELTA = 64L * 1024L * 1024L;
  private static final int LARGE_RECORD_COUNT = 30;
  private static final int LARGE_PAYLOAD_BYTES = 900_000;
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(6);

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379);

  @DynamicPropertySource
  static void isolatedRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
  }

  @LocalServerPort
  private int port;

  @Autowired private ApplicationContext applicationContext;
  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TradingLabHttpTraceSanitizer traceSanitizer;
  @Autowired private TradingLabReportChunkCodec chunkCodec;
  @Autowired private TradingLabReportRetentionService retentionService;
  @Autowired private TradingLabReportStreamer streamer;
  @Autowired private TradingLabReportWriter writer;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  private HttpClient httpClient;
  private UUID actorId;
  private UUID scenarioId;
  private String accessToken;

  @BeforeEach
  void prepareIsolatedFixture() throws Exception {
    assertThat(Arrays.stream(flyway.info().applied())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion()))
        .contains("60", "61", "62");
    assertThat(applicationContext.getBeansOfType(TradingLabReportCleanupScheduler.class))
        .as("cleanup is enabled only by the dedicated large-report IT profile")
        .hasSize(1);

    deleteTradingLabFixture();
    actorId = jdbcTemplate.queryForObject(
        "select id from auth.users where email = ?",
        UUID.class,
        ADMIN_EMAIL);
    scenarioId = insertScenario();
    httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    accessToken = api(
        "POST",
        "/api/auth/login",
        Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
        false).path("accessToken").asText();
    assertThat(accessToken).isNotBlank();
  }

  @AfterEach
  void removeIsolatedFixture() {
    deleteTradingLabFixture();
  }

  @Test
  @Timeout(value = 15, unit = MINUTES)
  void streamsARealPostgresReportLargerThanFiftyMiBThroughHttpIntoNode()
      throws Exception {
    UUID reportId = insertReport();
    String padding = "x".repeat(LARGE_PAYLOAD_BYTES);
    SafeTradingLabHttpTrace trace = largeSafeTrace(padding);

    for (int sequence = 0; sequence < LARGE_RECORD_COUNT; sequence++) {
      writer.append(reportId, TradingLabReportSection.API_TRACE, trace);
      writer.append(
          reportId,
          TradingLabReportSection.MARKET_TICKS,
          Map.of(
              "sequence", sequence,
              "symbol", "EURUSD",
              "payload", padding));
    }
    writer.complete(reportId);
    UUID runId = insertRun(reportId, "COMPLETED");

    ChunkStats apiTrace = verifyChunks(reportId, TradingLabReportSection.API_TRACE);
    ChunkStats marketTicks =
        verifyChunks(reportId, TradingLabReportSection.MARKET_TICKS);
    assertThat(apiTrace.chunkCount()).isGreaterThan(1);
    assertThat(marketTicks.chunkCount()).isGreaterThan(1);

    ReportTotals report = reportTotals(reportId);
    assertThat(report.status()).isEqualTo("COMPLETED");
    assertThat(report.uncompressedBytes()).isGreaterThan(FIFTY_MIB);
    assertThat(report.chunkCount())
        .isEqualTo(Math.addExact(apiTrace.chunkCount(), marketTicks.chunkCount()));
    assertThat(report.compressedBytes())
        .isEqualTo(Math.addExact(
            apiTrace.compressedBytes(),
            marketTicks.compressedBytes()));

    TradingLabReportReadTicket ticket = streamer.prepare(reportId);
    CountingOutputStream compactCounter = new CountingOutputStream();
    streamer.stream(ticket, compactCounter);
    assertThat(compactCounter.count())
        .isEqualTo(ticket.uncompressedBytes())
        .isEqualTo(report.uncompressedBytes());

    JsonNode detail = api(
        "GET",
        "/api/admin/trading-lab/reports/" + reportId,
        null,
        true);
    assertThat(detail.path("runId").asText()).isEqualTo(runId.toString());
    assertThat(detail.path("uncompressedBytes").asLong())
        .isEqualTo(report.uncompressedBytes());

    CheckerResult compact = streamHttpThroughChecker(
        "/api/admin/trading-lab/reports/" + reportId + "/download",
        Map.of("Accept", "application/json"),
        "application/json",
        report.uncompressedBytes());
    assertThat(compact.totalBytes()).isEqualTo(report.uncompressedBytes());

    JsonNode printInfo = api(
        "GET",
        "/api/admin/trading-lab/reports/" + reportId + "/print-info",
        null,
        true);
    assertThat(printInfo.path("requiresConfirmation").asBoolean()).isTrue();
    assertThat(printInfo.path("uncompressedBytes").asLong())
        .isEqualTo(report.uncompressedBytes());
    JsonNode confirmation = api(
        "POST",
        "/api/admin/trading-lab/reports/" + reportId + "/print-confirmation",
        null,
        true);
    String confirmationToken = confirmation.path("token").asText();
    assertThat(confirmationToken).isNotBlank();

    CheckerResult pretty = streamHttpThroughChecker(
        "/api/admin/trading-lab/reports/" + reportId + "/print",
        Map.of(
            "Accept", "text/plain",
            "X-Trading-Lab-Print-Confirmation", confirmationToken),
        "text/plain",
        FIFTY_MIB + 1L);
    assertThat(pretty.totalBytes()).isGreaterThan(report.uncompressedBytes());
    assertThat(pretty.peakHeapDeltaBytes()).isLessThan(CHECKER_MAX_HEAP_DELTA);
    assertThat(pretty.peakRssDeltaBytes()).isLessThan(CHECKER_MAX_RSS_DELTA);
    System.out.printf(
        Locale.ROOT,
        "TRADING_LAB_LARGE_REPORT_EVIDENCE "
            + "{\"reportBytes\":%d,\"reportChunks\":%d,"
            + "\"apiTraceChunks\":%d,\"marketTickChunks\":%d,"
            + "\"chunkByteLimit\":%d,"
            + "\"compactBytes\":%d,\"compactPeakHeapDeltaBytes\":%d,"
            + "\"compactPeakRssDeltaBytes\":%d,\"compactLargestInputChunkBytes\":%d,"
            + "\"prettyBytes\":%d,\"prettyPeakHeapDeltaBytes\":%d,"
            + "\"prettyPeakRssDeltaBytes\":%d,\"prettyLargestInputChunkBytes\":%d}%n",
        report.uncompressedBytes(),
        report.chunkCount(),
        apiTrace.chunkCount(),
        marketTicks.chunkCount(),
        chunkCodec.chunkBytes(),
        compact.totalBytes(),
        compact.peakHeapDeltaBytes(),
        compact.peakRssDeltaBytes(),
        compact.largestInputChunkBytes(),
        pretty.totalBytes(),
        pretty.peakHeapDeltaBytes(),
        pretty.peakRssDeltaBytes(),
        pretty.largestInputChunkBytes());
  }

  @Test
  void dedicatedCleanupProfileDeletesOnlyExpiredNonPermanentTerminalReports() {
    UUID expiredCompleted =
        insertRetentionReport("COMPLETED", true, -7_200, false);
    UUID expiredFailed =
        insertRetentionReport("FAILED", true, -3_600, false);
    UUID expiredCancelled =
        insertRetentionReport("CANCELLED", true, -1_800, false);
    UUID permanent =
        insertRetentionReport("COMPLETED", true, -3_600, true);
    UUID unexpired =
        insertRetentionReport("FAILED", true, 3_600, false);
    UUID active =
        insertRetentionReport("CANCELLED", true, -3_600, false);
    insertRun(active, "RUNNING");

    assertThat(retentionService.deleteExpiredTerminalReports(100)).isEqualTo(3);
    assertThat(existingReportIds())
        .containsExactlyInAnyOrder(permanent, unexpired, active)
        .doesNotContain(expiredCompleted, expiredFailed, expiredCancelled);
  }

  private SafeTradingLabHttpTrace largeSafeTrace(String padding) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("sequence", 0);
    requestBody.put("environment", "validation");
    requestBody.put("method", "GET");
    requestBody.put("url", "/api/validation/run");
    requestBody.put("virtualTime", null);
    requestBody.put("realTime", "2026-07-26T00:00:00Z");
    requestBody.put("sanitizedRequest", Map.of());
    Map<String, Object> responseBody = new LinkedHashMap<>();
    responseBody.put("status", 200);
    responseBody.put("duration", 1);
    responseBody.put("traceId", null);
    responseBody.put("correlationId", null);
    responseBody.put("recordedException", null);
    responseBody.put("padding", padding);
    return traceSanitizer.sanitize(new TradingLabHttpTraceInput(
        URI.create("http://validation-backend/api/validation/run"),
        Map.of(),
        Map.of(),
        "application/json",
        requestBody,
        "application/json",
        responseBody,
        null,
        null,
        List.of(),
        null));
  }

  private CheckerResult streamHttpThroughChecker(
      String path,
      Map<String, String> headers,
      String expectedContentType,
      long minBytes
  ) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
        .timeout(HTTP_TIMEOUT)
        .header("Authorization", "Bearer " + accessToken);
    headers.forEach(request::header);
    HttpResponse<InputStream> response = httpClient.send(
        request.GET().build(),
        HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .as("streaming HTTP content type")
        .startsWithIgnoringCase(expectedContentType);

    Path platformRoot = platformRoot();
    Process checker = new ProcessBuilder(
        nodeBinary(),
        platformRoot.resolve("scripts/trading-lab-report-check.mjs").toString(),
        "--schema",
        platformRoot.resolve("docs/testing/trading-lab/report-schema.json").toString(),
        "--min-bytes",
        Long.toString(minBytes),
        "--max-heap-delta-bytes",
        Long.toString(CHECKER_MAX_HEAP_DELTA),
        "--max-rss-delta-bytes",
        Long.toString(CHECKER_MAX_RSS_DELTA))
        .directory(platformRoot.toFile())
        .start();

    try {
      IOException transferFailure = null;
      long transferred = 0L;
      try (InputStream input = response.body();
           OutputStream output = checker.getOutputStream()) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
          transferred = Math.addExact(transferred, read);
        }
      } catch (IOException exception) {
        transferFailure = exception;
      }

      if (!checker.waitFor(6, MINUTES)) {
        throw new AssertionError("Node report checker did not exit within six minutes");
      }
      String stdout = new String(
          checker.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
      String stderr = new String(
          checker.getErrorStream().readAllBytes(),
          StandardCharsets.UTF_8);
      assertThat(transferFailure)
          .as("HTTP-to-Node streaming transfer; checker stderr=%s", stderr)
          .isNull();
      assertThat(checker.exitValue())
          .as("Node report checker exit; stdout=%s stderr=%s", stdout, stderr)
          .isZero();
      JsonNode result = objectMapper.readTree(stdout);
      assertThat(result.path("status").asText()).isEqualTo("PASS");
      assertThat(result.path("totalBytes").asLong()).isEqualTo(transferred);
      assertThat(result.path("base64Wrapper").asBoolean()).isFalse();
      assertThat(result.path("topLevelSections").size()).isEqualTo(14);
      assertThat(result.path("apiTraceItems").asInt()).isEqualTo(LARGE_RECORD_COUNT);
      assertThat(result.path("peakHeapDeltaBytes").asLong())
          .isLessThanOrEqualTo(CHECKER_MAX_HEAP_DELTA);
      assertThat(result.path("peakRssDeltaBytes").asLong())
          .isLessThanOrEqualTo(CHECKER_MAX_RSS_DELTA);
      assertThat(result.path("largestInputChunkBytes").asLong())
          .isLessThanOrEqualTo(64L * 1024L);
      return new CheckerResult(
          result.path("totalBytes").asLong(),
          result.path("peakHeapDeltaBytes").asLong(),
          result.path("peakRssDeltaBytes").asLong(),
          result.path("largestInputChunkBytes").asLong());
    } finally {
      if (checker.isAlive()) {
        try {
          checker.getOutputStream().close();
        } catch (IOException ignored) {
          // The checker may already have closed stdin after a fail-closed parse.
        }
        checker.destroyForcibly();
        try {
          checker.waitFor(5, SECONDS);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private JsonNode api(
      String method,
      String path,
      Object body,
      boolean authenticated
  ) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json");
    if (authenticated) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json; charset=UTF-8")
          .method(
              method,
              HttpRequest.BodyPublishers.ofByteArray(
                  objectMapper.writeValueAsBytes(body)));
    }
    HttpResponse<byte[]> response = httpClient.send(
        request.build(),
        HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode())
        .as("%s %s response=%s",
            method,
            path,
            new String(response.body(), StandardCharsets.UTF_8))
        .isEqualTo(200);
    JsonNode envelope = objectMapper.readTree(response.body());
    assertThat(envelope.path("success").asBoolean()).isTrue();
    assertThat(envelope.path("code").asText()).isEqualTo("OK");
    return envelope.path("data");
  }

  private ChunkStats verifyChunks(
      UUID reportId,
      TradingLabReportSection section
  ) {
    AtomicLong expectedSequence = new AtomicLong();
    AtomicLong rawBytes = new AtomicLong();
    AtomicLong compressedBytes = new AtomicLong();
    jdbcTemplate.query("""
        select id,
               report_id,
               section,
               sequence,
               encoding,
               uncompressed_bytes,
               compressed_bytes,
               payload,
               checksum
        from trading_lab.report_chunks
        where report_id = ? and section = ?
        order by sequence
        """,
        (RowCallbackHandler) row -> {
          TradingLabReportChunkEntity chunk = new TradingLabReportChunkEntity();
          chunk.setId(row.getObject("id", UUID.class));
          chunk.setReportId(row.getObject("report_id", UUID.class));
          chunk.setSection(row.getString("section"));
          chunk.setSequence(row.getLong("sequence"));
          chunk.setEncoding(row.getString("encoding"));
          chunk.setUncompressedBytes(row.getLong("uncompressed_bytes"));
          chunk.setCompressedBytes(row.getLong("compressed_bytes"));
          chunk.setPayload(row.getBytes("payload"));
          chunk.setChecksum(row.getString("checksum"));

          long sequence = expectedSequence.getAndIncrement();
          assertThat(chunk.getSequence()).isEqualTo(sequence);
          byte[] decoded = chunkCodec.decodeAndVerify(chunk);
          assertThat(decoded.length)
              .isEqualTo(chunk.getUncompressedBytes().intValue())
              .isLessThanOrEqualTo(chunkCodec.chunkBytes());
          rawBytes.addAndGet(decoded.length);
          compressedBytes.addAndGet(chunk.getCompressedBytes());
        },
        reportId,
        section.name());
    return new ChunkStats(
        Math.toIntExact(expectedSequence.get()),
        rawBytes.get(),
        compressedBytes.get());
  }

  private UUID insertScenario() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version)
        values (
          ?, 'Large report IT', 'DRAFT', false,
          'large-report-seed', 'large-report-model',
          '{"seed":"large-report-seed"}'::jsonb, '{}'::jsonb, ?,
          'large-report-symbols', 'large-report-code', ?, ?, 0)
        """, id, "a".repeat(64), actorId, actorId);
    return id;
  }

  private UUID insertReport() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version,
          config_snapshot_hash, code_version, metadata_json,
          retained_until, permanent, created_by, version)
        values (
          ?, ?, 'PENDING', 'large-report-model',
          ?, 'large-report-code', '{}'::jsonb,
          clock_timestamp() + interval '30 days', false, ?, 0)
        """, id, scenarioId, "b".repeat(64), actorId);
    return id;
  }

  private UUID insertRetentionReport(
      String status,
      boolean completed,
      int retainedOffsetSeconds,
      boolean permanent
  ) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version, config_snapshot_hash, code_version,
          metadata_json, uncompressed_bytes, compressed_bytes, chunk_count,
          retained_until, permanent, created_by, completed_at, version)
        values (
          ?, ?, ?, 'large-report-model', ?, 'large-report-code',
          '{}'::jsonb, 0, 0, 0,
          clock_timestamp() + (? * interval '1 second'), ?, ?,
          case when ? then clock_timestamp() - interval '2 hours' else null end, 0)
        """,
        id,
        scenarioId,
        status,
        "c".repeat(64),
        retainedOffsetSeconds,
        permanent,
        actorId,
        completed);
    return id;
  }

  private UUID insertRun(UUID reportId, String state) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, report_id,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version, created_by, version,
          finished_at)
        values (
          ?, ?, ?, ?,
          false, false,
          case when ? in ('COMPLETED', 'FAILED', 'CANCELLED') then 60 else 0 end,
          60,
          1.000000,
          case when ? in ('COMPLETED', 'FAILED', 'CANCELLED') then 60 else 0 end,
          '{}'::jsonb, '{}'::jsonb, ?,
          'large-report-model', 'large-report-symbols', 'large-report-code', ?, 0,
          case when ? in ('COMPLETED', 'FAILED', 'CANCELLED')
               then clock_timestamp() else null end)
        """,
        id,
        scenarioId,
        state,
        reportId,
        state,
        state,
        "d".repeat(64),
        actorId,
        state);
    return id;
  }

  private ReportTotals reportTotals(UUID reportId) {
    return jdbcTemplate.queryForObject("""
        select status,
               uncompressed_bytes,
               compressed_bytes,
               chunk_count
        from trading_lab.reports
        where id = ?
        """,
        (row, rowNumber) -> new ReportTotals(
            row.getString("status"),
            row.getLong("uncompressed_bytes"),
            row.getLong("compressed_bytes"),
            row.getInt("chunk_count")),
        reportId);
  }

  private List<UUID> existingReportIds() {
    return jdbcTemplate.queryForList(
        "select id from trading_lab.reports order by id",
        UUID.class);
  }

  private URI uri(String path) {
    assertThat(path).startsWith("/api/");
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private Path platformRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    for (int depth = 0; depth < 4 && candidate != null; depth++) {
      if (Files.isRegularFile(
          candidate.resolve("scripts/trading-lab-report-check.mjs"))
          && Files.isRegularFile(
              candidate.resolve("docs/testing/trading-lab/report-schema.json"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new AssertionError("Cannot locate the fx-trading-platform root");
  }

  private String nodeBinary() {
    String configured = System.getenv("TRADING_LAB_NODE_BINARY");
    return configured == null || configured.isBlank() ? "node" : configured;
  }

  private void deleteTradingLabFixture() {
    jdbcTemplate.update("delete from trading_lab.audit_events");
    jdbcTemplate.update("delete from trading_lab.report_appends");
    jdbcTemplate.update("delete from trading_lab.report_chunks");
    jdbcTemplate.update("delete from trading_lab.run_events");
    jdbcTemplate.update("delete from trading_lab.run_transitions");
    jdbcTemplate.update("delete from trading_lab.runs");
    jdbcTemplate.update("delete from trading_lab.reports");
    jdbcTemplate.update("delete from trading_lab.scenarios");
  }

  private record ChunkStats(
      int chunkCount,
      long uncompressedBytes,
      long compressedBytes
  ) {
  }

  private record ReportTotals(
      String status,
      long uncompressedBytes,
      long compressedBytes,
      int chunkCount
  ) {
  }

  private record CheckerResult(
      long totalBytes,
      long peakHeapDeltaBytes,
      long peakRssDeltaBytes,
      long largestInputChunkBytes
  ) {
  }

  private static final class CountingOutputStream extends OutputStream {

    private long count;

    @Override
    public void write(int value) {
      count = Math.addExact(count, 1L);
    }

    @Override
    public void write(byte[] value, int offset, int length) {
      count = Math.addExact(count, length);
    }

    private long count() {
      return count;
    }
  }
}
