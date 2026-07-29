package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationRunEngine.Accepted;
import com.fxplatform.validation.service.ValidationRunEngine.StartDecision;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Async lifecycle shell around the synchronous four-port validation run machine. */
@Profile("validation")
@Service
public class ValidationRunOrchestrator {

  private final ValidationRunEngine engine;
  private final ValidationRunPacer pacer;
  private final Duration recoveryScanInterval;
  private final AtomicBoolean recoveryLoopStarted = new AtomicBoolean();
  private long recoveryLoopGeneration = Long.MIN_VALUE;

  @Autowired
  public ValidationRunOrchestrator(
      ValidationRunEngine engine,
      ValidationRunPacer pacer
  ) {
    this(engine, pacer, Duration.ofSeconds(1));
  }

  ValidationRunOrchestrator(
      ValidationRunEngine engine,
      ValidationRunPacer pacer,
      Duration recoveryScanInterval
  ) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.pacer = Objects.requireNonNull(pacer, "pacer");
    this.recoveryScanInterval = Objects.requireNonNull(
        recoveryScanInterval, "recoveryScanInterval");
    if (recoveryScanInterval.isZero() || recoveryScanInterval.isNegative()) {
      throw new IllegalArgumentException("Recovery scan interval must be positive");
    }
  }

  public Accepted start(StartRequest request) {
    StartDecision decision = engine.start(request);
    if (decision.launchRequired()) {
      launch(request.runId(), false);
    }
    return decision.accepted();
  }

  public void recoverAcceptedRuns() {
    engine.prepareRecovery().forEach(runId -> launch(runId, true));
  }

  /** Starts a validation-profile-only tracked loop; reset interrupts and joins it through pacer. */
  public synchronized void startRecoveryLoop() {
    long pacerGeneration = pacer.generation();
    if (recoveryLoopStarted.get() && recoveryLoopGeneration == pacerGeneration) {
      return;
    }
    pacer.launch(() -> beginRecoveryLoop(pacerGeneration));
  }

  public State pause(UUID runId) {
    return engine.pause(runId);
  }

  public State resume(UUID runId) {
    engine.prepareResume(runId);
    launch(runId, true);
    return State.ACCEPTED;
  }

  public State cancel(UUID runId) {
    return engine.cancel(runId);
  }

  private void launch(UUID runId, boolean recovering) {
    pacer.launch(() -> engine.execute(runId, recovering));
  }

  private void beginRecoveryLoop(long pacerGeneration) {
    synchronized (this) {
      if (recoveryLoopStarted.get() && recoveryLoopGeneration == pacerGeneration) {
        return;
      }
      recoveryLoopStarted.set(true);
      recoveryLoopGeneration = pacerGeneration;
    }
    runRecoveryLoop(pacerGeneration);
  }

  private void runRecoveryLoop(long pacerGeneration) {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          recoverAcceptedRuns();
        } catch (RuntimeException ignored) {
          if (Thread.currentThread().isInterrupted()) {
            return;
          }
          // A reset or transient durable-store outage is retried by the next bounded scan.
        }
        try {
          TimeUnit.NANOSECONDS.sleep(recoveryScanInterval.toNanos());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    } finally {
      synchronized (this) {
        if (recoveryLoopGeneration == pacerGeneration) {
          recoveryLoopStarted.set(false);
        }
      }
    }
  }
}
