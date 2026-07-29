package com.fxplatform.validation.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpHop;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.LookupResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.LookupStatus;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationRunEventStore.EventPage;
import com.fxplatform.validation.service.ValidationRunEventStore.EventWrite;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Boundary;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.BoundaryOutcome;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Completion;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.LeaseClaim;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationIntent;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationState;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import com.fxplatform.validation.service.ValidationSystemStepService.Phase;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous deterministic run machine. Async ownership and lifecycle live in the orchestrator;
 * this module depends only on the four frozen control-plane seams required by the Task 5 contract.
 */
public class ValidationRunEngine {

  private static final long MINIMUM_PACE_NANOS = 1_000_000L;
  private static final long MAX_PACE_FENCE_INTERVAL_NANOS = 250_000_000L;
  private static final int MAX_TICKS = 86_400;
  private static final int MAX_ACTIONS = 100_000;
  private static final Comparator<PublicAction> ACTION_ORDER =
      Comparator.comparingLong(PublicAction::tickSequence)
          .thenComparingLong(PublicAction::sequence)
          .thenComparing(PublicAction::actionId);

  private final ValidationLoopbackHttpClient loopback;
  private final ValidationRunRuntimeStore runtime;
  private final ValidationMarketClock clock;
  private final ValidationRunEventStore events;

  public ValidationRunEngine(
      ValidationLoopbackHttpClient loopback,
      ValidationRunRuntimeStore runtime,
      ValidationMarketClock clock,
      ValidationRunEventStore events
  ) {
    this.loopback = Objects.requireNonNull(loopback, "loopback");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.events = Objects.requireNonNull(events, "events");
  }

  public StartDecision start(StartRequest request) {
    Objects.requireNonNull(request, "request");
    EventWrite acceptedEvent = event(
        request,
        "run:accepted",
        request.requestFingerprint(),
        "RUN_ACCEPTED",
        request.virtualStart(),
        null,
        Map.of("tickCount", request.ticks().size(), "actionCount", request.actions().size()));
    boolean launchRequired = runtime.accept(request, acceptedEvent);
    return new StartDecision(
        new Accepted(request.runId(), request.requestFingerprint(), State.ACCEPTED),
        launchRequired);
  }

  public List<UUID> prepareRecovery() {
    return runtime.takeOverRecoverableRunIds();
  }

  public void prepareResume(UUID runId) {
    runtime.requestResume(Objects.requireNonNull(runId, "runId"));
  }

  public State pause(UUID runId) {
    runtime.requestPause(Objects.requireNonNull(runId, "runId"));
    return runtime.currentState(runId);
  }

  public State cancel(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    StartRequest request = runtime.require(runId);
    return runtime.requestCancel(
        runId,
        event(
            request,
            "state:CANCELLED:external",
            digest("CANCELLED|EXTERNAL_CONTROL"),
            "RUN_STATE_CHANGED",
            null,
            null,
            Map.of("state", State.CANCELLED.name(), "reason", "EXTERNAL_CONTROL")));
  }

  public EventPage eventsAfter(UUID runId, long afterSequence, int limit) {
    return events.eventsAfter(runId, afterSequence, limit);
  }

  /** Invoked only by the validation run orchestrator inside a tracked reset-aware task. */
  public void execute(UUID runId, boolean recovering) {
    LeaseClaim claim;
    try {
      claim = runtime.claim(runId);
    } catch (BusinessException failure) {
      if ("VALIDATION_RUN_NOT_CLAIMABLE".equals(failure.getCode())) {
        return;
      }
      throw failure;
    }

    try {
      StartRequest request = runtime.require(runId);
      try {
        Boundary boundary = runtime.lastCompletedBoundary(claim).orElse(null);
        if (boundary == null) {
          if (recovering) {
            clock.restore(runId, request.generation(), 0L, request.virtualStart());
          } else {
            clock.start(runId, request.generation(), request.virtualStart());
          }
        } else {
          runtime.restore(claim, boundary);
          clock.restore(
              boundary.runId(),
              boundary.generation(),
              boundary.tickSequence(),
              boundary.virtualTime());
          runtime.appendEvents(claim, List.of(event(
              request,
              "recovery:boundary:" + boundary.tickSequence(),
              digest(boundary.tickFingerprint() + "|" + boundary.policyFingerprint()),
              "RECOVERY_BOUNDARY_RESTORED",
              boundary.virtualTime(),
              null,
              Map.of("tickSequence", boundary.tickSequence()))));
        }

        if (settleBeforeWork(claim, request) != BoundaryOutcome.CONTINUE) {
          return;
        }
        if (recovering) {
          transition(claim, request, State.RECOVERING, "RECOVERY_CLAIMED");
        }
        if (boundary == null) {
          transition(claim, request, State.STARTING, "RUN_STARTING");
        }

        boolean firstTickClockPrimed = executeSetup(claim, request, boundary != null);
        if (settleBeforeWork(claim, request) != BoundaryOutcome.CONTINUE) {
          return;
        }
        transition(
            claim,
            request,
            State.RUNNING,
            recovering ? "RECOVERY_RECONCILED" : "RUN_STARTED");
        executeTicks(
            claim,
            request,
            boundary == null ? 0L : boundary.tickSequence(),
            firstTickClockPrimed);
      } catch (StopExecution ignored) {
        // A reset interruption, accepted control, or recovery block has already chosen the outcome.
      } catch (PersistenceUncertain ignored) {
        // The durable intent remains unfinished. The recovery loop will reconcile it without
        // replaying a possibly committed external mutation blindly.
      } catch (RuntimeException failure) {
        if (fenced(failure)) {
          return;
        }
        try {
          transition(claim, request, State.FAILED, failureCode(failure));
        } catch (RuntimeException transitionFailure) {
          if (!fenced(transitionFailure)) {
            failure.addSuppressed(transitionFailure);
          }
        }
      }
    } finally {
      runtime.releaseLease(claim);
    }
  }

