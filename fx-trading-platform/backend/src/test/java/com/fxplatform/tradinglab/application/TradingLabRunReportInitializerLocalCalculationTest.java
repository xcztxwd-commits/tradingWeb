package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizerTestFactory;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class TradingLabRunReportInitializerLocalCalculationTest {

  @Test
  void projectsTheFrozenLocalCalculationInsteadOfAnEmptyPlaceholder() {
    Fixture fixture = fixture();

    fixture.initializer().initialize(fixture.runId(), fixture.owner());

    verify(fixture.writer()).appendSingleton(
        fixture.fence(),
        TradingLabReportSection.LOCAL_CALCULATION,
        Map.of("expectedEquity", "100000.00"));
  }

  @Test
  void actorMetadataDoesNotInventCredentialBearingFields() {
    Fixture fixture = fixture();
    ArgumentCaptor<Object> actorValue = ArgumentCaptor.forClass(Object.class);

    fixture.initializer().initialize(fixture.runId(), fixture.owner());

    verify(fixture.writer()).appendSingleton(
        eq(fixture.fence()),
        eq(TradingLabReportSection.ACTOR),
        actorValue.capture());
    assertThat(actorValue.getValue())
        .isEqualTo(Map.of("actorId", fixture.actorId().toString()));
  }

  @Test
  void projectsTheUniqueDurableAdminAcceptanceAsASealedPreambleTrace() {
    Fixture fixture = fixture();
    ArgumentCaptor<Object> traceValue = ArgumentCaptor.forClass(Object.class);

    fixture.initializer().initialize(fixture.runId(), fixture.owner());

    verify(fixture.writer()).appendEvent(
        eq(fixture.fence()),
        eq(TradingLabReportSection.API_TRACE),
        eq(-1L),
        traceValue.capture());
    assertThat(traceValue.getValue()).isInstanceOf(SafeTradingLabHttpTrace.class);
    Map<String, Object> trace =
        ((SafeTradingLabHttpTrace) traceValue.getValue()).toSafeMap();
    assertThat(trace).containsOnlyKeys(
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
    assertThat(trace)
        .containsEntry(
            "url",
            "http://main-admin.invalid/api/admin/trading-lab/scenarios/"
                + fixture.scenarioId() + "/runs")
        .containsEntry("queryParameters", Map.of())
        .containsEntry("authentication", null)
        .containsEntry("exception", null);
    assertThat(stringMap(trace.get("requestBody")))
        .containsEntry("sequence", 0L)
        .containsEntry("environment", "main-admin")
        .containsEntry("method", "POST")
        .containsEntry(
            "url",
            "http://main-admin.invalid/api/admin/trading-lab/scenarios/"
                + fixture.scenarioId() + "/runs");
    assertThat(stringMap(stringMap(trace.get("requestBody")).get("sanitizedRequest")))
        .containsEntry("originKind", "LOGICAL_ROUTE")
        .containsEntry("evidenceType", "ADMIN_RUN_ACCEPTED")
        .containsEntry("runId", fixture.runId().toString())
        .containsEntry("scenarioId", fixture.scenarioId().toString());
    assertThat(stringMap(trace.get("responseBody")))
        .containsEntry("status", 200)
        .containsEntry("duration", 0L)
        .containsEntry("correlationId", fixture.requestId().toString());
    assertThat(trace.toString())
        .doesNotContain(
            fixture.audit().getClientIp(),
            "Authorization",
            "Cookie",
            "accessToken",
            "password");
  }

  @Test
  void everyRecoveryReplaysTheSamePreambleTraceForWriterIdempotency() {
    Fixture fixture = fixture();
    ArgumentCaptor<Object> traces = ArgumentCaptor.forClass(Object.class);

    fixture.initializer().initialize(fixture.runId(), fixture.owner());
    fixture.initializer().initialize(fixture.runId(), fixture.owner());

    verify(fixture.writer(), times(2)).appendEvent(
        eq(fixture.fence()),
        eq(TradingLabReportSection.API_TRACE),
        eq(-1L),
        traces.capture());
    assertThat(traces.getAllValues())
        .allSatisfy(value -> assertThat(value).isInstanceOf(SafeTradingLabHttpTrace.class));
    assertThat(((SafeTradingLabHttpTrace) traces.getAllValues().get(0)).toSafeMap())
        .isEqualTo(((SafeTradingLabHttpTrace) traces.getAllValues().get(1)).toSafeMap());
  }

  @Test
  void missingOrDuplicateAcceptanceAuditFailsBeforeAnyReportWrite() {
    Fixture missing = fixture();
    when(missing.audits().findRunCreationSuccessAudits(missing.runId()))
        .thenReturn(List.of());

    assertThatThrownBy(() ->
        missing.initializer().initialize(missing.runId(), missing.owner()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab run acceptance audit is invalid");
    verifyNoInteractions(missing.writer());

    Fixture duplicate = fixture();
    TradingLabAuditEventEntity second = validAudit(
        duplicate.runId(),
        duplicate.scenarioId(),
        duplicate.reportId(),
        duplicate.actorId(),
        duplicate.requestId(),
        duplicate.totalTicks(),
        duplicate.configHash());
    second.setId(UUID.fromString("30000000-0000-0000-0000-000000000899"));
    when(duplicate.audits().findRunCreationSuccessAudits(duplicate.runId()))
        .thenReturn(List.of(duplicate.audit(), second));

    assertThatThrownBy(() ->
        duplicate.initializer().initialize(duplicate.runId(), duplicate.owner()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab run acceptance audit is invalid");
    verifyNoInteractions(duplicate.writer());
  }

  @Test
  void malformedOrCredentialBearingAcceptanceAuditFailsClosedWithoutEcho() {
    Fixture fixture = fixture();
    String canary = "raw-access-token-" + UUID.randomUUID();
    fixture.audit().setDetailsJson("""
        {
          "reportId":"%s",
          "configSnapshotHash":"%s",
          "totalTicks":1,
          "httpMethod":"POST",
          "httpPath":"/api/admin/trading-lab/scenarios/%s/runs",
          "httpStatus":200,
          "evidenceType":"ADMIN_RUN_ACCEPTED",
          "accessToken":"%s"
        }
        """.formatted(
            fixture.reportId(),
            fixture.configHash(),
            fixture.scenarioId(),
            canary));

    assertThatThrownBy(() ->
        fixture.initializer().initialize(fixture.runId(), fixture.owner()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab run acceptance audit is invalid")
        .hasMessageNotContaining(canary);
    verifyNoInteractions(fixture.writer());
  }

  private static Fixture fixture() {
    UUID runId = UUID.fromString("30000000-0000-0000-0000-000000000801");
    UUID scenarioId = UUID.fromString("30000000-0000-0000-0000-000000000802");
    UUID reportId = UUID.fromString("30000000-0000-0000-0000-000000000803");
    String owner = "task8-worker";
    UUID actorId = UUID.fromString("30000000-0000-0000-0000-000000000804");
    UUID requestId = UUID.fromString("30000000-0000-0000-0000-000000000805");
    String configHash = "0".repeat(64);
    long totalTicks = 1L;
    TradingLabWorkerRunSnapshot snapshot = new TradingLabWorkerRunSnapshot(
        runId,
        scenarioId,
        reportId,
        "PENDING",
        "QUEUED",
        1L,
        false,
        false,
        Instant.parse("2026-07-24T00:00:00Z"),
        Instant.parse("2026-07-24T00:00:00Z"),
        0L,
        1L,
        BigDecimal.ONE,
        "{}",
        "{}",
        "{\"expectedEquity\":\"100000.00\"}",
        configHash,
        "model-v1",
        "symbol-v1",
        "local+working-tree",
        actorId);
    TradingLabCoordinatorRunLoader loader = mock(TradingLabCoordinatorRunLoader.class);
    TradingLabFencedReportWriter writer = mock(TradingLabFencedReportWriter.class);
    TradingLabAuditEventRepository audits = mock(TradingLabAuditEventRepository.class);
    when(loader.requireLive(runId, owner))
        .thenReturn(new TradingLabCoordinatorRunContext(snapshot, owner));
    TradingLabAuditEventEntity audit = validAudit(
        runId,
        scenarioId,
        reportId,
        actorId,
        requestId,
        totalTicks,
        configHash);
    when(audits.findRunCreationSuccessAudits(runId)).thenReturn(List.of(audit));
    TradingLabHttpTraceSanitizer sanitizer =
        TradingLabHttpTraceSanitizerTestFactory.create(List.of(), 1_048_576);
    TradingLabRunReportInitializer initializer = new TradingLabRunReportInitializer(
        loader,
        writer,
        new ObjectMapper(),
        new TradingLabReportProperties(),
        audits,
        sanitizer);
    return new Fixture(
        initializer,
        writer,
        audits,
        audit,
        new TradingLabCoordinatorRunContext(snapshot, owner).reportFence(),
        runId,
        scenarioId,
        reportId,
        actorId,
        requestId,
        owner,
        configHash,
        totalTicks);
  }

  private static TradingLabAuditEventEntity validAudit(
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      UUID actorId,
      UUID requestId,
      long totalTicks,
      String configHash
  ) {
    TradingLabAuditEventEntity audit = new TradingLabAuditEventEntity();
    audit.setId(UUID.fromString("30000000-0000-0000-0000-000000000806"));
    audit.setActorId(actorId);
    audit.setClientIp("203.0.113.10");
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
    audit.setCreatedAt(Instant.parse("2026-07-24T00:00:01Z"));
    return audit;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> stringMap(Object value) {
    return (Map<String, Object>) value;
  }

  private record Fixture(
      TradingLabRunReportInitializer initializer,
      TradingLabFencedReportWriter writer,
      TradingLabAuditEventRepository audits,
      TradingLabAuditEventEntity audit,
      com.fxplatform.tradinglab.report.TradingLabReportWriteFence fence,
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      UUID actorId,
      UUID requestId,
      String owner,
      String configHash,
      long totalTicks
  ) {
  }
}
