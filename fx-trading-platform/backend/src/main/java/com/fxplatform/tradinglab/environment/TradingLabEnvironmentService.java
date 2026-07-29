package com.fxplatform.tradinglab.environment;

import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.client.ValidationSupervisorActionResult;
import com.fxplatform.tradinglab.client.ValidationSupervisorClient;
import com.fxplatform.tradinglab.client.ValidationSupervisorClientException;
import com.fxplatform.tradinglab.client.ValidationSupervisorHealth;
import com.fxplatform.tradinglab.client.ValidationSupervisorStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!validation")
@RequiredArgsConstructor
public class TradingLabEnvironmentService {

  private static final String CHECK_ACTION = "TRADING_LAB_ENVIRONMENT_CHECKED";
  private static final String MUTATION_ACTION = "TRADING_LAB_ENVIRONMENT_ACTION";

  private final ValidationSupervisorClient supervisorClient;
  private final TradingLabOperationGate operationGate;
  private final TradingLabAuditService auditService;

  public TradingLabEnvironmentStatusResponse status(
      UUID actorId,
      String clientIp,
      UUID requestId
  ) {
    requireAuditContext(actorId, clientIp, requestId);
    try {
      ValidationSupervisorStatus status = supervisorClient.status();
      ValidationSupervisorHealth health = supervisorClient.health();
      TradingLabEnvironmentStatusResponse response =
          new TradingLabEnvironmentStatusResponse(
              status.relayRunning(),
              health.status());
      auditService.record(
          actorId,
          clientIp,
          requestId,
          null,
          null,
          CHECK_ACTION,
          "SUCCESS",
          Map.of(
              "relayRunning", response.relayRunning(),
              "validationHealth", response.validationHealth()));
      return response;
    } catch (ValidationSupervisorClientException failure) {
      auditService.record(
          actorId,
          clientIp,
          requestId,
          null,
          null,
          CHECK_ACTION,
          "FAILED",
          failureDetails(null, failure));
      throw TradingLabEnvironmentException.supervisorUnavailable();
    }
  }

  @Transactional(noRollbackFor = TradingLabEnvironmentException.class)
  public TradingLabEnvironmentActionResponse action(
      TradingLabEnvironmentAction action,
      UUID actorId,
      String clientIp,
      UUID requestId
  ) {
    Objects.requireNonNull(action, "action");
    requireAuditContext(actorId, clientIp, requestId);
    try {
      operationGate.acquireEnvironmentMutationPermit();
      if (action.idleEnvironmentRequired()
          && operationGate.hasNonTerminalRuns()) {
        throw TradingLabEnvironmentException.activeRunConflict();
      }

      ValidationSupervisorActionResult result = switch (action) {
        case START -> supervisorClient.start();
        case STOP -> supervisorClient.stop();
        case RESTART -> supervisorClient.restart();
      };
      TradingLabEnvironmentActionResponse response =
          new TradingLabEnvironmentActionResponse(
              action.pathValue(),
              result.relayRunning());
      auditService.record(
          actorId,
          clientIp,
          requestId,
          null,
          null,
          MUTATION_ACTION,
          "SUCCESS",
          Map.of(
              "action", action.pathValue(),
              "relayRunning", response.relayRunning()));
      return response;
    } catch (TradingLabEnvironmentException failure) {
      auditService.record(
          actorId,
          clientIp,
          requestId,
          null,
          null,
          MUTATION_ACTION,
          "FAILED",
          Map.of(
              "action", action.pathValue(),
              "failureCode", failure.getCode()));
      throw failure;
    } catch (ValidationSupervisorClientException failure) {
      auditService.record(
          actorId,
          clientIp,
          requestId,
          null,
          null,
          MUTATION_ACTION,
          "FAILED",
          failureDetails(action, failure));
      throw mapSupervisorFailure(failure);
    }
  }

  private static TradingLabEnvironmentException mapSupervisorFailure(
      ValidationSupervisorClientException failure
  ) {
    if (failure.reason()
        == ValidationSupervisorClientException.Reason.MUTATION_BUSY) {
      return TradingLabEnvironmentException.mutationBusy();
    }
    return TradingLabEnvironmentException.supervisorUnavailable();
  }

  private static Map<String, Object> failureDetails(
      TradingLabEnvironmentAction action,
      ValidationSupervisorClientException failure
  ) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (action != null) {
      details.put("action", action.pathValue());
    }
    details.put("failureCode", failure.reason().name());
    details.put("supervisorCode", failure.remoteCode());
    details.put("httpStatus", failure.httpStatus());
    return details;
  }

  private static void requireAuditContext(
      UUID actorId,
      String clientIp,
      UUID requestId
  ) {
    Objects.requireNonNull(actorId, "actorId");
    if (clientIp == null || clientIp.isBlank()) {
      throw new IllegalArgumentException("clientIp is required");
    }
    Objects.requireNonNull(requestId, "requestId");
  }
}
