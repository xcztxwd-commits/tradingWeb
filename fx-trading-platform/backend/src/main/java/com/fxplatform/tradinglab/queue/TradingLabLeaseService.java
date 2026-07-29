package com.fxplatform.tradinglab.queue;

import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TradingLabLeaseService {

  private static final int MAX_OWNER_LENGTH = 160;
  private static final Set<TradingLabRunState> TERMINAL_STATES = EnumSet.of(
      TradingLabRunState.COMPLETED,
      TradingLabRunState.CANCELLED,
      TradingLabRunState.FAILED);

  private final TradingLabQueueRepository repository;

  public TradingLabLeaseService(TradingLabQueueRepository repository) {
    this.repository = repository;
  }

  public Optional<TradingLabRunClaim> acquireNext(
      String workerInstanceId,
      Duration leaseDuration) {
    long leaseMillis = requireLeaseMillis(leaseDuration);
    String newClaimOwner = newClaimOwner(workerInstanceId);
    if (!repository.tryAcquireSingletonTransactionLock()) {
      return Optional.empty();
    }

    Optional<TradingLabRunEntity> singleton = repository.findSingletonLeaseForUpdate();
    if (singleton.isEmpty() && repository.countSingletonLeaseRows() != 0) {
      return Optional.empty();
    }
    if (singleton.isPresent()) {
      TradingLabRunEntity held = singleton.orElseThrow();
      long expectedVersion = requireVersion(held);
      if (TERMINAL_STATES.contains(stateOf(held))) {
        if (repository.clearTerminalLeaseForAcquisition(
            held.getId(), held.getLeaseOwner(), expectedVersion) != 1) {
          return Optional.empty();
        }
      } else {
        if (repository.expiredFenceCount(
            held.getId(),
            held.getLeaseOwner(),
            expectedVersion) != 1) {
          return Optional.empty();
        }
        if (repository.reclaimExpired(
            held.getId(),
            held.getLeaseOwner(),
            newClaimOwner,
            expectedVersion,
            leaseMillis) != 1) {
          return Optional.empty();
        }
        TradingLabRunEntity reclaimed = repository.findClaimRow(held.getId()).orElseThrow();
        if (!TERMINAL_STATES.contains(stateOf(reclaimed))) {
          return Optional.of(toClaim(reclaimed));
        }
        if (repository.releaseTerminalFenced(
            reclaimed.getId(),
            newClaimOwner,
            requireVersion(reclaimed)) != 1) {
          return Optional.empty();
        }
      }
    }

    Optional<TradingLabRunEntity> recoverable =
        repository.findRecoverableUnleasedForUpdate();
    if (recoverable.isPresent()) {
      return claimUnleased(recoverable.orElseThrow(), newClaimOwner, leaseMillis);
    }
    if (repository.countRecoverableUnleasedRows() != 0) {
      return Optional.empty();
    }
    return repository.findOldestQueuedForUpdate()
        .flatMap(run -> claimUnleased(run, newClaimOwner, leaseMillis));
  }

  public Optional<TradingLabRunClaim> renew(
      UUID runId,
      String claimOwner,
      Duration leaseDuration) {
    requireRunId(runId);
    requireOwner(claimOwner);
    long leaseMillis = requireLeaseMillis(leaseDuration);
    Optional<TradingLabRunEntity> locked = repository.findByIdForUpdate(runId);
    if (locked.isEmpty()) {
      return Optional.empty();
    }
    long expectedVersion = requireVersion(locked.orElseThrow());
    if (repository.renewFenced(runId, claimOwner, expectedVersion, leaseMillis) != 1) {
      return Optional.empty();
    }
    return repository.findClaimRow(runId).map(TradingLabLeaseService::toClaim);
  }

  public boolean releaseIfTerminal(UUID runId, String claimOwner) {
    requireRunId(runId);
    requireOwner(claimOwner);
    Optional<TradingLabRunEntity> locked = repository.findByIdForUpdate(runId);
    if (locked.isEmpty()) {
      return false;
    }
    TradingLabRunEntity run = locked.orElseThrow();
    if (!TERMINAL_STATES.contains(stateOf(run))) {
      return false;
    }
    if (run.getLeaseKey() == null
        && run.getLeaseOwner() == null
        && run.getLeaseUntil() == null) {
      return true;
    }
    return repository.releaseTerminalFenced(
        runId,
        claimOwner,
        requireVersion(run)) == 1;
  }

  private Optional<TradingLabRunClaim> claimUnleased(
      TradingLabRunEntity run,
      String claimOwner,
      long leaseMillis) {
    long expectedVersion = requireVersion(run);
    if (repository.claimUnleased(
        run.getId(),
        stateOf(run).name(),
        expectedVersion,
        claimOwner,
        leaseMillis) != 1) {
      return Optional.empty();
    }
    return repository.findClaimRow(run.getId()).map(TradingLabLeaseService::toClaim);
  }

  private static TradingLabRunClaim toClaim(TradingLabRunEntity run) {
    if (run.getId() == null
        || run.getQueueSequence() == null
        || run.getLeaseOwner() == null
        || run.getLeaseUntil() == null) {
      throw new IllegalStateException("Trading Lab claim row is incomplete");
    }
    return new TradingLabRunClaim(
        run.getId(),
        stateOf(run),
        requireVersion(run),
        run.getQueueSequence(),
        run.getLeaseOwner(),
        run.getLeaseUntil());
  }

  private static TradingLabRunState stateOf(TradingLabRunEntity run) {
    try {
      return TradingLabRunState.valueOf(run.getState());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Trading Lab run has an invalid persisted state", exception);
    }
  }

  private static long requireVersion(TradingLabRunEntity run) {
    if (run.getVersion() == null || run.getVersion() < 0) {
      throw new IllegalStateException("Trading Lab run has no authoritative version");
    }
    return run.getVersion();
  }

  private static long requireLeaseMillis(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    long millis = duration.toMillis();
    if (millis <= 0) {
      throw new IllegalArgumentException("leaseDuration must be at least one millisecond");
    }
    return millis;
  }

  private static String newClaimOwner(String workerInstanceId) {
    if (workerInstanceId == null || workerInstanceId.isBlank()) {
      throw new IllegalArgumentException("workerInstanceId is required");
    }
    String owner = workerInstanceId + ":" + UUID.randomUUID();
    requireOwner(owner);
    return owner;
  }

  private static void requireOwner(String claimOwner) {
    if (claimOwner == null
        || claimOwner.isBlank()
        || claimOwner.length() > MAX_OWNER_LENGTH) {
      throw new IllegalArgumentException("claimOwner must contain 1 to 160 characters");
    }
  }

  private static void requireRunId(UUID runId) {
    if (runId == null) {
      throw new IllegalArgumentException("runId is required");
    }
  }
}
