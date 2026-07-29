package com.fxplatform.tradinglab.application;

import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.repository.TradingLabWorkerRunSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface TradingLabValidationStartRequestFactory {

  ValidationRunStartRequest compile(
      TradingLabValidationStartSource source,
      long generation);

  default ValidationRunStartRequest create(
      TradingLabWorkerRunSnapshot run,
      long generation
  ) {
    Objects.requireNonNull(run, "run");
    ValidationRunStartRequest compiled = compile(
        new TradingLabValidationStartSource(
            run.runId(),
            run.scenarioSnapshotJson(),
            run.configSnapshotJson(),
            run.configSnapshotHash(),
            run.modelVersion(),
            run.symbolConfigVersion(),
            run.codeVersion(),
            null,
            null,
            run.virtualStartedAt(),
            run.speedMultiplier()),
        generation);
    if (run.virtualStartedAt() == null
        || !run.virtualStartedAt().equals(compiled.virtualStart())
        || run.totalTicks() != compiled.ticks().size()
        || run.speedMultiplier().compareTo(compiled.speedMultiplier()) != 0) {
      throw new IllegalStateException(
          "Persisted Trading Lab run shape differs from compiled validation start");
    }
    return compiled;
  }

  record TradingLabValidationStartSource(
      UUID runId,
      String scenarioSnapshotJson,
      String configSnapshotJson,
      String configSnapshotHash,
      String modelVersion,
      String symbolConfigVersion,
      String codeVersion,
      String expectedSeed,
      Boolean expectedNegativeMode,
      Instant legacyVirtualStartFallback,
      BigDecimal legacySpeedMultiplierFallback
  ) {

    public TradingLabValidationStartSource {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(scenarioSnapshotJson, "scenarioSnapshotJson");
      Objects.requireNonNull(configSnapshotJson, "configSnapshotJson");
      Objects.requireNonNull(configSnapshotHash, "configSnapshotHash");
      Objects.requireNonNull(modelVersion, "modelVersion");
      Objects.requireNonNull(symbolConfigVersion, "symbolConfigVersion");
      Objects.requireNonNull(codeVersion, "codeVersion");
    }

    public TradingLabValidationStartSource(
        UUID runId,
        String scenarioSnapshotJson,
        String configSnapshotJson,
        String configSnapshotHash,
        String modelVersion,
        String symbolConfigVersion,
        String codeVersion
    ) {
      this(
          runId,
          scenarioSnapshotJson,
          configSnapshotJson,
          configSnapshotHash,
          modelVersion,
          symbolConfigVersion,
          codeVersion,
          null,
          null,
          null,
          null);
    }

    public TradingLabValidationStartSource(
        UUID runId,
        String scenarioSnapshotJson,
        String configSnapshotJson,
        String configSnapshotHash,
        String modelVersion,
        String symbolConfigVersion,
        String codeVersion,
        String expectedSeed,
        Boolean expectedNegativeMode
    ) {
      this(
          runId,
          scenarioSnapshotJson,
          configSnapshotJson,
          configSnapshotHash,
          modelVersion,
          symbolConfigVersion,
          codeVersion,
          expectedSeed,
          expectedNegativeMode,
          null,
          null);
    }
  }
}
