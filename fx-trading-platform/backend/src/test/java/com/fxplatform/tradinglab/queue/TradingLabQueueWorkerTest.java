package com.fxplatform.tradinglab.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.tradinglab.state.TradingLabRunState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradingLabQueueWorkerTest {

  private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String WORKER_INSTANCE_ID = "queue-worker-test";
  private static final String CLAIM_OWNER =
      "queue-worker-test:20000000-0000-0000-0000-000000000002";
  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
  private static final Instant LEASE_UNTIL = Instant.parse("2026-07-19T00:00:30Z");

  @Mock
  private TradingLabLeaseService leaseService;

  @Mock
  private TradingLabRunCoordinator coordinator;

  @Test
  void busyValidLeaseIsANoOp() {
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION))
        .thenReturn(Optional.empty());

    worker().poll();

    verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    verifyNoInteractions(coordinator);
    verifyNoMoreInteractions(leaseService);
  }

  @Test
  void retainedActiveClaimIsRenewedBeforeSecondCoordination() {
    TradingLabRunClaim acquired = claim(TradingLabRunState.RUNNING);
    TradingLabRunClaim renewed = new TradingLabRunClaim(
        RUN_ID,
        TradingLabRunState.RUNNING,
        8L,
        acquired.queueSequence(),
        CLAIM_OWNER,
        LEASE_UNTIL.plus(LEASE_DURATION));
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION))
        .thenReturn(Optional.of(acquired));
    when(leaseService.renew(RUN_ID, CLAIM_OWNER, LEASE_DURATION))
        .thenReturn(Optional.of(renewed));
    when(leaseService.releaseIfTerminal(RUN_ID, CLAIM_OWNER)).thenReturn(false);
    TradingLabQueueWorker worker = worker();

    worker.poll();
    worker.poll();

    InOrder ordered = inOrder(leaseService, coordinator);
    ordered.verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(acquired);
    ordered.verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    ordered.verify(leaseService).renew(RUN_ID, CLAIM_OWNER, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(renewed);
    ordered.verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    verifyNoMoreInteractions(leaseService, coordinator);
  }

  @Test
  void longCoordinationRenewsLeaseBeforeReturningAndFinallyUsesSafeRelease() throws Exception {
    Duration shortLeaseDuration = Duration.ofMillis(120);
    TradingLabRunClaim acquired = claim(TradingLabRunState.RUNNING);
    TradingLabRunClaim renewed = new TradingLabRunClaim(
        RUN_ID,
        TradingLabRunState.RUNNING,
        8L,
        acquired.queueSequence(),
        CLAIM_OWNER,
        LEASE_UNTIL.plus(shortLeaseDuration));
    CountDownLatch coordinationEntered = new CountDownLatch(1);
    CountDownLatch renewalObserved = new CountDownLatch(1);
    CountDownLatch allowCoordinationToReturn = new CountDownLatch(1);
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, shortLeaseDuration))
        .thenReturn(Optional.of(acquired));
    when(leaseService.renew(RUN_ID, CLAIM_OWNER, shortLeaseDuration))
        .thenAnswer(invocation -> {
          renewalObserved.countDown();
          return Optional.of(renewed);
        });
    when(leaseService.releaseIfTerminal(RUN_ID, CLAIM_OWNER)).thenReturn(true);
    doAnswer(invocation -> {
      coordinationEntered.countDown();
      assertThat(allowCoordinationToReturn.await(5, TimeUnit.SECONDS)).isTrue();
      return null;
    }).when(coordinator).coordinate(acquired);
    TradingLabQueueWorker worker = new TradingLabQueueWorker(
        leaseService,
        coordinator,
        WORKER_INSTANCE_ID,
        shortLeaseDuration);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> polling = executor.submit(worker::poll);

    try {
      assertThat(coordinationEntered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(renewalObserved.await(2, TimeUnit.SECONDS)).isTrue();
      verify(leaseService, atLeastOnce()).renew(RUN_ID, CLAIM_OWNER, shortLeaseDuration);
      verify(leaseService, never()).releaseIfTerminal(RUN_ID, CLAIM_OWNER);

      allowCoordinationToReturn.countDown();
      polling.get(2, TimeUnit.SECONDS);

      verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    } finally {
      allowCoordinationToReturn.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void lostRetainedClaimIsDiscardedBeforeTryingFreshAcquire() {
    String freshOwner = "queue-worker-test:30000000-0000-0000-0000-000000000003";
    TradingLabRunClaim retained = claim(TradingLabRunState.RUNNING);
    TradingLabRunClaim fresh = new TradingLabRunClaim(
        UUID.fromString("40000000-0000-0000-0000-000000000004"),
        TradingLabRunState.QUEUED,
        1L,
        12L,
        freshOwner,
        LEASE_UNTIL.plus(LEASE_DURATION));
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION))
        .thenReturn(Optional.of(retained), Optional.of(fresh));
    when(leaseService.renew(RUN_ID, CLAIM_OWNER, LEASE_DURATION))
        .thenReturn(Optional.empty());
    when(leaseService.releaseIfTerminal(RUN_ID, CLAIM_OWNER)).thenReturn(false);
    when(leaseService.releaseIfTerminal(fresh.runId(), freshOwner)).thenReturn(false);
    TradingLabQueueWorker worker = worker();

    worker.poll();
    worker.poll();

    InOrder ordered = inOrder(leaseService, coordinator);
    ordered.verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(retained);
    ordered.verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    ordered.verify(leaseService).renew(RUN_ID, CLAIM_OWNER, LEASE_DURATION);
    ordered.verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(fresh);
    ordered.verify(leaseService).releaseIfTerminal(fresh.runId(), freshOwner);
    verifyNoMoreInteractions(leaseService, coordinator);
  }

  @Test
  void claimedWorkIsDelegatedOnceAndFinallyUsesOnlyTheSafeFencedRelease() {
    TradingLabRunClaim claim = claim(TradingLabRunState.CLEANING);
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION))
        .thenReturn(Optional.of(claim));
    when(leaseService.releaseIfTerminal(RUN_ID, CLAIM_OWNER)).thenReturn(true);

    worker().poll();

    InOrder ordered = inOrder(leaseService, coordinator);
    ordered.verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(claim);
    ordered.verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    verifyNoMoreInteractions(leaseService, coordinator);
  }

  @Test
  void coordinatorFailureLeavesActiveWorkRecoverableWhenSafeReleaseRefusesIt() {
    TradingLabRunClaim claim = claim(TradingLabRunState.RUNNING);
    IllegalStateException failure = new IllegalStateException("coordinator fixture failure");
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION))
        .thenReturn(Optional.of(claim));
    doThrow(failure).when(coordinator).coordinate(claim);
    when(leaseService.releaseIfTerminal(RUN_ID, CLAIM_OWNER)).thenReturn(false);

    Throwable propagated = catchThrowable(worker()::poll);

    assertThat(propagated).isIn(null, failure);

    InOrder ordered = inOrder(leaseService, coordinator);
    ordered.verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(claim);
    ordered.verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    verifyNoMoreInteractions(leaseService, coordinator);
  }

  @ParameterizedTest
  @EnumSource(
      value = TradingLabRunState.class,
      names = {"QUEUED", "RESETTING", "RUNNING", "PAUSED", "CANCELLING", "CLEANING"})
  void finallyDoesNotFallbackWhenSafeReleaseRefusesNonterminalWork(
      TradingLabRunState nonterminalState) {
    TradingLabRunClaim claim = claim(nonterminalState);
    when(leaseService.acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION))
        .thenReturn(Optional.of(claim));
    when(leaseService.releaseIfTerminal(RUN_ID, CLAIM_OWNER)).thenReturn(false);

    worker().poll();

    InOrder ordered = inOrder(leaseService, coordinator);
    ordered.verify(leaseService).acquireNext(WORKER_INSTANCE_ID, LEASE_DURATION);
    ordered.verify(coordinator).coordinate(claim);
    ordered.verify(leaseService).releaseIfTerminal(RUN_ID, CLAIM_OWNER);
    verifyNoMoreInteractions(leaseService, coordinator);
  }

  @Test
  void workerAndCoordinatorExposeOnlyTheFrozenNarrowContract() {
    assertThat(TradingLabRunCoordinator.class).isInterface();
    Method[] coordinatorMethods = TradingLabRunCoordinator.class.getDeclaredMethods();
    assertThat(coordinatorMethods).hasSize(1);
    assertThat(coordinatorMethods[0].getName()).isEqualTo("coordinate");
    assertThat(coordinatorMethods[0].getReturnType()).isEqualTo(void.class);
    assertThat(coordinatorMethods[0].getParameterTypes())
        .containsExactly(TradingLabRunClaim.class);

    Constructor<?>[] workerConstructors = TradingLabQueueWorker.class.getDeclaredConstructors();
    assertThat(workerConstructors).hasSize(1);
    assertThat(workerConstructors[0].getParameterTypes()).containsExactly(
        TradingLabLeaseService.class,
        TradingLabRunCoordinator.class,
        String.class,
        Duration.class);
  }

  private TradingLabQueueWorker worker() {
    return new TradingLabQueueWorker(
        leaseService,
        coordinator,
        WORKER_INSTANCE_ID,
        LEASE_DURATION);
  }

  private TradingLabRunClaim claim(TradingLabRunState state) {
    return new TradingLabRunClaim(
        RUN_ID,
        state,
        7L,
        11L,
        CLAIM_OWNER,
        LEASE_UNTIL);
  }
}
