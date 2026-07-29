package com.fxplatform.tradinglab.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradingLabAuditService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TradingLabAuditEventRepository auditEventRepository;
  private final AuditLogService auditLogService;
  private final TradingLabCredentialSanitizer credentialSanitizer;

  @Transactional
  public void record(
      UUID actorId,
      String clientIp,
      UUID requestId,
      UUID scenarioId,
      UUID runId,
      String action,
      String result,
      Map<String, Object> details
  ) {
    if (requestId == null) {
      throw new IllegalArgumentException("Trading Lab audit request ID is required");
    }
    Map<String, Object> sanitizedDetails = credentialSanitizer.sanitize(details);
    String labDetailsJson = serialize(sanitizedDetails);
    String genericDetailsJson = serialize(genericEnvelope(
        clientIp,
        result,
        scenarioId,
        runId,
        sanitizedDetails));
    String genericTargetType = targetType(scenarioId, runId);
    String genericTargetId = targetId(requestId, scenarioId, runId).toString();

    TradingLabAuditEventEntity event = new TradingLabAuditEventEntity();
    event.setActorId(actorId);
    event.setClientIp(clientIp);
    event.setRequestId(requestId);
    event.setScenarioId(scenarioId);
    event.setRunId(runId);
    event.setAction(action);
    event.setResult(result);
    event.setDetailsJson(labDetailsJson);

    auditEventRepository.save(event);
    auditLogService.recordWithRequestId(
        actorId,
        action,
        genericTargetType,
        genericTargetId,
        requestId,
        genericDetailsJson);
  }

  private Map<String, Object> genericEnvelope(
      String clientIp,
      String result,
      UUID scenarioId,
      UUID runId,
      Map<String, Object> sanitizedDetails
  ) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("clientIp", clientIp);
    envelope.put("result", result);
    envelope.put("scenarioId", scenarioId == null ? null : scenarioId.toString());
    envelope.put("runId", runId == null ? null : runId.toString());
    envelope.put("details", sanitizedDetails);
    return envelope;
  }

  private String targetType(UUID scenarioId, UUID runId) {
    if (runId != null) {
      return "TRADING_LAB_RUN";
    }
    if (scenarioId != null) {
      return "TRADING_LAB_SCENARIO";
    }
    return "TRADING_LAB_REQUEST";
  }

  private UUID targetId(UUID requestId, UUID scenarioId, UUID runId) {
    if (runId != null) {
      return runId;
    }
    if (scenarioId != null) {
      return scenarioId;
    }
    return requestId;
  }

  private String serialize(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException | RuntimeException exception) {
      throw new IllegalArgumentException("Unable to serialize Trading Lab audit details", exception);
    }
  }
}
