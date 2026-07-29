package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValidationRunRecoveryLoopTest {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000570");

  @Test
  void aLaterScanRecoversWorkThatWasStillLiveDuringTheFirstScan() throws Exception {
    ValidationRunEngine engine = mock(ValidationRunEngine.class);
    AtomicInteger scans = new AtomicInteger();
    CountDownLatch recovered = new CountDownLatch(1);
    when(engine.prepareRecovery()).thenAnswer(ignored ->
        scans.incrementAndGet() == 1 ? List.of() : List.of(RUN_ID));
    org.mockito.Mockito.doAnswer(ignored -> {
      recovered.countDown();
      return null;
    }).when(engine).execute(RUN_ID, true);

    DefaultValidationRunPacer pacer = new DefaultValidationRunPacer();
    pacer.reset(66L);
    ValidationRunOrchestrator orchestrator =
        new ValidationRunOrchestrator(engine, pacer, Duration.ofMillis(10));
    try {
      orchestrator.startRecoveryLoop();

      assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
      verify(engine, atLeast(2)).prepareRecovery();

      pacer.quiesceAndJoin();
      int stoppedAt = scans.get();
      Thread.sleep(40L);
      assertThat(scans).hasValue(stoppedAt);
    } finally {
      pacer.close();
    }
  }

  @Test
  void aLoopCancelledBeforeItsRunnableStartsCanBeScheduledForTheNextGeneration() {
    ValidationRunEngine engine = mock(ValidationRunEngine.class);
    CapturingGenerationPacer pacer = new CapturingGenerationPacer(66L);
    ValidationRunOrchestrator orchestrator =
        new ValidationRunOrchestrator(engine, pacer, Duration.ofMillis(10));

    orchestrator.startRecoveryLoop();
    assertThat(pacer.launches()).isEqualTo(1);

    // Models FutureTask.cancel(true) winning before the submitted runnable ever starts.
    pacer.discardPendingAndAdvanceTo(67L);
    orchestrator.startRecoveryLoop();

    assertThat(pacer.launches()).isEqualTo(2);
  }

  @Test
  void aLoopCancelledBeforeItsRunnableStartsCanBeRetriedForTheSameGeneration() {
    ValidationRunEngine engine = mock(ValidationRunEngine.class);
    CapturingGenerationPacer pacer = new CapturingGenerationPacer(67L);
    ValidationRunOrchestrator orchestrator =
        new ValidationRunOrchestrator(engine, pacer, Duration.ofMillis(10));

    orchestrator.startRecoveryLoop();
    assertThat(pacer.launches()).isEqualTo(1);

    // Models a failed reset receipt followed by quiesce cancelling the pending loop. The
    // durable base generation did not advance, so the retry publishes the same target.
    pacer.discardPendingAndAdvanceTo(67L);
    orchestrator.startRecoveryLoop();

    assertThat(pacer.launches()).isEqualTo(2);
  }

  private static final class CapturingGenerationPacer implements ValidationRunPacer {

    private long generation;
    private int launches;

    private CapturingGenerationPacer(long generation) {
      this.generation = generation;
    }

    @Override
    public void launch(Runnable command) {
      launches++;
    }

    @Override
    public void awaitNextTick(java.math.BigDecimal speedMultiplier) {
      // No execution is needed: cancellation happens before the runnable starts.
    }

    public long generation() {
      return generation;
    }

    private void discardPendingAndAdvanceTo(long nextGeneration) {
      generation = nextGeneration;
    }

    private int launches() {
      return launches;
    }
  }
}
