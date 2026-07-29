package com.fxplatform.tradinglab.state;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunTransitionEntity;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunTransitionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TradingLabRunTransitionService {

  private static final int MAX_KEY_LENGTH = 160;
  private static final Set<TradingLabRunState> WORKER_OWNED_STATES = EnumSet.of(
      TradingLabRunState.QUEUED,
      TradingLabRunState.RESETTING,
      TradingLabRunState.RUNNING,
      TradingLabRunState.PAUSED,
      TradingLabRunState.CANCELLING,
      TradingLabRunState.CLEANING);

  private final TradingLabRunRepository runRepository;
  private final TradingLabRunTransitionRepository transitionRepository;
  private final TradingLabStateMachine stateMachine;

  public TradingLabRunTransitionService(
      TradingLabRunRepository runRepository,
      TradingLabRunTransitionRepository transitionRepository,
      TradingLabStateMachine stateMachine) {
    this.runRepository = runRepository;
    this.transitionRepository = transitionRepository;
    this.stateMachine = stateMachine;
  }

  public RunTransitionResult transition(RunTransitionCommand command) {
    validateCommand(command);
    return execute(command, null);
  }

  public RunTransitionResult transitionFenced(
      RunTransitionCommand command,
      String claimOwner) {
    validateCommand(command);
    validateBoundedText(claimOwner, "claimOwner");
    return execute(command, claimOwner);
  }

  private RunTransitionResult execute(RunTransitionCommand command, String claimOwner) {
    var existing = transitionRepository.findByRunIdAndIdempotencyKey(
        command.runId(),
        command.idempotencyKey());
    if (existing.isPresent()) {
      return replay(command, existing.orElseThrow());
    }

    runRepository.lockForStateTransition(command.runId());
    existing = transitionRepository.findByRunIdAndIdempotencyKey(
        command.runId(),
        command.idempotencyKey());
    if (existing.isPresent()) {
      return replay(command, existing.orElseThrow());
    }

    if (!stateMachine.canTransition(command.expected(), command.target())) {
      throw failure(
          "TRADING_LAB_INVALID_TRANSITION",
          "Illegal Trading Lab run transition: "
              + command.expected() + " -> " + command.target());
    }

    boolean workerOwned = WORKER_OWNED_STATES.contains(command.expected());
    if (claimOwner == null && workerOwned) {
      throw failure(
          "TRADING_LAB_FENCE_REQUIRED",
          "A worker-owned transition requires a fenced claim");
    }
    if (claimOwner != null && !workerOwned) {
      throw failure(
          "TRADING_LAB_FENCE_NOT_ALLOWED",
          "A pre-lease transition cannot impersonate a worker claim");
    }

    TradingLabRunEntity run = runRepository.findById(command.runId())
        .orElseThrow(() -> failure(
            "TRADING_LAB_RUN_NOT_FOUND",
            "Trading Lab run was not found"));
    TradingLabRunState persisted = persistedState(run);
    if (persisted != command.expected()) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab run state changed before the transition");
    }
    long expectedVersion = requireVersion(run);
    int updated = claimOwner == null
        ? runRepository.compareAndSetUnleasedState(
            command.runId(),
            command.expected().name(),
            command.target().name(),
            expectedVersion)
        : runRepository.compareAndSetFencedState(
            command.runId(),
            command.expected().name(),
            command.target().name(),
            expectedVersion,
            claimOwner);
    if (updated != 1) {
      var concurrent = transitionRepository.findByRunIdAndIdempotencyKey(
          command.runId(),
          command.idempotencyKey());
      if (concurrent.isPresent()) {
        return replay(command, concurrent.orElseThrow());
      }
      throw failure(
          claimOwner == null
              ? "TRADING_LAB_TRANSITION_LOST"
              : "TRADING_LAB_FENCE_LOST",
          "Trading Lab run transition lost its state, version, or lease fence");
    }

    TradingLabRunTransitionEntity transition = new TradingLabRunTransitionEntity();
    transition.setId(UUID.randomUUID());
    transition.setRunId(command.runId());
    transition.setFromState(command.expected().name());
    transition.setToState(command.target().name());
    transition.setRunVersion(expectedVersion + 1);
    transition.setReason(command.reason());
    transition.setIdempotencyKey(command.idempotencyKey());
    transition.setRealTime(databasePrecision(Instant.now()));
    transition.setVirtualTime(databasePrecision(command.virtualTime()));
    transition.setDetailsJson("{}");
    if (transitionRepository.insert(transition) != 1) {
      throw failure(
          "TRADING_LAB_TRANSITION_LOST",
          "Trading Lab transition record was not inserted");
    }
    return toResult(transition);
  }

  private RunTransitionResult replay(
      RunTransitionCommand command,
      TradingLabRunTransitionEntity existing) {
    TradingLabRunState from = TradingLabRunState.valueOf(existing.getFromState());
    TradingLabRunState to = TradingLabRunState.valueOf(existing.getToState());
    if (from != command.expected()
        || to != command.target()
        || !Objects.equals(existing.getReason(), command.reason())
        || !Objects.equals(
            databasePrecision(existing.getVirtualTime()),
            databasePrecision(command.virtualTime()))) {
      throw failure(
          "TRADING_LAB_IDEMPOTENCY_CONFLICT",
          "The idempotency key was already used for another transition meaning");
    }
    return toResult(existing);
  }

  private static RunTransitionResult toResult(TradingLabRunTransitionEntity transition) {
    return new RunTransitionResult(
        transition.getId(),
        transition.getRunId(),
        TradingLabRunState.valueOf(transition.getFromState()),
        TradingLabRunState.valueOf(transition.getToState()),
        transition.getRunVersion(),
        transition.getIdempotencyKey(),
        transition.getReason(),
        transition.getRealTime(),
        transition.getVirtualTime());
  }

  private static Instant databasePrecision(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }

  private static TradingLabRunState persistedState(TradingLabRunEntity run) {
    try {
      return TradingLabRunState.valueOf(run.getState());
    } catch (RuntimeException exception) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab run has an unknown persisted state");
    }
  }

  private static long requireVersion(TradingLabRunEntity run) {
    if (run.getVersion() == null || run.getVersion() < 0) {
      throw failure(
          "TRADING_LAB_TRANSITION_LOST",
          "Trading Lab run has no authoritative version");
    }
    return run.getVersion();
  }

  private static void validateCommand(RunTransitionCommand command) {
    if (command == null
        || command.runId() == null
        || command.expected() == null
        || command.target() == null) {
      throw new IllegalArgumentException(
          "runId, expected state, and target state are required");
    }
    validateBoundedText(command.idempotencyKey(), "idempotencyKey");
  }

  private static void validateBoundedText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException(field + " must contain 1 to 160 characters");
    }
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }
}
