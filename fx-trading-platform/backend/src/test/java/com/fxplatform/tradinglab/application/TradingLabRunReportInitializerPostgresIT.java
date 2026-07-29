package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.entity.TradingLabScenarioEntity;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabScenarioRepository;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
class TradingLabRunReportInitializerPostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingLabScenarioRepository scenarios;
  @Autowired private TradingLabReportRepository reports;
  @Autowired private TradingLabRunRepository runs;
  @Autowired private TradingLabAuditEventRepository audits;
  @Autowired private ObjectMapper json;
  @Autowired private TradingLabHttpTraceSanitizer traceSanitizer;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  @Test
  void durableAdminAcceptanceIsReadFromPostgresAndReplayedAsPreamble() {
    UUID actorId = insertUser();
    UUID scenarioId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    Instant virtualTime = Instant.parse("2026-07-25T00:00:00Z");
    String configHash = "a".repeat(64);
    long totalTicks = 2L;
    insertScenario(scenarioId, actorId, configHash);
    insertReport(reportId, scenarioId, actorId, configHash);
    insertRun(
        runId,
        scenarioId,
        reportId,
        actorId,
        virtualTime,
        configHash,
        totalTicks);
    insertAcceptanceAudit(
        runId,
        scenarioId,
        reportId,
        actorId,
        requestId,
        configHash,
        totalTicks);

    List<TradingLabAuditEventEntity> stored =
        audits.findRunCreationSuccessAudits(runId);
    assertThat(stored).singleElement().satisfies(audit -> {
      assertThat(audit.getRequestId()).isEqualTo(requestId);
      assertThat(audit.getDetailsJson())
          .contains(
              "\"evidenceType\": \"ADMIN_RUN_ACCEPTED\"",
              "\"httpPath\": \"/api/admin/trading-lab/scenarios/"
                  + scenarioId + "/runs\"");
    });

    TradingLabWorkerRunSnapshot snapshot = new TradingLabWorkerRunSnapshot(
        runId,
        scenarioId,
        reportId,
        "PENDING",
        "QUEUED",
        2L,
        false,
        false,
        virtualTime,
        virtualTime,
        0L,
        totalTicks,
        BigDecimal.ONE,
        "{}",
        "{}",
        "{\"expectedEquity\":\"100000.00\"}",
        configHash,
        "model-admin-audit",
        "symbols-admin-audit",
        "admin-audit+working-tree",
        actorId);
    String owner = "admin-audit-worker";
    TradingLabCoordinatorRunLoader loader = mock(TradingLabCoordinatorRunLoader.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabCoordinatorRunContext context =
        new TradingLabCoordinatorRunContext(snapshot, owner);
    when(loader.requireLive(runId, owner)).thenReturn(context);
    TradingLabRunReportInitializer initializer = new TradingLabRunReportInitializer(
        loader,
        writer,
        json,
        new TradingLabReportProperties(),
        audits,
        traceSanitizer);
    ArgumentCaptor<Object> traces = ArgumentCaptor.forClass(Object.class);

    initializer.initialize(runId, owner);
    initializer.initialize(runId, owner);

    verify(writer, times(2)).appendEvent(
        eq(context.reportFence()),
        eq(TradingLabReportSection.API_TRACE),
        eq(-1L),
        traces.capture());
    assertThat(traces.getAllValues()).hasSize(2);
    Map<String, Object> first =
        ((SafeTradingLabHttpTrace) traces.getAllValues().get(0)).toSafeMap();
    Map<String, Object> second =
        ((SafeTradingLabHttpTrace) traces.getAllValues().get(1)).toSafeMap();
    assertThat(second).isEqualTo(first);
    assertThat(first).containsOnlyKeys(
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
    assertThat(first.get("url")).isEqualTo(
        "http://main-admin.invalid/api/admin/trading-lab/scenarios/"
            + scenarioId + "/runs");
    assertThat(stringMap(first.get("requestBody")))
        .containsEntry("sequence", 0L)
        .containsEntry("environment", "main-admin")
        .containsEntry("method", "POST");
    assertThat(stringMap(stringMap(first.get("requestBody")).get("sanitizedRequest")))
        .containsEntry("originKind", "LOGICAL_ROUTE")
        .containsEntry("evidenceType", "ADMIN_RUN_ACCEPTED");
    assertThat(stringMap(first.get("responseBody"))).containsEntry("status", 200);
    assertThat(first.toString())
        .doesNotContain("198.51.100.42", "Authorization", "accessToken", "password");
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'test-hash', 'ACTIVE', 'ADMIN')
        """, id, "admin-audit-" + id + "@trading-lab.test");
    return id;
  }

  private void insertScenario(UUID scenarioId, UUID actorId, String configHash) {
    TradingLabScenarioEntity scenario = new TradingLabScenarioEntity();
    scenario.setId(scenarioId);
    scenario.setName("Admin acceptance audit");
    scenario.setStatus("FROZEN");
    scenario.setNegativeMode(false);
    scenario.setSeed("admin-audit-seed");
    scenario.setModelVersion("model-admin-audit");
    scenario.setScenarioJson("{\"seed\":\"admin-audit-seed\"}");
    scenario.setConfigSnapshotJson("{\"execution\":{\"mode\":\"demo\"}}");
    scenario.setConfigSnapshotHash(configHash);
    scenario.setSymbolConfigVersion("symbols-admin-audit");
    scenario.setCodeVersion("admin-audit+working-tree");
    scenario.setCreatedBy(actorId);
    scenario.setUpdatedBy(actorId);
    scenario.setVersion(1L);
    scenarios.save(scenario);
  }

  private void insertReport(
      UUID reportId,
      UUID scenarioId,
      UUID actorId,
      String configHash
  ) {
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setScenarioId(scenarioId);
    report.setStatus("PENDING");
    report.setModelVersion("model-admin-audit");
    report.setConfigSnapshotHash(configHash);
    report.setCodeVersion("admin-audit+working-tree");
    report.setMetadataJson("{}");
    report.setUncompressedBytes(0L);
    report.setCompressedBytes(0L);
    report.setChunkCount(0);
    report.setRetainedUntil(Instant.now().plus(30, ChronoUnit.DAYS));
    report.setPermanent(false);
    report.setCreatedBy(actorId);
    report.setVersion(0L);
    reports.save(report);
  }

  private void insertRun(
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      UUID actorId,
      Instant virtualTime,
      String configHash,
      long totalTicks
  ) {
    TradingLabRunEntity run = new TradingLabRunEntity();
    run.setId(runId);
    run.setScenarioId(scenarioId);
    run.setState("QUEUED");
    run.setCancelRequested(false);
    run.setPauseRequested(false);
    run.setVirtualStartedAt(virtualTime);
    run.setVirtualCurrentAt(virtualTime);
    run.setProcessedTicks(0L);
    run.setTotalTicks(totalTicks);
    run.setSpeedMultiplier(BigDecimal.ONE);
    run.setCurrentStep(0L);
    run.setReportId(reportId);
    run.setScenarioSnapshotJson("{}");
    run.setConfigSnapshotJson("{}");
    run.setLocalCalculationJson("{\"expectedEquity\":\"100000.00\"}");
    run.setConfigSnapshotHash(configHash);
    run.setModelVersion("model-admin-audit");
    run.setSymbolConfigVersion("symbols-admin-audit");
    run.setCodeVersion("admin-audit+working-tree");
    run.setCreatedBy(actorId);
    run.setVersion(2L);
    runs.save(run);
  }

  private void insertAcceptanceAudit(
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      UUID actorId,
      UUID requestId,
      String configHash,
      long totalTicks
  ) {
    TradingLabAuditEventEntity audit = new TradingLabAuditEventEntity();
    audit.setId(UUID.randomUUID());
    audit.setActorId(actorId);
    audit.setClientIp("198.51.100.42");
    audit.setRequestId(requestId);
    audit.setScenarioId(scenarioId);
    audit.setRunId(runId);
    audit.setAction("TRADING_LAB_RUN_CREATE");
    audit.setResult("SUCCESS");
    audit.setDetailsJson("""
        {
          "reportId":"%s",
          "configSnapshotHash":"%s",
          "totalTicks":%d,
          "httpMethod":"POST",
          "httpPath":"/api/admin/trading-lab/scenarios/%s/runs",
          "httpStatus":200,
          "evidenceType":"ADMIN_RUN_ACCEPTED"
        }
        """.formatted(reportId, configHash, totalTicks, scenarioId));
    audit.setCreatedAt(Instant.parse("2026-07-25T00:00:01Z"));
    audits.save(audit);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> stringMap(Object value) {
    return (Map<String, Object>) value;
  }
}
