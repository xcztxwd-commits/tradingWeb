package com.fxplatform.tradinglab.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TradingLabRunReportInitializer {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
  private static final String AUDIT_INVALID =
      "Trading Lab run acceptance audit is invalid";
  private static final Set<String> ACCEPTANCE_DETAIL_KEYS = Set.of(
      "reportId",
      "configSnapshotHash",
      "totalTicks",
      "httpMethod",
      "httpPath",
      "httpStatus",
      "evidenceType");

  private final TradingLabCoordinatorRunLoader runLoader;
  private final TradingLabFencedReportWriter writer;
  private final ObjectMapper json;
  private final int maxBytes;
  private final TradingLabAuditEventRepository auditEvents;
  private final TradingLabHttpTraceSanitizer traceSanitizer;

  public TradingLabRunReportInitializer(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabFencedReportWriter writer,
      ObjectMapper json,
      TradingLabReportProperties properties,
      TradingLabAuditEventRepository auditEvents,
      TradingLabHttpTraceSanitizer traceSanitizer
  ) {
    this.runLoader = Objects.requireNonNull(runLoader, "runLoader");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.json = Objects.requireNonNull(json, "json").copy();
    this.maxBytes = Objects.requireNonNull(properties, "properties").maxLogicalValueBytes();
    this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents");
    this.traceSanitizer = Objects.requireNonNull(traceSanitizer, "traceSanitizer");
  }

  public void initialize(UUID runId, String claimOwner) {
    TradingLabWorkerRunSnapshot run = runLoader.requireLive(runId, claimOwner).run();
    SafeTradingLabHttpTrace acceptanceTrace = acceptanceTrace(run);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("runId", run.runId().toString());
    metadata.put("scenarioId", run.scenarioId().toString());
    metadata.put("environment", "validation");
    metadata.put("modelVersion", run.modelVersion());
    metadata.put("configSnapshotHash", run.configSnapshotHash());
    metadata.put("symbolConfigVersion", run.symbolConfigVersion());
    metadata.put("codeVersion", run.codeVersion());
    writer.initializeMetadata(
        runLoader.requireLive(runId, claimOwner).reportFence(), metadata);

    writer.appendSingleton(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.ACTOR,
        Map.of("actorId", run.createdBy().toString()));
    writer.appendSingleton(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.ENVIRONMENT,
        Map.of(
            "name", "validation",
            "origin", "http://127.0.0.1:18087",
            "execution", "demo"));
    writer.appendSingleton(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.SCENARIO,
        Map.of(
            "scenarioId", run.scenarioId().toString(),
            "snapshotHash", run.configSnapshotHash()));
    writer.appendSingleton(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.MODEL_VERSION,
        run.modelVersion());
    writer.appendSingleton(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.CONFIG_SNAPSHOT,
        parse(run.configSnapshotJson(), "config snapshot"));
    writer.appendSingleton(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.LOCAL_CALCULATION,
        parse(run.localCalculationJson(), "local calculation"));
    writer.appendEvent(
        runLoader.requireLive(runId, claimOwner).reportFence(),
        TradingLabReportSection.API_TRACE,
        -1L,
        acceptanceTrace);
    writer.flush(runLoader.requireLive(runId, claimOwner).reportFence());
  }

  private SafeTradingLabHttpTrace acceptanceTrace(TradingLabWorkerRunSnapshot run) {
    try {
      List<TradingLabAuditEventEntity> matches =
          auditEvents.findRunCreationSuccessAudits(run.runId());
      if (matches == null || matches.size() != 1) {
        throw auditInvalid();
      }
      TradingLabAuditEventEntity audit = matches.getFirst();
      Map<String, Object> details = acceptanceDetails(run, audit);
      String path = requiredString(details, "httpPath");
      URI uri = URI.create("http://main-admin.invalid" + path);
      LinkedHashMap<String, Object> sanitizedRequest = new LinkedHashMap<>();
      sanitizedRequest.put("originKind", "LOGICAL_ROUTE");
      sanitizedRequest.put("evidenceType", "ADMIN_RUN_ACCEPTED");
      sanitizedRequest.put("runId", run.runId().toString());
      sanitizedRequest.put("scenarioId", run.scenarioId().toString());
      sanitizedRequest.put("reportId", run.reportId().toString());
      sanitizedRequest.put("requestId", audit.getRequestId().toString());
      sanitizedRequest.put("configSnapshotHash", run.configSnapshotHash());
      sanitizedRequest.put("totalTicks", run.totalTicks());

      LinkedHashMap<String, Object> request = new LinkedHashMap<>();
      request.put("sequence", 0L);
      request.put("environment", "main-admin");
      request.put("method", "POST");
      request.put("url", uri.toASCIIString());
      request.put(
          "virtualTime",
          run.virtualStartedAt() == null ? null : run.virtualStartedAt().toString());
      request.put("realTime", audit.getCreatedAt().toString());
      request.put("sanitizedRequest", sanitizedRequest);

      LinkedHashMap<String, Object> response = new LinkedHashMap<>();
      response.put("status", 200);
      response.put("duration", 0L);
      response.put("traceId", null);
      response.put("correlationId", audit.getRequestId().toString());
      response.put("recordedException", null);
      response.put(
          "sanitizedResponse",
          Map.of(
              "accepted", true,
              "runId", run.runId().toString(),
              "reportId", run.reportId().toString()));

      return traceSanitizer.sanitize(new TradingLabHttpTraceInput(
          uri,
          Map.of(),
          Map.of(),
          "application/json",
          request,
          "application/json",
          response,
          null,
          null,
          List.of(),
          null));
    } catch (RuntimeException invalid) {
      throw auditInvalid();
    }
  }

  private Map<String, Object> acceptanceDetails(
      TradingLabWorkerRunSnapshot run,
      TradingLabAuditEventEntity audit
  ) {
    if (audit == null
        || audit.getId() == null
        || audit.getCreatedAt() == null
        || audit.getRequestId() == null
        || !run.runId().equals(audit.getRunId())
        || !run.scenarioId().equals(audit.getScenarioId())
        || !run.createdBy().equals(audit.getActorId())
        || !"TRADING_LAB_RUN_CREATE".equals(audit.getAction())
        || !"SUCCESS".equals(audit.getResult())
        || audit.getClientIp() == null
        || audit.getClientIp().isBlank()
        || audit.getClientIp().length() > 64) {
      throw auditInvalid();
    }
    Map<String, Object> details = parse(audit.getDetailsJson(), "run acceptance audit");
    String expectedPath =
        "/api/admin/trading-lab/scenarios/" + run.scenarioId() + "/runs";
    if (!ACCEPTANCE_DETAIL_KEYS.equals(details.keySet())
        || !run.reportId().equals(canonicalUuid(details.get("reportId")))
        || !run.configSnapshotHash().equals(requiredString(details, "configSnapshotHash"))
        || exactLong(details.get("totalTicks")) != run.totalTicks()
        || !"POST".equals(requiredString(details, "httpMethod"))
        || !expectedPath.equals(requiredString(details, "httpPath"))
        || exactLong(details.get("httpStatus")) != 200L
        || !"ADMIN_RUN_ACCEPTED".equals(requiredString(details, "evidenceType"))) {
      throw auditInvalid();
    }
    return details;
  }

  private static UUID canonicalUuid(Object value) {
    String text = value instanceof String candidate ? candidate : null;
    if (text == null) {
      throw auditInvalid();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw auditInvalid();
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw auditInvalid();
    }
  }

  private static String requiredString(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw auditInvalid();
    }
    return text;
  }

  private static long exactLong(Object value) {
    try {
      if (value instanceof BigInteger integer) {
        return integer.longValueExact();
      }
      if (value instanceof BigDecimal decimal) {
        return decimal.longValueExact();
      }
      if (value instanceof Byte number) {
        return number.longValue();
      }
      if (value instanceof Short number) {
        return number.longValue();
      }
      if (value instanceof Integer number) {
        return number.longValue();
      }
      if (value instanceof Long number) {
        return number;
      }
    } catch (ArithmeticException invalid) {
      throw auditInvalid();
    }
    throw auditInvalid();
  }

  private static IllegalArgumentException auditInvalid() {
    return new IllegalArgumentException(AUDIT_INVALID);
  }

  private Map<String, Object> parse(String value, String field) {
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException("Trading Lab " + field + " is invalid");
    }
    try {
      return json.readValue(value, MAP);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Trading Lab " + field + " is invalid");
    }
  }
}
