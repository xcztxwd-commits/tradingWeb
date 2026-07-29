package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.ResetResult;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.WireResponse;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationResetHttpIT {

  @Test
  void resetPublishesOneCoherentGenerationThatCanCompleteARealRun() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();

    ResetResult firstReset = http.reset();
    StartRequest firstRequest =
        ValidationHttpIntegrationSupport.resetProbe(firstReset.generation());
    RunResult firstRun = http.startAndAwait(firstRequest);

    assertThat(firstReset.state().path("generationCoherent").asBoolean()).isTrue();
    assertThat(firstRun.events("MARKET_TICK")).singleElement().satisfies(event -> {
      assertThat(event.path("payload").path("tickSequence").asLong()).isEqualTo(1L);
      assertThat(event.path("payload").path("spotSymbols").asInt()).isEqualTo(1);
      assertThat(event.path("payload").path("perpetualSymbols").asInt()).isZero();
    });
    assertThat(firstRun.items("orders")).isEmpty();

    ResetResult secondReset = http.reset();
    assertThat(secondReset.generation()).isGreaterThan(firstReset.generation());
    assertThat(secondReset.state().path("run").isNull()).isTrue();
    assertThat(secondReset.state().path("clock").isNull()).isTrue();
    assertThat(secondReset.state().path("marketTickSequence").isNull()).isTrue();
    assertThat(secondReset.state().path("executionPolicyFrozen").asBoolean()).isFalse();
    assertThat(http.observeState(firstRequest.runId()).path("run").isNull()).isTrue();

    assertThat(http.rejectedStaleReset(firstReset.generation()).code())
        .isEqualTo("VALIDATION_RESET_STALE_GENERATION");
    WireResponse stale = http.rejectedStart(firstRequest);
    assertThat(stale.status()).isEqualTo(400);
    assertThat(stale.code()).isEqualTo("VALIDATION_GENERATION_FENCED");
    WireResponse oldEvents = http.rejectedEvents(firstRequest.runId());
    assertThat(oldEvents.status()).isEqualTo(400);
    assertThat(oldEvents.code()).isEqualTo("VALIDATION_RUN_NOT_FOUND");

    RunResult secondRun = http.startAndAwait(
        ValidationHttpIntegrationSupport.resetProbe(secondReset.generation()));
    assertThat(secondRun.request().runId()).isNotEqualTo(firstRequest.runId());
    assertThat(secondRun.items("orders")).isEmpty();
    assertThat(secondRun.items("trades")).isEmpty();
    assertThat(secondRun.items("positions")).isEmpty();
    assertThat(secondRun.apiTraces("PUBLIC_ACTION")).isEmpty();
  }
}
