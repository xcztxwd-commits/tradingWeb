package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DefaultValidationRunPacerTest {

  @Test
  void quiesceAndJoinWaitsUntilTheCancelledRunnableActuallyExits() throws Exception {
    DefaultValidationRunPacer pacer = new DefaultValidationRunPacer();
    ExecutorService resetCaller = Executors.newSingleThreadExecutor();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch allowExit = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);

    try {
      pacer.reset(1L);
      pacer.launch(() -> {
        started.countDown();
        try {
          boolean released = false;
          while (!released) {
            try {
              allowExit.await();
              released = true;
            } catch (InterruptedException ignored) {
              interrupted.countDown();
            }
          }
        } finally {
          exited.countDown();
        }
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> joining = resetCaller.submit(pacer::quiesceAndJoin);

      assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(exited.getCount()).isEqualTo(1L);
      assertThat(joining.isDone()).isFalse();

      allowExit.countDown();
      joining.get(5, TimeUnit.SECONDS);

      assertThat(exited.getCount()).isZero();
      assertThatCode(() -> pacer.reset(2L)).doesNotThrowAnyException();
    } finally {
      allowExit.countDown();
      resetCaller.shutdownNow();
      assertThat(resetCaller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      pacer.close();
    }
  }
}
