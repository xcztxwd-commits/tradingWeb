package com.fxplatform.tradinglab.admin;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabAuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradingLabRunControlFailureAuditor {

  private final TradingLabAuditService audit;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      TradingLabRunControlAction action,
      UUID runId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      RuntimeException failure
  ) {
    TradingLabAdminRunControlCommand.requireContext(
        action, runId, actorId, clientIp, requestId);
    String code = failureCode(failure);
    boolean missingRun = "TRADING_LAB_RUN_NOT_FOUND".equals(code);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("action", action.auditValue());
    details.put("failureCode", code);
    if (missingRun) {
      details.put("requestedRunId", runId.toString());
    }
    audit.record(
        actorId,
        clientIp,
        requestId,
        null,
        missingRun ? null : runId,
        TradingLabAdminRunControlCommand.AUDIT_ACTION,
        "FAILED",
        details);
  }

  private static String failureCode(RuntimeException failure) {
    if (failure instanceof BusinessException business
        && business.getCode() != null
        && !business.getCode().isBlank()) {
      return business.getCode();
    }
    return "TRADING_LAB_CONTROL_FAILED";
  }
}
