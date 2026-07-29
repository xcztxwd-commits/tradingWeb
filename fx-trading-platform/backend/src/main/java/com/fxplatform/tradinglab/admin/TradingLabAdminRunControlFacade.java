package com.fxplatform.tradinglab.admin;

import com.fxplatform.tradinglab.state.TradingLabRunControlResult;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TradingLabAdminRunControlFacade {

  private final TradingLabAdminRunControlCommand command;
  private final TradingLabRunControlFailureAuditor failureAuditor;

  public TradingLabRunControlResult execute(
      TradingLabRunControlAction action,
      UUID runId,
      UUID actorId,
      String clientIp,
      UUID requestId
  ) {
    try {
      return command.execute(action, runId, actorId, clientIp, requestId);
    } catch (RuntimeException failure) {
      failureAuditor.record(
          action,
          runId,
          actorId,
          clientIp,
          requestId,
          failure);
      throw failure;
    }
  }
}
