package com.fxplatform.tradinglab.state;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TradingLabRunControlService {

  private static final Set<TradingLabRunState> CANCELLABLE_STATES = EnumSet.of(
      TradingLabRunState.QUEUED,
      TradingLabRunState.RESETTING,
      TradingLabRunState.RUNNING,
      TradingLabRunState.PAUSED,
      TradingLabRunState.CANCELLING);

  private final TradingLabRunRepository runRepository;

  public TradingLabRunControlService(TradingLabRunRepository runRepository) {
    this.runRepository = runRepository;
  }

  public TradingLabRunControlResult requestPause(UUID runId) {
    TradingLabRunEntity run = load(runId);
    TradingLabRunState state = stateOf(run);
    boolean pauseRequested = pauseRequested(run);
    boolean cancelRequested = cancelRequested(run);
    long version = versionOf(run);

    if (pauseRequested
        && (state == TradingLabRunState.RUNNING || state == TradingLabRunState.PAUSED)) {
      return result(runId, state, version, true, cancelRequested);
    }
    if (state != TradingLabRunState.RUNNING) {
      throw invalidState("pause", state);
    }
    updatePause(runId, state, version, true);
    return result(runId, state, version + 1, true, cancelRequested);
  }

  public TradingLabRunControlResult requestResume(UUID runId) {
    TradingLabRunEntity run = load(runId);
    TradingLabRunState state = stateOf(run);
    boolean pauseRequested = pauseRequested(run);
    boolean cancelRequested = cancelRequested(run);
    long version = versionOf(run);

    if (state != TradingLabRunState.PAUSED) {
      throw invalidState("resume", state);
    }
    if (!pauseRequested) {
      return result(runId, state, version, false, cancelRequested);
    }
    updatePause(runId, state, version, false);
    return result(runId, state, version + 1, false, cancelRequested);
  }

  public TradingLabRunControlResult requestCancel(UUID runId) {
    TradingLabRunEntity run = load(runId);
    TradingLabRunState state = stateOf(run);
    boolean pauseRequested = pauseRequested(run);
    boolean cancelRequested = cancelRequested(run);
    long version = versionOf(run);

    if (!CANCELLABLE_STATES.contains(state)) {
      throw invalidState("cancel", state);
    }
    if (cancelRequested) {
      return result(runId, state, version, pauseRequested, true);
    }
    int updated = runRepository.compareAndSetCancelRequested(
        runId,
        state.name(),
        version);
    requireSingleUpdate(updated);
    return result(runId, state, version + 1, pauseRequested, true);
  }

  private TradingLabRunEntity load(UUID runId) {
    if (runId == null) {
      throw new IllegalArgumentException("runId is required");
    }
    return runRepository.findById(runId)
        .orElseThrow(() -> failure(
            "TRADING_LAB_RUN_NOT_FOUND",
            "Trading Lab run was not found"));
  }

  private void updatePause(
      UUID runId,
      TradingLabRunState state,
      long version,
      boolean pauseRequested) {
    int updated = runRepository.compareAndSetPauseRequested(
        runId,
        state.name(),
        version,
        pauseRequested);
    requireSingleUpdate(updated);
  }

  private static TradingLabRunState stateOf(TradingLabRunEntity run) {
    try {
      return TradingLabRunState.valueOf(run.getState());
    } catch (RuntimeException exception) {
      throw failure(
          "TRADING_LAB_CONTROL_INVALID_STATE",
          "Trading Lab run has an unknown persisted state");
    }
  }

  private static long versionOf(TradingLabRunEntity run) {
    if (run.getVersion() == null || run.getVersion() < 0) {
      throw failure(
          "TRADING_LAB_CONTROL_LOST",
          "Trading Lab run has no authoritative version");
    }
    return run.getVersion();
  }

  private static boolean pauseRequested(TradingLabRunEntity run) {
    if (run.getPauseRequested() == null) {
      throw failure(
          "TRADING_LAB_CONTROL_LOST",
          "Trading Lab run has no authoritative pause flag");
    }
    return run.getPauseRequested();
  }

  private static boolean cancelRequested(TradingLabRunEntity run) {
    if (run.getCancelRequested() == null) {
      throw failure(
          "TRADING_LAB_CONTROL_LOST",
          "Trading Lab run has no authoritative cancel flag");
    }
    return run.getCancelRequested();
  }

  private static void requireSingleUpdate(int updated) {
    if (updated != 1) {
      throw failure(
          "TRADING_LAB_CONTROL_LOST",
          "Trading Lab control lost its state or version compare-and-set");
    }
  }

  private static BusinessException invalidState(String operation, TradingLabRunState state) {
    return failure(
        "TRADING_LAB_CONTROL_INVALID_STATE",
        "Cannot " + operation + " a Trading Lab run in state " + state);
  }

  private static TradingLabRunControlResult result(
      UUID runId,
      TradingLabRunState state,
      long version,
      boolean pauseRequested,
      boolean cancelRequested) {
    return new TradingLabRunControlResult(
        runId,
        state,
        version,
        pauseRequested,
        cancelRequested);
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }
}
