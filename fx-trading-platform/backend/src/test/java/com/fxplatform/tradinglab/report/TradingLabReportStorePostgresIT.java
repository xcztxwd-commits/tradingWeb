package com.fxplatform.tradinglab.report;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("database-it")
@Testcontainers
class TradingLabReportStorePostgresIT {

  private static final int CHUNK_BYTES = 262_144;

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingLabReportStore store;
  @Autowired private TradingLabReportCanonicalizer canonicalizer;
  @Autowired private TradingLabReportStreamer streamer;
  @Autowired private TradingLabReportWriter writer;
  @Autowired private TradingLabFencedReportWriter fencedWriter;
  @Autowired private TradingLabReportChunkCodec codec;
  @Autowired private TradingLabHttpTraceSanitizer traceSanitizer;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  private UUID actorId;
  private UUID scenarioId;

  @BeforeEach
  void prepareFixture() {
    assertThat(Arrays.stream(flyway.info().applied())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion()))
        .contains("60", "61", "62");
    deleteFixture();
    actorId = insertUser();
    scenarioId = insertScenario();
  }

  @AfterEach
  void cleanFixture() {
    deleteFixture();
  }

  @Test
  void exactSingletonReplayDoesNotChangeRowsCountersOrVersionAndConflictPreservesWinner() {
    UUID reportId = insertReport();
    TradingLabCanonicalValue winner = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, java.util.Map.of("model", "winner"));
    TradingLabReportWriteBatch batch = batch(
        reportId,
        null,
        TradingLabReportSection.METADATA,
        0L,
        new TradingLabLogicalAppend(-1L, winner.bytes(), winner.checksum()));

    store.persist(batch);
    ReportSnapshot first = snapshot(reportId);
    store.persist(batch);

    assertThat(snapshot(reportId)).isEqualTo(first);
    assertThat(first).satisfies(snapshot -> {
      assertThat(snapshot.chunkCount()).isPositive();
      assertThat(snapshot.version()).isEqualTo(1L);
      assertThat(snapshot.status()).isEqualTo("WRITING");
    });
    assertThat(appendCount(reportId)).isOne();

    TradingLabCanonicalValue loser = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, java.util.Map.of("model", "loser"));
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.METADATA,
            0L,
            new TradingLabLogicalAppend(-1L, loser.bytes(), loser.checksum()))),
        "TRADING_LAB_REPORT_SINGLETON_CONFLICT");
    assertThat(snapshot(reportId)).isEqualTo(first);
    assertThat(appendCount(reportId)).isOne();
  }

  @Test
  void discardEvidenceAtomicallyQuarantinesAndStaysStickyAcrossFreshProbes() {
    UUID reportId = insertReport();
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "first"));
    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(0L, first.bytes(), first.checksum())));
    ReportSnapshot committed = snapshot(reportId);
    assertThat(chunkCount(reportId)).isPositive();
    assertThat(appendCount(reportId)).isOne();

    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);

    ReportSnapshot discarded = snapshot(reportId);
    assertThat(discarded.status()).isEqualTo("WRITING");
    assertThat(discarded.failureCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(discarded.failureMessage())
        .isEqualTo("Trading Lab report contains unsafe trace evidence");
    assertThat(discarded.uncompressedBytes()).isZero();
    assertThat(discarded.compressedBytes()).isZero();
    assertThat(discarded.chunkCount()).isZero();
    assertThat(discarded.version()).isEqualTo(committed.version() + 1L);
    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
    assertThat(store.nextChunkSequence(reportId, TradingLabReportSection.ERRORS)).isZero();
    assertThat(store.ensureWritable(reportId, null))
        .isEqualTo(TradingLabReportWriteState.QUARANTINED);
    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    assertThat(snapshot(reportId)).isEqualTo(discarded);

    TradingLabCanonicalValue replacement = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "replacement"));
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            0L,
            new TradingLabLogicalAppend(
                0L, replacement.bytes(), replacement.checksum()))),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(snapshot(reportId)).isEqualTo(discarded);
    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
  }

  @Test
  void explicitQuarantineMarksEvenAPristineEmptyReportAndIsIdempotent() {
    UUID reportId = insertReport();
    ReportSnapshot pristine = snapshot(reportId);

    assertThat(store.ensureWritable(reportId, null))
        .isEqualTo(TradingLabReportWriteState.WRITABLE);
    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.EMPTY);
    assertThat(snapshot(reportId)).isEqualTo(pristine);

    assertThat(store.quarantineEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    ReportSnapshot quarantined = snapshot(reportId);
    assertThat(quarantined.status()).isEqualTo("WRITING");
    assertThat(quarantined.failureCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(quarantined.failureMessage())
        .isEqualTo("Trading Lab report contains unsafe trace evidence");
    assertThat(quarantined.version()).isEqualTo(pristine.version() + 1L);

    assertThat(store.quarantineEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    assertThat(snapshot(reportId)).isEqualTo(quarantined);
  }

  @Test
  void unexpectedOpenFailureFieldsFailClosedUntilRecoveryNormalizesTheMarker() {
    UUID reportId = insertReport();
    jdbcTemplate.update("""
        update trading_lab.reports
        set failure_code = 'UNTRUSTED_OPEN_FAILURE',
            failure_message = 'must-not-be-reused'
        where id = ?
        """, reportId);

    assertCode(
        () -> store.ensureWritable(reportId, null),
        "TRADING_LAB_REPORT_CORRUPT");
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "rejected"));
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            0L,
            new TradingLabLogicalAppend(0L, value.bytes(), value.checksum()))),
        "TRADING_LAB_REPORT_CORRUPT");

    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    ReportSnapshot quarantined = snapshot(reportId);
    assertThat(quarantined.failureCode()).isEqualTo("TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(quarantined.failureMessage())
        .isEqualTo("Trading Lab report contains unsafe trace evidence");
    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
  }

  @Test
  void terminalRecoveryProbeNeverPurgesOrRewritesTheReport() {
    UUID reportId = insertReport();
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, java.util.Map.of("value", "terminal"));
    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.METADATA,
        0L,
        new TradingLabLogicalAppend(-1L, value.bytes(), value.checksum())));
    TradingLabReportMeasurement measurement = streamer.measureForClose(reportId);
    assertThat(store.finalizeReport(
        reportId,
        null,
        measurement,
        TradingLabReportOutcome.completed(),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.CLOSED);
    ReportSnapshot terminal = snapshot(reportId);
    long terminalChunks = chunkCount(reportId);
    long terminalAppends = appendCount(reportId);

    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.TERMINAL);
    assertThat(store.quarantineEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.TERMINAL);

    assertThat(snapshot(reportId)).isEqualTo(terminal);
    assertThat(chunkCount(reportId)).isEqualTo(terminalChunks);
    assertThat(appendCount(reportId)).isEqualTo(terminalAppends);
  }

  @Test
  void quarantinedReportOnlyAcceptsFixedGenericTerminalOutcomes() {
    UUID failedReportId = insertReport();
    assertThat(store.quarantineEvidence(failedReportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    TradingLabReportMeasurement failedMeasurement = streamer.measureForClose(failedReportId);
    ReportSnapshot quarantined = snapshot(failedReportId);

    assertCode(
        () -> store.finalizeReport(
            failedReportId,
            null,
            failedMeasurement,
            TradingLabReportOutcome.completed(),
            Duration.ofDays(30)),
        "TRADING_LAB_REPORT_INCOMPLETE");
    assertCode(
        () -> store.finalizeReport(
            failedReportId,
            null,
            failedMeasurement,
            TradingLabReportOutcome.failed("USER_FAILURE", "user-controlled"),
            Duration.ofDays(30)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertCode(
        () -> store.finalizeReport(
            failedReportId,
            null,
            failedMeasurement,
            TradingLabReportOutcome.cancelled("user-controlled"),
            Duration.ofDays(30)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(snapshot(failedReportId)).isEqualTo(quarantined);
    assertThat(store.finalizeReport(
        failedReportId,
        null,
        failedMeasurement,
        TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.CLOSED);

    UUID cancelledReportId = insertReport();
    assertThat(store.quarantineEvidence(cancelledReportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    TradingLabReportMeasurement cancelledMeasurement =
        streamer.measureForClose(cancelledReportId);
    assertThat(store.finalizeReport(
        cancelledReportId,
        null,
        cancelledMeasurement,
        TradingLabReportOutcome.cancelled(
            "Trading Lab report contains unsafe trace evidence"),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.CLOSED);
  }

  @Test
  void quarantineRedactsModelVersionAcrossFreshTerminalRecoveryAndReplay() throws Exception {
    for (boolean cancelled : List.of(false, true)) {
      UUID reportId = insertReport();
      String canary = "lost-model-version-secret-" + UUID.randomUUID();
      jdbcTemplate.update(
          "update trading_lab.reports set model_version = ? where id = ?",
          canary,
          reportId);

      assertThat(store.quarantineEvidence(reportId, null))
          .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
      assertThat(modelVersion(reportId)).isEqualTo("[REDACTED]");

      TradingLabChunkedReportWriter recovering = newReportWriter();
      TradingLabReportOutcome outcome;
      if (cancelled) {
        recovering.cancel(reportId, "caller-controlled reason");
        outcome = TradingLabReportOutcome.cancelled(
            "Trading Lab report contains unsafe trace evidence");
      } else {
        recovering.fail(reportId, "CALLER_FAILURE", "caller-controlled summary");
        outcome = TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence");
      }

      ByteArrayOutputStream output = new ByteArrayOutputStream();
      streamer.stream(reportId, output);
      String json = output.toString(StandardCharsets.UTF_8);
      assertThat(json)
          .contains("\"modelVersion\":\"[REDACTED]\"")
          .doesNotContain(canary);

      ReportSnapshot terminal = snapshot(reportId);
      assertThat(store.discardEvidence(reportId, null))
          .isEqualTo(TradingLabReportRecoveryState.QUARANTINED_TERMINAL);
      assertThat(store.quarantineEvidence(reportId, null))
          .isEqualTo(TradingLabReportRecoveryState.QUARANTINED_TERMINAL);
      assertThat(snapshot(reportId)).isEqualTo(terminal);
      assertThat(modelVersion(reportId)).isEqualTo("[REDACTED]");

      TradingLabReportMeasurement replayMeasurement = streamer.measureForClose(reportId);
      assertThat(store.finalizeReport(
          reportId,
          null,
          replayMeasurement,
          outcome,
          Duration.ofDays(30)))
          .isEqualTo(TradingLabReportFinalizeResult.REPLAY);

      TradingLabChunkedReportWriter replaying = newReportWriter();
      if (cancelled) {
        replaying.cancel(reportId, "caller-controlled reason");
      } else {
        replaying.fail(reportId, "CALLER_FAILURE", "caller-controlled summary");
      }
      assertThat(snapshot(reportId)).isEqualTo(terminal);
    }
  }

  @Test
  void legacyQuarantineMarkerRepairsModelVersionBeforeFreshClose() throws Exception {
    UUID reportId = insertReport();
    String canary = "legacy-quarantine-model-secret-" + UUID.randomUUID();
    jdbcTemplate.update("""
        update trading_lab.reports
        set status = 'WRITING',
            model_version = ?,
            failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE',
            failure_message = 'Trading Lab report contains unsafe trace evidence',
            version = version + 1
        where id = ?
        """, canary, reportId);
    ReportSnapshot legacy = snapshot(reportId);

    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);
    assertThat(modelVersion(reportId)).isEqualTo("[REDACTED]");
    assertThat(snapshot(reportId).version()).isEqualTo(legacy.version() + 1L);

    newReportWriter().fail(reportId, "CALLER_FAILURE", "caller-controlled summary");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamer.stream(reportId, output);
    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("\"modelVersion\":\"[REDACTED]\"")
        .doesNotContain(canary);
  }

  @Test
  void staleFenceCannotDiscardDurableEvidence() {
    UUID reportId = insertReport();
    String owner = "discard-owner";
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        runId, reportId, owner);
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "retained"));
    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(0L, value.bytes(), value.checksum())));
    ReportSnapshot committed = snapshot(reportId);
    jdbcTemplate.update(
        "update trading_lab.runs set lease_until = clock_timestamp() - interval '1 second' "
            + "where id = ?",
        runId);

    assertCode(
        () -> store.discardEvidence(reportId, fence),
        "TRADING_LAB_REPORT_FENCE_LOST");

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkCount(reportId)).isPositive();
    assertThat(appendCount(reportId)).isOne();
  }

  @Test
  void fenceLossAtTheQuarantineMutationRollsBackPayloadLedgerMarkerAndCounters() {
    UUID reportId = insertReport();
    String owner = "quarantine-owner:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        runId, reportId, owner);
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "retained"));
    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(0L, value.bytes(), value.checksum())));
    ReportSnapshot committed = snapshot(reportId);
    long committedChunks = chunkCount(reportId);
    long committedAppends = appendCount(reportId);

    jdbcTemplate.execute("""
        create function trading_lab.report_store_it_expire_quarantine_fence()
        returns trigger
        language plpgsql
        as $$
        begin
          update trading_lab.runs
          set lease_until = clock_timestamp() - interval '1 second'
          where report_id = old.report_id;
          return old;
        end
        $$
        """);
    jdbcTemplate.execute("""
        create trigger report_store_it_expire_quarantine_fence
        before delete on trading_lab.report_chunks
        for each row execute function trading_lab.report_store_it_expire_quarantine_fence()
        """);
    try {
      assertCode(
          () -> store.quarantineEvidence(reportId, fence),
          "TRADING_LAB_REPORT_FENCE_LOST");
    } finally {
      jdbcTemplate.execute("""
          drop trigger if exists report_store_it_expire_quarantine_fence
          on trading_lab.report_chunks
          """);
      jdbcTemplate.execute("""
          drop function if exists trading_lab.report_store_it_expire_quarantine_fence()
          """);
    }

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkCount(reportId)).isEqualTo(committedChunks);
    assertThat(appendCount(reportId)).isEqualTo(committedAppends);
    assertThat(store.ensureWritable(reportId, fence))
        .isEqualTo(TradingLabReportWriteState.WRITABLE);
  }

  @Test
  void conflictingBatchCannotRewriteExistingEvidence() {
    UUID reportId = insertReport();
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "first"));
    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(0L, first.bytes(), first.checksum())));
    ReportSnapshot committed = snapshot(reportId);
    assertThat(chunkCount(reportId)).isPositive();
    assertThat(appendCount(reportId)).isOne();

    TradingLabCanonicalValue conflicting = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "conflicting"));
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            0L,
            new TradingLabLogicalAppend(
                0L, conflicting.bytes(), conflicting.checksum()))),
        "TRADING_LAB_REPORT_EVENT_CONFLICT");

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkCount(reportId)).isPositive();
    assertThat(appendCount(reportId)).isOne();
  }

  @Test
  void expiredFenceCannotRewriteExistingEvidence() {
    UUID reportId = insertReport();
    String owner = "expired-owner";
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        runId, reportId, owner);
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "retained"));
    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(0L, value.bytes(), value.checksum())));
    ReportSnapshot committed = snapshot(reportId);
    jdbcTemplate.update(
        "update trading_lab.runs set lease_until = clock_timestamp() - interval '1 second' "
            + "where id = ?",
        runId);
    TradingLabCanonicalValue rejected = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "rejected"));

    assertCode(
        () -> store.persist(batch(
            reportId,
            fence,
            TradingLabReportSection.ERRORS,
            1L,
            new TradingLabLogicalAppend(1L, rejected.bytes(), rejected.checksum()))),
        "TRADING_LAB_REPORT_FENCE_LOST");

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkCount(reportId)).isPositive();
    assertThat(appendCount(reportId)).isOne();
  }

  @Test
  void conflictReplayUsesVerifiedCanonicalBytesNotGzipBinaryIdentity() {
    UUID reportId = insertReport();
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "A"));
    TradingLabReportWriteBatch batch = batch(
        reportId,
        null,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(null, value.bytes(), value.checksum()));
    store.persist(batch);
    ReportSnapshot committed = snapshot(reportId);
    byte[] original = jdbcTemplate.queryForObject("""
        select payload
        from trading_lab.report_chunks
        where report_id = ? and section = ? and sequence = 0
        """, byte[].class, reportId, TradingLabReportSection.ERRORS.name());
    byte[] alternateHeader = Arrays.copyOf(original, original.length);
    alternateHeader[4] = (byte) (alternateHeader[4] + 1);
    jdbcTemplate.update("""
        update trading_lab.report_chunks
        set payload = ?
        where report_id = ? and section = ? and sequence = 0
        """, alternateHeader, reportId, TradingLabReportSection.ERRORS.name());

    store.persist(batch);

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkCount(reportId)).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select payload
        from trading_lab.report_chunks
        where report_id = ? and section = ? and sequence = 0
        """, byte[].class, reportId, TradingLabReportSection.ERRORS.name()))
        .containsExactly(alternateHeader);

    TradingLabCanonicalValue different = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("value", "B"));
    assertThat(different.byteLength()).isEqualTo(value.byteLength());
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            0L,
            new TradingLabLogicalAppend(
                null, different.bytes(), different.checksum()))),
        "TRADING_LAB_REPORT_CHUNK_CONFLICT");
    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkCount(reportId)).isOne();
  }
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void actualWriterCodecAndPostgresChainNeverPersistsOrLogsRandomCanary(
      CapturedOutput capturedOutput
  ) throws Exception {
    UUID reportId = insertReport();
    String canary = "pg-chain-\"quoted\"-\\slash-" + UUID.randomUUID();
    String rawBearer = "BearerToken."
        + UUID.randomUUID().toString().replace("-", "");
    String jsonEscapedCanary = objectMapper.writeValueAsString(canary);
    jsonEscapedCanary = jsonEscapedCanary.substring(
        1, jsonEscapedCanary.length() - 1);
    SafeTradingLabHttpTrace safe = traceSanitizer.sanitize(
        new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            java.util.Map.of(
                "Authorization", List.of("Bearer " + rawBearer),
                "X-Validation-Internal-Token", List.of(canary),
                "X-Safe-Echo", List.of("header-" + canary)),
            java.util.Map.of(
                "X-Safe-Echo", List.of("response-header-" + canary)),
            "application/json",
            java.util.Map.of(
                "password", canary,
                "safeEcho", "request-body-" + canary),
            "application/json",
            java.util.Map.of("safeEcho", "response-body-" + canary),
            null,
            actorId,
            List.of("TRADING_LAB_EXECUTE"),
            Instant.parse("2026-07-20T04:00:00Z")));

    String sanitized = objectMapper.writeValueAsString(safe.toSafeMap());
    assertThat(sanitized)
        .contains("[REDACTED]")
        .doesNotContain(canary, jsonEscapedCanary, rawBearer);

    writer.append(reportId, TradingLabReportSection.API_TRACE, safe);
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        java.util.Map.of("safeEcho", "later-" + canary));
    writer.complete(reportId);

    List<TradingLabReportChunkEntity> persisted = jdbcTemplate.query("""
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
        where report_id = ?
        order by section, sequence
        """, (row, rowNumber) -> {
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
          return chunk;
        }, reportId);
    assertThat(persisted).isNotEmpty();
    StringBuilder canonical = new StringBuilder();
    for (TradingLabReportChunkEntity chunk : persisted) {
      assertThat(chunk.getEncoding()).isEqualTo("GZIP");
      String decoded = new String(
          codec.decodeAndVerify(chunk), StandardCharsets.UTF_8);
      assertThat(decoded).doesNotContain(canary, jsonEscapedCanary, rawBearer);
      canonical.append(decoded);
    }
    assertThat(canonical.toString())
        .contains("[REDACTED]")
        .doesNotContain(canary, jsonEscapedCanary, rawBearer);

    ByteArrayOutputStream assembledBytes = new ByteArrayOutputStream();
    streamer.stream(reportId, assembledBytes);
    String assembled = assembledBytes.toString(StandardCharsets.UTF_8);
    assertThat(assembled)
        .contains("[REDACTED]")
        .doesNotContain(canary, jsonEscapedCanary, rawBearer);
    assertThat(snapshot(reportId).status()).isEqualTo("COMPLETED");
    assertThat(capturedOutput.getAll())
        .doesNotContain(canary, jsonEscapedCanary, rawBearer);
  }

  @Test
  void actualWriterKeepsTheRealDurableTailAcrossPostgresEventReplay() {
    UUID reportId = insertReport();
    String owner = "tail-owner:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(runId, reportId, owner);
    String canary = "abc\"}\n{\"right\":\"def";
    String rawBearer = "BearerToken."
        + UUID.randomUUID().toString().replace("-", "");
    SafeTradingLabHttpTrace safe = traceSanitizer.sanitize(
        new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            java.util.Map.of(
                "Authorization", List.of("Bearer " + rawBearer),
                "X-Validation-Internal-Token", List.of(canary)),
            java.util.Map.of(),
            "application/json",
            java.util.Map.of("accessToken", canary),
            null,
            null,
            null,
            actorId,
            List.of("TRADING_LAB_EXECUTE"),
            Instant.parse("2026-07-20T04:00:00Z")));
    fencedWriter.appendEvent(fence, TradingLabReportSection.API_TRACE, 0L, safe);
    fencedWriter.appendEvent(
        fence, TradingLabReportSection.ERRORS, 0L, java.util.Map.of("old", "older"));
    fencedWriter.appendEvent(
        fence, TradingLabReportSection.ERRORS, 1L, java.util.Map.of("left", "abc"));
    fencedWriter.flush(fence);
    ReportSnapshot beforeReplay = snapshot(reportId);

    fencedWriter.appendEvent(
        fence, TradingLabReportSection.ERRORS, 0L, java.util.Map.of("old", "older"));
    fencedWriter.flush(fence);

    assertThat(snapshot(reportId)).isEqualTo(beforeReplay);
    assertThatThrownBy(() -> fencedWriter.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        2L,
        java.util.Map.of("right", "def")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);

    fencedWriter.fail(fence, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
    assertThat(snapshot(reportId).status()).isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject(
        "select failure_code from trading_lab.reports where id = ?",
        String.class,
        reportId)).isEqualTo("TRADING_LAB_REPORT_UNSAFE_TRACE");
  }


  @Test
  void fencedSourceReplayIsSemanticAndExpiredReplacementClaimCannotMutate() {
    UUID reportId = insertReport();
    String owner = "report-worker:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabCanonicalValue event = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 7));
    TradingLabReportWriteBatch batch = batch(
        reportId,
        fence,
        TradingLabReportSection.CHECKPOINTS,
        0L,
        new TradingLabLogicalAppend(7L, event.bytes(), event.checksum()));

    store.persist(batch);
    ReportSnapshot committed = snapshot(reportId);
    store.persist(batch);

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(appendCount(reportId)).isOne();

    TradingLabCanonicalValue conflict = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 8));
    assertCode(
        () -> store.persist(batch(
            reportId,
            fence,
            TradingLabReportSection.CHECKPOINTS,
            0L,
            new TradingLabLogicalAppend(7L, conflict.bytes(), conflict.checksum()))),
        "TRADING_LAB_REPORT_EVENT_CONFLICT");

    jdbcTemplate.update("""
        update trading_lab.runs
        set lease_until = clock_timestamp() - interval '1 second'
        where id = ?
        """, runId);
    assertCode(() -> store.persist(batch), "TRADING_LAB_REPORT_FENCE_LOST");
    assertThat(snapshot(reportId)).isEqualTo(committed);
  }

  @Test
  void acknowledgedEventReplayCanShareARetainedBatchWithTheNextNewEvent() {
    UUID reportId = insertReport();
    String owner = "report-worker:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 7));
    TradingLabCanonicalValue second = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 8));
    TradingLabLogicalAppend eventSeven =
        new TradingLabLogicalAppend(7L, first.bytes(), first.checksum());
    TradingLabLogicalAppend eventEight =
        new TradingLabLogicalAppend(8L, second.bytes(), second.checksum());

    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.CHECKPOINTS,
        0L,
        eventSeven));
    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.CHECKPOINTS,
        0L,
        eventSeven,
        eventEight));

    assertThat(appendCount(reportId)).isEqualTo(2L);
    assertThat(chunkSequences(reportId, TradingLabReportSection.CHECKPOINTS))
        .containsExactly(0L, 1L);
    assertThat(snapshot(reportId).version()).isEqualTo(2L);
  }

  @Test
  void fencedSemanticSequencesAllowAReplayPrefixButRejectNewRegressionOrReordering() {
    UUID reportId = insertReport();
    String owner = "report-worker:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabCanonicalValue ten = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 10));
    TradingLabCanonicalValue eleven = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 11));
    TradingLabCanonicalValue twelve = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 12));
    TradingLabLogicalAppend eventTen =
        new TradingLabLogicalAppend(10L, ten.bytes(), ten.checksum());
    TradingLabLogicalAppend eventEleven =
        new TradingLabLogicalAppend(11L, eleven.bytes(), eleven.checksum());
    TradingLabLogicalAppend eventTwelve =
        new TradingLabLogicalAppend(12L, twelve.bytes(), twelve.checksum());

    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.CHECKPOINTS,
        0L,
        eventTen));
    ReportSnapshot afterTen = snapshot(reportId);

    assertCode(
        () -> store.persist(batch(
            reportId,
            fence,
            TradingLabReportSection.CHECKPOINTS,
            1L,
            eventEleven,
            new TradingLabLogicalAppend(9L, eleven.bytes(), eleven.checksum()))),
        "TRADING_LAB_REPORT_EVENT_CONFLICT");
    assertCode(
        () -> store.persist(batch(
            reportId,
            fence,
            TradingLabReportSection.CHECKPOINTS,
            1L,
            new TradingLabLogicalAppend(9L, eleven.bytes(), eleven.checksum()))),
        "TRADING_LAB_REPORT_EVENT_CONFLICT");
    assertThat(snapshot(reportId)).isEqualTo(afterTen);
    assertThat(appendCount(reportId)).isOne();

    store.persist(batch(
        reportId,
        fence,
        TradingLabReportSection.CHECKPOINTS,
        0L,
        eventTen,
        eventTwelve));

    assertThat(appendCount(reportId)).isEqualTo(2L);
    assertThat(chunkSequences(reportId, TradingLabReportSection.CHECKPOINTS))
        .containsExactly(0L, 1L);
    assertCode(
        () -> store.persist(batch(
            reportId,
            fence,
            TradingLabReportSection.CHECKPOINTS,
            1L,
            eventTwelve,
            eventEleven)),
        "TRADING_LAB_REPORT_EVENT_CONFLICT");
    assertThat(appendCount(reportId)).isEqualTo(2L);
  }

  @Test
  void unmarkedRetainedBatchStreamsAcrossAppendAndUtf8BoundariesWithExactReplay() {
    UUID reportId = insertReport();
    byte[] prefix = "a".repeat(CHUNK_BYTES - 1).getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "€tail\n".getBytes(StandardCharsets.UTF_8);
    TradingLabReportWriteBatch retained = batch(
        reportId,
        null,
        TradingLabReportSection.LIFECYCLE,
        0L,
        new TradingLabLogicalAppend(null, prefix, sha256(prefix)),
        new TradingLabLogicalAppend(null, suffix, sha256(suffix)));

    store.persist(retained);
    ReportSnapshot committed = snapshot(reportId);
    store.persist(retained);

    assertThat(snapshot(reportId)).isEqualTo(committed);
    assertThat(chunkRawSizes(reportId, TradingLabReportSection.LIFECYCLE))
        .containsExactly((long) CHUNK_BYTES - 1L, (long) suffix.length);
    assertThat(chunkSequences(reportId, TradingLabReportSection.LIFECYCLE))
        .containsExactly(0L, 1L);
  }

  @Test
  void aGapInAnyDurableSectionFailsClosedBeforeAnotherMutation() {
    UUID reportId = insertReport();
    byte[] oversized = "x".repeat(CHUNK_BYTES + 1).getBytes(StandardCharsets.UTF_8);
    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.LIFECYCLE,
        0L,
        new TradingLabLogicalAppend(null, oversized, sha256(oversized))));
    jdbcTemplate.update("""
        delete from trading_lab.report_chunks
        where report_id = ? and section = ? and sequence = 0
        """, reportId, TradingLabReportSection.LIFECYCLE.name());
    ReportSnapshot corrupt = snapshot(reportId);
    byte[] later = "{\"later\":true}\n".getBytes(StandardCharsets.UTF_8);

    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            0L,
            new TradingLabLogicalAppend(null, later, sha256(later)))),
        "TRADING_LAB_REPORT_CORRUPT");

    assertThat(snapshot(reportId)).isEqualTo(corrupt);
    assertThat(chunkCount(reportId)).isOne();
  }

  @Test
  void fenceLossAtTheCounterMutationRollsBackChunksLedgerAndCounters() {
    UUID reportId = insertReport();
    String owner = "report-worker:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabCanonicalValue event = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 1));
    ReportSnapshot before = snapshot(reportId);
    jdbcTemplate.execute("""
        create function trading_lab.report_store_it_expire_fence()
        returns trigger
        language plpgsql
        as $$
        begin
          update trading_lab.runs
          set lease_until = clock_timestamp() - interval '1 second'
          where report_id = new.report_id;
          return new;
        end
        $$
        """);
    jdbcTemplate.execute("""
        create trigger report_store_it_expire_fence
        before insert on trading_lab.report_chunks
        for each row execute function trading_lab.report_store_it_expire_fence()
        """);
    try {
      assertCode(
          () -> store.persist(batch(
              reportId,
              fence,
              TradingLabReportSection.CHECKPOINTS,
              0L,
              new TradingLabLogicalAppend(1L, event.bytes(), event.checksum()))),
          "TRADING_LAB_REPORT_FENCE_LOST");
    } finally {
      jdbcTemplate.execute("""
          drop trigger if exists report_store_it_expire_fence
          on trading_lab.report_chunks
          """);
      jdbcTemplate.execute("""
          drop function if exists trading_lab.report_store_it_expire_fence()
          """);
    }

    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
    assertThat(snapshot(reportId)).isEqualTo(before);
  }

  @Test
  void exactTerminalReplaySurvivesAnExpiredFenceButDifferentOutcomeStillConflicts() {
    UUID reportId = insertReport();
    String owner = "report-worker:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, owner, 60);
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabReportMeasurement measurement = streamer.measureForClose(reportId);

    assertThat(store.finalizeReport(
        reportId,
        fence,
        measurement,
        TradingLabReportOutcome.completed(),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.CLOSED);
    jdbcTemplate.update("""
        update trading_lab.runs
        set lease_until = clock_timestamp() - interval '1 second'
        where id = ?
        """, runId);
    ReportSnapshot terminal = snapshot(reportId);

    assertThat(store.discardEvidence(reportId, fence))
        .isEqualTo(TradingLabReportRecoveryState.TERMINAL);
    assertThat(store.quarantineEvidence(reportId, fence))
        .isEqualTo(TradingLabReportRecoveryState.TERMINAL);
    assertThat(snapshot(reportId)).isEqualTo(terminal);

    assertThat(store.finalizeReport(
        reportId,
        fence,
        measurement,
        TradingLabReportOutcome.completed(),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.REPLAY);
    assertCode(
        () -> store.finalizeReport(
            reportId,
            fence,
            measurement,
            TradingLabReportOutcome.failed("DIFFERENT", "controlled"),
            Duration.ofDays(30)),
        "TRADING_LAB_REPORT_STATUS_CONFLICT");
  }

  @Test
  void linkedEarlyRunAllowsOnlyUnfencedFailureAndRejectedOutcomesWriteNothing() {
    UUID failedReportId = insertReport();
    insertUnleasedRun(failedReportId, "DRAFT");
    TradingLabReportMeasurement failedMeasurement = streamer.measureForClose(failedReportId);

    assertThat(store.finalizeReport(
        failedReportId,
        null,
        failedMeasurement,
        TradingLabReportOutcome.failed("VALIDATION_FAILED", "controlled"),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.CLOSED);
    assertThat(snapshot(failedReportId).status()).isEqualTo("FAILED");

    for (TradingLabReportOutcome rejected : List.of(
        TradingLabReportOutcome.completed(),
        TradingLabReportOutcome.cancelled("not started"))) {
      UUID reportId = insertReport();
      insertUnleasedRun(reportId, "QUEUED");
      TradingLabReportMeasurement measurement = streamer.measureForClose(reportId);
      ReportSnapshot before = snapshot(reportId);

      assertCode(
          () -> store.finalizeReport(
              reportId,
              null,
              measurement,
              rejected,
              Duration.ofDays(30)),
          "TRADING_LAB_REPORT_FENCE_LOST");
      assertThat(snapshot(reportId)).isEqualTo(before);
      assertThat(chunkCount(reportId)).isZero();
      assertThat(appendCount(reportId)).isZero();
    }
  }

  @Test
  void oversizedLogicalValueCreatesContiguousIndependentChunksAndExactTerminalTotals()
      throws Exception {
    UUID reportId = insertReport();
    String large = "abc".repeat(100_000);
    TradingLabCanonicalValue event = canonicalizer.canonicalize(
        TradingLabReportSection.MARKET_TICKS,
        java.util.Map.of("payload", large));
    assertThat(event.byteLength()).isGreaterThan(CHUNK_BYTES);

    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.MARKET_TICKS,
        0L,
        new TradingLabLogicalAppend(null, event.bytes(), event.checksum())));

    assertThat(chunkSequences(reportId, TradingLabReportSection.MARKET_TICKS))
        .containsExactly(0L, 1L);
    assertThat(maxRawChunkBytes(reportId)).isLessThanOrEqualTo(CHUNK_BYTES);
    TradingLabReportMeasurement measurement = streamer.measureForClose(reportId);

    assertThat(store.finalizeReport(
        reportId,
        null,
        measurement,
        TradingLabReportOutcome.completed(),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.CLOSED);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamer.stream(reportId, output);
    ReportSnapshot closed = snapshot(reportId);
    assertThat(closed.status()).isEqualTo("COMPLETED");
    assertThat(closed.uncompressedBytes()).isEqualTo((long) output.size());
    assertThat(closed.compressedBytes()).isEqualTo(measurement.compressedBytes());
    assertThat(closed.chunkCount()).isEqualTo(measurement.chunkCount());
    assertThat(Duration.between(closed.completedAt(), closed.retainedUntil()))
        .isEqualTo(Duration.ofDays(30));
    assertThat(new String(output.toByteArray(), StandardCharsets.UTF_8))
        .contains("\"marketTicks\":[{\"payload\":\"")
        .endsWith("}");

    assertThat(store.finalizeReport(
        reportId,
        null,
        measurement,
        TradingLabReportOutcome.completed(),
        Duration.ofDays(30)))
        .isEqualTo(TradingLabReportFinalizeResult.REPLAY);
    assertCode(
        () -> store.finalizeReport(
            reportId,
            null,
            measurement,
            TradingLabReportOutcome.failed("OTHER", "different"),
            Duration.ofDays(30)),
        "TRADING_LAB_REPORT_STATUS_CONFLICT");
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            0L,
            new TradingLabLogicalAppend(
                null,
                "{\"late\":true}\n".getBytes(StandardCharsets.UTF_8),
                sha256("{\"late\":true}\n".getBytes(StandardCharsets.UTF_8))))),
        "TRADING_LAB_REPORT_CLOSED");
  }

  @Test
  void laterChunkEncodingFailureRollsBackEarlierChunksLedgerAndCounters() {
    UUID reportId = insertReport();
    byte[] invalid = new byte[CHUNK_BYTES + 1];
    Arrays.fill(invalid, 0, CHUNK_BYTES, (byte) 'a');
    invalid[CHUNK_BYTES] = (byte) 0xc3;

    assertThatThrownBy(() -> store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.LIFECYCLE,
        0L,
        new TradingLabLogicalAppend(null, invalid, sha256(invalid)))))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
    assertThat(snapshot(reportId)).satisfies(snapshot -> {
      assertThat(snapshot.status()).isEqualTo("PENDING");
      assertThat(snapshot.uncompressedBytes()).isZero();
      assertThat(snapshot.compressedBytes()).isZero();
      assertThat(snapshot.chunkCount()).isZero();
      assertThat(snapshot.version()).isZero();
      assertThat(snapshot.completedAt()).isNull();
      assertThat(snapshot.retainedUntil()).isNotNull();
    });
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void concurrentDifferentChunkMeaningsProduceOneWinnerWithoutLostCounters()
      throws Exception {
    UUID reportId = insertReport();
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("winner", "first"));
    TradingLabCanonicalValue second = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("winner", "second"));
    TradingLabReportWriteBatch firstBatch = batch(
        reportId,
        null,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(null, first.bytes(), first.checksum()));
    TradingLabReportWriteBatch secondBatch = batch(
        reportId,
        null,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(null, second.bytes(), second.checksum()));
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService writers = Executors.newFixedThreadPool(2);
    try {
      Future<Attempt> a = writers.submit(() -> persistAfter(start, firstBatch));
      Future<Attempt> b = writers.submit(() -> persistAfter(start, secondBatch));
      List<Attempt> attempts = List.of(a.get(20, SECONDS), b.get(20, SECONDS));

      assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
      assertThat(attempts).extracting(Attempt::code)
          .containsExactlyInAnyOrder(null, "TRADING_LAB_REPORT_CHUNK_CONFLICT");
      assertThat(chunkCount(reportId)).isOne();
      assertThat(snapshot(reportId).version()).isEqualTo(1L);
      assertThat(snapshot(reportId).chunkCount()).isOne();
    } finally {
      writers.shutdownNow();
      assertThat(writers.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void completeVersusFailHasOneAuthoritativeTerminalOutcome() throws Exception {
    UUID reportId = insertReport();
    TradingLabReportMeasurement measurement = streamer.measureForClose(reportId);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService closers = Executors.newFixedThreadPool(2);
    try {
      Future<Attempt> completed = closers.submit(() -> finalizeAfter(
          start, reportId, measurement, TradingLabReportOutcome.completed()));
      Future<Attempt> failed = closers.submit(() -> finalizeAfter(
          start,
          reportId,
          measurement,
          TradingLabReportOutcome.failed("FAILED_PATH", "controlled")));
      List<Attempt> attempts = List.of(
          completed.get(20, SECONDS), failed.get(20, SECONDS));

      assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
      assertThat(attempts).extracting(Attempt::code)
          .containsExactlyInAnyOrder(null, "TRADING_LAB_REPORT_STATUS_CONFLICT");
      assertThat(snapshot(reportId).status()).isIn("COMPLETED", "FAILED");
      assertThat(snapshot(reportId).version()).isEqualTo(1L);
    } finally {
      closers.shutdownNow();
      assertThat(closers.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void completeVersusAppendHasOneAuthoritativeTerminalReportWithoutLostWrites()
      throws Exception {
    UUID reportId = insertReport();
    TradingLabCanonicalValue event = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, java.util.Map.of("event", "concurrent"));
    TradingLabReportWriteBatch appendBatch = batch(
        reportId,
        null,
        TradingLabReportSection.ERRORS,
        0L,
        new TradingLabLogicalAppend(1L, event.bytes(), event.checksum()));
    TradingLabReportMeasurement initialMeasurement = streamer.measureForClose(reportId);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService contenders = Executors.newFixedThreadPool(2);
    Attempt appendAttempt;
    FinalizeAttempt closeAttempt;
    try {
      Future<Attempt> append = contenders.submit(() -> persistAfter(start, appendBatch));
      Future<FinalizeAttempt> close = contenders.submit(() -> finalizeAfterResult(
          start,
          reportId,
          initialMeasurement,
          TradingLabReportOutcome.completed()));
      appendAttempt = append.get(20, SECONDS);
      closeAttempt = close.get(20, SECONDS);
    } finally {
      contenders.shutdownNow();
      assertThat(contenders.awaitTermination(10, SECONDS)).isTrue();
    }

    assertThat(closeAttempt.code()).isNull();
    assertThat(closeAttempt.result())
        .isIn(TradingLabReportFinalizeResult.CLOSED, TradingLabReportFinalizeResult.RETRY);
    if (closeAttempt.result() == TradingLabReportFinalizeResult.CLOSED) {
      assertThat(appendAttempt.success()).isFalse();
      assertThat(appendAttempt.code()).isEqualTo("TRADING_LAB_REPORT_CLOSED");
    } else {
      assertThat(appendAttempt).isEqualTo(new Attempt(true, null));
      TradingLabReportMeasurement currentMeasurement = streamer.measureForClose(reportId);
      assertThat(store.finalizeReport(
          reportId,
          null,
          currentMeasurement,
          TradingLabReportOutcome.completed(),
          Duration.ofDays(30)))
          .isEqualTo(TradingLabReportFinalizeResult.CLOSED);
    }

    boolean appendWon = appendAttempt.success();
    ReportSnapshot terminal = snapshot(reportId);
    ChunkTotals durable = chunkTotals(reportId);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamer.stream(reportId, output);
    assertThat(terminal.status()).isEqualTo("COMPLETED");
    assertThat(terminal.uncompressedBytes()).isEqualTo((long) output.size());
    assertThat(terminal.compressedBytes()).isEqualTo(durable.compressedBytes());
    assertThat(terminal.chunkCount()).isEqualTo(durable.chunkCount());
    assertThat(terminal.chunkCount()).isEqualTo(appendWon ? 1 : 0);
    assertThat(terminal.version()).isEqualTo(appendWon ? 2L : 1L);
    assertThat(appendCount(reportId)).isEqualTo(appendWon ? 1L : 0L);
    if (appendWon) {
      assertThat(chunkSequences(reportId, TradingLabReportSection.ERRORS))
          .containsExactly(0L);
    } else {
      assertThat(chunkSequences(reportId, TradingLabReportSection.ERRORS)).isEmpty();
    }

    ReportSnapshot beforeLateWrite = snapshot(reportId);
    long nextSequence = store.nextChunkSequence(reportId, TradingLabReportSection.ERRORS);
    byte[] late = "{\"late\":true}\n".getBytes(StandardCharsets.UTF_8);
    assertCode(
        () -> store.persist(batch(
            reportId,
            null,
            TradingLabReportSection.ERRORS,
            nextSequence,
            new TradingLabLogicalAppend(2L, late, sha256(late)))),
        "TRADING_LAB_REPORT_CLOSED");
    assertThat(snapshot(reportId)).isEqualTo(beforeLateWrite);
    assertThat(chunkTotals(reportId)).isEqualTo(durable);
    assertThat(appendCount(reportId)).isEqualTo(appendWon ? 1L : 0L);
  }

  @Test
  void restartedWriterContinuesFromTheHighestDurableChunkSequence() {
    UUID reportId = insertReport();
    byte[] beforeRestart = "r".repeat(CHUNK_BYTES + 1).getBytes(StandardCharsets.UTF_8);
    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.LIFECYCLE,
        0L,
        new TradingLabLogicalAppend(null, beforeRestart, sha256(beforeRestart))));

    long recoveredNextSequence = store.nextChunkSequence(
        reportId, TradingLabReportSection.LIFECYCLE);
    assertThat(recoveredNextSequence).isEqualTo(2L);
    byte[] afterRestart = "{\"afterRestart\":true}\n".getBytes(StandardCharsets.UTF_8);
    store.persist(batch(
        reportId,
        null,
        TradingLabReportSection.LIFECYCLE,
        recoveredNextSequence,
        new TradingLabLogicalAppend(null, afterRestart, sha256(afterRestart))));

    assertThat(chunkSequences(reportId, TradingLabReportSection.LIFECYCLE))
        .containsExactly(0L, 1L, 2L);
    assertThat(chunkRawSizes(reportId, TradingLabReportSection.LIFECYCLE))
        .containsExactly((long) CHUNK_BYTES, 1L, (long) afterRestart.length);
    assertThat(store.nextChunkSequence(reportId, TradingLabReportSection.LIFECYCLE))
        .isEqualTo(3L);
    assertThat(snapshot(reportId)).satisfies(snapshot -> {
      assertThat(snapshot.status()).isEqualTo("WRITING");
      assertThat(snapshot.uncompressedBytes())
          .isEqualTo((long) beforeRestart.length + afterRestart.length);
      assertThat(snapshot.compressedBytes())
          .isEqualTo(chunkTotals(reportId).compressedBytes());
      assertThat(snapshot.chunkCount()).isEqualTo(3);
      assertThat(snapshot.version()).isEqualTo(2L);
    });
  }

  @Test
  void replacedLeaseOwnerAtCounterMutationRollsBackChunksLedgerAndCounters() {
    UUID reportId = insertReport();
    String oldOwner = "report-worker:" + UUID.randomUUID();
    UUID runId = insertLeasedRun(reportId, oldOwner, 60);
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        runId, reportId, oldOwner);
    TradingLabCanonicalValue event = canonicalizer.canonicalize(
        TradingLabReportSection.CHECKPOINTS, java.util.Map.of("step", 21));
    ReportSnapshot before = snapshot(reportId);
    jdbcTemplate.execute("""
        create function trading_lab.report_store_it_replace_owner()
        returns trigger
        language plpgsql
        as $$
        begin
          update trading_lab.runs
          set lease_owner = 'report-worker:replacement',
              lease_until = clock_timestamp() + interval '60 seconds'
          where report_id = new.report_id;
          return new;
        end
        $$
        """);
    jdbcTemplate.execute("""
        create trigger report_store_it_replace_owner
        before insert on trading_lab.report_chunks
        for each row execute function trading_lab.report_store_it_replace_owner()
        """);
    try {
      assertCode(
          () -> store.persist(batch(
              reportId,
              oldFence,
              TradingLabReportSection.CHECKPOINTS,
              0L,
              new TradingLabLogicalAppend(21L, event.bytes(), event.checksum()))),
          "TRADING_LAB_REPORT_FENCE_LOST");
    } finally {
      jdbcTemplate.execute("""
          drop trigger if exists report_store_it_replace_owner
          on trading_lab.report_chunks
          """);
      jdbcTemplate.execute("""
          drop function if exists trading_lab.report_store_it_replace_owner()
          """);
    }

    assertThat(chunkCount(reportId)).isZero();
    assertThat(appendCount(reportId)).isZero();
    assertThat(snapshot(reportId)).isEqualTo(before);
    assertThat(leaseOwner(runId)).isEqualTo(oldOwner);
  }
  private Attempt persistAfter(
      CyclicBarrier start,
      TradingLabReportWriteBatch batch
  ) throws Exception {
    start.await(10, SECONDS);
    try {
      store.persist(batch);
      return new Attempt(true, null);
    } catch (BusinessException exception) {
      return new Attempt(false, exception.getCode());
    }
  }

  private Attempt finalizeAfter(
      CyclicBarrier start,
      UUID reportId,
      TradingLabReportMeasurement measurement,
      TradingLabReportOutcome outcome
  ) throws Exception {
    start.await(10, SECONDS);
    try {
      TradingLabReportFinalizeResult result = store.finalizeReport(
          reportId, null, measurement, outcome, Duration.ofDays(30));
      return new Attempt(result != TradingLabReportFinalizeResult.RETRY, null);
    } catch (BusinessException exception) {
      return new Attempt(false, exception.getCode());
    }
  }

  private FinalizeAttempt finalizeAfterResult(
      CyclicBarrier start,
      UUID reportId,
      TradingLabReportMeasurement measurement,
      TradingLabReportOutcome outcome
  ) throws Exception {
    start.await(10, SECONDS);
    try {
      return new FinalizeAttempt(
          store.finalizeReport(
              reportId, null, measurement, outcome, Duration.ofDays(30)),
          null);
    } catch (BusinessException exception) {
      return new FinalizeAttempt(null, exception.getCode());
    }
  }
  private TradingLabReportWriteBatch batch(
      UUID reportId,
      TradingLabReportWriteFence fence,
      TradingLabReportSection section,
      long firstSequence,
      TradingLabLogicalAppend... appends
  ) {
    return new TradingLabReportWriteBatch(
        reportId,
        fence,
        List.of(new TradingLabReportSectionWrite(
            section, firstSequence, List.of(appends))));
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'report-it-hash', 'ACTIVE', 'ADMIN')
        """, id, "report-" + id + "@trading-lab-it.test");
    return id;
  }

  private UUID insertScenario() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version)
        values (
          ?, 'Report store IT', 'DRAFT', false, 'report-it-seed', 'report-it-model',
          '{"seed":"report-it-seed"}'::jsonb, '{}'::jsonb, ?,
          'report-it-symbols', 'report-it-code', ?, ?, 0)
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
          ?, ?, 'PENDING', 'report-it-model',
          ?, 'report-it-code', '{}'::jsonb,
          clock_timestamp() + interval '30 days', false, ?, 0)
        """, id, scenarioId, "b".repeat(64), actorId);
    return id;
  }

  private UUID insertLeasedRun(UUID reportId, String owner, int leaseSeconds) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, lease_key, lease_owner, lease_until,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          report_id, scenario_snapshot_json, config_snapshot_json,
          config_snapshot_hash, model_version, symbol_config_version,
          code_version, created_by, version)
        values (
          ?, ?, 'RUNNING', 1, ?, clock_timestamp() + (? * interval '1 second'),
          false, false,
          0, 10, 1.000000, 0,
          ?, '{}'::jsonb, '{}'::jsonb,
          ?, 'report-it-model', 'report-it-symbols',
          'report-it-code', ?, 0)
        """, id, scenarioId, owner, leaseSeconds, reportId, "c".repeat(64), actorId);
    return id;
  }

  private String modelVersion(UUID reportId) {
    return jdbcTemplate.queryForObject(
        "select model_version from trading_lab.reports where id = ?",
        String.class,
        reportId);
  }

  private TradingLabChunkedReportWriter newReportWriter() {
    return new TradingLabChunkedReportWriter(
        canonicalizer,
        store,
        streamer,
        new TradingLabFixedValidationSecretProvider(List.of()),
        CHUNK_BYTES,
        1,
        Duration.ofDays(30));
  }

  private UUID insertUnleasedRun(UUID reportId, String state) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          report_id, scenario_snapshot_json, config_snapshot_json,
          config_snapshot_hash, model_version, symbol_config_version,
          code_version, created_by, version)
        values (
          ?, ?, ?,
          false, false,
          0, 10, 1.000000, 0,
          ?, '{}'::jsonb, '{}'::jsonb,
          ?, 'report-it-model', 'report-it-symbols',
          'report-it-code', ?, 0)
        """, id, scenarioId, state, reportId, "c".repeat(64), actorId);
    return id;
  }

  private ReportSnapshot snapshot(UUID reportId) {
    return jdbcTemplate.queryForObject("""
        select status,
               failure_code,
               failure_message,
               uncompressed_bytes,
               compressed_bytes,
               chunk_count,
               version,
               completed_at,
               retained_until
        from trading_lab.reports
        where id = ?
        """, (row, rowNumber) -> new ReportSnapshot(
            row.getString("status"),
            row.getString("failure_code"),
            row.getString("failure_message"),
            row.getLong("uncompressed_bytes"),
            row.getLong("compressed_bytes"),
            row.getInt("chunk_count"),
            row.getLong("version"),
            row.getTimestamp("completed_at") == null
                ? null
                : row.getTimestamp("completed_at").toInstant(),
            row.getTimestamp("retained_until").toInstant()),
        reportId);
  }

  private long chunkCount(UUID reportId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from trading_lab.report_chunks where report_id = ?",
        Long.class,
        reportId);
  }

  private long appendCount(UUID reportId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from trading_lab.report_appends where report_id = ?",
        Long.class,
        reportId);
  }

  private ChunkTotals chunkTotals(UUID reportId) {
    return jdbcTemplate.queryForObject("""
        select coalesce(sum(compressed_bytes), 0) as compressed_bytes,
               count(*) as chunk_count
        from trading_lab.report_chunks
        where report_id = ?
        """, (row, rowNumber) -> new ChunkTotals(
            row.getLong("compressed_bytes"),
            row.getInt("chunk_count")),
        reportId);
  }

  private String leaseOwner(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select lease_owner from trading_lab.runs where id = ?",
        String.class,
        runId);
  }
  private List<Long> chunkSequences(UUID reportId, TradingLabReportSection section) {
    return jdbcTemplate.queryForList("""
        select sequence
        from trading_lab.report_chunks
        where report_id = ? and section = ?
        order by sequence
        """, Long.class, reportId, section.name());
  }

  private List<Long> chunkRawSizes(UUID reportId, TradingLabReportSection section) {
    return jdbcTemplate.queryForList("""
        select uncompressed_bytes
        from trading_lab.report_chunks
        where report_id = ? and section = ?
        order by sequence
        """, Long.class, reportId, section.name());
  }

  private long maxRawChunkBytes(UUID reportId) {
    return jdbcTemplate.queryForObject(
        "select max(uncompressed_bytes) from trading_lab.report_chunks where report_id = ?",
        Long.class,
        reportId);
  }

  private void deleteFixture() {
    jdbcTemplate.update("delete from trading_lab.audit_events");
    jdbcTemplate.update("delete from trading_lab.report_appends");
    jdbcTemplate.update("delete from trading_lab.report_chunks");
    jdbcTemplate.update("delete from trading_lab.run_events");
    jdbcTemplate.update("delete from trading_lab.run_transitions");
    jdbcTemplate.update("delete from trading_lab.runs");
    jdbcTemplate.update("delete from trading_lab.reports");
    jdbcTemplate.update("delete from trading_lab.scenarios");
    jdbcTemplate.update("delete from auth.users where email like '%@trading-lab-it.test'");
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void assertCode(Runnable invocation, String code) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private record ReportSnapshot(
      String status,
      String failureCode,
      String failureMessage,
      long uncompressedBytes,
      long compressedBytes,
      int chunkCount,
      long version,
      Instant completedAt,
      Instant retainedUntil
  ) {
  }

  private record Attempt(boolean success, String code) {
  }

  private record FinalizeAttempt(
      TradingLabReportFinalizeResult result,
      String code
  ) {
  }

  private record ChunkTotals(long compressedBytes, int chunkCount) {
  }
}
