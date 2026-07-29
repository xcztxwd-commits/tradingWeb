package com.fxplatform.tradinglab.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-time worker view. A snapshot is useful only for the single operation that loaded it;
 * callers must load another snapshot before the next worker-owned side effect.
 */
public record TradingLabWorkerRunSnapshot(
    UUID runId,
    UUID scenarioId,
    UUID reportId,
    String reportStatus,
    boolean reportQuarantined,
    String state,
    long version,
    boolean pauseRequested,
    boolean cancelRequested,
    Instant virtualStartedAt,
    Instant virtualCurrentAt,
    long processedTicks,
    long totalTicks,
    BigDecimal speedMultiplier,
    String scenarioSnapshotJson,
    String configSnapshotJson,
    String localCalculationJson,
    String configSnapshotHash,
    String modelVersion,
    String symbolConfigVersion,
    String codeVersion,
    UUID createdBy
) {

  public TradingLabWorkerRunSnapshot {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(scenarioId, "scenarioId");
    Objects.requireNonNull(reportStatus, "reportStatus");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(speedMultiplier, "speedMultiplier");
    Objects.requireNonNull(scenarioSnapshotJson, "scenarioSnapshotJson");
    Objects.requireNonNull(configSnapshotJson, "configSnapshotJson");
    Objects.requireNonNull(localCalculationJson, "localCalculationJson");
    Objects.requireNonNull(configSnapshotHash, "configSnapshotHash");
    Objects.requireNonNull(modelVersion, "modelVersion");
    Objects.requireNonNull(symbolConfigVersion, "symbolConfigVersion");
    Objects.requireNonNull(codeVersion, "codeVersion");
    Objects.requireNonNull(createdBy, "createdBy");
    if (!reportStatus.matches("PENDING|WRITING|COMPLETED|FAILED|CANCELLED")
        || version < 0L || processedTicks < 0L || totalTicks < 0L
        || speedMultiplier.signum() <= 0) {
      throw new IllegalArgumentException("Invalid Trading Lab worker snapshot");
    }
  }

  public TradingLabWorkerRunSnapshot(
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      String reportStatus,
      String state,
      long version,
      boolean pauseRequested,
      boolean cancelRequested,
      Instant virtualStartedAt,
      Instant virtualCurrentAt,
      long processedTicks,
      long totalTicks,
      BigDecimal speedMultiplier,
      String scenarioSnapshotJson,
      String configSnapshotJson,
      String localCalculationJson,
      String configSnapshotHash,
      String modelVersion,
      String symbolConfigVersion,
      String codeVersion,
      UUID createdBy
  ) {
    this(
        runId,
        scenarioId,
        reportId,
        reportStatus,
        false,
        state,
        version,
        pauseRequested,
        cancelRequested,
        virtualStartedAt,
        virtualCurrentAt,
        processedTicks,
        totalTicks,
        speedMultiplier,
        scenarioSnapshotJson,
        configSnapshotJson,
        localCalculationJson,
        configSnapshotHash,
        modelVersion,
        symbolConfigVersion,
        codeVersion,
        createdBy);
  }

  public TradingLabWorkerRunSnapshot(
      UUID runId,
      UUID scenarioId,
      UUID reportId,
      String reportStatus,
      String state,
      long version,
      boolean pauseRequested,
      boolean cancelRequested,
      Instant virtualStartedAt,
      Instant virtualCurrentAt,
      long processedTicks,
      long totalTicks,
      BigDecimal speedMultiplier,
      String scenarioSnapshotJson,
      String configSnapshotJson,
      String configSnapshotHash,
      String modelVersion,
      String symbolConfigVersion,
      String codeVersion,
      UUID createdBy
  ) {
    this(
        runId,
        scenarioId,
        reportId,
        reportStatus,
        false,
        state,
        version,
        pauseRequested,
        cancelRequested,
        virtualStartedAt,
        virtualCurrentAt,
        processedTicks,
        totalTicks,
        speedMultiplier,
        scenarioSnapshotJson,
        configSnapshotJson,
        "{}",
        configSnapshotHash,
        modelVersion,
        symbolConfigVersion,
        codeVersion,
        createdBy);
  }

  public boolean hasReport() {
    return reportId != null;
  }
}
