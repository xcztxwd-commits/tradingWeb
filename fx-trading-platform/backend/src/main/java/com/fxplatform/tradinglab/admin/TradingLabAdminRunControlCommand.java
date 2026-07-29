package com.fxplatform.tradinglab.admin;

import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.state.TradingLabRunControlResult;
import com.fxplatform.tradinglab.state.TradingLabRunControlService;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradingLabAdminRunControlCommand {

  static final String AUDIT_ACTION = "TRADING_LAB_RUN_CONTROL";

  private final TradingLabRunControlService controls;
  private final TradingLabAuditService audit;

  @Transactional
  public TradingLabRunControlResult execute(
      TradingLabRunControlAction action,
      UUID runId,
      UUID actorId,
      String clientIp,
      UUID requestId
  ) {
    requireContext(action, runId, actorId, clientIp, requestId);
    TradingLabRunControlResult result = switch (action) {
      case PAUSE -> controls.requestPause(runId);
      case RESUME -> controls.requestResume(runId);
      case CANCEL -> controls.requestCancel(runId);
    };
    audit.record(
        actorId,
        clientIp,
        requestId,
        null,
        runId,
        AUDIT_ACTION,
        "SUCCESS",
        Map.of(
            "action", action.auditValue(),
            "state", result.state().name(),
            "version", result.runVersion(),
            "pauseRequested", result.pauseRequested(),
            "cancelRequested", result.cancelRequested()));
    return result;
  }

  static void requireContext(
      TradingLabRunControlAction action,
      UUID runId,
      UUID actorId,
      String clientIp,
      UUID requestId
  ) {
    Objects.requireNonNull(action, "Trading Lab control action is required");
    Objects.requireNonNull(runId, "Trading Lab run ID is required");
    Objects.requireNonNull(actorId, "Trading Lab actor is required");
    if (clientIp == null || clientIp.isBlank()) {
      throw new IllegalArgumentException("Trading Lab client IP is required");
    }
    Objects.requireNonNull(requestId, "Trading Lab request ID is required");
  }
}
