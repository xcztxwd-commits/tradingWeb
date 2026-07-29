package com.fxplatform.validation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import com.fxplatform.validation.service.ValidationStateService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationRunControllerRecoveryStateTest {

  @Test
  void exposesDurableAcceptedIdentityFlagsAndTerminalHighWatermark() {
    UUID runId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-23T01:02:03Z");
    ValidationStateService stateService = mock(ValidationStateService.class);
    ValidationStateService.RunSnapshot run = new ValidationStateService.RunSnapshot(
        runId,
        73L,
        "frozen-request-fingerprint",
        State.COMPLETED,
        false,
        true,
        18L,
        now,
        29L,
        "VALIDATION_RUN_FAILED",
        now,
        true);
    when(stateService.snapshot(runId)).thenReturn(new ValidationStateService.Snapshot(
        ValidationResetGate.State.READY,
        73L,
        "READY",
        73L,
        73L,
        73L,
        73L,
        true,
        run,
        new ValidationMarketClock.Tick(runId, 73L, 18L, now),
        18L,
        true));
    ValidationStateController controller = new ValidationStateController(stateService);

    ValidationStateService.Snapshot response = controller.state(runId).data();

    assertThat(response.generationCoherent()).isTrue();
    assertThat(response.run().runId()).isEqualTo(runId);
    assertThat(response.run().generation()).isEqualTo(73L);
    assertThat(response.run().requestFingerprint()).isEqualTo("frozen-request-fingerprint");
    assertThat(response.run().state()).isEqualTo(State.COMPLETED);
    assertThat(response.run().pauseRequested()).isFalse();
    assertThat(response.run().cancelRequested()).isTrue();
    assertThat(response.run().lastCompletedTickSequence()).isEqualTo(18L);
    assertThat(response.run().eventHighWatermark()).isEqualTo(29L);
    assertThat(response.run().failureCode()).isEqualTo("VALIDATION_RUN_FAILED");
    assertThat(response.run().terminal()).isTrue();
  }
}