  private boolean executeSetup(LeaseClaim claim, StartRequest request, boolean hasBoundary) {
    HttpResult registration = executeSlot(
        claim,
        request,
        command(
            Operation.REGISTER,
            request,
            0L,
            request.runId() + ":register:0",
            digest(request.requestFingerprint() + "|REGISTER"),
            Map.of("runId", request.runId().toString())),
        Rehydrate.REGISTER);
    bindIdentity(claim, registration);

    executeSlot(
        claim,
        request,
        command(
            Operation.SEED_ACCOUNT,
            request,
            0L,
            request.runId() + ":seed:0",
            digest(request.requestFingerprint() + "|SEED_ACCOUNT"),
            new SeedPlan(seedId(request.runId()), request.initialBalances())),
        Rehydrate.NONE);

    List<String> perpetualSymbols = request.ticks().stream()
        .flatMap(tick -> tick.perpetualBundles().stream())
        .map(bundle -> bundle.platformSymbol())
        .distinct()
        .sorted()
        .toList();
    executeSlot(
        claim,
        request,
        command(
            Operation.START_MARKET_PATH,
            request,
            0L,
            request.runId() + ":market-path:0",
            digest(request.requestFingerprint() + "|START_MARKET_PATH"),
            request),
        hasBoundary ? Rehydrate.NONE : Rehydrate.RUNTIME);

    boolean firstTickClockPrimed = false;
    if (!hasBoundary) {
      CompositeTick firstTick = request.ticks().stream()
          .min(Comparator.comparingLong(CompositeTick::sequence))
          .orElseThrow(() -> new IllegalStateException("Validation run requires at least one Tick"));
      advanceClock(request, firstTick);
      firstTickClockPrimed = true;
    }

    executeSlot(
        claim,
        request,
        command(
            Operation.CONFIGURE_ACCOUNT,
            request,
            0L,
            request.runId() + ":account-config:0",
            digest(request.requestFingerprint() + "|CONFIGURE_ACCOUNT"),
            new AccountConfigurationPlan(request.accountSettings(), perpetualSymbols)),
        Rehydrate.NONE);
    return firstTickClockPrimed;
  }

  private void executeTicks(
      LeaseClaim claim,
      StartRequest request,
      long completedSequence,
      boolean firstTickClockPrimed) {
    List<CompositeTick> remaining = request.ticks().stream()
        .filter(tick -> tick.sequence() > completedSequence)
        .sorted(Comparator.comparingLong(CompositeTick::sequence))
        .toList();
    if (remaining.isEmpty()) {
      BoundaryOutcome outcome = runtime.settleBoundary(
          claim,
          false,
          stateEvent(request, State.PAUSED, "PAUSE_REQUESTED"),
          stateEvent(request, State.CANCELLED, "CANCEL_REQUESTED"),
          stateEvent(request, State.COMPLETED, "ALL_TICKS_COMPLETED"));
      if (outcome == BoundaryOutcome.CONTINUE) {
        throw new IllegalStateException("Terminal validation boundary remained active");
      }
      return;
    }

    for (int index = 0; index < remaining.size(); index++) {
      CompositeTick tick = remaining.get(index);
      if (firstTickClockPrimed && index == 0 && tick.sequence() == 1L) {
        firstTickClockPrimed = false;
      } else {
        advanceClock(request, tick);
      }
      runtime.appendEvents(claim, List.of(event(
          request,
          "tick:" + tick.sequence(),
          tick.fingerprint(),
          "MARKET_TICK",
          tick.virtualTime(),
          null,
          marketTickPayload(tick))));

      executeSystemPhase(claim, request, tick, Phase.PRE_ACTIONS, Rehydrate.RUNTIME);
      for (PublicAction action : dueActions(request, tick)) {
        HttpResult actionResult = executeSlot(
            claim,
            request,
            command(
                Operation.PUBLIC_ACTION,
                request,
                tick.sequence(),
                request.runId() + ":" + action.actionId() + ":0",
                action.requestFingerprint(),
                action),
            Rehydrate.NONE);
        queryState(
            claim,
            request,
            tick,
            action.actionId().toString(),
            actionResult.correlationId());
      }

      executeSystemPhase(claim, request, tick, Phase.POST_ACTIONS, Rehydrate.NONE);
      HttpResult finalState = queryState(claim, request, tick, "tick", null);
      Boundary boundary = new Boundary(
          request.runId(),
          request.generation(),
          tick.sequence(),
          tick.virtualTime(),
          tick.fingerprint(),
          policyFingerprint(request));
      runtime.completeBoundary(claim, boundary, List.of(event(
          request,
          "tick:" + tick.sequence() + ":checkpoint",
          tick.fingerprint(),
          "CHECKPOINT",
          tick.virtualTime(),
          finalState.correlationId(),
          Map.of("tickSequence", tick.sequence(), "state", finalState.body()))));

      boolean hasMore = index + 1 < remaining.size();
      BoundaryOutcome outcome = runtime.settleBoundary(
          claim,
          hasMore,
          stateEvent(request, State.PAUSED, "PAUSE_REQUESTED"),
          stateEvent(request, State.CANCELLED, "CANCEL_REQUESTED"),
          stateEvent(request, State.COMPLETED, "ALL_TICKS_COMPLETED"));
      if (outcome != BoundaryOutcome.CONTINUE) {
        return;
      }
      pace(claim, request.speedMultiplier());
    }
  }

  private List<PublicAction> dueActions(StartRequest request, CompositeTick tick) {
    return request.actions().stream()
        .filter(action -> action.tickSequence() == tick.sequence())
        .sorted(Comparator.comparingLong(PublicAction::sequence)
            .thenComparing(PublicAction::actionId))
        .toList();
  }

