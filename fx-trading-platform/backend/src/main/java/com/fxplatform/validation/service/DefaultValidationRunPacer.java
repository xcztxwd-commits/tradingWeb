package com.fxplatform.validation.service;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Single-process validation task registry and speed pacer with a reset join barrier. */
@Profile("validation")
@Component
public class DefaultValidationRunPacer implements ValidationRunPacer {

  private static final long QUIESCE_TIMEOUT_SECONDS = 15L;
  private static final long MINIMUM_PACE_NANOS = 1_000_000L;

  private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("validation-run-", 0).factory());
  private final Set<TrackedTask> tasks = new HashSet<>();

  private boolean accepting;
  private long generation = -1L;

  @Override
  public void launch(Runnable command) {
    TrackedTask task = new TrackedTask(command);
    synchronized (this) {
      if (!accepting || generation <= 0L) {
        throw new IllegalStateException("Validation runtime is not ready to launch work");
      }
      tasks.add(task);
    }
    executor.execute(task);
  }

  @Override
  public void awaitNextTick(BigDecimal speedMultiplier) {
    if (speedMultiplier == null || speedMultiplier.signum() <= 0) {
      throw new IllegalArgumentException("speedMultiplier must be positive");
    }
    long nanos = BigDecimal.valueOf(1_000_000_000L)
        .divide(speedMultiplier, 0, RoundingMode.CEILING)
        .min(BigDecimal.valueOf(Long.MAX_VALUE))
        .longValueExact();
    nanos = Math.max(MINIMUM_PACE_NANOS, nanos);
    try {
      TimeUnit.NANOSECONDS.sleep(nanos);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Validation run was interrupted by reset", exception);
    }
  }

  public void quiesceAndJoin() {
    ArrayList<TrackedTask> active;
    synchronized (this) {
      accepting = false;
      active = new ArrayList<>(tasks);
      active.forEach(task -> task.cancel(true));
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUIESCE_TIMEOUT_SECONDS);
    for (TrackedTask task : active) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        throw new IllegalStateException("Validation run tasks did not stop before reset");
      }
      try {
        if (!task.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
          throw new IllegalStateException("Validation run tasks did not stop before reset");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Validation reset was interrupted");
      }
    }
    synchronized (this) {
      if (!tasks.isEmpty()) {
        throw new IllegalStateException("Validation run tasks did not stop before reset");
      }
    }
  }

  public synchronized void reset(long resetGeneration) {
    if (resetGeneration <= 0L || !tasks.isEmpty()) {
      throw new IllegalStateException("Validation runtime cannot publish the reset generation");
    }
    generation = resetGeneration;
    accepting = true;
  }

  @Override
  public synchronized long generation() {
    return generation;
  }

  @PreDestroy
  void close() {
    synchronized (this) {
      accepting = false;
      tasks.forEach(task -> task.cancel(true));
    }
    executor.close();
  }

  private final class TrackedTask extends FutureTask<Void> {

    private final CountDownLatch terminated = new CountDownLatch(1);

    private TrackedTask(Runnable command) {
      super(command, null);
    }

    @Override
    public void run() {
      try {
        super.run();
      } finally {
        synchronized (DefaultValidationRunPacer.this) {
          tasks.remove(this);
        }
        terminated.countDown();
      }
    }

    private boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return terminated.await(timeout, unit);
    }
  }
}
