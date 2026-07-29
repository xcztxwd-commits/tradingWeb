package com.fxplatform.tradinglab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunTransitionEntity;
import com.fxplatform.tradinglab.entity.TradingLabScenarioEntity;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunEventRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunTransitionRepository;
import com.fxplatform.tradinglab.repository.TradingLabScenarioRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
class TradingLabPersistencePostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingLabScenarioRepository scenarioRepository;
  @Autowired private TradingLabReportRepository reportRepository;
  @Autowired private TradingLabRunRepository runRepository;
  @Autowired private TradingLabRunTransitionRepository transitionRepository;
  @Autowired private TradingLabRunEventRepository eventRepository;
  @Autowired private TradingLabReportChunkRepository chunkRepository;
  @Autowired private TradingLabAuditEventRepository auditEventRepository;
  @Autowired private TradingLabAuditService auditService;

  @MockitoBean
  private AuditLogService genericAuditService;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  @AfterEach
  void clearGenericAuditStub() {
    reset(genericAuditService);
  }

  @Test
  void everyTradingLabMapperRoundTripsJsonbByteaAndCasTimestamps() {
    UUID actorId = insertUser();
    UUID scenarioId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    TradingLabScenarioEntity scenario = new TradingLabScenarioEntity();
    scenario.setId(scenarioId);
    scenario.setName("Mapper round trip");
    scenario.setDescription("postgres mapper proof");
    scenario.setStatus("DRAFT");
    scenario.setNegativeMode(false);
    scenario.setSeed("seed-007");
    scenario.setModelVersion("model-mapper");
    scenario.setScenarioJson("{\"kind\":\"mapper\",\"seed\":\"seed-007\"}");
    scenario.setConfigSnapshotJson("{\"mode\":\"demo\"}");
    scenario.setConfigSnapshotHash("a".repeat(64));
    scenario.setSymbolConfigVersion("symbols-mapper");
    scenario.setCodeVersion("code-mapper");
    scenario.setCreatedBy(actorId);
    scenario.setUpdatedBy(actorId);
    scenario.setVersion(0L);
    scenarioRepository.save(scenario);

    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setScenarioId(scenarioId);
    report.setStatus("PENDING");
    report.setModelVersion("model-mapper");
    report.setConfigSnapshotHash("b".repeat(64));
    report.setCodeVersion("code-mapper");
    report.setMetadataJson("{\"report\":\"mapper\"}");
    report.setUncompressedBytes(6L);
    report.setCompressedBytes(4L);
    report.setChunkCount(1);
    report.setRetainedUntil(now.plus(30, ChronoUnit.DAYS));
    report.setPermanent(false);
    report.setCreatedBy(actorId);
    report.setVersion(0L);
    reportRepository.save(report);

    TradingLabRunEntity run = new TradingLabRunEntity();
    run.setId(runId);
    run.setScenarioId(scenarioId);
    run.setState("QUEUED");
    run.setCancelRequested(false);
    run.setPauseRequested(false);
    run.setProcessedTicks(0L);
    run.setTotalTicks(10L);
    run.setSpeedMultiplier(new BigDecimal("1.000000"));
    run.setCurrentStep(0L);
    run.setReportId(reportId);
    run.setScenarioSnapshotJson("{\"snapshot\":\"mapper\"}");
    run.setConfigSnapshotJson("{\"mode\":\"demo\"}");
    run.setConfigSnapshotHash("c".repeat(64));
    run.setModelVersion("model-mapper");
    run.setSymbolConfigVersion("symbols-mapper");
    run.setCodeVersion("code-mapper");
    run.setCreatedBy(actorId);
    run.setVersion(0L);
    runRepository.save(run);

    TradingLabRunTransitionEntity transition = new TradingLabRunTransitionEntity();
    transition.setId(UUID.randomUUID());
    transition.setRunId(runId);
    transition.setFromState("QUEUED");
    transition.setToState("RESETTING");
    transition.setRunVersion(1L);
    transition.setReason("mapper proof");
    transition.setIdempotencyKey("mapper-transition");
    transition.setRealTime(now);
    transition.setActorId(actorId);
    transition.setDetailsJson("{\"transition\":true}");
    transitionRepository.save(transition);

    TradingLabRunEventEntity event = new TradingLabRunEventEntity();
    event.setId(UUID.randomUUID());
    event.setRunId(runId);
    event.setSequence(1L);
    event.setEventType("TICK");
    event.setRealTime(now);
    event.setCorrelationId("mapper-correlation");
    event.setPayloadJson("{\"price\":60000}");
    eventRepository.save(event);

    byte[] payload = new byte[] {0, 1, 2, 3, 127, -1};
    TradingLabReportChunkEntity chunk = new TradingLabReportChunkEntity();
    chunk.setId(UUID.randomUUID());
    chunk.setReportId(reportId);
    chunk.setSection("events");
    chunk.setSequence(0L);
    chunk.setEncoding("GZIP");
    chunk.setUncompressedBytes(6L);
    chunk.setCompressedBytes(4L);
    chunk.setPayload(payload);
    chunk.setChecksum("mapper-checksum");
    chunkRepository.save(chunk);

    TradingLabAuditEventEntity audit = new TradingLabAuditEventEntity();
    audit.setId(UUID.randomUUID());
    audit.setActorId(actorId);
    audit.setClientIp("127.0.0.1");
    audit.setRequestId(UUID.randomUUID());
    audit.setScenarioId(scenarioId);
    audit.setRunId(runId);
    audit.setAction("MAPPER_PROOF");
    audit.setResult("SUCCESS");
    audit.setDetailsJson("{\"safe\":true}");
    auditEventRepository.save(audit);

    assertThat(scenarioRepository.selectById(scenarioId).getScenarioJson())
        .isEqualTo("{\"kind\": \"mapper\"}");
    assertThat(reportRepository.selectById(reportId).getMetadataJson())
        .isEqualTo("{\"report\": \"mapper\"}");
    TradingLabRunEntity storedRun = runRepository.selectById(runId);
    assertThat(storedRun.getScenarioSnapshotJson())
        .isEqualTo("{\"snapshot\": \"mapper\"}");
    assertThat(storedRun.getConfigSnapshotJson()).isEqualTo("{\"mode\": \"demo\"}");
    assertThat(storedRun.getQueueSequence()).isPositive();
    assertThat(storedRun.getCreatedAt()).isNotNull();
    assertThat(storedRun.getUpdatedAt()).isNotNull();
    assertThat(transitionRepository.selectById(transition.getId()).getDetailsJson())
        .isEqualTo("{\"transition\": true}");
    assertThat(eventRepository.selectById(event.getId()).getPayloadJson())
        .isEqualTo("{\"price\": 60000}");
    assertThat(chunkRepository.selectById(chunk.getId()).getPayload())
        .containsExactly(payload);
    assertThat(auditEventRepository.selectById(audit.getId()).getDetailsJson())
        .isEqualTo("{\"safe\": true}");
  }

  @Test
  void genericAuditFailureRollsBackTheEarlierTradingLabAuditInsert() {
    UUID requestId = UUID.randomUUID();
    doThrow(new IllegalStateException("generic audit unavailable"))
        .when(genericAuditService)
        .recordWithRequestId(any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> auditService.record(
        null,
        "127.0.0.1",
        requestId,
        null,
        null,
        "ROLLBACK_PROOF",
        "FAILED",
        Map.of("accessToken", "must-never-persist", "safe", "visible")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generic audit unavailable");

    assertThat(auditEventRepository.selectCount(
        new LambdaQueryWrapper<TradingLabAuditEventEntity>()
            .eq(TradingLabAuditEventEntity::getRequestId, requestId)))
        .isZero();
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'test-hash', 'ACTIVE', 'ADMIN')
        """, id, "mapper-" + id + "@trading-lab.test");
    return id;
  }
}
