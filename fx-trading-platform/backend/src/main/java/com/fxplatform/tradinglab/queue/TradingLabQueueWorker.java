package com.fxplatform.tradinglab.queue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "trading-lab.queue",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class TradingLabQueueWorker {

  private static final Logger log = LoggerFactory.getLogger(TradingLabQueueWorker.class);

  private final TradingLabLeaseService leaseService;
  private final TradingLabRunCoordinator coordinator;
  private final String workerInstanceId;
  private final Duration leaseDuration;

  private volatile TradingLabRunClaim activeClaim;

  public TradingLabQueueWorker(
      TradingLabLeaseService leaseService,
      TradingLabRunCoordinator coordinator,
      @Value("${trading-lab.queue.worker-instance-id:fx-platform-backend}")
      String workerInstanceId,
      @Value("#{T(java.time.Duration).parse('${trading-lab.queue.lease-duration:PT30S}')}")
      Duration leaseDuration) {
    this.leaseService = leaseService;
    this.coordinator = coordinator;
    this.workerInstanceId = workerInstanceId;
    this.leaseDuration = leaseDuration;
  }

  @Scheduled(fixedDelayString = "${trading-lab.queue.poll-delay:PT1S}")
  public synchronized void poll() {
    Optional<TradingLabRunClaim> claim = currentOrAcquire();
    if (claim.isEmpty()) {
      return;
    }

    TradingLabRunClaim fenced = claim.orElseThrow();
    activeClaim = fenced;
    try (LeaseHeartbeat heartbeat = new LeaseHeartbeat(fenced)) {
      coordinator.coordinate(fenced);
    } finally {
      if (leaseService.releaseIfTerminal(fenced.runId(), fenced.claimOwner())) {
        activeClaim = null;
      }
    }
  }

  private Optional<TradingLabRunClaim> currentOrAcquire() {
    if (activeClaim == null) {
      return leaseService.acquireNext(workerInstanceId, leaseDuration);
    }
    Optional<TradingLabRunClaim> renewed = leaseService.renew(
        activeClaim.runId(),
        activeClaim.claimOwner(),
        leaseDuration);
    if (renewed.isPresent()) {
      return renewed;
    }
    activeClaim = null;
    return leaseService.acquireNext(workerInstanceId, leaseDuration);
  }

  private final class LeaseHeartbeat implements AutoCloseable {

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> renewal;
    private final UUID runId;
    private final String claimOwner;

    private LeaseHeartbeat(TradingLabRunClaim claim) {
      runId = claim.runId();
      claimOwner = claim.claimOwner();
      executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "trading-lab-lease-heartbeat-" + runId);
        thread.setDaemon(true);
        return thread;
      });
      long intervalMillis = Math.max(1L, leaseDuration.toMillis() / 3L);
      renewal = executor.scheduleWithFixedDelay(
          this::renew,
          intervalMillis,
          intervalMillis,
          TimeUnit.MILLISECONDS);
    }

    private void renew() {
      if (stopped.get()) {
        return;
      }
      try {
        Optional<TradingLabRunClaim> renewed = leaseService.renew(
            runId,
            claimOwner,
            leaseDuration);
        if (renewed.isPresent()) {
          activeClaim = renewed.orElseThrow();
          return;
        }
        activeClaim = null;
        stopped.set(true);
      } catch (RuntimeException exception) {
        activeClaim = null;
        stopped.set(true);
        log.warn("Trading Lab lease heartbeat failed for run {}", runId, exception);
      }
    }

    @Override
    public void close() {
      stopped.set(true);
      renewal.cancel(false);
      executor.shutdown();
    }
  }
}
