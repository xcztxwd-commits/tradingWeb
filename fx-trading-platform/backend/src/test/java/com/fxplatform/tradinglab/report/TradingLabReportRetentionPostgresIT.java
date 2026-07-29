package com.fxplatform.tradinglab.report;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
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

@SpringBootTest
@ActiveProfiles("database-it")
@Testcontainers
class TradingLabReportRetentionPostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingLabReportRetentionService retentionService;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  private UUID actorId;
  private UUID scenarioId;

  @BeforeEach
  void prepareIsolatedFixture() {
    assertThat(Arrays.stream(flyway.info().applied())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion()))
        .contains("60", "62");
    deleteTradingLabFixture();
    jdbcTemplate.update("delete from auth.users where email like '%@retention-it.test'");
    actorId = insertUser();
    scenarioId = insertScenario(actorId);
  }

  @AfterEach
  void removeIsolatedFixture() {
    deleteTradingLabFixture();
    jdbcTemplate.update("delete from auth.users where email like '%@retention-it.test'");
  }

  @Test
  void deletingAnExpiredTerminalReportCascadesPayloadButPreservesRunHistory() {
    UUID reportId = insertReport("COMPLETED", true, -3_600, false);
    UUID runId = insertRun(reportId, "COMPLETED");
    insertChunk(reportId);
    insertAppend(reportId);
    insertTransition(runId);
    insertEvent(runId);

    assertThat(retentionService.deleteExpiredTerminalReports(10)).isOne();

    assertThat(count("trading_lab.reports", "id", reportId)).isZero();
    assertThat(count("trading_lab.report_chunks", "report_id", reportId)).isZero();
    assertThat(count("trading_lab.report_appends", "report_id", reportId)).isZero();
    assertThat(count("trading_lab.runs", "id", runId)).isOne();
    assertThat(runReportId(runId)).isNull();
    assertThat(count("trading_lab.run_transitions", "run_id", runId)).isOne();
    assertThat(count("trading_lab.run_events", "run_id", runId)).isOne();
  }

  @Test
  void cleanupNeverExceedsTheRequestedBatchSize() {
    UUID oldest = insertReport("FAILED", true, -7_200, false);
    UUID newer = insertReport("CANCELLED", true, -3_600, false);

    assertThat(retentionService.deleteExpiredTerminalReports(1)).isOne();
    assertThat(countReports()).isOne();
    assertThat(count("trading_lab.reports", "id", oldest)).isZero();
    assertThat(count("trading_lab.reports", "id", newer)).isOne();

    assertThat(retentionService.deleteExpiredTerminalReports(1)).isOne();
    assertThat(countReports()).isZero();
  }

  @Test
  void permanentUnexpiredIncompleteAndActiveRunReportsAreNotDeleted() {
    UUID permanent = insertReport("COMPLETED", true, -3_600, true);
    UUID unexpired = insertReport("FAILED", true, 3_600, false);
    UUID incomplete = insertReport("COMPLETED", false, -3_600, false);
    UUID active = insertReport("CANCELLED", true, -3_600, false);
    insertRun(active, "RUNNING");

    assertThat(retentionService.deleteExpiredTerminalReports(100)).isZero();

    assertThat(existingReportIds())
        .containsExactlyInAnyOrder(permanent, unexpired, incomplete, active);
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void reportHeldForStreamingIsSkippedWithoutWaitingAndRemainsEligibleAfterUnlock()
      throws Exception {
    UUID reportId = insertReport("COMPLETED", true, -3_600, false);
    ExecutorService cleanupWorker = Executors.newSingleThreadExecutor();

    try {
      try (Connection locker = dataSource.getConnection()) {
        locker.setAutoCommit(false);
        try {
          lockForStream(locker, reportId);

          Future<Integer> cleanup = cleanupWorker.submit(
              () -> retentionService.deleteExpiredTerminalReports(10));

          assertThat(cleanup.get(2, SECONDS)).isZero();
          assertThat(count("trading_lab.reports", "id", reportId)).isOne();
        } finally {
          locker.rollback();
        }
      }
    } finally {
      cleanupWorker.shutdownNow();
      assertThat(cleanupWorker.awaitTermination(10, SECONDS)).isTrue();
    }

    assertThat(retentionService.deleteExpiredTerminalReports(10)).isOne();
    assertThat(count("trading_lab.reports", "id", reportId)).isZero();
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'retention-it-hash', 'ACTIVE', 'ADMIN')
        """, id, "actor-" + id + "@retention-it.test");
    return id;
  }

  private UUID insertScenario(UUID creatorId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version)
        values (
          ?, 'Retention repository IT', 'DRAFT', false,
          'retention-it-seed', 'retention-it-model',
          '{"seed":"retention-it-seed"}'::jsonb, '{}'::jsonb, ?,
          'retention-it-symbols', 'retention-it-code', ?, ?, 0)
        """, id, "a".repeat(64), creatorId, creatorId);
    return id;
  }

  private UUID insertReport(
      String status, boolean completed, int retainedOffsetSeconds, boolean permanent) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version, config_snapshot_hash, code_version,
          metadata_json, uncompressed_bytes, compressed_bytes, chunk_count,
          retained_until, permanent, created_by, completed_at, version)
        values (
          ?, ?, ?, 'retention-it-model', ?, 'retention-it-code',
          '{}'::jsonb, 0, 0, 0,
          clock_timestamp() + (? * interval '1 second'), ?, ?,
          case when ? then clock_timestamp() - interval '2 hours' else null end, 0)
        """,
        id,
        scenarioId,
        status,
        "b".repeat(64),
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
          0, 10, 1.000000, 0,
          '{}'::jsonb, '{}'::jsonb, ?,
          'retention-it-model', 'retention-it-symbols', 'retention-it-code', ?, 1,
          case when ? in ('COMPLETED', 'FAILED', 'CANCELLED')
               then clock_timestamp() else null end)
        """, id, scenarioId, state, reportId, "c".repeat(64), actorId, state);
    return id;
  }

  private void insertChunk(UUID reportId) {
    jdbcTemplate.update("""
        insert into trading_lab.report_chunks (
          id, report_id, section, sequence, encoding,
          uncompressed_bytes, compressed_bytes, payload, checksum)
        values (?, ?, 'METADATA', 0, 'GZIP', 2, 2, ?, 'fixture-checksum')
        """, UUID.randomUUID(), reportId, new byte[] {1, 2});
  }

  private void insertAppend(UUID reportId) {
    jdbcTemplate.update("""
        insert into trading_lab.report_appends (
          report_id, section, source_sequence, canonical_bytes, canonical_checksum)
        values (?, 'METADATA', -1, 2, ?)
        """, reportId, "sha256:" + "d".repeat(64));
  }

  private void insertTransition(UUID runId) {
    jdbcTemplate.update("""
        insert into trading_lab.run_transitions (
          id, run_id, from_state, to_state, run_version,
          reason, idempotency_key, actor_id, details_json)
        values (?, ?, 'RUNNING', 'COMPLETED', 1,
                'retention fixture', ?, ?, '{}'::jsonb)
        """, UUID.randomUUID(), runId, "retention-" + runId, actorId);
  }

  private void insertEvent(UUID runId) {
    jdbcTemplate.update("""
        insert into trading_lab.run_events (
          id, run_id, sequence, event_type, real_time, payload_json)
        values (?, ?, 0, 'RETENTION_FIXTURE', clock_timestamp(), '{}'::jsonb)
        """, UUID.randomUUID(), runId);
  }

  private void lockForStream(Connection connection, UUID reportId) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("""
        select id
        from trading_lab.reports
        where id = ?
        for share
        """)) {
      statement.setObject(1, reportId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  private long count(String table, String column, UUID id) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Long.class, id);
  }

  private long countReports() {
    return jdbcTemplate.queryForObject("select count(*) from trading_lab.reports", Long.class);
  }

  private java.util.List<UUID> existingReportIds() {
    return jdbcTemplate.queryForList(
        "select id from trading_lab.reports order by id", UUID.class);
  }

  private UUID runReportId(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select report_id from trading_lab.runs where id = ?", UUID.class, runId);
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
}