  private static Map<String, Object> marketTickPayload(CompositeTick tick) {
    List<Map<String, Object>> instruments = new ArrayList<>(
        tick.spotBundles().size() + tick.perpetualBundles().size());
    tick.spotBundles().forEach(bundle -> instruments.add(Map.of(
        "productType", "CRYPTO_SPOT",
        "symbol", bundle.platformSymbol(),
        "bid", bundle.bid().toPlainString(),
        "ask", bundle.ask().toPlainString(),
        "last", bundle.last().toPlainString())));
    tick.perpetualBundles().forEach(bundle -> instruments.add(Map.of(
        "productType", "LINEAR_PERP",
        "symbol", bundle.platformSymbol(),
        "bid", bundle.bid().toPlainString(),
        "ask", bundle.ask().toPlainString(),
        "last", bundle.last().toPlainString(),
        "mark", bundle.mark().toPlainString(),
        "index", bundle.index().toPlainString())));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tickSequence", tick.sequence());
    payload.put("spotSymbols", tick.spotBundles().size());
    payload.put("perpetualSymbols", tick.perpetualBundles().size());
    payload.put("instruments", List.copyOf(instruments));
    return Collections.unmodifiableMap(payload);
  }

  private void advanceClock(StartRequest request, CompositeTick tick) {
    ValidationMarketClock.Tick advanced = clock.advance(request.runId(), request.generation());
    if (advanced.sequence() != tick.sequence()
        || !advanced.virtualTime().equals(tick.virtualTime())) {
      throw new BusinessException(
          "VALIDATION_CLOCK_TICK_CONFLICT",
          "Scenario Tick is not contiguous with the virtual clock");
    }
  }

  private void executeSystemPhase(
      LeaseClaim claim,
      StartRequest run,
      CompositeTick tick,
      Phase phase,
      Rehydrate rehydrate
  ) {
    String fingerprint = digest(tick.fingerprint() + "|" + phase.name());
    executeSlot(
        claim,
        run,
        command(
            Operation.SYSTEM_STEP,
            run,
            tick.sequence(),
            run.runId() + ":" + tick.sequence() + ":" + phase.name(),
            fingerprint,
            new Request(tick, phase, fingerprint)),
        rehydrate);
  }

  private HttpResult queryState(
      LeaseClaim claim,
      StartRequest run,
      CompositeTick tick,
      String suffix,
      String correlationId
  ) {
    return executeSlot(
        claim,
        run,
        command(
            Operation.QUERY_STATE,
            run,
            tick.sequence(),
            run.runId() + ":" + tick.sequence() + ":state:" + suffix,
            digest(tick.fingerprint() + "|QUERY_STATE|" + suffix),
            Map.of(
                "tickSequence", tick.sequence(),
                "causedByCorrelationId", correlationId == null ? "" : correlationId)),
        Rehydrate.NONE);
  }

  private HttpResult executeSlot(
      LeaseClaim claim,
      StartRequest run,
      Command command,
      Rehydrate rehydrate
  ) {
    OperationIntent intent = runtime.persistIntent(claim, command);
    return switch (intent.state()) {
      case NEW -> executeAndComplete(claim, run, intent, Completion.SUCCEEDED, "SUCCEEDED");
      case UNFINISHED -> reconcileAtSlot(claim, run, intent);
      case COMPLETED, RECOVERED -> reuseCompleted(claim, run, intent, rehydrate);
      case FAILED -> throw new BusinessException(
          "VALIDATION_OPERATION_PREVIOUSLY_FAILED",
          "A frozen validation operation previously failed");
    };
  }

  private HttpResult reuseCompleted(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent,
      Rehydrate rehydrate
  ) {
    HttpResult frozen = requireAcceptedResult(intent.request(), intent.result());
    if (rehydrate != Rehydrate.NONE) {
      runtime.heartbeat(claim);
      HttpResult restored = requireSuccess(loopback.rehydrate(intent.request()));
      runtime.heartbeat(claim);
      if (rehydrate == Rehydrate.REGISTER) {
        requireSameIdentity(frozen, restored);
      }
      runtime.appendEvents(claim, List.of(recoveryEvent(
          run,
          intent.request(),
          "RUNTIME_REHYDRATED")));
    }
    return frozen;
  }

  private HttpResult reconcileAtSlot(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent
  ) {
    Command command = intent.request();
    if (command.operation() == Operation.SYSTEM_STEP) {
      return executeAndComplete(claim, run, intent, Completion.RECOVERED, "SYSTEM_STEP_REPLAYED");
    }

    runtime.heartbeat(claim);
    LookupResult lookup = loopback.lookup(command);
    runtime.heartbeat(claim);
    if (lookup == null || lookup.status() == LookupStatus.UNKNOWN) {
      transition(claim, run, State.RECOVERY_BLOCKED, "UNCERTAIN_OPERATION_OUTCOME");
      throw new StopExecution();
    }
    if (lookup.status() == LookupStatus.FOUND) {
      HttpResult recovered;
      try {
        recovered = requireAcceptedResult(command, lookup.result());
      } catch (RuntimeException knownFailure) {
        failKnownIntent(claim, run, intent, knownFailure, lookup.result());
        throw knownFailure;
      }
      runtime.completeIntent(
          claim,
          intent.id(),
          Completion.RECOVERED,
          recovered,
          completionEvents(command, recovered, completionOutcome(
              command, recovered, "RECOVERED"), recoveryEvent(
              run, command, "FOUND")));
      return recovered;
    }
    return executeAndComplete(
        claim, run, intent, Completion.RECOVERED, "REPLAYED", false);
  }

  private HttpResult executeAndComplete(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent,
      Completion completion,
      String outcome
  ) {
    return executeAndComplete(claim, run, intent, completion, outcome, true);
  }

