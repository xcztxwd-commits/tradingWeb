package com.fxplatform.validation.service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Tracks validation-engine loopback handlers and supplies a reset-time server-side drain barrier. */
@Profile("validation")
@Component
public class ValidationLoopbackRequestActivityBarrier {

  private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(15);

  private final long drainTimeoutNanos;
  private final ValidationDatabaseFence durableFence;
  private final ValidationResetGate resetGate;
  private boolean accepting = true;
  private int activeRequests;

  public ValidationLoopbackRequestActivityBarrier() {
    this(DEFAULT_DRAIN_TIMEOUT, null, null);
  }

  ValidationLoopbackRequestActivityBarrier(Duration drainTimeout) {
    this(drainTimeout, null, null);
  }

  @Autowired
  public ValidationLoopbackRequestActivityBarrier(
      ValidationDatabaseFence durableFence,
      ValidationResetGate resetGate
  ) {
    this(DEFAULT_DRAIN_TIMEOUT, durableFence, resetGate);
  }

  private ValidationLoopbackRequestActivityBarrier(
      Duration drainTimeout,
      ValidationDatabaseFence durableFence,
      ValidationResetGate resetGate
  ) {
    if (drainTimeout == null || drainTimeout.isZero() || drainTimeout.isNegative()) {
      throw new IllegalArgumentException("Validation loopback drain timeout must be positive");
    }
    this.drainTimeoutNanos = drainTimeout.toNanos();
    this.durableFence = durableFence;
    this.resetGate = resetGate;
  }

  public Optional<Activity> tryEnter() {
    return tryEnter(null);
  }

  /** Enters only when the request's immutable generation is still the active generation. */
  public Optional<Activity> tryEnter(long requiredGeneration) {
    if (requiredGeneration <= 0L) {
      return Optional.empty();
    }
    return tryEnter(Long.valueOf(requiredGeneration));
  }

  private Optional<Activity> tryEnter(Long declaredGeneration) {
    long requiredGeneration = 0L;
    synchronized (this) {
      if (!accepting) {
        return Optional.empty();
      }
      if (durableFence != null) {
        long localGeneration;
        try {
          localGeneration = resetGate.readyGeneration();
        } catch (RuntimeException failure) {
          return Optional.empty();
        }
        if (declaredGeneration != null && declaredGeneration.longValue() != localGeneration) {
          return Optional.empty();
        }
        requiredGeneration = declaredGeneration == null
            ? localGeneration
            : declaredGeneration.longValue();
      }
    }

    ValidationDatabaseFence.RequestFence requestFence = null;
    if (durableFence != null) {
      Optional<ValidationDatabaseFence.RequestFence> entered =
          durableFence.tryEnterRequest(requiredGeneration);
      if (entered.isEmpty()) {
        return Optional.empty();
      }
      requestFence = entered.orElseThrow();
    }

    boolean admitted;
    synchronized (this) {
      admitted = accepting;
      if (admitted && durableFence != null) {
        try {
          admitted = resetGate.readyGeneration() == requiredGeneration;
        } catch (RuntimeException failure) {
          admitted = false;
        }
      }
      if (admitted) {
        try {
          activeRequests = Math.addExact(activeRequests, 1);
        } catch (ArithmeticException exhausted) {
          admitted = false;
        }
      }
    }
    if (!admitted) {
      if (requestFence != null) {
        requestFence.close();
      }
      return Optional.empty();
    }
    return Optional.of(new Activity(this, requestFence));
  }

  public synchronized void quiesceAndAwait() {
    accepting = false;
    long deadline = System.nanoTime() + drainTimeoutNanos;
    while (activeRequests > 0) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        throw new IllegalStateException(
            "Validation loopback requests did not become idle before reset");
      }
      try {
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Validation loopback request drain was interrupted",
            exception);
      }
    }
  }

  public synchronized void reopen() {
    if (activeRequests != 0) {
      throw new IllegalStateException("Validation loopback requests are still active");
    }
    accepting = true;
  }

  synchronized int activeRequests() {
    return activeRequests;
  }

  private synchronized void leave() {
    if (activeRequests <= 0) {
      throw new IllegalStateException("Validation loopback activity underflow");
    }
    activeRequests--;
    if (activeRequests == 0) {
      notifyAll();
    }
  }

  public static final class Activity implements AutoCloseable {

    private final ValidationLoopbackRequestActivityBarrier barrier;
    private final ValidationDatabaseFence.RequestFence requestFence;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Activity(
        ValidationLoopbackRequestActivityBarrier barrier,
        ValidationDatabaseFence.RequestFence requestFence
    ) {
      this.barrier = barrier;
      this.requestFence = requestFence;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          if (requestFence != null) {
            requestFence.close();
          }
        } finally {
          barrier.leave();
        }
      }
    }
  }
}
