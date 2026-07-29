package com.fxplatform.tradinglab.application;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.client.ThrowableInfo;
import com.fxplatform.tradinglab.client.ValidationBackendClient;
import com.fxplatform.tradinglab.client.ValidationBackendExchange;
import com.fxplatform.tradinglab.client.ValidationControlReceipt;
import com.fxplatform.tradinglab.client.ValidationEventPage;
import com.fxplatform.tradinglab.client.ValidationHttpResult;
import com.fxplatform.tradinglab.client.ValidationResetReceipt;
import com.fxplatform.tradinglab.client.ValidationResetRequest;
import com.fxplatform.tradinglab.client.ValidationRunAccepted;
import com.fxplatform.tradinglab.client.ValidationRunEvent;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.client.ValidationRunState;
import com.fxplatform.tradinglab.client.ValidationRunStateObservation;
import com.fxplatform.tradinglab.client.ValidationRunStateSnapshot;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidence;
import com.fxplatform.tradinglab.evidence.TradingLabCoordinatorEvidenceStore;
import com.fxplatform.tradinglab.queue.TradingLabRunClaim;
import com.fxplatform.tradinglab.queue.TradingLabRunCoordinator;
import com.fxplatform.tradinglab.report.TradingLabFencedReportWriter;
import com.fxplatform.tradinglab.report.TradingLabReportSection;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.state.RunTransitionCommand;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import com.fxplatform.tradinglab.state.TradingLabRunTransitionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** One bounded coordinator turn; the scheduled worker calls it again for non-terminal runs. */
@Service
@Profile("!validation")
@ConditionalOnProperty(
    prefix = "trading-lab.queue",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class DefaultTradingLabRunCoordinator implements TradingLabRunCoordinator {

  private static final int MAX_EVENTS_PER_TURN = 200;
  private static final String INITIAL_RESET_KEY = "initial-reset";
  private static final String FINAL_RESET_KEY = "final-reset";
  private static final String START_KEY = "start";
  private static final String INITIAL_RUN_PRESENT =
      "VALIDATION_RESET_INITIAL_RUN_PRESENT";
  private static final String RESET_TIMEOUT =
      "VALIDATION_HTTP_TIMEOUT";
  private static final String RESET_IN_PROGRESS =
      "VALIDATION_RESET_OPERATION_IN_PROGRESS";
  private static final String UNSAFE_REPORT =
      "TRADING_LAB_REPORT_UNSAFE_TRACE";
  private static final String UNSAFE_REPORT_SUMMARY =
      "Trading Lab report contains unsafe trace evidence";
  private static final String GENERIC_VALIDATION_FAILURE =
      "TRADING_LAB_VALIDATION_FAILED";

  private final TradingLabCoordinatorRunLoader runLoader;
  private final TradingLabRunRepository runs;
  private final TradingLabRunTransitionService transitions;
  private final TradingLabCoordinatorEvidenceStore evidenceStore;
  private final TradingLabEvidenceReportProjector projector;
  private final TradingLabValidationExchangeRecorder exchanges;
  private final TradingLabRunReportInitializer reportInitializer;
  private final TradingLabValidationStartRequestFactory startRequests;
  private final TradingLabFencedReportWriter reportWriter;
  private final ValidationBackendClient validation;

  public DefaultTradingLabRunCoordinator(
      TradingLabCoordinatorRunLoader runLoader,
      TradingLabRunRepository runs,
      TradingLabRunTransitionService transitions,
      TradingLabCoordinatorEvidenceStore evidenceStore,
      TradingLabEvidenceReportProjector projector,
      TradingLabValidationExchangeRecorder exchanges,
      TradingLabRunReportInitializer reportInitializer,
      TradingLabValidationStartRequestFactory startRequests,
      TradingLabFencedReportWriter reportWriter,
      ValidationBackendClient validation
  ) {
    this.runLoader = Objects.requireNonNull(runLoader, "runLoader");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
    this.reportInitializer = Objects.requireNonNull(reportInitializer, "reportInitializer");
    this.startRequests = Objects.requireNonNull(startRequests, "startRequests");
    this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
    this.validation = Objects.requireNonNull(validation, "validation");
  }

  @Override
  public void coordinate(TradingLabRunClaim claim) {
    Objects.requireNonNull(claim, "claim");
    TradingLabCoordinatorRunContext context = runLoader.requireLive(claim);
    if (terminal(context.state())) {
      return;
    }
    if (context.state() == TradingLabRunState.QUEUED) {
      CleanupIntentSelection queuedIntent = findUniqueCleanupIntent(context, true);
      if (queuedIntent != null) {
        requireCleanupIntentCompatible(context, queuedIntent);
      }
      if (context.run().reportQuarantined()) {
        finishQueuedQuarantined(context);
        return;
      }
    }
    if (context.state() == TradingLabRunState.CLEANING) {
      resumeCleaning(context);
      return;
    }
    if (requiresQuarantineRecovery(context)) {
      coordinateQuarantined(context);
      return;
    }
    if (context.state() == TradingLabRunState.QUEUED) {
      reportInitializer.initialize(context.run().runId(), context.claimOwner());
      context = runLoader.requireLive(claim);
    }

    if (context.state() == TradingLabRunState.CANCELLING
        && resumePlannedCleanup(context)) {
      return;
    }
    if ((context.state() == TradingLabRunState.RUNNING
        || context.state() == TradingLabRunState.PAUSED)
        && resumeActiveCleanup(context)) {
      return;
    }
    if (hasPendingActiveControl(context)) {
      coordinateActive(context);
      return;
    }
    projector.replayMissing(claim.runId(), claim.claimOwner());
    context = runLoader.requireLive(claim);
    if (context.state() == TradingLabRunState.CANCELLING
        && resumePlannedCleanup(context)) {
      return;
    }
    switch (context.state()) {
      case QUEUED -> coordinateQueued(context);
      case RESETTING -> coordinateResetting(context);
      case RUNNING, PAUSED, CANCELLING -> coordinateActive(context);
      case CLEANING -> resumeCleaning(context);
      default -> throw failure(
          "TRADING_LAB_COORDINATOR_STATE_INVALID",
          "Trading Lab run is not worker-coordinateable");
    }
  }

  private static boolean hasPendingActiveControl(
      TradingLabCoordinatorRunContext context
  ) {
    return switch (context.state()) {
      case RUNNING ->
          context.run().pauseRequested() || context.run().cancelRequested();
      case PAUSED ->
          !context.run().pauseRequested() || context.run().cancelRequested();
      case CANCELLING -> true;
      default -> false;
    };
  }

  private void coordinateQuarantined(TradingLabCoordinatorRunContext initial) {
    TradingLabCoordinatorRunContext context = runLoader.requireLive(
        initial.run().runId(), initial.claimOwner());
    CleanupIntentSelection selected = findUniqueCleanupIntent(context, true);
    if (selected == null) {
      coordinateQuarantinedWithoutCleanupIntent(context);
      return;
    }
    Map<String, Object> request = selected.request();
    requireCleanupIntentCompatible(context, selected);
    if (exactBoolean(request.get("finalResetRequired"))) {
      coordinateQuarantinedAfterCleanupIntent(context, selected);
    } else {
      finishQuarantinedWithoutValidation(context);
    }
  }

  private void coordinateQuarantinedWithoutCleanupIntent(
      TradingLabCoordinatorRunContext initial
  ) {
    coordinateRawFailedReportRecovery(initial, true, null);
  }

  private void coordinateQuarantinedAfterCleanupIntent(
      TradingLabCoordinatorRunContext initial,
      CleanupIntentSelection cleanupIntent
  ) {
    coordinateRawFailedReportRecovery(initial, true, cleanupIntent);
  }

  private void coordinateFailedReportAfterCleanupIntent(
      TradingLabCoordinatorRunContext initial,
      CleanupIntentSelection cleanupIntent
  ) {
    coordinateRawFailedReportRecovery(initial, false, cleanupIntent);
  }

  private void coordinateRawFailedReportRecovery(
      TradingLabCoordinatorRunContext initial,
      boolean quarantined,
      CleanupIntentSelection requiredCleanupIntent
  ) {
    UUID runId = initial.run().runId();
    String owner = initial.claimOwner();
    TradingLabCoordinatorRunContext context = runLoader.requireLive(runId, owner);
    boolean recoverable = quarantined
        ? requiresQuarantineRecovery(context)
        : requiresFailedReportCleanupRecovery(context);
    if (!recoverable) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab failed-report recovery lost its live state");
    }

    ValidationRunStateObservation observed = requireSuccess(validation.state(runId));
    if (observed == null) {
      throw failure(
          "TRADING_LAB_VALIDATION_HTTP_INVALID",
          "Validation backend returned no state observation");
    }
    ValidationRunStateSnapshot remote = observed.run();
    long expectedGeneration;
    boolean mayFallbackToCurrentGeneration = false;
    if (requiredCleanupIntent != null) {
      Map<String, Object> cleanupRequest = requiredCleanupIntent.request();
      long intentGeneration = exactLong(cleanupRequest.get("generation"));
      long intentValidationSequence =
          exactLong(cleanupRequest.get("validationSequence"));
      String intentFailureCode = (String) cleanupRequest.get("failureCode");
      if (remote != null) {
        ValidationRunStartRequest expected = startRequests.create(
            context.run(), intentGeneration);
        requireAcceptedIdentity(remote, expected);
        if (!remote.terminal()
            || remote.generation() != intentGeneration
            || terminalTarget(remote.state()) != requiredCleanupIntent.target()
            || remote.eventHighWatermark() != intentValidationSequence
            || !Objects.equals(remote.failureCode(), intentFailureCode)) {
          throw failure(
              "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
              "Validation run does not match its durable cleanup intent");
        }
      } else if ("RESETTING".equals(observed.durableResetState())
          && observed.durableGeneration() > 0L
          && !observed.generationCoherent()) {
        if (observed.durableGeneration() != intentGeneration) {
          throw failure(
              "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
              "Validation reset generation does not match its durable cleanup intent");
        }
        return;
      } else if (!"READY".equals(observed.durableResetState())
          || !observed.generationCoherent()
          || (observed.durableGeneration() != intentGeneration
              && (intentGeneration == Long.MAX_VALUE
                  || observed.durableGeneration() != intentGeneration + 1L))) {
        throw failure(
            "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
            "Validation state does not match its durable cleanup intent");
      }
      expectedGeneration = intentGeneration;
      } else {
        if (quarantined
            && (context.state() == TradingLabRunState.RESETTING
                || context.state() == TradingLabRunState.CLEANING)
            && remote == null
            && !observed.generationCoherent()
            && observed.authoritativeResetBaseGeneration().orElse(-1L) == 0L) {
          finishQuarantinedWithoutValidation(
              runLoader.requireLive(runId, owner));
          return;
        }
        if (remote != null) {
          ValidationRunStartRequest expected = startRequests.create(
              context.run(), remote.generation());
        requireAcceptedIdentity(remote, expected);
        if (!remote.terminal()) {
          cancelQuarantinedRun(context, remote);
          return;
        }
        expectedGeneration = remote.generation();
      } else {
        if ("RESETTING".equals(observed.durableResetState())
            && observed.durableGeneration() > 0L
            && !observed.generationCoherent()) {
          return;
        }
        if ("READY".equals(observed.durableResetState())
            && observed.generationCoherent()
            && observed.durableGeneration() > 0L) {
          expectedGeneration = observed.durableGeneration() == 1L
              ? 1L
              : observed.durableGeneration() - 1L;
          mayFallbackToCurrentGeneration = observed.durableGeneration() > 1L
              && observed.durableGeneration() != Long.MAX_VALUE;
        } else {
          expectedGeneration = quarantinedReplayGeneration(observed);
        }
      }
    }

    if (expectedGeneration == Long.MAX_VALUE) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Validation final reset generation cannot advance safely");
    }
    runLoader.requireLive(runId, owner);
    UUID operationId = operationId(runId, FINAL_RESET_KEY);
    ValidationResetRequest reset = new ValidationResetRequest(
        runId,
        operationId,
        ValidationResetRequest.Mode.FINAL,
        expectedGeneration);
    ValidationBackendExchange<ValidationResetReceipt> exchange = validation.reset(reset);
    if (mayFallbackToCurrentGeneration
        && exactStaleGenerationRejection(exchange)) {
      expectedGeneration = observed.durableGeneration();
      runLoader.requireLive(runId, owner);
      reset = new ValidationResetRequest(
          runId,
          operationId,
          ValidationResetRequest.Mode.FINAL,
          expectedGeneration);
      exchange = validation.reset(reset);
    } else if (!mayFallbackToCurrentGeneration
        && remote == null
        && observed.durableGeneration() == Long.MAX_VALUE
        && exactStaleGenerationRejection(exchange)) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Validation final reset cannot fall back beyond the generation range");
    }
    if (quarantined
        && requiredCleanupIntent == null
        && remote == null
        && "READY".equals(observed.durableResetState())
        && observed.generationCoherent()
        && exactFinalRunMismatchRejection(exchange)) {
      finishQuarantinedWithoutValidation(
          runLoader.requireLive(runId, owner));
      return;
    }
    if (pendingResetResult(exchange == null ? null : exchange.result())) {
      return;
    }
    ValidationResetReceipt receipt = requireReset(exchange);
    if (expectedGeneration == Long.MAX_VALUE
        || receipt.memoryGeneration() != expectedGeneration + 1L) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Validation final reset generation does not match its durable command");
    }

    context = runLoader.requireLive(runId, owner);
    if (context.state() != TradingLabRunState.CLEANING) {
      TradingLabRunState current = context.state();
      if (!quarantined || !quarantinedActiveState(current)) {
        throw failure(
            "TRADING_LAB_STALE_STATE",
            "Trading Lab failed-report cleanup started from an invalid state");
      }
      transition(
          context,
          current,
          TradingLabRunState.CLEANING,
          "task10:quarantined-" + current.name().toLowerCase() + "-cleaning",
          UNSAFE_REPORT);
      context = runLoader.requireLive(runId, owner);
    }
    finishRawFailedReportCleaning(context, quarantined);
  }

  private void cancelQuarantinedRun(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateSnapshot remote
  ) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    TradingLabRunState current = context.state();
    if (current == TradingLabRunState.RESETTING
        || current == TradingLabRunState.RUNNING
        || current == TradingLabRunState.PAUSED) {
      transition(
          context,
          current,
          TradingLabRunState.CANCELLING,
          "task10:quarantined-" + current.name().toLowerCase() + "-cancelling",
          UNSAFE_REPORT);
    }
    if (remote.cancelRequested()
        || remote.state() == ValidationRunState.CANCELLING) {
      return;
    }
    runLoader.requireLive(runId, owner);
    requireControl(validation.cancel(runId), runId, "CANCEL_REQUESTED");
  }

  private static long quarantinedReplayGeneration(
      ValidationRunStateObservation observed
  ) {
    if ("READY".equals(observed.durableResetState())
        && observed.generationCoherent()
        && observed.durableGeneration() > 1L) {
      return observed.durableGeneration() - 1L;
    }
    throw failure(
        "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
        "Validation quarantine reset has no exact replay generation");
  }

  private static boolean exactStaleGenerationRejection(
      ValidationBackendExchange<ValidationResetReceipt> exchange
  ) {
    if (exchange == null || exchange.succeeded() || exchange.result() == null) {
      return false;
    }
    ValidationHttpResult result = exchange.result();
    ThrowableInfo exception = result.exception();
    return result.status() >= 400
        && result.status() < 500
        && exception != null
        && "REMOTE".equals(exception.type())
        && !exception.retryable()
        && "VALIDATION_RESET_STALE_GENERATION".equals(exception.code());
  }

  private static boolean exactFinalRunMismatchRejection(
      ValidationBackendExchange<ValidationResetReceipt> exchange
  ) {
    if (exchange == null || exchange.succeeded() || exchange.result() == null) {
      return false;
    }
    ValidationHttpResult result = exchange.result();
    ThrowableInfo exception = result.exception();
    return result.status() >= 400
        && result.status() < 500
        && exception != null
        && "REMOTE".equals(exception.type())
        && !exception.retryable()
        && "VALIDATION_RESET_FINAL_RUN_MISMATCH".equals(exception.code());
  }

  private static boolean quarantinedRecoveryState(TradingLabRunState state) {
    return quarantinedActiveState(state)
        || state == TradingLabRunState.CLEANING;
  }

  private static boolean quarantinedActiveState(TradingLabRunState state) {
    return state == TradingLabRunState.RESETTING
        || state == TradingLabRunState.RUNNING
        || state == TradingLabRunState.PAUSED
        || state == TradingLabRunState.CANCELLING;
  }

  private static boolean requiresQuarantineRecovery(
      TradingLabCoordinatorRunContext context
  ) {
    return context.run().reportQuarantined()
        && quarantinedRecoveryState(context.state());
  }

  private static boolean requiresFailedReportCleanupRecovery(
      TradingLabCoordinatorRunContext context
  ) {
    return !context.run().reportQuarantined()
        && context.state() == TradingLabRunState.CLEANING
        && "FAILED".equals(context.run().reportStatus());
  }

  private void finishQueuedQuarantined(TradingLabCoordinatorRunContext context) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    String reportStatus = context.run().reportStatus();
    if ("PENDING".equals(reportStatus) || "WRITING".equals(reportStatus)) {
      reportWriter.fail(
          runLoader.requireLive(runId, owner).reportFence(),
          UNSAFE_REPORT,
          UNSAFE_REPORT_SUMMARY);
    } else if (!"FAILED".equals(reportStatus)
        && !"CANCELLED".equals(reportStatus)) {
      throw failure(
          "TRADING_LAB_REPORT_TERMINAL_CONFLICT",
          "Trading Lab quarantined report has an invalid status");
    }
    transition(
        runLoader.requireLive(runId, owner),
        TradingLabRunState.QUEUED,
        TradingLabRunState.FAILED,
        "task10:queued-failed-unsafe-report",
        UNSAFE_REPORT);
  }

  private void coordinateQueued(TradingLabCoordinatorRunContext context) {
    CleanupIntentSelection selected = findUniqueCleanupIntent(context, true);
    if (selected != null) {
      requireCleanupIntentCompatible(context, selected);
      finishWithoutValidation(context);
      return;
    }
    if (context.run().cancelRequested()) {
      finishWithoutValidation(context);
      return;
    }
    transition(
        context,
        TradingLabRunState.QUEUED,
        TradingLabRunState.RESETTING,
        "task7:queued-resetting",
        "VALIDATION_INITIAL_RESET");
    coordinateResetting(runLoader.requireLive(
        context.run().runId(), context.claimOwner()));
  }

  private void coordinateResetting(TradingLabCoordinatorRunContext context) {
    if (resumeResettingCleanup(context)) {
      return;
    }
    reportInitializer.initialize(context.run().runId(), context.claimOwner());
    context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    if (resumeResettingCleanup(context)) {
      return;
    }
    ValidationBackendExchange<ValidationRunStateObservation> observed = state(context);
    if (!observed.succeeded()) {
      throwRemote(observed.result());
    }
    context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    if (observed.data().run() != null) {
      ValidationRunStateSnapshot remote = observed.data().run();
      ValidationRunStartRequest request = startRequests.create(
          context.run(), remote.generation());
      requireAcceptedIdentity(remote, request);
      context = runLoader.requireLive(context.run().runId(), context.claimOwner());
      if (context.run().cancelRequested()) {
        transition(
            context,
            TradingLabRunState.RESETTING,
            TradingLabRunState.CANCELLING,
            "task7:resetting-cancelling",
            "CANCEL_REQUESTED_DURING_START");
        coordinateActive(runLoader.requireLive(
            context.run().runId(), context.claimOwner()));
        return;
      }
      transition(
          context,
          TradingLabRunState.RESETTING,
          TradingLabRunState.RUNNING,
          "task7:resetting-running",
          "VALIDATION_RUN_ACCEPTED");
      coordinateActive(runLoader.requireLive(
          context.run().runId(), context.claimOwner()));
      return;
    }
    Optional<ValidationHttpResult> accepted = exchanges.previousResult(
        context.run().runId(), context.claimOwner(), START_KEY);
    if (accepted.isPresent() && successful(accepted.orElseThrow())) {
      throw failure(
          "TRADING_LAB_VALIDATION_IDENTITY_LOST",
          "An accepted validation run is absent from its authoritative generation");
    }
    boolean definitelyAbsent = observed.data().definitelyAbsent(context.run().runId());
    Optional<TradingLabCoordinatorEvidence> resetIntent = evidenceStore.findIntent(
        context.run().runId(), context.claimOwner(), INITIAL_RESET_KEY);
    Optional<TradingLabCoordinatorEvidence> startIntent = evidenceStore.findIntent(
        context.run().runId(), context.claimOwner(), START_KEY);
    if (context.run().cancelRequested()) {
      if (resetIntent.isEmpty()) {
        finishWithoutValidation(context);
        return;
      }
      if (!definitelyAbsent && startIntent.isPresent()) {
        throw failure(
            "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
            "Validation start acknowledgement is not authoritative");
      }
      Optional<ValidationResetReceipt> reset = performInitialReset(context, observed.data());
      if (reset.isEmpty()) {
        return;
      }
      long generation = reset.orElseThrow().memoryGeneration();
      finishAfterInitialResetWithoutAcceptedRun(runLoader.requireLive(
          context.run().runId(), context.claimOwner()), generation);
      return;
    }
    if (startIntent.isPresent() && !definitelyAbsent) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Validation run absence is not authoritative");
    }
    if (resetIntent.isEmpty()
        && observed.data().authoritativeResetBaseGeneration().isEmpty()) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Validation reset base generation is not authoritative");
    }
    if (startAfterInitialReset(context, observed.data())) {
      coordinateActive(runLoader.requireLive(
          context.run().runId(), context.claimOwner()));
    }
  }

  private boolean startAfterInitialReset(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateObservation observation
  ) {
    Optional<ValidationResetReceipt> reset = performInitialReset(context, observation);
    if (reset.isEmpty()) {
      return false;
    }
    ValidationResetReceipt receipt = reset.orElseThrow();
    UUID runId = context.run().runId();
    long generation = receipt.memoryGeneration();
    TradingLabCoordinatorRunContext fresh = runLoader.requireLive(
        runId, context.claimOwner());
    if (fresh.run().cancelRequested()) {
      finishAfterInitialResetWithoutAcceptedRun(fresh, generation);
      return false;
    }
    ValidationRunStartRequest start = startRequests.create(fresh.run(), generation);
    ValidationBackendExchange<ValidationRunAccepted> started = exchanges.mutation(
        runId,
        context.claimOwner(),
        START_KEY,
        startSummary(start),
        () -> {
          TradingLabCoordinatorRunContext live = runLoader.requireLive(
              runId, context.claimOwner());
          if (live.run().cancelRequested()) {
            throw failure(
                "TRADING_LAB_CONTROL_CHANGED",
                "Trading Lab cancellation arrived before validation start");
          }
          return validation.startRun(start);
        });
    requireSuccess(started);
    transition(
        runLoader.requireLive(runId, context.claimOwner()),
        TradingLabRunState.RESETTING,
        TradingLabRunState.RUNNING,
        "task7:resetting-running",
        "VALIDATION_RUN_ACCEPTED");
    return true;
  }

  private Optional<ValidationResetReceipt> performInitialReset(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateObservation observation
  ) {
    UUID runId = context.run().runId();
    UUID operationId = operationId(runId, INITIAL_RESET_KEY);
    Optional<TradingLabCoordinatorEvidence> existingIntent = evidenceStore.findIntent(
        runId, context.claimOwner(), INITIAL_RESET_KEY);
    long expectedGeneration = existingIntent
        .map(DefaultTradingLabRunCoordinator::nestedEvidence)
        .map(value -> exactLong(value.get("expectedGeneration")))
        .orElseGet(() -> observation.authoritativeResetBaseGeneration()
            .orElseThrow(() -> failure(
                "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
                "Validation reset base generation is not authoritative")));
    ValidationResetRequest reset = new ValidationResetRequest(
        runId, operationId, ValidationResetRequest.Mode.INITIAL, expectedGeneration);
    if (existingIntent.isPresent()
        && awaitPriorPendingReset(
            context,
            INITIAL_RESET_KEY,
            expectedGeneration,
            observation)) {
      return Optional.empty();
    }
    ValidationBackendExchange<ValidationResetReceipt> resetExchange = exchanges.mutation(
        runId,
        context.claimOwner(),
        INITIAL_RESET_KEY,
        Map.of(
            "runId", runId.toString(),
            "operationId", operationId.toString(),
            "mode", "INITIAL",
            "expectedGeneration", expectedGeneration),
        () -> {
          runLoader.requireLive(runId, context.claimOwner());
          return validation.reset(reset);
        });
    if (permanentInitialResetRejection(resetExchange)) {
      failBeforeValidation(context);
      return Optional.empty();
    }
    if (pendingResetResult(resetExchange.result())) {
      return Optional.empty();
    }
    return Optional.of(requireReset(resetExchange));
  }

  private void coordinateActive(TradingLabCoordinatorRunContext initial) {
    UUID runId = initial.run().runId();
    String owner = initial.claimOwner();
    TradingLabCoordinatorRunContext context = runLoader.requireLive(runId, owner);
    ValidationBackendExchange<ValidationRunStateObservation> observed = state(context);
    if (!observed.succeeded()) {
      throwRemote(observed.result());
    }
    ValidationRunStateSnapshot remote = observed.data().run();
    if (remote == null) {
      throw failure(
          "TRADING_LAB_VALIDATION_IDENTITY_LOST",
          "Active Trading Lab run is absent from validation");
    }
    ValidationRunStartRequest expected = startRequests.create(
        context.run(), remote.generation());
    requireAcceptedIdentity(remote, expected);
    context = runLoader.requireLive(runId, owner);
    observeHighWatermark(context, remote.generation(), remote.eventHighWatermark());
    context = projectValidationProgress(context, remote);

    context = synchronizeMainState(context, remote);
    if (forwardControl(context, remote)) {
      return;
    }
    if (awaitingControlSettlement(context, remote)) {
      return;
    }

    long cursor = evidenceStore.lastValidationSequence(runId, owner);
    long terminalHighWatermark = remote.eventHighWatermark();
    requireNonRegressingHighWatermark(cursor, terminalHighWatermark);
    boolean remoteTerminal = remote.terminal();
    for (int mirrored = 0; mirrored < MAX_EVENTS_PER_TURN; mirrored++) {
      if (cursor == terminalHighWatermark && remoteTerminal) {
        enterCleaning(
            runLoader.requireLive(runId, owner), remote, cursor);
        return;
      }
      ValidationBackendExchange<ValidationEventPage> pageExchange = exchanges.query(
          runId,
          owner,
          () -> {
            runLoader.requireLive(runId, owner);
            return validation.eventsAfter(runId, cursorForCall(runId, owner));
      });
      ValidationEventPage page = requireSuccess(pageExchange);
      observeHighWatermark(
          runLoader.requireLive(runId, owner), remote.generation(), page.highWatermark());
      if (page.highWatermark() < terminalHighWatermark) {
        throw failure(
            "TRADING_LAB_VALIDATION_HIGH_WATERMARK_REGRESSED",
            "Validation event high watermark regressed");
      }
      requireNonRegressingHighWatermark(cursor, page.highWatermark());
      terminalHighWatermark = page.highWatermark();
      if (page.events().isEmpty()) {
        if (page.terminal() && cursor == page.highWatermark()) {
          ValidationRunStateSnapshot terminalState = requireObservedRun(
              state(runLoader.requireLive(runId, owner)));
          requireAcceptedIdentity(terminalState, expected);
          observeHighWatermark(
              runLoader.requireLive(runId, owner),
              terminalState.generation(),
              terminalState.eventHighWatermark());
          TradingLabCoordinatorRunContext terminalContext = projectValidationProgress(
              runLoader.requireLive(runId, owner), terminalState);
          enterCleaning(
              terminalContext, terminalState, cursor);
        }
        return;
      }
      ValidationRunEvent event = page.events().getFirst();
      TradingLabCoordinatorEvidence evidence = evidenceStore.appendValidationEvent(
          runId, owner, event);
      projector.project(runId, owner, evidence);
      cursor = event.sequence();
      if (page.terminal() && !page.hasMore() && cursor == page.highWatermark()) {
        ValidationBackendExchange<ValidationRunStateObservation> terminalState = state(
            runLoader.requireLive(runId, owner));
        ValidationRunStateSnapshot terminalSnapshot = requireObservedRun(terminalState);
        requireAcceptedIdentity(terminalSnapshot, expected);
        observeHighWatermark(
            runLoader.requireLive(runId, owner),
            terminalSnapshot.generation(),
            terminalSnapshot.eventHighWatermark());
        TradingLabCoordinatorRunContext terminalContext = projectValidationProgress(
            runLoader.requireLive(runId, owner), terminalSnapshot);
        enterCleaning(
            terminalContext,
            terminalSnapshot,
            cursor);
        return;
      }
      if ("CHECKPOINT".equals(event.type())) {
        return;
      }
      if (!page.hasMore()) {
        return;
      }
      TradingLabCoordinatorRunContext latest = runLoader.requireLive(runId, owner);
      if (controlIntentChanged(context, latest)) {
        return;
      }
    }
  }

  private static boolean controlIntentChanged(
      TradingLabCoordinatorRunContext baseline,
      TradingLabCoordinatorRunContext latest
  ) {
    return baseline.run().pauseRequested() != latest.run().pauseRequested()
        || baseline.run().cancelRequested() != latest.run().cancelRequested();
  }

  private long cursorForCall(UUID runId, String owner) {
    return evidenceStore.lastValidationSequence(runId, owner);
  }

  private void observeHighWatermark(
      TradingLabCoordinatorRunContext context,
      long generation,
      long highWatermark
  ) {
    evidenceStore.observeValidationHighWatermark(
            context.run().runId(), context.claimOwner(), generation, highWatermark)
        .ifPresent(evidence -> projector.project(
            context.run().runId(), context.claimOwner(), evidence));
  }

  private TradingLabCoordinatorRunContext projectValidationProgress(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateSnapshot remote
  ) {
    long completed = Math.max(0L, remote.lastCompletedTickSequence());
    if (completed > context.run().totalTicks()) {
      throw failure(
          "TRADING_LAB_VALIDATION_PROGRESS_INVALID",
          "Validation completed tick sequence exceeds the frozen run total");
    }
    if (completed <= context.run().processedTicks()) {
      return context;
    }
    int updated = runs.advanceFencedProcessedTicks(
        context.run().runId(),
        context.claimOwner(),
        completed);
    TradingLabCoordinatorRunContext refreshed =
        runLoader.requireLive(context.run().runId(), context.claimOwner());
    if (updated == 0 && refreshed.run().processedTicks() < completed) {
      throw failure(
          "TRADING_LAB_PROGRESS_FENCE_LOST",
          "Trading Lab progress update lost its live worker fence");
    }
    return refreshed;
  }

  private TradingLabCoordinatorRunContext synchronizeMainState(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateSnapshot remote
  ) {
    TradingLabRunState current = context.state();
    long remoteCursor = evidenceStore.lastValidationSequence(
        context.run().runId(), context.claimOwner());
    if (current == TradingLabRunState.RUNNING
        && remote.state() == ValidationRunState.PAUSED) {
      transition(
          context,
          TradingLabRunState.RUNNING,
          TradingLabRunState.PAUSED,
          "task7:running-paused:" + remoteCursor,
          "VALIDATION_PAUSED");
      return runLoader.requireLive(context.run().runId(), context.claimOwner());
    }
    if (current == TradingLabRunState.PAUSED
        && remote.state() != ValidationRunState.PAUSED
        && !remote.state().terminal()) {
      transition(
          context,
          TradingLabRunState.PAUSED,
          TradingLabRunState.RUNNING,
          "task7:paused-running:" + remoteCursor,
          "VALIDATION_RESUMED");
      return runLoader.requireLive(context.run().runId(), context.claimOwner());
    }
    if ((current == TradingLabRunState.RUNNING || current == TradingLabRunState.PAUSED)
        && context.run().cancelRequested()) {
      transition(
          context,
          current,
          TradingLabRunState.CANCELLING,
          "task7:" + current.name().toLowerCase() + "-cancelling",
          "CANCEL_REQUESTED");
      return runLoader.requireLive(context.run().runId(), context.claimOwner());
    }
    return context;
  }

  private boolean forwardControl(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateSnapshot remote
  ) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    if (remote.terminal()) {
      return false;
    }
    if (context.run().cancelRequested()
        && !remote.cancelRequested()
        && remote.state() != ValidationRunState.CANCELLING) {
      String key = "cancel";
      ValidationBackendExchange<ValidationControlReceipt> exchange =
          exchanges.mutationAttempt(
              runId,
              owner,
              key,
              Map.of("runId", runId.toString(), "control", "cancel"),
              () -> validation.cancel(runId));
      requireControl(exchange, runId, "CANCEL_REQUESTED");
      return true;
    }
    if (context.run().pauseRequested()
        && context.state() == TradingLabRunState.RUNNING
        && !remote.pauseRequested()
        && remote.state() != ValidationRunState.PAUSED) {
      String key = "pause:" + context.run().version();
      ValidationBackendExchange<ValidationControlReceipt> exchange =
          exchanges.mutationAttempt(
              runId,
              owner,
              key,
              Map.of("runId", runId.toString(), "control", "pause"),
              () -> validation.pause(runId));
      requireControl(exchange, runId, "PAUSE_REQUESTED");
      return true;
    }
    if (!context.run().pauseRequested()
        && context.state() == TradingLabRunState.PAUSED
        && remote.pauseRequested()
        && remote.state() == ValidationRunState.PAUSED) {
      String key = "resume:" + context.run().version();
      ValidationBackendExchange<ValidationControlReceipt> exchange =
          exchanges.mutationAttempt(
              runId,
              owner,
              key,
              Map.of("runId", runId.toString(), "control", "resume"),
              () -> validation.resume(runId));
      requireControl(exchange, runId, "RESUME_REQUESTED");
      return true;
    }
    return false;
  }

  private static boolean awaitingControlSettlement(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateSnapshot remote
  ) {
    return !remote.terminal()
        && (context.run().cancelRequested()
            || (context.run().pauseRequested()
                && context.state() == TradingLabRunState.RUNNING
                && remote.state() != ValidationRunState.PAUSED));
  }

  private void enterCleaning(
      TradingLabCoordinatorRunContext context,
      ValidationRunStateSnapshot remote,
      long validationSequence
  ) {
    if (!remote.terminal() || validationSequence != remote.eventHighWatermark()) {
      throw failure(
          "TRADING_LAB_VALIDATION_NOT_DRAINED",
          "Validation terminal evidence has not been fully mirrored");
    }
    TradingLabRunState target = terminalTarget(remote.state());
    String cleanupKey = "cleanup:" + target.name();
    Map<String, Object> cleanupRequest = cleanupIntent(
        target,
        remote.generation(),
        validationSequence,
        remote.failureCode(),
        true);
    CleanupIntentSelection existing = findUniqueCleanupIntent(context, true);
    if (existing != null) {
      requireExactCleanupIntent(
          existing,
          target,
          remote.generation(),
          validationSequence,
          remote.failureCode(),
          true);
    }
    TradingLabCoordinatorEvidence intent = evidenceStore.appendIntent(
        context.run().runId(),
        context.claimOwner(),
        cleanupKey,
        cleanupRequest);
    Map<String, Object> appendedRequest = nestedEvidence(intent);
    requireCleanupIntent(target, appendedRequest);
    CleanupIntentSelection persisted = findUniqueCleanupIntent(
        runLoader.requireLive(context.run().runId(), context.claimOwner()),
        true);
    requireExactCleanupIntent(
        persisted,
        target,
        exactLong(appendedRequest.get("generation")),
        exactLong(appendedRequest.get("validationSequence")),
        (String) appendedRequest.get("failureCode"),
        exactBoolean(appendedRequest.get("finalResetRequired")));
    projector.project(context.run().runId(), context.claimOwner(), intent);
    context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    TradingLabRunState current = context.state();
    if (current != TradingLabRunState.CLEANING) {
      transition(
          context,
          current,
          TradingLabRunState.CLEANING,
          "task7:" + current.name().toLowerCase() + "-cleaning:" + validationSequence,
          "VALIDATION_" + target.name());
      context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    }
    finishCleanup(context, target, remote.generation(), validationSequence, remote.failureCode());
  }

  private void resumeCleaning(TradingLabCoordinatorRunContext context) {
    CleanupIntentSelection selected = findUniqueCleanupIntent(context, true);
    if (selected == null) {
      if (context.run().reportQuarantined()) {
        coordinateQuarantinedWithoutCleanupIntent(context);
        return;
      }
      throw failure(
          "TRADING_LAB_CLEANUP_INTENT_MISSING",
          "Trading Lab cleaning state has no durable terminal intent");
    }

    TradingLabRunState target = selected.target();
    Map<String, Object> request = selected.request();
    requireCleanupIntentCompatible(context, selected);
    long generation = exactLong(request.get("generation"));
    long validationSequence = exactLong(request.get("validationSequence"));
    String failureCode = (String) request.get("failureCode");
    boolean finalResetRequired = exactBoolean(request.get("finalResetRequired"));
    if (context.run().reportQuarantined()) {
      if (finalResetRequired) {
        coordinateQuarantinedAfterCleanupIntent(context, selected);
      } else {
        finishQuarantinedWithoutValidation(context);
      }
      return;
    }
    if ("FAILED".equals(context.run().reportStatus())) {
      if (finalResetRequired) {
        coordinateFailedReportAfterCleanupIntent(context, selected);
      } else {
        transition(
            runLoader.requireLive(context.run().runId(), context.claimOwner()),
            TradingLabRunState.CLEANING,
            TradingLabRunState.FAILED,
            "task10:cleaning-failed-no-validation-reset",
            "VALIDATION_CLEANUP_FAILED");
      }
      return;
    }
    TradingLabRunState closedTarget = closedReportTarget(context, target);
    if (closedTarget != null) {
      transition(
          runLoader.requireLive(context.run().runId(), context.claimOwner()),
          TradingLabRunState.CLEANING,
          closedTarget,
          "task10:cleaning-" + closedTarget.name().toLowerCase(),
          closedTarget == target
              ? "VALIDATION_CLEANUP_FINISHED"
              : "VALIDATION_CLEANUP_FAILED");
      return;
    }
    projector.replayMissing(context.run().runId(), context.claimOwner());
    context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    if (finalResetRequired) {
      if (awaitPriorPendingReset(
          context,
          finalResetKey(target),
          generation,
          null)) {
        return;
      }
      finishCleanup(context, target, generation, validationSequence, failureCode);
    } else if (target == TradingLabRunState.FAILED) {
      finishFailedWithoutValidation(context);
    } else {
      finishWithoutValidation(context);
    }
  }

  private void finishRawFailedReportCleaning(
      TradingLabCoordinatorRunContext context,
      boolean quarantined
  ) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    String reportStatus = context.run().reportStatus();
    if (quarantined) {
      if ("PENDING".equals(reportStatus) || "WRITING".equals(reportStatus)) {
        reportWriter.fail(
            runLoader.requireLive(runId, owner).reportFence(),
            UNSAFE_REPORT,
            UNSAFE_REPORT_SUMMARY);
      } else if (!"FAILED".equals(reportStatus)
          && !"CANCELLED".equals(reportStatus)) {
        throw failure(
            "TRADING_LAB_REPORT_TERMINAL_CONFLICT",
            "Trading Lab quarantined report has an invalid status");
      }
    } else if (!"FAILED".equals(reportStatus)) {
      throw failure(
          "TRADING_LAB_REPORT_TERMINAL_CONFLICT",
          "Trading Lab cleanup report is not failed");
    }
    transition(
        runLoader.requireLive(runId, owner),
        TradingLabRunState.CLEANING,
        TradingLabRunState.FAILED,
        quarantined
            ? "task10:cleaning-failed-unsafe-report"
            : "task10:cleaning-failed-reset-proven",
        quarantined
            ? UNSAFE_REPORT
            : "VALIDATION_CLEANUP_FINISHED");
  }

  private void finishQuarantinedWithoutValidation(
      TradingLabCoordinatorRunContext initial
  ) {
    UUID runId = initial.run().runId();
    String owner = initial.claimOwner();
    TradingLabCoordinatorRunContext context = runLoader.requireLive(runId, owner);
    if (context.state() != TradingLabRunState.CLEANING) {
      TradingLabRunState current = context.state();
      if (!quarantinedActiveState(current)) {
        throw failure(
            "TRADING_LAB_STALE_STATE",
            "Trading Lab no-reset quarantine started from an invalid state");
      }
      transition(
          context,
          current,
          TradingLabRunState.CLEANING,
          "task10:quarantined-" + current.name().toLowerCase()
              + "-cleaning-no-validation-reset",
          UNSAFE_REPORT);
      context = runLoader.requireLive(runId, owner);
    }
    finishRawFailedReportCleaning(context, true);
  }

  private void finishCleanup(
      TradingLabCoordinatorRunContext context,
      TradingLabRunState target,
      long generation,
      long validationSequence,
      String failureCode
  ) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    String key = finalResetKey(target);
    UUID operationId = operationId(runId, FINAL_RESET_KEY);
    Map<String, Object> actual = terminalActualState(
        runId, owner, target, generation, validationSequence, failureCode);
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.ACTUAL_STATE,
        actual);
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());

    ValidationResetRequest reset = new ValidationResetRequest(
        runId, operationId, ValidationResetRequest.Mode.FINAL, generation);
    ValidationBackendExchange<ValidationResetReceipt> exchange = exchanges.mutation(
        runId,
        owner,
        key,
        Map.of(
            "runId", runId.toString(),
            "operationId", operationId.toString(),
            "mode", "FINAL",
            "expectedGeneration", generation),
        () -> validation.reset(reset));
    if (pendingResetResult(exchange.result())) {
      return;
    }
    if (!coherentResetSuccess(exchange, generation)) {
      if (finalResetFailureMayRecover(exchange)) {
        requireReset(exchange);
        throw failure(
            "TRADING_LAB_VALIDATION_RESET_RETRY_REQUIRED",
            "Validation final reset has no durable terminal receipt");
      }
      recordFailedCleanup(
          runId,
          owner,
          operationId,
          generation,
          cleanupFailureCode(exchange));
      return;
    }
    ValidationResetReceipt receipt = exchange.data();

    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("status", receipt.status().name());
    cleanup.put("operationId", operationId.toString());
    cleanup.put("expectedGeneration", generation);
    cleanup.put("resultGeneration", receipt.memoryGeneration());
    cleanup.put("finishedAt", receipt.finishedAt().toString());
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.CLEANUP,
        cleanup);
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());
    closeReport(runId, owner, target, failureCode);
    TradingLabCoordinatorRunContext closed = runLoader.requireLive(runId, owner);
    if (closed.run().reportQuarantined()) {
      finishRawFailedReportCleaning(closed, true);
      return;
    }
    transition(
        closed,
        TradingLabRunState.CLEANING,
        target,
        "task7:cleaning-" + target.name().toLowerCase(),
        "VALIDATION_CLEANUP_FINISHED");
  }

  private Map<String, Object> terminalActualState(
      UUID runId,
      String owner,
      TradingLabRunState target,
      long generation,
      long validationSequence,
      String failureCode
  ) {
    Optional<TradingLabEvidenceReportProjector.DurableValidationState> latest =
        projector.latestDurableState(runId, owner);
    Map<String, Object> actual = new LinkedHashMap<>();
    actual.put("terminalState", target.name());
    actual.put("validationGeneration", generation);
    actual.put("lastValidationSequence", validationSequence);
    actual.put("failureCode", failureCode);
    actual.put("stateAvailable", latest.isPresent());
    if (latest.isEmpty()) {
      actual.put("stateSource", null);
      actual.put("state", null);
      return actual;
    }
    TradingLabEvidenceReportProjector.DurableValidationState durable =
        latest.orElseThrow();
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("journalSequence", durable.journalSequence());
    source.put("validationSequence", durable.validationSequence());
    source.put("eventType", durable.eventType());
    source.put(
        "virtualTime",
        durable.virtualTime() == null ? null : durable.virtualTime().toString());
    source.put("correlationId", durable.correlationId());
    actual.put("stateSource", source);
    actual.put("state", durable.state());
    return actual;
  }

  private void recordFailedCleanup(
      UUID runId,
      String owner,
      UUID operationId,
      long generation,
      String failureCode
  ) {
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("status", "FAILED");
    cleanup.put("cleanupFailed", true);
    cleanup.put("environmentClean", false);
    cleanup.put("operationId", operationId.toString());
    cleanup.put("expectedGeneration", generation);
    cleanup.put("failureCode", failureCode);
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.CLEANUP,
        cleanup);
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());
  }

  private void finishAfterInitialResetWithoutAcceptedRun(
      TradingLabCoordinatorRunContext context,
      long generation
  ) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    TradingLabCoordinatorEvidence intent = evidenceStore.appendIntent(
        runId,
        owner,
        "cleanup:" + TradingLabRunState.CANCELLED.name(),
        cleanupIntent(
            TradingLabRunState.CANCELLED,
            generation,
            evidenceStore.lastValidationSequence(runId, owner),
            null,
            true));
    projector.project(runId, owner, intent);
    TradingLabCoordinatorRunContext fresh = runLoader.requireLive(runId, owner);
    if (fresh.state() == TradingLabRunState.RESETTING) {
      transition(
          fresh,
          TradingLabRunState.RESETTING,
          TradingLabRunState.CANCELLING,
          "task7:resetting-cancelling-after-reset",
          "CANCEL_REQUESTED_AFTER_INITIAL_RESET");
      fresh = runLoader.requireLive(runId, owner);
    }
    if (fresh.state() == TradingLabRunState.CANCELLING) {
      transition(
          fresh,
          TradingLabRunState.CANCELLING,
          TradingLabRunState.CLEANING,
          "task7:cancelling-cleaning:reset-only",
          "CANCELLED_BEFORE_VALIDATION_ACCEPTED");
      fresh = runLoader.requireLive(runId, owner);
    }
    if (fresh.state() != TradingLabRunState.CLEANING) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab reset-only cleanup started from an invalid state");
    }
    finishCleanup(
        fresh,
        TradingLabRunState.CANCELLED,
        generation,
        evidenceStore.lastValidationSequence(runId, owner),
        null);
  }

  private void finishWithoutValidation(TradingLabCoordinatorRunContext context) {
    TradingLabCoordinatorEvidence intent = evidenceStore.appendIntent(
        context.run().runId(),
        context.claimOwner(),
        "cleanup:" + TradingLabRunState.CANCELLED.name(),
        cleanupIntent(
            TradingLabRunState.CANCELLED,
            0L,
            0L,
            null,
            false));
    projector.project(context.run().runId(), context.claimOwner(), intent);
    context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    TradingLabRunState current = context.state();
    if (current == TradingLabRunState.QUEUED || current == TradingLabRunState.RESETTING) {
      transition(
          context,
          current,
          TradingLabRunState.CANCELLING,
          "task7:" + current.name().toLowerCase() + "-cancelling-unstarted",
          "CANCEL_REQUESTED_BEFORE_VALIDATION_START");
      context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    }
    if (context.state() == TradingLabRunState.CANCELLING) {
      transition(
          context,
          TradingLabRunState.CANCELLING,
          TradingLabRunState.CLEANING,
          "task7:cancelling-cleaning:unstarted",
          "CANCELLED_BEFORE_VALIDATION_START");
      context = runLoader.requireLive(context.run().runId(), context.claimOwner());
    }
    if (context.state() != TradingLabRunState.CLEANING) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab unstarted cleanup started from an invalid state");
    }
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.ACTUAL_STATE,
        terminalActualState(
            runId,
            owner,
            TradingLabRunState.CANCELLED,
            0L,
            0L,
            null));
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.CLEANUP,
        Map.of("status", "SKIPPED", "reason", "VALIDATION_NOT_STARTED"));
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());
    reportWriter.cancel(
        runLoader.requireLive(runId, owner).reportFence(),
        "Cancelled before validation start");
    TradingLabCoordinatorRunContext closed = runLoader.requireLive(runId, owner);
    if (closed.run().reportQuarantined()) {
      finishRawFailedReportCleaning(closed, true);
      return;
    }
    transition(
        closed,
        TradingLabRunState.CLEANING,
        TradingLabRunState.CANCELLED,
        "task7:cleaning-cancelled-unstarted",
        "CANCELLED_BEFORE_VALIDATION_START");
  }

  private boolean resumeResettingCleanup(TradingLabCoordinatorRunContext context) {
    CleanupIntentSelection selected = findUniqueCleanupIntent(context, true);
    if (selected == null) {
      return false;
    }
    requireCleanupIntentCompatible(context, selected);
    Map<String, Object> request = selected.request();
    if (selected.target() == TradingLabRunState.CANCELLED) {
      if (exactBoolean(request.get("finalResetRequired"))) {
        finishAfterInitialResetWithoutAcceptedRun(
            context, exactLong(request.get("generation")));
      } else {
        finishWithoutValidation(context);
      }
      return true;
    }
    transition(
        context,
        TradingLabRunState.RESETTING,
        TradingLabRunState.CLEANING,
        "task7:resetting-cleaning:initial-reset-rejected",
        "VALIDATION_INITIAL_RESET_REJECTED");
    resumeCleaning(runLoader.requireLive(
        context.run().runId(), context.claimOwner()));
    return true;
  }

  private void failBeforeValidation(TradingLabCoordinatorRunContext context) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    TradingLabCoordinatorEvidence intent = evidenceStore.appendIntent(
        runId,
        owner,
        "cleanup:" + TradingLabRunState.FAILED.name(),
        cleanupIntent(
            TradingLabRunState.FAILED,
            0L,
            0L,
            GENERIC_VALIDATION_FAILURE,
            false));
    projector.project(runId, owner, intent);
    context = runLoader.requireLive(runId, owner);
    if (context.state() == TradingLabRunState.RESETTING) {
      transition(
          context,
          TradingLabRunState.RESETTING,
          TradingLabRunState.CLEANING,
          "task7:resetting-cleaning:initial-reset-rejected",
          "VALIDATION_INITIAL_RESET_REJECTED");
      context = runLoader.requireLive(runId, owner);
    }
    if (context.state() != TradingLabRunState.CLEANING) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab rejected-reset cleanup started from an invalid state");
    }
    finishFailedWithoutValidation(context);
  }

  private void finishFailedWithoutValidation(TradingLabCoordinatorRunContext context) {
    UUID runId = context.run().runId();
    String owner = context.claimOwner();
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.ACTUAL_STATE,
        terminalActualState(
            runId,
            owner,
            TradingLabRunState.FAILED,
            0L,
            0L,
            GENERIC_VALIDATION_FAILURE));
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());
    Map<String, Object> cleanup = new LinkedHashMap<>();
    cleanup.put("status", "SKIPPED");
    cleanup.put("reason", "VALIDATION_INITIAL_RESET_REJECTED");
    cleanup.put("environmentClean", false);
    reportWriter.appendSingleton(
        runLoader.requireLive(runId, owner).reportFence(),
        TradingLabReportSection.CLEANUP,
        cleanup);
    reportWriter.flush(runLoader.requireLive(runId, owner).reportFence());
    reportWriter.fail(
        runLoader.requireLive(runId, owner).reportFence(),
        GENERIC_VALIDATION_FAILURE,
        "Validation run failed before validation start");
    TradingLabCoordinatorRunContext closed = runLoader.requireLive(runId, owner);
    if (closed.run().reportQuarantined()) {
      finishRawFailedReportCleaning(closed, true);
      return;
    }
    transition(
        closed,
        TradingLabRunState.CLEANING,
        TradingLabRunState.FAILED,
        "task7:cleaning-failed-unstarted",
        "VALIDATION_INITIAL_RESET_REJECTED");
  }

  private boolean resumePlannedCleanup(TradingLabCoordinatorRunContext context) {
    CleanupIntentSelection selected = findUniqueCleanupIntent(context, true);
    if (selected == null) {
      return false;
    }
    requireCleanupIntentCompatible(context, selected);
    TradingLabRunState target = selected.target();
    transition(
        context,
        TradingLabRunState.CANCELLING,
        TradingLabRunState.CLEANING,
        "task7:cancelling-cleaning:planned:" + target.name().toLowerCase(),
        "VALIDATION_CLEANUP_PLANNED");
    resumeCleaning(runLoader.requireLive(
        context.run().runId(), context.claimOwner()));
    return true;
  }

  private boolean resumeActiveCleanup(TradingLabCoordinatorRunContext context) {
    CleanupIntentSelection selected = findUniqueCleanupIntent(context, true);
    if (selected == null) {
      return false;
    }
    requireCleanupIntentCompatible(context, selected);
    TradingLabRunState current = context.state();
    transition(
        context,
        current,
        TradingLabRunState.CLEANING,
        "task7:" + current.name().toLowerCase()
            + "-cleaning:planned:" + selected.target().name().toLowerCase(),
        "VALIDATION_CLEANUP_PLANNED");
    resumeCleaning(runLoader.requireLive(
        context.run().runId(), context.claimOwner()));
    return true;
  }

  private CleanupIntentSelection findUniqueCleanupIntent(
      TradingLabCoordinatorRunContext context,
      boolean recoveryRead
  ) {
    CleanupIntentSelection selected = null;
    for (TradingLabRunState candidate : new TradingLabRunState[] {
        TradingLabRunState.COMPLETED,
        TradingLabRunState.CANCELLED,
        TradingLabRunState.FAILED}) {
      Optional<TradingLabCoordinatorEvidence> intent = recoveryRead
          ? evidenceStore.findCleanupIntentForRecovery(
              context.run().runId(),
              context.claimOwner(),
              "cleanup:" + candidate.name())
          : evidenceStore.findIntent(
              context.run().runId(),
              context.claimOwner(),
              "cleanup:" + candidate.name());
      if (intent.isEmpty()) {
        continue;
      }
      if (selected != null) {
        throw failure(
            "TRADING_LAB_EVIDENCE_CORRUPT",
            "Trading Lab run has multiple terminal cleanup intents");
      }
      Map<String, Object> request = nestedEvidence(intent.orElseThrow());
      requireCleanupIntent(candidate, request);
      selected = new CleanupIntentSelection(candidate, request);
    }
    return selected;
  }

  private static void requireCleanupIntentCompatible(
      TradingLabCoordinatorRunContext context,
      CleanupIntentSelection selected
  ) {
    TradingLabRunState state = context.state();
    Map<String, Object> request = selected.request();
    boolean finalResetRequired = exactBoolean(
        request.get("finalResetRequired"));
    TradingLabRunState target = selected.target();
    boolean compatible = switch (state) {
      case QUEUED ->
          target == TradingLabRunState.CANCELLED
              && !finalResetRequired
              && context.run().cancelRequested();
      case RESETTING ->
          (target == TradingLabRunState.CANCELLED
              && context.run().cancelRequested()
              && (!finalResetRequired
                  || (exactLong(request.get("validationSequence")) == 0L
                      && request.get("failureCode") == null)))
              || (target == TradingLabRunState.FAILED && !finalResetRequired);
      case RUNNING, PAUSED -> finalResetRequired;
      case CANCELLING ->
          finalResetRequired
              || target == TradingLabRunState.CANCELLED;
      case CLEANING -> true;
      default -> false;
    };
    if (!compatible) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup intent conflicts with its run state");
    }
  }

  private static void requireExactCleanupIntent(
      CleanupIntentSelection selected,
      TradingLabRunState target,
      long generation,
      long validationSequence,
      String failureCode,
      boolean finalResetRequired
  ) {
    if (selected == null
        || selected.target() != target
        || exactLong(selected.request().get("generation")) != generation
        || exactLong(selected.request().get("validationSequence")) != validationSequence
        || !Objects.equals(selected.request().get("failureCode"), failureCode)
        || exactBoolean(selected.request().get("finalResetRequired"))
            != finalResetRequired) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup intent conflicts with terminal validation evidence");
    }
  }

  private ValidationBackendExchange<ValidationRunStateObservation> state(
      TradingLabCoordinatorRunContext context
  ) {
    UUID runId = context.run().runId();
    return exchanges.query(
        runId,
        context.claimOwner(),
        () -> {
          runLoader.requireLive(runId, context.claimOwner());
          return validation.state(runId);
        });
  }

  private void closeReport(
      UUID runId,
      String owner,
      TradingLabRunState target,
      String failureCode
  ) {
    switch (target) {
      case COMPLETED -> reportWriter.complete(
          runLoader.requireLive(runId, owner).reportFence());
      case CANCELLED -> reportWriter.cancel(
          runLoader.requireLive(runId, owner).reportFence(),
          "Validation run cancelled");
      case FAILED -> reportWriter.fail(
          runLoader.requireLive(runId, owner).reportFence(),
          safeReportFailureCode(failureCode),
          "Validation run failed");
      default -> throw new IllegalArgumentException("Target is not terminal");
    }
  }

  private void transition(
      TradingLabCoordinatorRunContext context,
      TradingLabRunState expected,
      TradingLabRunState target,
      String idempotencyKey,
      String reason
  ) {
    if (context.state() != expected) {
      throw failure(
          "TRADING_LAB_STALE_STATE",
          "Trading Lab run state changed before coordinator transition");
    }
    transitions.transitionFenced(
        new RunTransitionCommand(
            context.run().runId(),
            expected,
            target,
            idempotencyKey,
            reason,
            null),
        context.claimOwner());
  }

  private static ValidationResetReceipt requireReset(
      ValidationBackendExchange<ValidationResetReceipt> exchange
  ) {
    ValidationResetReceipt receipt = requireSuccess(exchange);
    if (receipt.status() != ValidationResetReceipt.Status.SUCCEEDED
        || receipt.memoryGeneration() == null
        || receipt.redisGeneration() == null
        || !receipt.memoryGeneration().equals(receipt.redisGeneration())
        || receipt.memoryGeneration() <= 0L) {
      throw failure(
          receipt.errorCode() == null
              ? "TRADING_LAB_VALIDATION_RESET_FAILED"
              : receipt.errorCode(),
          "Validation reset did not produce a coherent generation");
    }
    return receipt;
  }

  private boolean awaitPriorPendingReset(
      TradingLabCoordinatorRunContext context,
      String logicalKey,
      long expectedGeneration,
      ValidationRunStateObservation alreadyObserved
  ) {
    if (exchanges.previousResult(
        context.run().runId(), context.claimOwner(), logicalKey)
        .filter(DefaultTradingLabRunCoordinator::pendingResetResult)
        .isEmpty()) {
      return false;
    }
    ValidationRunStateObservation observed = alreadyObserved;
    if (observed == null) {
      observed = requireSuccess(state(context));
    }
    if (observed == null) {
      throw failure(
          "TRADING_LAB_VALIDATION_HTTP_INVALID",
          "Validation backend returned no state observation");
    }
    if (!"RESETTING".equals(observed.durableResetState())) {
      return false;
    }
    if (observed.durableGeneration() != expectedGeneration
        || observed.generationCoherent()
        || observed.run() != null) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Validation reset generation does not match its durable intent");
    }
    return true;
  }

  private static boolean pendingResetResult(ValidationHttpResult result) {
    ThrowableInfo exception = result == null ? null : result.exception();
    if (exception == null) {
      return false;
    }
    if (RESET_TIMEOUT.equals(exception.code())) {
      return result.status() == 0
          && "TRANSPORT".equals(exception.type())
          && exception.retryable();
    }
    return RESET_IN_PROGRESS.equals(exception.code())
        && result.status() == 400
        && "REMOTE".equals(exception.type())
        && !exception.retryable();
  }

  private static String finalResetKey(TradingLabRunState target) {
    return "final-reset:" + target.name();
  }

  private static boolean coherentResetSuccess(
      ValidationBackendExchange<ValidationResetReceipt> exchange,
      long expectedGeneration
  ) {
    if (exchange == null
        || !exchange.succeeded()
        || expectedGeneration == Long.MAX_VALUE) {
      return false;
    }
    ValidationResetReceipt receipt = exchange.data();
    return receipt != null
        && receipt.status() == ValidationResetReceipt.Status.SUCCEEDED
        && receipt.memoryGeneration() != null
        && receipt.redisGeneration() != null
        && receipt.memoryGeneration().equals(receipt.redisGeneration())
        && receipt.memoryGeneration() == expectedGeneration + 1L;
  }

  private static boolean permanentInitialResetRejection(
      ValidationBackendExchange<ValidationResetReceipt> exchange
  ) {
    if (exchange == null || exchange.succeeded() || exchange.result() == null) {
      return false;
    }
    ValidationHttpResult result = exchange.result();
    ThrowableInfo exception = result.exception();
    return result.status() >= 400
        && result.status() < 500
        && exception != null
        && "REMOTE".equals(exception.type())
        && !exception.retryable()
        && INITIAL_RUN_PRESENT.equals(exception.code());
  }

  private static boolean finalResetFailureMayRecover(
      ValidationBackendExchange<ValidationResetReceipt> exchange
  ) {
    if (exchange == null) {
      return true;
    }
    if (exchange.succeeded()) {
      ValidationResetReceipt receipt = exchange.data();
      return receipt != null
          && receipt.status() == ValidationResetReceipt.Status.FAILED
          && "RESET_ALREADY_IN_PROGRESS".equals(receipt.errorCode());
    }
    return !permanentFinalResetRejection(exchange.result());
  }

  private static boolean permanentFinalResetRejection(ValidationHttpResult result) {
    if (result == null || result.status() < 400 || result.status() >= 500) {
      return false;
    }
    ThrowableInfo exception = result.exception();
    if (exception == null
        || !"REMOTE".equals(exception.type())
        || exception.retryable()
        || exception.code() == null) {
      return false;
    }
    return switch (exception.code()) {
      case "VALIDATION_RESET_COMMAND_REQUIRED",
          "VALIDATION_RESET_COMMAND_INVALID",
          "VALIDATION_RESET_IDEMPOTENCY_CONFLICT",
          "VALIDATION_RESET_STALE_GENERATION",
          "VALIDATION_RESET_INITIAL_RUN_PRESENT",
          "VALIDATION_RESET_FINAL_RUN_MISMATCH",
          "VALIDATION_RESET_FINAL_RUN_NOT_TERMINAL" -> true;
      default -> false;
    };
  }

  private static String cleanupFailureCode(
      ValidationBackendExchange<ValidationResetReceipt> exchange
  ) {
    String candidate = null;
    if (exchange != null) {
      if (exchange.succeeded() && exchange.data() != null) {
        candidate = exchange.data().errorCode();
      } else if (exchange.result() != null && exchange.result().exception() != null) {
        candidate = exchange.result().exception().code();
      }
    }
    return candidate != null && candidate.matches("[A-Z0-9_]{1,80}")
        ? candidate
        : "TRADING_LAB_VALIDATION_RESET_FAILED";
  }

  private static ValidationRunStateSnapshot requireObservedRun(
      ValidationBackendExchange<ValidationRunStateObservation> exchange
  ) {
    ValidationRunStateObservation observation = requireSuccess(exchange);
    if (observation == null || observation.run() == null) {
      throw failure(
          "TRADING_LAB_VALIDATION_IDENTITY_LOST",
          "Validation run disappeared during event reconciliation");
    }
    return observation.run();
  }

  private static void requireControl(
      ValidationBackendExchange<ValidationControlReceipt> exchange,
      UUID runId,
      String expectedControl
  ) {
    ValidationControlReceipt receipt = requireSuccess(exchange);
    if (!runId.equals(receipt.runId())
        || !expectedControl.equals(receipt.control())) {
      throw failure(
          "TRADING_LAB_VALIDATION_CONTROL_CONFLICT",
          "Validation control receipt has a conflicting identity");
    }
  }

  private static void requireNonRegressingHighWatermark(
      long durableCursor,
      long remoteHighWatermark
  ) {
    if (remoteHighWatermark < durableCursor) {
      throw failure(
          "TRADING_LAB_VALIDATION_HIGH_WATERMARK_REGRESSED",
          "Validation event high watermark is behind durable evidence");
    }
  }

  private static <T> T requireSuccess(ValidationBackendExchange<T> exchange) {
    if (exchange == null || !exchange.succeeded()) {
      if (exchange == null) {
        throw failure(
            "TRADING_LAB_VALIDATION_HTTP_INVALID",
            "Validation backend returned no exchange evidence");
      }
      throwRemote(exchange.result());
    }
    return exchange.data();
  }

  private static void requireAcceptedIdentity(
      ValidationRunStateSnapshot remote,
      ValidationRunStartRequest expected
  ) {
    if (!remote.matchesAcceptedIdentity(expected)) {
      throw failure(
          "TRADING_LAB_VALIDATION_IDENTITY_CONFLICT",
          "Validation run identity differs from the frozen Trading Lab request");
    }
  }

  private static void throwRemote(ValidationHttpResult result) {
    ThrowableInfo exception = result == null ? null : result.exception();
    String code = exception == null
        ? "TRADING_LAB_VALIDATION_HTTP_FAILED"
        : exception.code();
    throw failure(code, "Validation backend exchange did not succeed");
  }

  private static Map<String, Object> startSummary(ValidationRunStartRequest request) {
    return Map.of(
        "runId", request.runId().toString(),
        "generation", request.generation(),
        "requestFingerprint", request.requestFingerprint(),
        "seed", request.seed(),
        "tickCount", request.ticks().size(),
        "actionCount", request.actions().size());
  }

  private static Map<String, Object> cleanupIntent(
      TradingLabRunState target,
      long generation,
      long validationSequence,
      String failureCode,
      boolean finalResetRequired
  ) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("target", target.name());
    value.put("generation", generation);
    value.put("validationSequence", validationSequence);
    value.put("failureCode", failureCode);
    value.put("finalResetRequired", finalResetRequired);
    return value;
  }

  private static void requireCleanupIntent(
      TradingLabRunState target,
      Map<String, Object> request
  ) {
    if (!request.keySet().equals(Set.of(
        "target",
        "generation",
        "validationSequence",
        "failureCode",
        "finalResetRequired"))
        || !target.name().equals(request.get("target"))) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup intent has a conflicting target");
    }
    Object rawFailureCode = request.get("failureCode");
    if (rawFailureCode != null
        && (!(rawFailureCode instanceof String failureCode)
            || !failureCode.matches("[A-Z0-9_]{1,128}"))) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup intent has an invalid failure code");
    }
    long generation = exactLong(request.get("generation"));
    long validationSequence = exactLong(request.get("validationSequence"));
    boolean finalResetRequired = exactBoolean(request.get("finalResetRequired"));
    if (generation < 0L
        || validationSequence < 0L
        || (finalResetRequired
            && (generation == 0L || generation == Long.MAX_VALUE))) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup intent has an invalid durable cursor");
    }
    if (!finalResetRequired
        && (generation != 0L
            || validationSequence != 0L
            || (target == TradingLabRunState.CANCELLED && rawFailureCode != null)
            || (target == TradingLabRunState.FAILED
                && !GENERIC_VALIDATION_FAILURE.equals(rawFailureCode))
            || (target != TradingLabRunState.CANCELLED
                && target != TradingLabRunState.FAILED))) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup intent cannot skip its final reset");
    }
  }

  private static Map<String, Object> nestedEvidence(
      TradingLabCoordinatorEvidence evidence
  ) {
    Object nested = evidence.payload().get("evidence");
    if (!(nested instanceof Map<?, ?> map)) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup evidence is malformed");
    }
    Map<String, Object> typed = new LinkedHashMap<>();
    map.forEach((key, value) -> {
      if (!(key instanceof String text)) {
        throw failure(
            "TRADING_LAB_EVIDENCE_CORRUPT",
            "Trading Lab cleanup evidence has an invalid key");
      }
      typed.put(text, value);
    });
    return typed;
  }

  private static long exactLong(Object value) {
    try {
      if (value instanceof Integer integer) {
        return integer.longValue();
      }
      if (value instanceof Long number) {
        return number;
      }
      if (value instanceof java.math.BigInteger integer) {
        return integer.longValueExact();
      }
      if (value instanceof java.math.BigDecimal decimal) {
        return decimal.longValueExact();
      }
    } catch (ArithmeticException exception) {
      throw failure(
          "TRADING_LAB_EVIDENCE_CORRUPT",
          "Trading Lab cleanup sequence is outside the supported range");
    }
    throw failure(
        "TRADING_LAB_EVIDENCE_CORRUPT",
        "Trading Lab cleanup sequence is not an exact integer");
  }

  private static boolean exactBoolean(Object value) {
    if (value instanceof Boolean flag) {
      return flag;
    }
    throw failure(
        "TRADING_LAB_EVIDENCE_CORRUPT",
        "Trading Lab cleanup reset flag is invalid");
  }

  private record CleanupIntentSelection(
      TradingLabRunState target,
      Map<String, Object> request
  ) {
  }

  private static TradingLabRunState closedReportTarget(
      TradingLabCoordinatorRunContext context,
      TradingLabRunState target
  ) {
    String status = context.run().reportStatus();
    if ("PENDING".equals(status) || "WRITING".equals(status)) {
      return null;
    }
    if (target.name().equals(status)) {
      if (target == TradingLabRunState.FAILED) {
        throw failure(
            "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
            "Trading Lab failed report has no exact final reset proof");
      }
      return target;
    }
    if ("FAILED".equals(status)) {
      throw failure(
          "TRADING_LAB_VALIDATION_STATE_UNCERTAIN",
          "Trading Lab failed report has no exact final reset proof");
    }
    throw failure(
        "TRADING_LAB_REPORT_TERMINAL_CONFLICT",
        "Trading Lab report closed with a conflicting terminal outcome");
  }

  private static TradingLabRunState terminalTarget(ValidationRunState state) {
    return switch (state) {
      case COMPLETED -> TradingLabRunState.COMPLETED;
      case CANCELLED -> TradingLabRunState.CANCELLED;
      case FAILED -> TradingLabRunState.FAILED;
      default -> throw failure(
          "TRADING_LAB_VALIDATION_NOT_TERMINAL",
          "Validation state is not terminal");
    };
  }

  private static boolean successful(ValidationHttpResult result) {
    return result.exception() == null && result.status() >= 200 && result.status() < 300;
  }

  private static String safeReportFailureCode(String value) {
    return value != null && value.matches("[A-Z0-9_]{1,80}")
        ? value
        : "VALIDATION_RUN_FAILED";
  }

  private static boolean terminal(TradingLabRunState state) {
    return state == TradingLabRunState.COMPLETED
        || state == TradingLabRunState.CANCELLED
        || state == TradingLabRunState.FAILED;
  }

  private static UUID operationId(UUID runId, String stage) {
    return UUID.nameUUIDFromBytes(
        (runId + ":trading-lab:" + stage).getBytes(StandardCharsets.UTF_8));
  }

  private static BusinessException failure(String code, String message) {
    String safeCode = code == null || !code.matches("[A-Z0-9_]{1,128}")
        ? "TRADING_LAB_VALIDATION_FAILED"
        : code;
    return new BusinessException(safeCode, message);
  }
}
