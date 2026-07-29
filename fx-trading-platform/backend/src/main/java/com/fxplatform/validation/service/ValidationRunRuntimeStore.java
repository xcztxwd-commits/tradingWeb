package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore.EventWrite;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ValidationRunRuntimeStore {

  boolean accept(StartRequest request, EventWrite acceptedEvent);

  LeaseClaim claim(UUID runId);

  /** Releases the live database session that is authoritative for this claim. */
  void releaseLease(LeaseClaim claim);

  void heartbeat(LeaseClaim claim);

  List<UUID> takeOverRecoverableRunIds();

  StartRequest require(UUID runId);

  Optional<Boundary> lastCompletedBoundary(LeaseClaim claim);

  OperationIntent persistIntent(LeaseClaim claim, Command request);

  void completeIntent(
      LeaseClaim claim,
      UUID operationId,
      Completion completion,
      HttpResult result,
      List<EventWrite> events
  );

  void recordFirstHttpObservation(
      LeaseClaim claim,
      UUID operationId,
      HttpResult result,
      EventWrite event
  );

  void failIntent(
      LeaseClaim claim,
      UUID operationId,
      String failureCode,
      String failureMessage,
      HttpResult result,
      List<EventWrite> events
  );

  void appendEvents(LeaseClaim claim, List<EventWrite> events);

  void completeBoundary(LeaseClaim claim, Boundary boundary, List<EventWrite> events);

  void restore(LeaseClaim claim, Boundary boundary);

  void transition(LeaseClaim claim, State state, String reason, EventWrite event);

  BoundaryOutcome settleBeforeWork(
      LeaseClaim claim,
      EventWrite pausedEvent,
      EventWrite cancelledEvent
  );

  BoundaryOutcome settleBoundary(
      LeaseClaim claim,
      boolean hasMoreTicks,
      EventWrite pausedEvent,
      EventWrite cancelledEvent,
      EventWrite completedEvent
  );

  void bindIdentity(LeaseClaim claim, UUID userId, UUID accountId);

  void requestPause(UUID runId);

  void requestResume(UUID runId);

  State requestCancel(UUID runId, EventWrite cancelledEvent);

  State currentState(UUID runId);

  enum Completion {
    SUCCEEDED,
    RECOVERED
  }

  enum BoundaryOutcome {
    CONTINUE,
    PAUSED,
    CANCELLED,
    COMPLETED
  }

  enum OperationState {
    NEW,
    UNFINISHED,
    COMPLETED,
    RECOVERED,
    FAILED
  }

  enum State {
    ACCEPTED,
    STARTING,
    RUNNING,
    PAUSED,
    RECOVERING,
    RECOVERY_BLOCKED,
    CANCELLING,
    CANCELLED,
    FAILED,
    COMPLETED
  }

  record LeaseClaim(UUID runId, long generation, String owner, UUID token) {

    public LeaseClaim {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(token, "token");
      if (generation <= 0L || owner.isBlank()) {
        throw new IllegalArgumentException("Lease claim identity is required");
      }
    }
  }

  record OperationIntent(
      UUID id,
      Command request,
      Instant persistedAt,
      OperationState state,
      HttpResult result
  ) {

    public OperationIntent {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(request, "request");
      persistedAt = ValidationInstantPrecision.require(persistedAt, "persistedAt");
      Objects.requireNonNull(state, "state");
      if (Set.of(OperationState.COMPLETED, OperationState.RECOVERED).contains(state)
          != (result != null)) {
        throw new IllegalArgumentException("Completed operation state requires its frozen result");
      }
    }
  }

  record Boundary(
      UUID runId,
      long generation,
      long tickSequence,
      Instant virtualTime,
      String tickFingerprint,
      String policyFingerprint
  ) {

    public Boundary {
      Objects.requireNonNull(runId, "runId");
      virtualTime = ValidationInstantPrecision.require(virtualTime, "virtualTime");
      Objects.requireNonNull(tickFingerprint, "tickFingerprint");
      Objects.requireNonNull(policyFingerprint, "policyFingerprint");
      if (generation < 0L || tickSequence < 0L) {
        throw new IllegalArgumentException("generation and tickSequence must be non-negative");
      }
    }
  }
}