  private HttpResult executeAndComplete(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent,
      Completion completion,
      String outcome,
      boolean reconcileUncertain
  ) {
    Command command = intent.request();
    runtime.heartbeat(claim);
    HttpResult result;
    try {
      result = loopback.execute(command);
    } catch (RuntimeException failure) {
      if (fenced(failure)) {
        throw failure;
      }
      if (uncertainLoopbackFailure(failure)) {
        if (reconcileUncertain) {
          return reconcileUncertainExecution(claim, run, intent);
        }
        blockUncertain(claim, run);
      }
      failKnownIntent(claim, run, intent, failure);
      throw failure;
    }
    if (uncertainHttpResult(result)) {
      recordFirstHttpObservation(claim, intent, result);
      if (reconcileUncertain) {
        return reconcileUncertainExecution(claim, run, intent);
      }
      blockUncertain(claim, run);
    }
    try {
      result = requireAcceptedResult(command, result);
    } catch (RuntimeException failure) {
      failKnownIntent(claim, run, intent, failure, result);
      throw failure;
    }
    try {
      runtime.heartbeat(claim);
      List<EventWrite> writes = completionEvents(
          command,
          result,
          completionOutcome(command, result, outcome),
          completion == Completion.RECOVERED
              ? recoveryEvent(run, command, outcome)
              : null);
      runtime.completeIntent(claim, intent.id(), completion, result, writes);
      return result;
    } catch (RuntimeException persistenceFailure) {
      if (fenced(persistenceFailure)) {
        throw persistenceFailure;
      }
      throw new PersistenceUncertain();
    }
  }

  private HttpResult reconcileUncertainExecution(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent
  ) {
    Command command = intent.request();
    LookupResult lookup;
    try {
      runtime.heartbeat(claim);
      lookup = loopback.lookup(command);
      runtime.heartbeat(claim);
    } catch (RuntimeException lookupFailure) {
      if (fenced(lookupFailure)) {
        throw lookupFailure;
      }
      blockUncertain(claim, run);
      throw new IllegalStateException("unreachable");
    }
    if (lookup == null || lookup.status() == LookupStatus.UNKNOWN) {
      blockUncertain(claim, run);
    }
    if (lookup.status() == LookupStatus.FOUND) {
      HttpResult recovered;
      try {
        recovered = requireAcceptedResult(command, lookup.result());
      } catch (RuntimeException knownFailure) {
        failKnownIntent(claim, run, intent, knownFailure, lookup.result());
        throw knownFailure;
      }
      try {
        runtime.completeIntent(
            claim,
            intent.id(),
            Completion.RECOVERED,
            recovered,
            completionEvents(command, recovered, completionOutcome(
                command, recovered, "RECOVERED"), recoveryEvent(
                run, command, "FOUND_AFTER_UNCERTAIN_EXECUTE")));
      } catch (RuntimeException persistenceFailure) {
        if (fenced(persistenceFailure)) {
          throw persistenceFailure;
        }
        throw new PersistenceUncertain();
      }
      return recovered;
    }
    return executeAndComplete(
        claim,
        run,
        intent,
        Completion.RECOVERED,
        "REPLAYED_AFTER_UNCERTAIN_EXECUTE",
        false);
  }

  private void blockUncertain(LeaseClaim claim, StartRequest run) {
    transition(claim, run, State.RECOVERY_BLOCKED, "UNCERTAIN_OPERATION_OUTCOME");
    throw new StopExecution();
  }

  private void failKnownIntent(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent,
      RuntimeException failure
  ) {
    failKnownIntent(claim, run, intent, failure, null);
  }

  private void failKnownIntent(
      LeaseClaim claim,
      StartRequest run,
      OperationIntent intent,
      RuntimeException failure,
      HttpResult result
  ) {
    String code = failureCode(failure);
    try {
      runtime.failIntent(
          claim,
          intent.id(),
          code,
          code,
          result,
          failureEvents(run, intent.request(), result, code));
    } catch (RuntimeException persistenceFailure) {
      if (fenced(persistenceFailure)) {
        throw persistenceFailure;
      }
      throw new PersistenceUncertain();
    }
  }

