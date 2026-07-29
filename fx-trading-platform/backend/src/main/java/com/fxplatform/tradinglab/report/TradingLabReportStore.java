package com.fxplatform.tradinglab.report;

import java.time.Duration;
import java.util.UUID;

public interface TradingLabReportStore {

  TradingLabReportWriteState ensureWritable(
      UUID reportId,
      TradingLabReportWriteFence fence);

  /**
   * Initializes the structured report metadata under a live write fence.
   *
   * <p>The value must already have passed the report canonicalizer. Replaying the same canonical
   * JSON is a no-op; attempting to replace it with a different value is a conflict.
   */
  void initializeMetadata(
      TradingLabReportWriteFence fence,
      TradingLabCanonicalValue metadata);

  long highestDurableSourceSequence(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section);

  long nextChunkSequence(UUID reportId, TradingLabReportSection section);

  /**
   * Persists one exact candidate batch.
   *
   * <p>If an append without a source sequence has an uncertain result, the caller must retry that
   * exact batch until it is acknowledged before sending any later unmarked append in a separate
   * batch. The store cannot infer an idempotency boundary that the caller did not supply.
   */
  void persist(TradingLabReportWriteBatch batch);

  /**
   * Probes recovery state and atomically quarantines any open report that is not pristine.
   *
   * <p>A terminal report is never purged. A normalized quarantine marker is sticky across process
   * restarts, and a generic quarantine terminal is reported as {@code QUARANTINED_TERMINAL} so a
   * fresh writer can reproduce its fixed outcome exactly. Only a clean, empty, never-written
   * {@code PENDING} report returns {@code EMPTY}.
   */
  TradingLabReportRecoveryState discardEvidence(
      UUID reportId,
      TradingLabReportWriteFence fence);

  /** Atomically purges and quarantines an open report, including an otherwise empty report. */
  TradingLabReportRecoveryState quarantineEvidence(
      UUID reportId,
      TradingLabReportWriteFence fence);

  TradingLabReportFinalizeResult finalizeReport(
      UUID reportId,
      TradingLabReportWriteFence fence,
      TradingLabReportMeasurement measurement,
      TradingLabReportOutcome outcome,
      Duration retention);
}
