package com.fxplatform.tradinglab.application;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.queue.TradingLabRunClaim;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TradingLabCoordinatorRunLoader {

  private final TradingLabRunRepository runs;

  public TradingLabCoordinatorRunLoader(TradingLabRunRepository runs) {
    this.runs = Objects.requireNonNull(runs, "runs");
  }

  public TradingLabCoordinatorRunContext requireLive(TradingLabRunClaim claim) {
    Objects.requireNonNull(claim, "claim");
    return requireLive(claim.runId(), claim.claimOwner());
  }

  public TradingLabCoordinatorRunContext requireLive(UUID runId, String claimOwner) {
    requireIdentity(runId, claimOwner);
    return new TradingLabCoordinatorRunContext(
        runs.findLiveWorkerSnapshot(runId, claimOwner)
            .orElseThrow(TradingLabCoordinatorRunLoader::fenceLost),
        claimOwner);
  }

  public TradingLabCoordinatorRunContext lockLive(UUID runId, String claimOwner) {
    requireIdentity(runId, claimOwner);
    return new TradingLabCoordinatorRunContext(
        runs.lockLiveWorkerSnapshot(runId, claimOwner)
            .orElseThrow(TradingLabCoordinatorRunLoader::fenceLost),
        claimOwner);
  }

  private static void requireIdentity(UUID runId, String claimOwner) {
    Objects.requireNonNull(runId, "runId");
    if (claimOwner == null || claimOwner.isBlank() || claimOwner.length() > 160) {
      throw new IllegalArgumentException("claimOwner is required");
    }
  }

  private static BusinessException fenceLost() {
    return new BusinessException(
        "TRADING_LAB_FENCE_LOST",
        "Trading Lab worker lease is no longer valid");
  }
}
