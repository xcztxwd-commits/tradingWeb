package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ValidationRuntimeGenerationSynchronizerTest {

  private static final long CURRENT = 66L;
  private static final long NEXT = 67L;

  @Test
  void passiveInstanceAdoptsOnlyANewerCompleteDurableGeneration() {
    try (Fixture fixture = new Fixture(NEXT)) {
      assertThat(fixture.synchronizer.synchronizeOnce()).isTrue();

      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.READY);
      assertThat(fixture.gate.generation()).isEqualTo(NEXT);
      assertThat(fixture.marketState.generation()).isEqualTo(NEXT);
      assertThat(fixture.marketClock.generation()).isEqualTo(NEXT);
      assertThat(fixture.policies.generation()).isEqualTo(NEXT);
      assertThat(fixture.pacer.generation()).isEqualTo(NEXT);
      verify(fixture.participant).clearForGeneration(NEXT);
      verify(fixture.orchestrator).startRecoveryLoop();
      try (ValidationLoopbackRequestActivityBarrier.Activity ignored =
               fixture.barrier.tryEnter().orElseThrow()) {
        assertThat(fixture.barrier.activeRequests()).isEqualTo(1);
      }
    }
  }

  @Test
  void localResettingIsNeverOverwrittenByPassiveAdoption() {
    try (Fixture fixture = new Fixture(NEXT)) {
      assertThat(fixture.gate.tryBeginReset()).isTrue();

      assertThat(fixture.synchronizer.synchronizeOnce()).isFalse();

      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.RESETTING);
      assertThat(fixture.gate.generation()).isEqualTo(CURRENT);
      verify(fixture.participant, never()).clearForGeneration(NEXT);
      verify(fixture.orchestrator, never()).startRecoveryLoop();
    }
  }

  @Test
  void interruptedDurableResetIsRecoveredBeforeReadyGenerationAdoption() {
    try (Fixture fixture = new Fixture(73L, false)) {
      ValidationResetReceipt receipt = mock(ValidationResetReceipt.class);
      when(receipt.status()).thenReturn(ValidationResetReceipt.Status.SUCCEEDED);
      when(fixture.database.currentResetState()).thenReturn(
          new ValidationAdministrativeDatabase.ResetControlState(
              73L,
              "RESETTING",
              null,
              null));
      when(fixture.resetService.recoverInterruptedReset()).thenAnswer(invocation -> {
        assertThat(fixture.gate.tryBeginReset()).isTrue();
        fixture.gate.markReady(74L);
        return Optional.of(receipt);
      });

      assertThat(fixture.synchronizer.synchronizeOnce()).isTrue();

      verify(fixture.resetService).recoverInterruptedReset();
      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.READY);
      assertThat(fixture.gate.generation()).isEqualTo(74L);
      verify(fixture.fence, never()).tryEnterRequest(73L);
    }
  }

  @Test
  void sameGenerationNeverReopensAFailedGate() {
    try (Fixture fixture = new Fixture(CURRENT)) {
      assertThat(fixture.gate.tryBeginReset()).isTrue();
      fixture.gate.markFailed();

      assertThat(fixture.synchronizer.synchronizeOnce()).isFalse();

      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
      verify(fixture.participant, never()).clearForGeneration(CURRENT);
    }
  }

  @Test
  void adoptionFailureLeavesPacerBarrierAndGateFailClosed() {
    try (Fixture fixture = new Fixture(NEXT)) {
      doThrow(new IllegalStateException("participant failed"))
          .when(fixture.participant).clearForGeneration(NEXT);

      assertThat(fixture.synchronizer.synchronizeOnce()).isFalse();

      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
      assertThat(fixture.barrier.tryEnter()).isEmpty();
      assertThatThrownBy(() -> fixture.pacer.launch(() -> { }))
          .isInstanceOf(IllegalStateException.class);
      verify(fixture.orchestrator, never()).startRecoveryLoop();
    }
  }

  @Test
  void monitorAdoptsWithoutDependingOnARequestOrRunPacerTask() throws Exception {
    try (Fixture fixture = new Fixture(NEXT)) {
      CountDownLatch recovered = new CountDownLatch(1);
      org.mockito.Mockito.doAnswer(invocation -> {
        recovered.countDown();
        return null;
      }).when(fixture.orchestrator).startRecoveryLoop();

      fixture.synchronizer.start();

      assertThat(recovered.await(2L, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
      while (fixture.gate.state() != ValidationResetGate.State.READY
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.READY);
      assertThat(fixture.gate.generation()).isEqualTo(NEXT);
    }
  }

  @Test
  void staleCandidateCannotPoisonANewerLocallyCompletedReset() throws Exception {
    try (Fixture fixture = new Fixture(CURRENT)) {
      CountDownLatch candidateRead = new CountDownLatch(1);
      CountDownLatch allowReturn = new CountDownLatch(1);
      when(fixture.database.currentResetState()).thenAnswer(invocation -> {
        candidateRead.countDown();
        if (!allowReturn.await(2L, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test candidate read was not released");
        }
        return new ValidationAdministrativeDatabase.ResetControlState(
            CURRENT,
            "READY",
            CURRENT,
            CURRENT);
      });
      AtomicBoolean adopted = new AtomicBoolean(true);
      Thread monitor = Thread.ofVirtual().start(() ->
          adopted.set(fixture.synchronizer.synchronizeOnce()));

      assertThat(candidateRead.await(2L, TimeUnit.SECONDS)).isTrue();
      assertThat(fixture.gate.tryBeginReset()).isTrue();
      fixture.gate.markReady(NEXT);
      allowReturn.countDown();
      assertThat(monitor.join(Duration.ofSeconds(2L))).isTrue();

      assertThat(adopted).isFalse();
      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.READY);
      assertThat(fixture.gate.generation()).isEqualTo(NEXT);
    }
  }

  @Test
  void sameGenerationRedisDriftInvalidatesLocalReadiness() {
    try (Fixture fixture = new Fixture(CURRENT)) {
      when(fixture.redis.currentGeneration()).thenReturn(CURRENT - 1L);

      assertThat(fixture.synchronizer.synchronizeOnce()).isFalse();

      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
      assertThat(fixture.barrier.tryEnter()).isEmpty();
      assertThatThrownBy(() -> fixture.pacer.launch(() -> { }))
          .isInstanceOf(IllegalStateException.class);
      verify(fixture.orchestrator, never()).startRecoveryLoop();
    }
  }

  @Test
  void sameGenerationRedisOutageInvalidatesLocalReadiness() {
    try (Fixture fixture = new Fixture(CURRENT)) {
      when(fixture.redis.currentGeneration())
          .thenThrow(new IllegalStateException("redis unavailable"));

      assertThat(fixture.synchronizer.synchronizeOnce()).isFalse();

      assertThat(fixture.gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
      assertThat(fixture.barrier.tryEnter()).isEmpty();
      assertThatThrownBy(() -> fixture.pacer.launch(() -> { }))
          .isInstanceOf(IllegalStateException.class);
      verify(fixture.orchestrator, never()).startRecoveryLoop();
    }
  }

  private static final class Fixture implements AutoCloseable {

    private final ValidationAdministrativeDatabase database =
        mock(ValidationAdministrativeDatabase.class);
    private final ValidationResetService resetService = mock(ValidationResetService.class);
    private final ValidationRedisResetter redis = mock(ValidationRedisResetter.class);
    private final ValidationDatabaseFence fence = mock(ValidationDatabaseFence.class);
    private final ValidationDatabaseFence.RequestFence requestFence =
        mock(ValidationDatabaseFence.RequestFence.class);
    private final ValidationResetGate gate = new ValidationResetGate();
    private final ValidationMarketState marketState = new ValidationMarketState();
    private final ValidationMarketClock marketClock = new ValidationMarketClock();
    private final ValidationDemoExecutionPolicyProvider policies =
        new ValidationDemoExecutionPolicyProvider();
    private final DefaultValidationRunPacer pacer = new DefaultValidationRunPacer();
    private final ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofMillis(100L));
    private final ValidationRunOrchestrator orchestrator =
        mock(ValidationRunOrchestrator.class);
    private final ValidationRuntimeResetParticipant participant =
        mock(ValidationRuntimeResetParticipant.class);
    private final ValidationRuntimeGenerationSynchronizer synchronizer;

    private Fixture(long durableGeneration) {
      this(durableGeneration, true);
    }

    private Fixture(long durableGeneration, boolean hydrateCurrentGeneration) {
      if (hydrateCurrentGeneration) {
        gate.hydrateReady(CURRENT);
        marketState.reset(CURRENT);
        marketClock.reset(CURRENT);
        policies.reset(CURRENT);
        pacer.reset(CURRENT);
      }
      when(database.currentResetState()).thenReturn(
          new ValidationAdministrativeDatabase.ResetControlState(
              durableGeneration,
              "READY",
              durableGeneration,
              durableGeneration));
      when(redis.currentGeneration()).thenReturn(durableGeneration);
      when(fence.tryEnterRequest(durableGeneration)).thenReturn(Optional.of(requestFence));
      synchronizer = new ValidationRuntimeGenerationSynchronizer(
          database,
          resetService,
          redis,
          fence,
          gate,
          marketState,
          marketClock,
          policies,
          pacer,
          barrier,
          orchestrator,
          List.of(participant),
          Duration.ofMillis(10L));
    }

    @Override
    public void close() {
      synchronizer.close();
      pacer.close();
    }
  }
}
