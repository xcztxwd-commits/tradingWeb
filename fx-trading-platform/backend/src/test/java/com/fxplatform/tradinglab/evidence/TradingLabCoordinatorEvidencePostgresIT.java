package com.fxplatform.tradinglab.evidence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.client.ThrowableInfo;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "trading-lab.report.max-active-writers=8")
@ActiveProfiles("database-it")
@Testcontainers
class TradingLabCoordinatorEvidencePostgresIT {

  private static final Instant VIRTUAL_TIME = Instant.parse("2026-07-23T16:00:00Z");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TradingLabRunRepository runs;
  @Autowired private TradingLabCoordinatorEvidenceStore evidence;
  @Autowired private TradingLabFencedReportWriter reportWriter;
  @Autowired private TradingLabHttpTraceSanitizer traceSanitizer;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  private UUID actorId;
  private UUID scenarioId;
  private UUID reportId;
  private UUID runId;
  private String claimOwner;

  @BeforeEach
  void prepareFixture() {
    assertThat(Arrays.stream(flyway.info().applied())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion()))
        .contains("60", "62");
    deleteFixture();
    jdbcTemplate.update(
        "delete from auth.users where email like '%@task7-evidence-it.test'");
    actorId = insertUser();
    scenarioId = insertScenario();
    reportId = insertReport("PENDING");
    claimOwner = "task7-evidence-worker:" + UUID.randomUUID();
    runId = insertLeasedRun(reportId, claimOwner);
    reportWriter.initializeMetadata(
        new TradingLabReportWriteFence(runId, reportId, claimOwner),
        Map.of("test", "task7-coordinator-evidence"));
  }

  @AfterEach
  void cleanFixture() {
    try {
      if (runId != null
          && reportId != null
          && claimOwner != null
          && jdbcTemplate.queryForObject(
              "select count(*) from trading_lab.runs where id = ?",
              Long.class,
              runId) == 1L) {
        jdbcTemplate.update("""
            update trading_lab.runs
            set lease_key = 1,
                lease_owner = ?,
                lease_until = clock_timestamp() + interval '1 minute'
            where id = ?
            """, claimOwner, runId);
        jdbcTemplate.update("""
            update trading_lab.reports
            set status = 'WRITING',
                completed_at = null,
                failure_code = null,
                failure_message = null,
                model_version = 'task7-model',
                uncompressed_bytes = 0,
                compressed_bytes = 0,
                chunk_count = 0
            where id = ?
            """, reportId);
        reportWriter.cancel(
            new TradingLabReportWriteFence(runId, reportId, claimOwner),
            "PostgreSQL integration fixture cleanup");
      }
    } finally {
      deleteFixture();
      jdbcTemplate.update(
          "delete from auth.users where email like '%@task7-evidence-it.test'");
    }
  }

  @Test
  void joinedWorkerSnapshotMapsReportStatusAndUsesTheDatabaseLeaseFence() throws Exception {
    TradingLabWorkerRunSnapshot snapshot =
        runs.findLiveWorkerSnapshot(runId, claimOwner).orElseThrow();

    assertThat(snapshot.runId()).isEqualTo(runId);
    assertThat(snapshot.scenarioId()).isEqualTo(scenarioId);
    assertThat(snapshot.reportId()).isEqualTo(reportId);
    assertThat(snapshot.reportStatus()).isEqualTo("PENDING");
    assertThat(snapshot.reportQuarantined()).isFalse();
    assertThat(snapshot.state()).isEqualTo("RUNNING");
    assertThat(snapshot.version()).isEqualTo(7L);
    assertThat(snapshot.pauseRequested()).isTrue();
    assertThat(snapshot.cancelRequested()).isFalse();
    assertThat(snapshot.virtualStartedAt())
        .isEqualTo(Instant.parse("2026-07-23T15:00:00Z"));
    assertThat(snapshot.virtualCurrentAt())
        .isEqualTo(Instant.parse("2026-07-23T15:00:09Z"));
    assertThat(snapshot.processedTicks()).isEqualTo(9L);
    assertThat(snapshot.totalTicks()).isEqualTo(12L);
    assertThat(snapshot.speedMultiplier()).isEqualByComparingTo("2.500000");
    assertThat(objectMapper.readTree(snapshot.scenarioSnapshotJson()))
        .isEqualTo(objectMapper.readTree("{\"scenario\":\"task7\"}"));
    assertThat(objectMapper.readTree(snapshot.configSnapshotJson()))
        .isEqualTo(objectMapper.readTree("{\"config\":\"task7\"}"));
    assertThat(snapshot.configSnapshotHash()).isEqualTo("c".repeat(64));
    assertThat(snapshot.modelVersion()).isEqualTo("task7-model");
    assertThat(snapshot.symbolConfigVersion()).isEqualTo("task7-symbols");
    assertThat(snapshot.codeVersion()).isEqualTo("task7-code");
    assertThat(snapshot.createdBy()).isEqualTo(actorId);

    jdbcTemplate.update("""
        update trading_lab.reports
        set failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE',
            failure_message = 'different fixed message'
        where id = ?
        """, reportId);
    assertThat(runs.findLiveWorkerSnapshot(runId, claimOwner))
        .get()
        .extracting(TradingLabWorkerRunSnapshot::reportQuarantined)
        .isEqualTo(false);

    jdbcTemplate.update("""
        update trading_lab.reports
        set status = 'WRITING',
            completed_at = null,
            model_version = '[REDACTED]',
            metadata_json = '{}'::jsonb,
            uncompressed_bytes = 0,
            compressed_bytes = 0,
            chunk_count = 0,
            failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE',
            failure_message = 'Trading Lab report contains unsafe trace evidence'
        where id = ?
        """, reportId);
    assertThat(runs.findLiveWorkerSnapshot(runId, claimOwner))
        .get()
        .extracting(TradingLabWorkerRunSnapshot::reportQuarantined)
        .isEqualTo(true);

    jdbcTemplate.update("""
        update trading_lab.reports
        set status = 'FAILED',
            completed_at = clock_timestamp(),
            uncompressed_bytes = 137
        where id = ?
        """, reportId);

    TradingLabWorkerRunSnapshot failedQuarantine =
        runs.findLiveWorkerSnapshot(runId, claimOwner).orElseThrow();
    assertThat(failedQuarantine.reportStatus()).isEqualTo("FAILED");
    assertThat(failedQuarantine.reportQuarantined()).isTrue();

    jdbcTemplate.update("""
        update trading_lab.reports
        set status = 'CANCELLED',
            failure_code = 'CANCELLED',
            uncompressed_bytes = 149
        where id = ?
        """, reportId);

    TradingLabWorkerRunSnapshot quarantined =
        runs.lockLiveWorkerSnapshot(runId, claimOwner).orElseThrow();
    assertThat(quarantined.reportStatus()).isEqualTo("CANCELLED");
    assertThat(quarantined.reportQuarantined()).isTrue();
    assertThat(runs.findLiveWorkerSnapshot(runId, claimOwner + "-wrong")).isEmpty();

    jdbcTemplate.update("""
        update trading_lab.runs
        set lease_until = clock_timestamp() - interval '1 millisecond'
        where id = ?
        """, runId);

    assertThat(runs.findLiveWorkerSnapshot(runId, claimOwner)).isEmpty();
    assertThat(runs.lockLiveWorkerSnapshot(runId, claimOwner)).isEmpty();
  }

  @Test
  void fencedProgressProjectionIsMonotonicBoundedAndLeavesUnprovenFieldsUntouched() {
    assertThat(runs.advanceFencedProcessedTicks(runId, claimOwner + "-wrong", 11L))
        .isZero();
    assertThat(runLong("processed_ticks")).isEqualTo(9L);
    assertThat(runLong("version")).isEqualTo(7L);

    jdbcTemplate.update(
        "update trading_lab.runs set state = 'PAUSED' where id = ?",
        runId);
    assertThat(runs.advanceFencedProcessedTicks(runId, claimOwner, 9L)).isZero();
    assertThat(runs.advanceFencedProcessedTicks(runId, claimOwner, 4L)).isZero();
    assertThat(runLong("processed_ticks")).isEqualTo(9L);
    assertThat(runLong("version")).isEqualTo(7L);

    jdbcTemplate.update(
        "update trading_lab.runs set state = 'RUNNING' where id = ?",
        runId);
    assertThat(runs.advanceFencedProcessedTicks(runId, claimOwner, 11L)).isOne();
    assertThat(runLong("processed_ticks")).isEqualTo(11L);
    assertThat(runLong("version")).isEqualTo(7L);
    assertThat(runLong("current_step")).isEqualTo(9L);
    assertThat(jdbcTemplate.queryForObject(
        "select virtual_current_at from trading_lab.runs where id = ?",
        Instant.class,
        runId)).isEqualTo(Instant.parse("2026-07-23T15:00:09Z"));

    assertThat(runs.advanceFencedProcessedTicks(runId, claimOwner, 99L)).isOne();
    assertThat(runLong("processed_ticks")).isEqualTo(12L);
    assertThat(runLong("version")).isEqualTo(7L);

    jdbcTemplate.update("""
        update trading_lab.runs
        set processed_ticks = 10,
            lease_until = clock_timestamp() - interval '1 millisecond'
        where id = ?
        """, runId);
    assertThat(runs.advanceFencedProcessedTicks(runId, claimOwner, 12L)).isZero();
    assertThat(runLong("processed_ticks")).isEqualTo(10L);
    assertThat(runLong("version")).isEqualTo(7L);
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void serializesOneGlobalSequenceAndEnforcesValidationContinuity() throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);

    try {
      Future<TradingLabCoordinatorEvidence> first = workers.submit(() -> {
        start.await(10, SECONDS);
        return evidence.appendIntent(
            runId, claimOwner, "concurrent-a", Map.of("attempt", "a"));
      });
      Future<TradingLabCoordinatorEvidence> second = workers.submit(() -> {
        start.await(10, SECONDS);
        return evidence.appendIntent(
            runId, claimOwner, "concurrent-b", Map.of("attempt", "b"));
      });

      assertThat(List.of(first.get(20, SECONDS), second.get(20, SECONDS)))
          .extracting(TradingLabCoordinatorEvidence::sequence)
          .containsExactlyInAnyOrder(0L, 1L);
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
    }

    TradingLabCoordinatorEvidence validationOne =
        evidence.appendValidationEvent(runId, claimOwner, validationEvent(1L, "fingerprint-1"));
    TradingLabCoordinatorEvidence validationTwo =
        evidence.appendValidationEvent(runId, claimOwner, validationEvent(2L, "fingerprint-2"));
    TradingLabCoordinatorEvidence exactReplay =
        evidence.appendValidationEvent(runId, claimOwner, validationEvent(2L, "fingerprint-2"));

    assertThat(validationOne.sequence()).isEqualTo(2L);
    assertThat(validationTwo.sequence()).isEqualTo(3L);
    assertThat(exactReplay).isEqualTo(validationTwo);
    assertThat(evidence.lastEvidenceSequence(runId, claimOwner)).isEqualTo(3L);
    assertThat(evidence.lastValidationSequence(runId, claimOwner)).isEqualTo(2L);
    assertThat(evidence.journalAfter(runId, claimOwner, -1L, 20))
        .extracting(TradingLabCoordinatorEvidence::sequence)
        .containsExactly(0L, 1L, 2L, 3L);
    assertThat(jdbcTemplate.queryForList("""
        select (payload_json ->> 'validationSequence')::bigint
        from trading_lab.run_events
        where run_id = ? and event_type = 'VALIDATION_EVENT'
        order by sequence
        """, Long.class, runId))
        .containsExactly(1L, 2L);

    assertThatThrownBy(() -> evidence.appendValidationEvent(
        runId, claimOwner, validationEvent(4L, "fingerprint-4")))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode())
                .isEqualTo("TRADING_LAB_EVIDENCE_CONFLICT"))
        .hasMessageContaining("contiguously");
    assertThatThrownBy(() -> evidence.appendValidationEvent(
        runId, claimOwner, validationEvent(2L, "changed-fingerprint")))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode())
                .isEqualTo("TRADING_LAB_EVIDENCE_CONFLICT"))
        .hasMessageContaining("another meaning");
    assertThat(eventCount()).isEqualTo(4L);
  }

  @Test
  void everyWriteReloadsTheLiveLeaseBeforeItAllocatesSequence() {
    evidence.appendIntent(runId, claimOwner, "before-expiry", Map.of("step", 1));
    jdbcTemplate.update("""
        update trading_lab.runs
        set lease_until = clock_timestamp() - interval '1 millisecond'
        where id = ?
        """, runId);

    assertThatThrownBy(() ->
        evidence.appendIntent(runId, claimOwner, "after-expiry", Map.of("step", 2)))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("TRADING_LAB_FENCE_LOST"));
    assertThat(eventCount()).isOne();
  }

  @Test
  void closedReportRecoveryReadsTheExactCleanupIntentAndRejectsDatabaseTampering() {
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("target", "COMPLETED");
    cleanup.put("generation", 7L);
    cleanup.put("validationSequence", 12L);
    cleanup.put("failureCode", null);
    cleanup.put("finalResetRequired", true);
    TradingLabCoordinatorEvidence intent = evidence.appendIntent(
        runId,
        claimOwner,
        "cleanup:COMPLETED",
        cleanup);
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, reportId, claimOwner);

    reportWriter.complete(fence);

    TradingLabWorkerRunSnapshot closed =
        runs.findLiveWorkerSnapshot(runId, claimOwner).orElseThrow();
    assertThat(closed.reportStatus()).isEqualTo("COMPLETED");
    assertThatThrownBy(() ->
        evidence.findIntent(runId, claimOwner, "cleanup:COMPLETED"))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode())
                .isEqualTo("TRADING_LAB_REPORT_CLOSED"));
    assertThat(evidence.findCleanupIntentForRecovery(
        runId, claimOwner, "cleanup:COMPLETED")).contains(intent);

    assertThat(jdbcTemplate.update("""
        update trading_lab.run_events
        set payload_json = jsonb_set(
            payload_json,
            '{evidence,generation}',
            '999'::jsonb,
            false)
        where id = ?
        """, intent.id())).isOne();

    assertThatThrownBy(() ->
        evidence.findCleanupIntentForRecovery(
            runId, claimOwner, "cleanup:COMPLETED"))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode())
                .isEqualTo("TRADING_LAB_EVIDENCE_CORRUPT"));
  }

  @Test
  void preferredAttemptSqlChoosesLatestSuccessThenLatestFailure() {
    TradingLabCoordinatorEvidence firstFailure = evidence.appendHttpResult(
        runId,
        claimOwner,
        "start",
        httpResult(
            "trace-start-failure",
            503,
            new ThrowableInfo("REMOTE", "UNAVAILABLE", "try again", true)));
    TradingLabCoordinatorEvidence success = evidence.appendHttpResult(
        runId,
        claimOwner,
        "start",
        httpResult("trace-start-success", 201, null));
    TradingLabCoordinatorEvidence laterFailure = evidence.appendHttpResult(
        runId,
        claimOwner,
        "start",
        httpResult(
            "trace-start-later-failure",
            0,
            new ThrowableInfo("TRANSPORT", "TIMEOUT", "timed out", true)));

    assertThat(firstFailure.sequence()).isZero();
    assertThat(success.sequence()).isEqualTo(1L);
    assertThat(laterFailure.sequence()).isEqualTo(2L);
    assertThat(evidence.findHttpResult(runId, claimOwner, "start"))
        .contains(success);

    TradingLabCoordinatorEvidence olderFailure = evidence.appendHttpResult(
        runId,
        claimOwner,
        "pause",
        httpResult(
            "trace-pause-failure",
            409,
            new ThrowableInfo("REMOTE", "CONFLICT", "not running", false)));
    TradingLabCoordinatorEvidence newestFailure = evidence.appendHttpResult(
        runId,
        claimOwner,
        "pause",
        httpResult(
            "trace-pause-timeout",
            0,
            new ThrowableInfo("TRANSPORT", "TIMEOUT", "timed out", true)));

    assertThat(olderFailure.sequence()).isEqualTo(3L);
    assertThat(newestFailure.sequence()).isEqualTo(4L);
    assertThat(evidence.findHttpResult(runId, claimOwner, "pause"))
        .contains(newestFailure);
  }

  @Test
  void reportProjectionQueryFiltersSparseSectionsWithoutReplayingExpectedErrors() {
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(1L, "RUN_STATE_CHANGED", Map.of("state", "RUNNING")));
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(2L, "MARKET_TICK", Map.of("tickSequence", 1L)));
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            3L,
            "CHECKPOINT",
            Map.of("tickSequence", 1L, "state", Map.of("orders", List.of()))));
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            4L,
            "API_TRACE",
            Map.of("operation", "PUBLIC_ACTION", "outcome", "EXPECTED_ERROR", "status", 400)));
    TradingLabCoordinatorEvidence http = evidence.appendHttpResult(
        runId,
        claimOwner,
        "state",
        httpResult("trace-state", 200, null));

    assertThat(evidence.reportProjectionCandidates(
        runId,
        claimOwner,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            http.sequence(),
            http.sequence(),
            1L,
            2L,
            -1L),
        200)).isEmpty();

    TradingLabCoordinatorEvidence failed = evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            5L,
            "API_TRACE",
            Map.of("operation", "PUBLIC_ACTION", "outcome", "FAILED", "status", 500)));
    TradingLabCoordinatorEvidence executionFailed = evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            6L,
            "RUN_EXECUTION_FAILED",
            Map.of(
                "failurePoint", Map.of(
                    "operation", "PUBLIC_ACTION",
                    "actionId", "00000000-0000-0000-0000-000000000091",
                    "tickSequence", 1L,
                    "actionSequence", 1L,
                    "status", 409,
                    "code", "INSUFFICIENT_BALANCE"),
                "unexecuted", List.of())));

    assertThat(evidence.reportProjectionCandidates(
        runId,
        claimOwner,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            executionFailed.sequence(),
            http.sequence(),
            1L,
            2L,
            -1L),
        200))
        .extracting(TradingLabCoordinatorEvidence::sequence)
        .containsExactly(failed.sequence(), executionFailed.sequence());
  }

  @Test
  void reportProjectionQueryUsesApiTraceCursorForTraceableValidationEvents() {
    TradingLabCoordinatorEvidence hop = evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            1L,
            "API_TRACE",
            Map.of(
                "operation", "QUERY_STATE",
                "outcome", "SUCCEEDED",
                "traceScope", "HTTP_HOP",
                "trace", Map.of("url", "http://127.0.0.1:8080/api/ledger"))));

    assertThat(evidence.reportProjectionCandidates(
        runId,
        claimOwner,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            hop.sequence(),
            -1L,
            hop.sequence(),
            hop.sequence(),
            hop.sequence()),
        200))
        .extracting(TradingLabCoordinatorEvidence::sequence)
        .containsExactly(hop.sequence());

    assertThat(evidence.reportProjectionCandidates(
        runId,
        claimOwner,
        new TradingLabCoordinatorEvidenceStore.ReportProjectionCursors(
            hop.sequence(),
            hop.sequence(),
            hop.sequence(),
            hop.sequence(),
            hop.sequence()),
        200)).isEmpty();
  }

  @Test
  void latestStateQueryIgnoresNewerNonStateValidationEvents() {
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(1L, "MARKET_TICK", Map.of("tickSequence", 1L)));
    TradingLabCoordinatorEvidence snapshot = evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            2L,
            "STATE_SNAPSHOT",
            Map.of("tickSequence", 1L, "state", Map.of("orders", List.of()))));
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(3L, "RUN_STATE_CHANGED", Map.of("state", "RUNNING")));
    TradingLabCoordinatorEvidence checkpoint = evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            4L,
            "CHECKPOINT",
            Map.of("tickSequence", 2L, "state", Map.of("positions", List.of()))));
    evidence.appendValidationEvent(
        runId,
        claimOwner,
        validationEvent(
            5L,
            "API_TRACE",
            Map.of("operation", "PUBLIC_ACTION", "outcome", "SUCCEEDED", "status", 200)));

    assertThat(snapshot.sequence()).isLessThan(checkpoint.sequence());
    assertThat(evidence.latestStateBearingValidationEvent(runId, claimOwner))
        .contains(checkpoint);
  }

  @Test
  void lateSecretQuarantineCommitsOutsideTheRejectedJournalTransaction() {
    String canary = "task7-late-secret-" + UUID.randomUUID();
    var trace = traceSanitizer.sanitize(new TradingLabHttpTraceInput(
        URI.create("http://127.0.0.1:18087/internal/validation/runs"),
        Map.of(),
        Map.of(),
        "application/json",
        Map.of("accessToken", canary),
        null,
        null,
        null,
        null,
        List.of(),
        null));

    assertThatThrownBy(() -> evidence.appendHttpResult(
        runId,
        claimOwner,
        "late-secret",
        httpResult("trace-late-secret", 200, null),
        trace))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);

    assertThat(eventCount()).isZero();
    Map<String, Object> report = jdbcTemplate.queryForMap("""
        select status, model_version, metadata_json::text as metadata_json,
               failure_code, failure_message, chunk_count
        from trading_lab.reports
        where id = ?
        """, reportId);
    assertThat(report)
        .containsEntry("status", "WRITING")
        .containsEntry("model_version", "[REDACTED]")
        .containsEntry("metadata_json", "{}")
        .containsEntry("failure_code", "TRADING_LAB_REPORT_UNSAFE_TRACE")
        .containsEntry(
            "failure_message",
            "Trading Lab report contains unsafe trace evidence");
    assertThat(((Number) report.get("chunk_count")).longValue()).isZero();
    assertThat(report.toString()).doesNotContain(canary);
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'task7-evidence-it-hash', 'ACTIVE', 'ADMIN')
        """, id, "actor-" + id + "@task7-evidence-it.test");
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
          ?, 'Task 7 evidence IT', 'DRAFT', false, 'task7-seed', 'task7-model',
          '{"scenario":"task7","seed":"task7-seed"}'::jsonb,
          '{"config":"task7"}'::jsonb, ?,
          'task7-symbols', 'task7-code', ?, ?, 0)
        """, id, "a".repeat(64), actorId, actorId);
    return id;
  }

  private UUID insertReport(String status) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version,
          config_snapshot_hash, code_version, metadata_json,
          retained_until, permanent, created_by, version)
        values (
          ?, ?, ?, 'task7-model',
          ?, 'task7-code', '{}'::jsonb,
          clock_timestamp() + interval '30 days', false, ?, 0)
        """, id, scenarioId, status, "b".repeat(64), actorId);
    return id;
  }

  private UUID insertLeasedRun(UUID linkedReportId, String owner) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, lease_key, lease_owner, lease_until,
          cancel_requested, pause_requested,
          virtual_started_at, virtual_current_at,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          report_id, scenario_snapshot_json, config_snapshot_json,
          config_snapshot_hash, model_version, symbol_config_version,
          code_version, created_by, version)
        values (
          ?, ?, 'RUNNING', 1, ?, clock_timestamp() + interval '5 minutes',
          false, true,
          '2026-07-23T15:00:00Z'::timestamptz,
          '2026-07-23T15:00:09Z'::timestamptz,
          9, 12, 2.500000, 9,
          ?, '{"scenario":"task7"}'::jsonb, '{"config":"task7"}'::jsonb,
          ?, 'task7-model', 'task7-symbols',
          'task7-code', ?, 7)
        """, id, scenarioId, owner, linkedReportId, "c".repeat(64), actorId);
    return id;
  }

  private long eventCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from trading_lab.run_events where run_id = ?",
        Long.class,
        runId);
  }

  private long runLong(String column) {
    if (!column.matches("processed_ticks|current_step|version")) {
      throw new IllegalArgumentException("Unsupported run column");
    }
    return jdbcTemplate.queryForObject(
        "select " + column + " from trading_lab.runs where id = ?",
        Long.class,
        runId);
  }

  private ValidationRunEvent validationEvent(long sequence, String fingerprint) {
    return validationEvent(
        sequence,
        "RUN_STATE_CHANGED",
        Map.of("state", sequence == 1L ? "RUNNING" : "COMPLETED"),
        fingerprint);
  }

  private ValidationRunEvent validationEvent(
      long sequence,
      String type,
      Map<String, Object> payload
  ) {
    return validationEvent(sequence, type, payload, "fingerprint-" + sequence);
  }

  private ValidationRunEvent validationEvent(
      long sequence,
      String type,
      Map<String, Object> payload,
      String fingerprint
  ) {
    return new ValidationRunEvent(
        runId,
        sequence,
        "validation-durable-" + sequence,
        fingerprint,
        type,
        VIRTUAL_TIME.plusSeconds(sequence),
        "correlation-" + sequence,
        payload);
  }

  private ValidationHttpResult httpResult(
      String traceId,
      int status,
      ThrowableInfo failure
  ) {
    return new ValidationHttpResult(
        0L,
        "validation",
        "POST",
        URI.create("http://127.0.0.1:18087/internal/validation/runs"),
        VIRTUAL_TIME,
        VIRTUAL_TIME.plusSeconds(30),
        status,
        Duration.ofMillis(5),
        Map.of("body", Map.of("runId", runId.toString())),
        Map.of("body", Map.of("accepted", status >= 200 && status < 300)),
        traceId,
        "correlation-task7",
        failure);
  }

  private void deleteFixture() {
    jdbcTemplate.update("delete from trading_lab.audit_events");
    jdbcTemplate.update("delete from trading_lab.report_chunks");
    jdbcTemplate.update("delete from trading_lab.run_events");
    jdbcTemplate.update("delete from trading_lab.run_transitions");
    jdbcTemplate.update("delete from trading_lab.runs");
    jdbcTemplate.update("delete from trading_lab.reports");
    jdbcTemplate.update("delete from trading_lab.scenarios");
  }
}
