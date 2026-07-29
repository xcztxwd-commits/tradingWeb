package com.fxplatform.tradinglab.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fxplatform.audit.repository.AuditLogRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.mybatis.AuditFieldFillHandler;
import com.fxplatform.common.mybatis.MybatisPlusConfig;
import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import com.fxplatform.tradinglab.report.TradingLabPrettyReportStreamer;
import com.fxplatform.tradinglab.report.TradingLabReportStreamer;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportAppendRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportWriteFenceRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TradingLabReportAdminPostgresIT.TestApplication.class)
@ActiveProfiles("database-it")
@Testcontainers
class TradingLabReportAdminPostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingLabReportAdminService service;

  private UUID actorId;
  private UUID scenarioId;

  @BeforeEach
  void prepareFixture() {
    assertThat(Arrays.stream(flyway.info().applied())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion()))
        .contains("60", "62");
    deleteFixture();
    actorId = insertUser();
    scenarioId = insertScenario();
  }

  @AfterEach
  void cleanFixture() {
    dropAuditFailureTrigger();
    deleteFixture();
  }

  @Test
  void deleteCascadesPayloadPreservesRunHistoryAndCommitsBothAudits() {
    UUID reportId = insertTerminalReport(false, 7L);
    UUID runId = insertTerminalRun(reportId);
    insertPayload(reportId);
    insertRunHistory(runId);
    UUID requestId = UUID.randomUUID();

    service.delete(context(requestId), reportId);

    assertThat(count("trading_lab.reports", "id", reportId)).isZero();
    assertThat(count("trading_lab.report_chunks", "report_id", reportId)).isZero();
    assertThat(count("trading_lab.report_appends", "report_id", reportId)).isZero();
    assertThat(count("trading_lab.runs", "id", runId)).isOne();
    assertThat(runReportId(runId)).isNull();
    assertThat(count("trading_lab.run_transitions", "run_id", runId)).isOne();
    assertThat(count("trading_lab.run_events", "run_id", runId)).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select count(*)
        from trading_lab.audit_events
        where request_id = ?
          and action = 'TRADING_LAB_REPORT_DELETE'
          and details_json ->> 'reportId' = ?
          and details_json ->> 'reportVersion' = '7'
          and details_json ->> 'deletedAppends' = '1'
          and details_json ->> 'deletedChunks' = '1'
        """, Long.class, requestId, reportId.toString())).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select count(*)
        from audit.audit_logs
        where request_id = ?
          and action = 'TRADING_LAB_REPORT_DELETE'
          and target_id = ?
        """, Long.class, requestId.toString(), runId.toString())).isOne();
  }

  @Test
  void auditFailureRollsBackPayloadDeleteReportDeleteAndRunForeignKeyUpdate() {
    UUID reportId = insertTerminalReport(false, 9L);
    UUID runId = insertTerminalRun(reportId);
    insertPayload(reportId);
    insertRunHistory(runId);
    installAuditFailureTrigger();
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() -> service.delete(context(requestId), reportId))
        .isInstanceOf(RuntimeException.class);

    assertThat(count("trading_lab.reports", "id", reportId)).isOne();
    assertThat(count("trading_lab.report_chunks", "report_id", reportId)).isOne();
    assertThat(count("trading_lab.report_appends", "report_id", reportId)).isOne();
    assertThat(runReportId(runId)).isEqualTo(reportId);
    assertThat(count("trading_lab.run_transitions", "run_id", runId)).isOne();
    assertThat(count("trading_lab.run_events", "run_id", runId)).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from trading_lab.audit_events where request_id = ?
        """, Long.class, requestId)).isZero();
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from audit.audit_logs where request_id = ?
        """, Long.class, requestId.toString())).isZero();
  }

  @Test
  void permanentTrueFalseAndReplayReturnTheDurableVersion() {
    UUID reportId = insertTerminalReport(false, 11L);
    insertTerminalRun(reportId);

    TradingLabPermanentResponse enabled =
        service.setPermanent(context(UUID.randomUUID()), reportId, true);
    TradingLabPermanentResponse replay =
        service.setPermanent(context(UUID.randomUUID()), reportId, true);
    TradingLabPermanentResponse disabled =
        service.setPermanent(context(UUID.randomUUID()), reportId, false);

    assertThat(enabled)
        .isEqualTo(new TradingLabPermanentResponse(reportId, true, 12L));
    assertThat(replay)
        .isEqualTo(new TradingLabPermanentResponse(reportId, true, 12L));
    assertThat(disabled)
        .isEqualTo(new TradingLabPermanentResponse(reportId, false, 13L));
    assertThat(jdbcTemplate.queryForObject("""
        select permanent from trading_lab.reports where id = ?
        """, Boolean.class, reportId)).isFalse();
    assertThat(jdbcTemplate.queryForObject("""
        select version from trading_lab.reports where id = ?
        """, Long.class, reportId)).isEqualTo(13L);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*)
        from trading_lab.audit_events
        where action = 'TRADING_LAB_REPORT_PERMANENT'
        """, Long.class)).isEqualTo(3L);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void writerOrderedLocksMakeConcurrentDeleteWaitThenConflictWithoutPartialDeletion()
      throws Exception {
    UUID reportId = insertOpenReport();
    UUID runId = insertActiveRun(reportId);
    insertPayload(reportId);
    ExecutorService deletionWorker = Executors.newSingleThreadExecutor();
    CountDownLatch deletionStarted = new CountDownLatch(1);

    try (Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      lockRow(writer, "trading_lab.runs", runId);
      lockRow(writer, "trading_lab.reports", reportId);
      Future<String> deletion = deletionWorker.submit(() -> {
        deletionStarted.countDown();
        try {
          service.delete(context(UUID.randomUUID()), reportId);
          return null;
        } catch (TradingLabReportAdminException exception) {
          return exception.getCode();
        }
      });

      assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> deletion.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(java.util.concurrent.TimeoutException.class);
      writer.commit();

      assertThat(deletion.get(10, TimeUnit.SECONDS))
          .isEqualTo("TRADING_LAB_REPORT_STATE_CONFLICT");
    } finally {
      deletionWorker.shutdownNow();
      assertThat(deletionWorker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(count("trading_lab.reports", "id", reportId)).isOne();
    assertThat(count("trading_lab.report_chunks", "report_id", reportId)).isOne();
    assertThat(count("trading_lab.report_appends", "report_id", reportId)).isOne();
    assertThat(runReportId(runId)).isEqualTo(reportId);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*)
        from trading_lab.audit_events
        where action = 'TRADING_LAB_REPORT_DELETE'
        """, Long.class)).isZero();
  }

  private TradingLabAdminRequestContext context(UUID requestId) {
    return new TradingLabAdminRequestContext(
        actorId,
        "127.0.0.1",
        requestId);
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'report-admin-it-hash', 'ACTIVE', 'ADMIN')
        """, id, "report-admin-" + id + "@trading-lab-it.test");
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
          ?, 'Report admin IT', 'FROZEN', false,
          'report-admin-it-seed', 'report-admin-it-model',
          '{"seed":"report-admin-it-seed"}'::jsonb, '{}'::jsonb, ?,
          'report-admin-it-symbols', 'report-admin-it-code', ?, ?, 0)
        """, id, "a".repeat(64), actorId, actorId);
    return id;
  }

  private UUID insertTerminalReport(boolean permanent, long version) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version, config_snapshot_hash,
          code_version, metadata_json, uncompressed_bytes, compressed_bytes,
          chunk_count, retained_until, permanent, created_by, completed_at, version)
        values (
          ?, ?, 'COMPLETED', 'report-admin-it-model', ?,
          'report-admin-it-code', '{}'::jsonb, 2, 2,
          1, clock_timestamp() + interval '30 days', ?, ?,
          clock_timestamp(), ?)
        """, id, scenarioId, "b".repeat(64), permanent, actorId, version);
    return id;
  }

  private UUID insertOpenReport() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.reports (
          id, scenario_id, status, model_version, config_snapshot_hash,
          code_version, metadata_json, uncompressed_bytes, compressed_bytes,
          chunk_count, retained_until, permanent, created_by, version)
        values (
          ?, ?, 'WRITING', 'report-admin-it-model', ?,
          'report-admin-it-code', '{}'::jsonb, 2, 2,
          1, clock_timestamp() + interval '30 days', false, ?, 3)
        """, id, scenarioId, "b".repeat(64), actorId);
    return id;
  }

  private UUID insertTerminalRun(UUID reportId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, report_id,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version,
          created_by, finished_at, version)
        values (
          ?, ?, 'COMPLETED', ?,
          false, false,
          1, 1, 1.000000, 1,
          '{}'::jsonb, '{}'::jsonb, ?,
          'report-admin-it-model', 'report-admin-it-symbols',
          'report-admin-it-code', ?, clock_timestamp(), 1)
        """, id, scenarioId, reportId, "c".repeat(64), actorId);
    return id;
  }

  private UUID insertActiveRun(UUID reportId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, report_id,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version,
          created_by, version)
        values (
          ?, ?, 'RUNNING', ?,
          false, false,
          0, 1, 1.000000, 0,
          '{}'::jsonb, '{}'::jsonb, ?,
          'report-admin-it-model', 'report-admin-it-symbols',
          'report-admin-it-code', ?, 0)
        """, id, scenarioId, reportId, "c".repeat(64), actorId);
    return id;
  }

  private void insertPayload(UUID reportId) {
    jdbcTemplate.update("""
        insert into trading_lab.report_chunks (
          id, report_id, section, sequence, encoding,
          uncompressed_bytes, compressed_bytes, payload, checksum)
        values (?, ?, 'METADATA', 0, 'GZIP', 2, 2, ?, ?)
        """,
        UUID.randomUUID(),
        reportId,
        new byte[] {1, 2},
        "sha256:" + "d".repeat(64));
    jdbcTemplate.update("""
        insert into trading_lab.report_appends (
          report_id, section, source_sequence, canonical_bytes, canonical_checksum)
        values (?, 'METADATA', -1, 2, ?)
        """, reportId, "sha256:" + "e".repeat(64));
  }

  private void insertRunHistory(UUID runId) {
    jdbcTemplate.update("""
        insert into trading_lab.run_transitions (
          id, run_id, from_state, to_state, run_version,
          reason, idempotency_key, actor_id, details_json)
        values (?, ?, 'RUNNING', 'COMPLETED', 1,
                'report admin fixture', ?, ?, '{}'::jsonb)
        """, UUID.randomUUID(), runId, "report-admin-" + runId, actorId);
    jdbcTemplate.update("""
        insert into trading_lab.run_events (
          id, run_id, sequence, event_type, real_time, payload_json)
        values (?, ?, 0, 'REPORT_ADMIN_FIXTURE', clock_timestamp(), '{}'::jsonb)
        """, UUID.randomUUID(), runId);
  }

  private void installAuditFailureTrigger() {
    jdbcTemplate.execute("""
        create function trading_lab.report_admin_it_reject_audit()
        returns trigger
        language plpgsql
        as $$
        begin
          if new.action = 'TRADING_LAB_REPORT_DELETE' then
            raise exception 'report delete audit rejected';
          end if;
          return new;
        end
        $$
        """);
    jdbcTemplate.execute("""
        create trigger report_admin_it_reject_audit
        before insert on trading_lab.audit_events
        for each row execute function trading_lab.report_admin_it_reject_audit()
        """);
  }

  private void dropAuditFailureTrigger() {
    jdbcTemplate.execute("""
        drop trigger if exists report_admin_it_reject_audit
        on trading_lab.audit_events
        """);
    jdbcTemplate.execute("""
        drop function if exists trading_lab.report_admin_it_reject_audit()
        """);
  }

  private long count(String table, String column, UUID id) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?",
        Long.class,
        id);
  }

  private UUID runReportId(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select report_id from trading_lab.runs where id = ?",
        UUID.class,
        runId);
  }

  private void lockRow(Connection connection, String table, UUID id)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "select id from " + table + " where id = ? for update")) {
      statement.setObject(1, id);
      assertThat(statement.executeQuery().next()).isTrue();
    }
  }

  private void deleteFixture() {
    jdbcTemplate.update("delete from audit.audit_logs");
    jdbcTemplate.update("delete from trading_lab.audit_events");
    jdbcTemplate.update("delete from trading_lab.report_appends");
    jdbcTemplate.update("delete from trading_lab.report_chunks");
    jdbcTemplate.update("delete from trading_lab.run_events");
    jdbcTemplate.update("delete from trading_lab.run_transitions");
    jdbcTemplate.update("delete from trading_lab.runs");
    jdbcTemplate.update("delete from trading_lab.reports");
    jdbcTemplate.update("delete from trading_lab.scenarios");
    jdbcTemplate.update(
        "delete from auth.users where email like '%@trading-lab-it.test'");
  }

  @SpringBootConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
      MybatisPlusConfig.class,
      AuditFieldFillHandler.class,
      TradingLabReportAdminService.class,
      TradingLabAuditService.class,
      TradingLabCredentialSanitizer.class,
      AuditLogService.class
  })
  static class TestApplication {

    @Bean
    MapperFactoryBean<TradingLabReportRepository> tradingLabReportRepository(
        SqlSessionFactory sqlSessionFactory
    ) {
      return mapper(TradingLabReportRepository.class, sqlSessionFactory);
    }

    @Bean
    MapperFactoryBean<TradingLabReportChunkRepository> tradingLabReportChunkRepository(
        SqlSessionFactory sqlSessionFactory
    ) {
      return mapper(TradingLabReportChunkRepository.class, sqlSessionFactory);
    }

    @Bean
    MapperFactoryBean<TradingLabReportAppendRepository> tradingLabReportAppendRepository(
        SqlSessionFactory sqlSessionFactory
    ) {
      return mapper(TradingLabReportAppendRepository.class, sqlSessionFactory);
    }

    @Bean
    MapperFactoryBean<TradingLabReportWriteFenceRepository>
        tradingLabReportWriteFenceRepository(
            SqlSessionFactory sqlSessionFactory
        ) {
      return mapper(TradingLabReportWriteFenceRepository.class, sqlSessionFactory);
    }

    @Bean
    MapperFactoryBean<TradingLabAuditEventRepository> tradingLabAuditEventRepository(
        SqlSessionFactory sqlSessionFactory
    ) {
      return mapper(TradingLabAuditEventRepository.class, sqlSessionFactory);
    }

    @Bean
    MapperFactoryBean<AuditLogRepository> auditLogRepository(
        SqlSessionFactory sqlSessionFactory
    ) {
      return mapper(AuditLogRepository.class, sqlSessionFactory);
    }

    @Bean
    TradingLabReportStreamer tradingLabReportStreamer() {
      return mock(TradingLabReportStreamer.class);
    }

    @Bean
    TradingLabPrettyReportStreamer tradingLabPrettyReportStreamer() {
      return mock(TradingLabPrettyReportStreamer.class);
    }

    @Bean
    TradingLabPrintConfirmationService tradingLabPrintConfirmationService() {
      return mock(TradingLabPrintConfirmationService.class);
    }

    private static <T> MapperFactoryBean<T> mapper(
        Class<T> mapperType,
        SqlSessionFactory sqlSessionFactory
    ) {
      MapperFactoryBean<T> mapper = new MapperFactoryBean<>(mapperType);
      mapper.setSqlSessionFactory(sqlSessionFactory);
      return mapper;
    }
  }
}
