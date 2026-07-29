package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationAdministrativeDatabase.ResetControlState;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Adopts a newer durable validation generation on passive backend instances.
 *
 * <p>The monitor deliberately lives outside {@link ValidationRunPacer}: adoption must be able to
 * cancel and join every old-generation run task without cancelling or joining itself.
 */
@Profile("validation")
@Component
public class ValidationRuntimeGenerationSynchronizer implements AutoCloseable {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1L);

  private final ValidationAdministrativeDatabase administrativeDatabase;
  private final ValidationResetService resetService;
  private final ValidationRedisResetter redisResetter;
  private final ValidationDatabaseFence databaseFence;
  private final ValidationResetGate resetGate;
  private final ValidationMarketState marketState;
  private final ValidationMarketClock marketClock;
  private final ValidationDemoExecutionPolicyProvider executionPolicyProvider;
  private final DefaultValidationRunPacer runPacer;
  private final ValidationLoopbackRequestActivityBarrier loopbackRequestBarrier;
  private final ValidationRunOrchestrator runOrchestrator;
  private final List<ValidationRuntimeResetParticipant> resetParticipants;
  private final Duration pollInterval;
  private final ExecutorService monitorExecutor;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  @Autowired
  public ValidationRuntimeGenerationSynchronizer(
      ValidationAdministrativeDatabase administrativeDatabase,
      ValidationResetService resetService,
      ValidationRedisResetter redisResetter,
      ValidationDatabaseFence databaseFence,
      ValidationResetGate resetGate,
      ValidationMarketState marketState,
      ValidationMarketClock marketClock,
      ValidationDemoExecutionPolicyProvider executionPolicyProvider,
      DefaultValidationRunPacer runPacer,
      ValidationLoopbackRequestActivityBarrier loopbackRequestBarrier,
      ValidationRunOrchestrator runOrchestrator,
      List<ValidationRuntimeResetParticipant> resetParticipants
  ) {
    this(
        administrativeDatabase,
        resetService,
        redisResetter,
        databaseFence,
        resetGate,
        marketState,
        marketClock,
        executionPolicyProvider,
        runPacer,
        loopbackRequestBarrier,
        runOrchestrator,
        resetParticipants,
        DEFAULT_POLL_INTERVAL);
  }

  ValidationRuntimeGenerationSynchronizer(
      ValidationAdministrativeDatabase administrativeDatabase,
      ValidationResetService resetService,
      ValidationRedisResetter redisResetter,
      ValidationDatabaseFence databaseFence,
      ValidationResetGate resetGate,
      ValidationMarketState marketState,
      ValidationMarketClock marketClock,
      ValidationDemoExecutionPolicyProvider executionPolicyProvider,
      DefaultValidationRunPacer runPacer,
      ValidationLoopbackRequestActivityBarrier loopbackRequestBarrier,
      ValidationRunOrchestrator runOrchestrator,
      List<ValidationRuntimeResetParticipant> resetParticipants,
      Duration pollInterval
  ) {
    this.administrativeDatabase = Objects.requireNonNull(
        administrativeDatabase, "administrativeDatabase");
    this.resetService = Objects.requireNonNull(resetService, "resetService");
    this.redisResetter = Objects.requireNonNull(redisResetter, "redisResetter");
    this.databaseFence = Objects.requireNonNull(databaseFence, "databaseFence");
    this.resetGate = Objects.requireNonNull(resetGate, "resetGate");
    this.marketState = Objects.requireNonNull(marketState, "marketState");
    this.marketClock = Objects.requireNonNull(marketClock, "marketClock");
    this.executionPolicyProvider = Objects.requireNonNull(
        executionPolicyProvider, "executionPolicyProvider");
    this.runPacer = Objects.requireNonNull(runPacer, "runPacer");
    this.loopbackRequestBarrier = Objects.requireNonNull(
        loopbackRequestBarrier, "loopbackRequestBarrier");
    this.runOrchestrator = Objects.requireNonNull(runOrchestrator, "runOrchestrator");
    this.resetParticipants = List.copyOf(resetParticipants);
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    if (pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException("Validation generation poll interval must be positive");
    }
    this.monitorExecutor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("validation-generation-sync-", 0).factory());
  }

  public void start() {
    if (closed.get()) {
      throw new IllegalStateException("Validation generation synchronizer is closed");
    }
    if (started.compareAndSet(false, true)) {
      monitorExecutor.execute(this::monitor);
    }
  }

  boolean synchronizeOnce() {
    ResetControlState candidate;
    try {
      candidate = administrativeDatabase.currentResetState();
    } catch (RuntimeException unavailable) {
      return false;
    }
    if ("RESETTING".equals(candidate.state())) {
      return recoverInterruptedReset();
    }
    if (!completeReady(candidate)) {
      return false;
    }

    long targetGeneration = candidate.generation();
    ValidationResetGate.State localState = resetGate.state();
    long localGeneration = resetGate.generation();
    if (localState == ValidationResetGate.State.RESETTING
        || localState == ValidationResetGate.State.ADOPTING) {
      return false;
    }
    if (targetGeneration < localGeneration) {
      return false;
    }
    if (targetGeneration == localGeneration) {
      if (localState == ValidationResetGate.State.READY
          && !redisGenerationMatches(targetGeneration)) {
        invalidateReadyGeneration(targetGeneration);
      }
      return false;
    }

    Optional<ValidationDatabaseFence.RequestFence> entered =
        databaseFence.tryEnterRequest(targetGeneration);
    if (entered.isEmpty()) {
      return false;
    }
    try (ValidationDatabaseFence.RequestFence ignored = entered.orElseThrow()) {
      ResetControlState retained = administrativeDatabase.currentResetState();
      if (!completeReady(retained)
          || retained.generation() != targetGeneration
          || redisResetter.currentGeneration() != targetGeneration
          || !resetGate.tryBeginAdoption(targetGeneration)) {
        return false;
      }
      return adopt(targetGeneration);
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private boolean recoverInterruptedReset() {
    ValidationResetGate.State localState = resetGate.state();
    if (localState == ValidationResetGate.State.RESETTING
        || localState == ValidationResetGate.State.ADOPTING) {
      return false;
    }
    try {
      return resetService.recoverInterruptedReset()
          .filter(receipt -> receipt.status() == ValidationResetReceipt.Status.SUCCEEDED)
          .isPresent();
    } catch (RuntimeException unavailableOrInconsistent) {
      return false;
    }
  }

  private boolean adopt(long targetGeneration) {
    try {
      runPacer.quiesceAndJoin();
      loopbackRequestBarrier.quiesceAndAwait();
      if (redisResetter.currentGeneration() != targetGeneration) {
        throw new IllegalStateException("Validation Redis generation changed during adoption");
      }
      resetParticipants.forEach(
          participant -> participant.clearForGeneration(targetGeneration));
      marketState.reset(targetGeneration);
      marketClock.reset(targetGeneration);
      executionPolicyProvider.reset(targetGeneration);
      runPacer.reset(targetGeneration);
      loopbackRequestBarrier.reopen();
      runOrchestrator.startRecoveryLoop();
      resetGate.completeAdoption(targetGeneration);
      return true;
    } catch (RuntimeException failure) {
      failClosedAdoption();
      return false;
    }
  }

  private void failClosedAdoption() {
    try {
      runPacer.quiesceAndJoin();
    } catch (RuntimeException ignored) {
      // The gate remains closed even if a non-cooperative local task cannot be joined.
    }
    try {
      loopbackRequestBarrier.quiesceAndAwait();
    } catch (RuntimeException ignored) {
      // The gate remains closed and the barrier remains non-accepting.
    }
    resetGate.failAdoption();
  }

  private boolean redisGenerationMatches(long expectedGeneration) {
    try {
      return redisResetter.currentGeneration() == expectedGeneration;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void invalidateReadyGeneration(long failedGeneration) {
    if (!resetGate.tryBeginDurabilityFailure(failedGeneration)) {
      return;
    }
    try {
      try {
        runPacer.quiesceAndJoin();
      } catch (RuntimeException ignored) {
        // The gate is already closed even if a non-cooperative task cannot be joined.
      }
      try {
        loopbackRequestBarrier.quiesceAndAwait();
      } catch (RuntimeException ignored) {
        // The gate remains closed and the barrier remains non-accepting.
      }
    } finally {
      resetGate.completeDurabilityFailure(failedGeneration);
    }
  }

  private void monitor() {
    while (!closed.get() && !Thread.currentThread().isInterrupted()) {
      synchronizeOnce();
      try {
        TimeUnit.NANOSECONDS.sleep(pollInterval.toNanos());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static boolean completeReady(ResetControlState state) {
    return state != null
        && "READY".equals(state.state())
        && state.generation() > 0L
        && Long.valueOf(state.generation()).equals(state.redisGeneration())
        && Long.valueOf(state.generation()).equals(state.memoryGeneration());
  }

  @Override
  @PreDestroy
  public void close() {
    if (closed.compareAndSet(false, true)) {
      monitorExecutor.shutdownNow();
      try {
        monitorExecutor.awaitTermination(5L, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