  private List<EventWrite> failureEvents(
      StartRequest run,
      Command command,
      HttpResult result,
      String engineCode
  ) {
    ArrayList<EventWrite> writes = new ArrayList<>(
        apiEvents(command, result, "FAILED"));
    if (command.operation() != Operation.PUBLIC_ACTION) {
      return List.copyOf(writes);
    }

    PublicAction failed = run.actions().stream()
        .filter(action -> command.idempotencyKey().equals(
            run.runId() + ":" + action.actionId() + ":0"))
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            "VALIDATION_OPERATION_CORRUPT",
            "Stored validation public action is not part of the frozen run"));
    List<PublicAction> laterActions = run.actions().stream()
        .filter(action -> ACTION_ORDER.compare(action, failed) > 0)
        .sorted(ACTION_ORDER)
        .toList();
    List<Map<String, Object>> unexecuted = laterActions.stream()
        .map(ValidationRunEngine::unexecutedAction)
        .toList();

    String responseCode = result == null
        ? null
        : result.body().get("code") instanceof String text && !text.isBlank()
            ? text
            : null;
    String evidenceCode = responseCode == null ? engineCode : responseCode;
    LinkedHashMap<String, Object> failurePoint = new LinkedHashMap<>();
    failurePoint.put("operation", command.operation().name());
    failurePoint.put("actionId", failed.actionId().toString());
    failurePoint.put("tickSequence", failed.tickSequence());
    failurePoint.put("actionSequence", failed.sequence());
    failurePoint.put("status", result == null ? 0 : result.status());
    failurePoint.put("code", evidenceCode);

    String laterFingerprint = String.join(
        "|",
        laterActions.stream()
            .map(action -> action.tickSequence() + ":" + action.sequence()
                + ":" + action.actionId())
            .toList());
    writes.add(event(
        run,
        "run-execution-failed:" + digest(command.idempotencyKey()),
        digest(command.requestFingerprint() + "|" + evidenceCode + "|"
            + (result == null ? "NO_HTTP_RESULT" : result.status()) + "|"
            + laterFingerprint),
        "RUN_EXECUTION_FAILED",
        currentVirtualTime(),
        result == null ? null : result.correlationId(),
        Map.of(
            "failurePoint", Collections.unmodifiableMap(failurePoint),
            "unexecuted", unexecuted)));
    return List.copyOf(writes);
  }

  private static Map<String, Object> unexecutedAction(PublicAction action) {
    return Map.of(
        "actionId", action.actionId().toString(),
        "tickSequence", action.tickSequence(),
        "actionSequence", action.sequence(),
        "type", action.type().name());
  }

  private void recordFirstHttpObservation(
      LeaseClaim claim,
      OperationIntent intent,
      HttpResult result
  ) {
    try {
      runtime.recordFirstHttpObservation(
          claim,
          intent.id(),
          result,
          apiObservationEvent(intent.request(), result));
    } catch (RuntimeException persistenceFailure) {
      if (fenced(persistenceFailure)) {
        throw persistenceFailure;
      }
      throw new PersistenceUncertain();
    }
  }

  private List<EventWrite> completionEvents(
      Command command,
      HttpResult result,
      String outcome,
      EventWrite recoveryEvent
  ) {
    ArrayList<EventWrite> writes = new ArrayList<>();
    writes.addAll(apiEvents(command, result, outcome));
    if (command.operation() == Operation.QUERY_STATE) {
      writes.add(new EventWrite(
          command.runId(),
          "snapshot:" + digest(command.idempotencyKey()),
          digest(command.requestFingerprint() + "|" + result.body()),
          "STATE_SNAPSHOT",
          currentVirtualTime(),
          result.correlationId(),
          Map.of(
              "tickSequence", command.tickSequence(),
              "state", result.body())));
    }
    if (recoveryEvent != null) {
      writes.add(recoveryEvent);
    }
    return List.copyOf(writes);
  }

  private BoundaryOutcome settleBeforeWork(LeaseClaim claim, StartRequest request) {
    return runtime.settleBeforeWork(
        claim,
        stateEvent(request, State.PAUSED, "PAUSE_REQUESTED"),
        stateEvent(request, State.CANCELLED, "CANCEL_REQUESTED"));
  }

  private void bindIdentity(LeaseClaim claim, HttpResult registration) {
    try {
      UUID userId = UUID.fromString(String.valueOf(registration.body().get("userId")));
      UUID accountId = UUID.fromString(String.valueOf(registration.body().get("accountId")));
      runtime.bindIdentity(claim, userId, accountId);
    } catch (RuntimeException invalid) {
      if (invalid instanceof BusinessException) {
        throw invalid;
      }
      throw new BusinessException(
          "VALIDATION_RUN_IDENTITY_INVALID",
          "Validation registration did not return durable identity");
    }
  }

  private static void requireSameIdentity(HttpResult frozen, HttpResult restored) {
    if (!Objects.equals(frozen.body().get("userId"), restored.body().get("userId"))
        || !Objects.equals(frozen.body().get("accountId"), restored.body().get("accountId"))) {
      throw new BusinessException(
          "VALIDATION_RUN_IDENTITY_CONFLICT",
          "Recovered validation identity conflicts with the frozen registration");
    }
  }

  private static HttpResult requireSuccess(HttpResult result) {
    if (result == null || result.status() < 200 || result.status() >= 300) {
      throw new BusinessException(
          "VALIDATION_LOOPBACK_FAILED",
          "Validation loopback request failed");
    }
    return result;
  }

  private static HttpResult requireAcceptedResult(Command command, HttpResult result) {
    if (result == null) {
      throw new BusinessException(
          "VALIDATION_LOOPBACK_FAILED",
          "Validation loopback request failed");
    }
    ExpectedHttpError expected = expectedError(command);
    boolean success = result.status() >= 200 && result.status() < 300;
    if (expected == null) {
      if (!success) {
        throw new BusinessException(
            "VALIDATION_LOOPBACK_FAILED",
            "Validation loopback request failed");
      }
      return result;
    }
    if (success) {
      throw new BusinessException(
          "VALIDATION_EXPECTED_HTTP_ERROR_NOT_OBSERVED",
          "Expected validation HTTP error was not observed");
    }
    Object code = result.body().get("code");
    if (result.status() != expected.status()
        || !(code instanceof String actualCode)
        || !expected.code().equals(actualCode)) {
      throw new BusinessException(
          "VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH",
          "Validation HTTP error did not match the frozen expectation");
    }
    return result;
  }

  private static String completionOutcome(
      Command command,
      HttpResult result,
      String ordinaryOutcome
  ) {
    ExpectedHttpError expected = expectedError(command);
    return expected != null
        && result.status() == expected.status()
        && expected.code().equals(result.body().get("code"))
            ? "EXPECTED_ERROR"
            : ordinaryOutcome;
  }

  private static ExpectedHttpError expectedError(Command command) {
    if (command.operation() != Operation.PUBLIC_ACTION || command.body() == null) {
      return null;
    }
    if (command.body() instanceof PublicAction action) {
      return action.expectedError();
    }
    if (!(command.body() instanceof Map<?, ?> action)) {
      throw new BusinessException(
          "VALIDATION_OPERATION_CORRUPT",
          "Stored validation public action is invalid");
    }
    Object rawExpected = action.get("expectedError");
    if (rawExpected == null) {
      return null;
    }
    if (!(rawExpected instanceof Map<?, ?> expected)
        || !(expected.get("status") instanceof Number status)
        || !(expected.get("code") instanceof String code)) {
      throw new BusinessException(
          "VALIDATION_OPERATION_CORRUPT",
          "Stored validation expected HTTP error is invalid");
    }
    try {
      return new ExpectedHttpError(status.intValue(), code);
    } catch (RuntimeException invalid) {
      throw new BusinessException(
          "VALIDATION_OPERATION_CORRUPT",
          "Stored validation expected HTTP error is invalid");
    }
  }

  private void transition(LeaseClaim claim, StartRequest run, State state, String reason) {
    runtime.transition(claim, state, reason, stateEvent(run, state, reason));
  }

  private EventWrite stateEvent(StartRequest run, State state, String reason) {
    long tick = currentTickSequence();
    return event(
        run,
        "state:" + state + ":" + tick + ":" + digest(reason),
        digest(state + "|" + reason + "|" + tick),
        "RUN_STATE_CHANGED",
        currentVirtualTime(),
        null,
        Map.of("state", state.name(), "reason", reason));
  }

  private EventWrite apiEvent(Command command, HttpResult result, String outcome) {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", command.operation().name());
    payload.put("outcome", outcome);
    ExpectedHttpError expected = expectedError(command);
    if (expected != null) {
      payload.put("expectedStatus", expected.status());
      payload.put("expectedCode", expected.code());
    }
    if (result != null) {
      payload.put("status", result.status());
      if (result.status() < 200 || result.status() >= 300) {
        payload.put("response", result.body());
      }
      if (!result.hops().isEmpty()) {
        HttpHop last = result.hops().getLast();
        payload.put("traceScope", "COMMAND");
        payload.put("method", last.method());
        payload.put("path", last.path());
        payload.put("durationMillis", last.durationMillis());
        payload.put("trace", commandSummaryTrace(command, outcome, last.trace()));
      }
    }
    return new EventWrite(
        command.runId(),
        "api:" + digest(command.operation() + "|" + command.idempotencyKey()),
        digest(command.requestFingerprint() + "|" + outcome),
        "API_TRACE",
        currentVirtualTime(),
        result == null ? null : result.correlationId(),
        Collections.unmodifiableMap(payload));
  }

  private EventWrite apiObservationEvent(Command command, HttpResult result) {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", command.operation().name());
    payload.put("outcome", "UNCERTAIN_HTTP_RESPONSE");
    ExpectedHttpError expected = expectedError(command);
    if (expected != null) {
      payload.put("expectedStatus", expected.status());
      payload.put("expectedCode", expected.code());
    }
    payload.put("status", result.status());
    payload.put("response", result.body());
    if (!result.hops().isEmpty()) {
      HttpHop last = result.hops().getLast();
      payload.put("traceScope", "COMMAND");
      payload.put("method", last.method());
      payload.put("path", last.path());
      payload.put("durationMillis", last.durationMillis());
      payload.put("trace", commandSummaryTrace(
          command, "UNCERTAIN_HTTP_RESPONSE", last.trace()));
    }
    return new EventWrite(
        command.runId(),
        "api-observation:" + digest(command.operation() + "|" + command.idempotencyKey()),
        digest(command.requestFingerprint()
            + "|" + result.status()
            + "|" + result.correlationId()
            + "|" + result.body()),
        "API_TRACE",
        currentVirtualTime(),
        result.correlationId(),
        Collections.unmodifiableMap(payload));
  }

  private List<EventWrite> apiEvents(
      Command command,
      HttpResult result,
      String outcome
  ) {
    ArrayList<EventWrite> writes = new ArrayList<>();
    if (result != null) {
      for (int index = 0; index < result.hops().size(); index++) {
        writes.add(apiHopEvent(command, result.hops().get(index), index, outcome));
      }
    }
    writes.add(apiEvent(command, result, outcome));
    return List.copyOf(writes);
  }

  private EventWrite apiHopEvent(
      Command command,
      HttpHop hop,
      int index,
      String outcome
  ) {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", command.operation().name());
    payload.put("outcome", outcome);
    payload.put("traceScope", "HTTP_HOP");
    payload.put("hopIndex", index);
    payload.put("method", hop.method());
    payload.put("path", hop.path());
    payload.put("status", hop.status());
    payload.put("durationMillis", hop.durationMillis());
    payload.put("trace", hop.trace());
    ExpectedHttpError expected = expectedError(command);
    if (expected != null) {
      payload.put("expectedStatus", expected.status());
      payload.put("expectedCode", expected.code());
    }
    String hopIdentity = command.operation()
        + "|" + command.idempotencyKey()
        + "|" + index
        + "|" + hop.method()
        + "|" + hop.path();
    return new EventWrite(
        command.runId(),
        "api-hop:" + digest(hopIdentity),
        digest(command.requestFingerprint() + "|HTTP_HOP|" + index
            + "|" + hop.method() + "|" + hop.path()),
        "API_TRACE",
        currentVirtualTime(),
        hop.correlationId(),
        Collections.unmodifiableMap(payload));
  }

  private static Map<String, Object> commandSummaryTrace(
      Command command,
      String outcome,
      Map<String, Object> hopTrace
  ) {
    LinkedHashMap<String, Object> summary = new LinkedHashMap<>(hopTrace);
    Object rawRequest = hopTrace.get("requestBody");
    if (!(rawRequest instanceof Map<?, ?> request)) {
      throw new IllegalArgumentException("Validation HTTP hop trace request is invalid");
    }
    LinkedHashMap<String, Object> summaryRequest = new LinkedHashMap<>();
    request.forEach((key, value) -> {
      if (!(key instanceof String text)) {
        throw new IllegalArgumentException("Validation HTTP hop trace request is invalid");
      }
      summaryRequest.put(text, value);
    });
    LinkedHashMap<String, Object> commandScope = new LinkedHashMap<>();
    commandScope.put("scope", "COMMAND");
    commandScope.put("operation", command.operation().name());
    commandScope.put("outcome", outcome);
    commandScope.put("request", summaryRequest.get("sanitizedRequest"));
    summaryRequest.put("sanitizedRequest", Collections.unmodifiableMap(commandScope));
    summary.put("requestBody", Collections.unmodifiableMap(summaryRequest));
    return Collections.unmodifiableMap(summary);
  }

  private EventWrite recoveryEvent(StartRequest run, Command command, String outcome) {
    return event(
        run,
        "recovery:" + currentTickSequence() + ":"
            + digest(command.idempotencyKey() + "|" + outcome),
        digest(command.requestFingerprint() + "|" + outcome),
        "RECOVERY_RECONCILIATION",
        currentVirtualTime(),
        null,
        Map.of("operation", command.operation().name(), "outcome", outcome));
  }

  private static EventWrite event(
      StartRequest run,
      String durableKey,
      String fingerprint,
      String type,
      Instant virtualTime,
      String correlationId,
      Map<String, Object> payload
  ) {
    return new EventWrite(
        run.runId(),
        durableKey,
        fingerprint,
        type,
        virtualTime,
        correlationId,
        payload);
  }

  private Instant currentVirtualTime() {
    return clock.current().map(ValidationMarketClock.Tick::virtualTime).orElse(null);
  }

  private long currentTickSequence() {
    return clock.current().map(ValidationMarketClock.Tick::sequence).orElse(0L);
  }

  private static Command command(
      Operation operation,
      StartRequest run,
      long tickSequence,
      String idempotencyKey,
      String fingerprint,
      Object body
  ) {
    return new Command(
        operation,
        run.runId(),
        run.generation(),
        tickSequence,
        idempotencyKey,
        fingerprint,
        body);
  }

  private void pace(LeaseClaim claim, BigDecimal speedMultiplier) {
    long remainingNanos = BigDecimal.valueOf(1_000_000_000L)
        .divide(speedMultiplier, 0, RoundingMode.CEILING)
        .min(BigDecimal.valueOf(Long.MAX_VALUE))
        .longValueExact();
    remainingNanos = Math.max(MINIMUM_PACE_NANOS, remainingNanos);
    while (remainingNanos > 0L) {
      long interval = Math.min(remainingNanos, MAX_PACE_FENCE_INTERVAL_NANOS);
      try {
        TimeUnit.NANOSECONDS.sleep(interval);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new StopExecution();
      }
      remainingNanos -= interval;
      runtime.heartbeat(claim);
    }
  }

  private static UUID seedId(UUID runId) {
    return UUID.nameUUIDFromBytes(
        (runId + ":validation-seed").getBytes(StandardCharsets.UTF_8));
  }

  private static String policyFingerprint(StartRequest request) {
    return Integer.toHexString(request.executionPolicy().hashCode());
  }

  private static String failureCode(RuntimeException failure) {
    if (failure instanceof BusinessException businessException) {
      return businessException.getCode();
    }
    return "VALIDATION_RUN_FAILED";
  }

  private static boolean fenced(RuntimeException failure) {
    if (!(failure instanceof BusinessException businessException)) {
      return false;
    }
    return Set.of(
        "VALIDATION_RUN_LEASE_LOST",
        "VALIDATION_GENERATION_FENCED",
        "VALIDATION_RESET_NOT_READY").contains(businessException.getCode());
  }

  private static boolean uncertainLoopbackFailure(RuntimeException failure) {
    if (!(failure instanceof BusinessException businessException)) {
      return false;
    }
    return Set.of(
        "VALIDATION_LOOPBACK_UNAVAILABLE",
        "VALIDATION_LOOPBACK_INTERRUPTED",
        "VALIDATION_LOOPBACK_RESPONSE_TOO_LARGE")
        .contains(businessException.getCode());
  }

  private static boolean uncertainHttpResult(HttpResult result) {
    return result != null && result.status() >= 500;
  }

  private static String digest(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private enum Rehydrate {
    NONE,
    REGISTER,
    RUNTIME
  }

  private static final class StopExecution extends RuntimeException {
  }

  private static final class PersistenceUncertain extends RuntimeException {
  }

  public record StartDecision(Accepted accepted, boolean launchRequired) {

    public StartDecision {
      Objects.requireNonNull(accepted, "accepted");
    }
  }

  public record Accepted(UUID runId, String requestFingerprint, State state) {

    public Accepted {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      Objects.requireNonNull(state, "state");
    }
  }

  public enum PublicActionType {
    PLACE_ORDER,
    CANCEL_ORDER,
    CANCEL_ALL,
    SET_POSITION_MODE,
    SET_MARGIN_MODE,
    SET_LEVERAGE
  }

  public record ExpectedHttpError(int status, String code) {

    public ExpectedHttpError {
      if (status < 400
          || status > 499
          || code == null
          || !code.matches("[A-Z][A-Z0-9_]{0,79}")) {
        throw new IllegalArgumentException(
            "Expected HTTP error requires an exact 4xx status and business code");
      }
    }
  }

  public record PublicAction(
      UUID actionId,
      long tickSequence,
      long sequence,
      PublicActionType type,
      String requestFingerprint,
      Map<String, Object> payload,
      @JsonInclude(JsonInclude.Include.NON_NULL)
      ExpectedHttpError expectedError
  ) {

    public PublicAction {
      Objects.requireNonNull(actionId, "actionId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      if (tickSequence < 1L || sequence < 0L || requestFingerprint.isBlank()
          || requestFingerprint.length() > 128) {
        throw new IllegalArgumentException("Public action identity and sequence are required");
      }
      payload = ValidationPublicActionPayloadPolicy.validateAndFreeze(type, payload);
    }

    public PublicAction(
        UUID actionId,
        long tickSequence,
        long sequence,
        PublicActionType type,
        String requestFingerprint,
        Map<String, Object> payload
    ) {
      this(
          actionId,
          tickSequence,
          sequence,
          type,
          requestFingerprint,
          payload,
          null);
    }
  }

  public record StartRequest(
      UUID runId,
      long generation,
      String requestFingerprint,
      @JsonDeserialize(using = ValidationExactTextDeserializer.class)
      String seed,
      Instant virtualStart,
      DemoExecutionPolicy executionPolicy,
      Map<String, BigDecimal> initialBalances,
      AccountSettings accountSettings,
      List<CompositeTick> ticks,
      List<PublicAction> actions,
      BigDecimal speedMultiplier
  ) {

    public StartRequest {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(requestFingerprint, "requestFingerprint");
      Objects.requireNonNull(seed, "seed");
      virtualStart = ValidationInstantPrecision.require(virtualStart, "virtualStart");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      Objects.requireNonNull(accountSettings, "accountSettings");
      Objects.requireNonNull(speedMultiplier, "speedMultiplier");
      initialBalances = canonicalBalances(initialBalances);
      ticks = List.copyOf(ticks == null ? List.of() : ticks);
      actions = List.copyOf(actions == null ? List.of() : actions);
      if (generation <= 0L || requestFingerprint.isBlank() || requestFingerprint.length() > 128
          || seed.isBlank() || seed.length() > 256
          || speedMultiplier.signum() <= 0) {
        throw new IllegalArgumentException("Run identity and positive speedMultiplier are required");
      }
      try {
        speedMultiplier = speedMultiplier.setScale(6, RoundingMode.UNNECESSARY);
        if (speedMultiplier.precision() > 18) {
          throw new ArithmeticException("precision");
        }
      } catch (ArithmeticException exception) {
        throw new IllegalArgumentException(
            "speedMultiplier must be an exact positive NUMERIC(18,6) value",
            exception);
      }
      if (ticks.isEmpty() || ticks.size() > MAX_TICKS || actions.size() > MAX_ACTIONS) {
        throw new IllegalArgumentException("Validation run size is outside the fixed bounds");
      }
      ValidationMarketPathService.validateTicks(new ValidationMarketPathService.PathRequest(
          runId,
          generation,
          requestFingerprint,
          virtualStart,
          executionPolicy,
          ticks));
      Set<Long> tickSequences = new HashSet<>();
      for (int index = 0; index < ticks.size(); index++) {
        CompositeTick tick = ticks.get(index);
        long expectedSequence = index + 1L;
        if (!runId.equals(tick.runId())
            || generation != tick.generation()
            || tick.sequence() != expectedSequence
            || !virtualStart.plusSeconds(expectedSequence).equals(tick.virtualTime())) {
          throw new IllegalArgumentException(
              "Every Tick must be contiguous and belong to the run generation");
        }
        tickSequences.add(tick.sequence());
      }
      Set<UUID> actionIds = new HashSet<>();
      for (PublicAction action : actions) {
        if (!tickSequences.contains(action.tickSequence())) {
          throw new IllegalArgumentException("Every action must reference an existing scenario Tick");
        }
        if (!actionIds.add(action.actionId())) {
          throw new IllegalArgumentException("Public action ids must be unique");
        }
      }
    }

    public StartRequest(
        UUID runId,
        long generation,
        String requestFingerprint,
        String seed,
        Instant virtualStart,
        DemoExecutionPolicy executionPolicy,
        List<CompositeTick> ticks,
        List<PublicAction> actions,
        BigDecimal speedMultiplier
    ) {
      this(
          runId,
          generation,
          requestFingerprint,
          seed,
          virtualStart,
          executionPolicy,
          Map.of("USDT", new BigDecimal("50000.00000000")),
          AccountSettings.defaults(),
          ticks,
          actions,
          speedMultiplier);
    }

    private static Map<String, BigDecimal> canonicalBalances(
        Map<String, BigDecimal> requested
    ) {
      if (requested == null || requested.isEmpty()) {
        throw new IllegalArgumentException("initialBalances must include USDT");
      }
      TreeMap<String, BigDecimal> sorted = new TreeMap<>();
      requested.forEach((asset, amount) -> {
        String canonicalAsset = asset == null
            ? ""
            : asset.strip().toUpperCase(java.util.Locale.ROOT);
        if (!canonicalAsset.matches("[A-Z0-9]{2,20}") || amount == null || amount.signum() < 0) {
          throw new IllegalArgumentException("Initial balance asset and amount are invalid");
        }
        try {
          BigDecimal exact = amount.setScale(8, RoundingMode.UNNECESSARY);
          if (exact.precision() > 24) {
            throw new ArithmeticException("precision");
          }
          if (sorted.put(canonicalAsset, exact) != null) {
            throw new IllegalArgumentException("Initial balance assets must be unique");
          }
        } catch (ArithmeticException exception) {
          throw new IllegalArgumentException(
              "Initial balances must be exact NUMERIC(24,8) values",
              exception);
        }
      });
      if (!sorted.containsKey("USDT")) {
        throw new IllegalArgumentException("initialBalances must include USDT");
      }
      return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
  }

  public record AccountSettings(
      String positionMode,
      String marginMode,
      int leverage,
      String quantityUnit
  ) {

    public AccountSettings {
      Objects.requireNonNull(positionMode, "positionMode");
      Objects.requireNonNull(marginMode, "marginMode");
      Objects.requireNonNull(quantityUnit, "quantityUnit");
      positionMode = positionMode.strip().toUpperCase(java.util.Locale.ROOT);
      marginMode = marginMode.strip().toUpperCase(java.util.Locale.ROOT);
      quantityUnit = quantityUnit.strip().toUpperCase(java.util.Locale.ROOT);
      if (!Set.of("ONE_WAY", "HEDGE").contains(positionMode)
          || !Set.of("CROSS", "ISOLATED").contains(marginMode)
          || !Set.of("BASE", "LOTS").contains(quantityUnit)
          || leverage < 1
          || leverage > 125) {
        throw new IllegalArgumentException("Validation account settings are invalid");
      }
    }

    public static AccountSettings defaults() {
      return new AccountSettings("HEDGE", "CROSS", 10, "BASE");
    }
  }

  public record SeedPlan(UUID seedId, Map<String, BigDecimal> balances) {

    public SeedPlan {
      Objects.requireNonNull(seedId, "seedId");
      balances = Map.copyOf(balances == null ? Map.of() : balances);
    }
  }

  public record AccountConfigurationPlan(
      AccountSettings settings,
      List<String> perpetualSymbols
  ) {

    public AccountConfigurationPlan {
      Objects.requireNonNull(settings, "settings");
      perpetualSymbols = List.copyOf(perpetualSymbols == null ? List.of() : perpetualSymbols);
    }
  }
}
