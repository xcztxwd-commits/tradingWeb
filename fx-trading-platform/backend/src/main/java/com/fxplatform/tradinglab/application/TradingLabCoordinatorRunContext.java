package com.fxplatform.tradinglab.application;

import com.fxplatform.tradinglab.report.TradingLabReportWriteFence;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import java.util.Objects;

public record TradingLabCoordinatorRunContext(
    TradingLabWorkerRunSnapshot run,
    String claimOwner
) {

  public TradingLabCoordinatorRunContext {
    Objects.requireNonNull(run, "run");
    if (claimOwner == null || claimOwner.isBlank()) {
      throw new IllegalArgumentException("claimOwner is required");
    }
  }

  public TradingLabRunState state() {
    return TradingLabRunState.valueOf(run.state());
  }

  public TradingLabReportWriteFence reportFence() {
    if (!run.hasReport()) {
      throw new IllegalStateException("Trading Lab worker run has no report");
    }
    return new TradingLabReportWriteFence(run.runId(), run.reportId(), claimOwner);
  }
}
