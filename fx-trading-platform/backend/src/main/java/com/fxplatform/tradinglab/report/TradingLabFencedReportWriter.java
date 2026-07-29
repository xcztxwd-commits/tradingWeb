package com.fxplatform.tradinglab.report;

import java.util.function.Function;

public interface TradingLabFencedReportWriter {

  /** Registers a live exchange's dynamic secrets before any related journal commit. */
  void registerTraceSecrets(
      TradingLabReportWriteFence fence,
      SafeTradingLabHttpTrace trace);

  /** Rejects a canonical journal value that contains any report-lifetime known secret. */
  void requireSafeJournalEvidence(
      TradingLabReportWriteFence fence,
      Object value);

  /**
   * Holds the report-lifetime registry stable while a journal transaction validates its lease,
   * materializes its assigned sequence, scans the complete value, and accesses durable rows.
   */
  <T> T withJournalEvidenceGuard(
      TradingLabReportWriteFence fence,
      SafeTradingLabHttpTrace trace,
      Function<TradingLabJournalEvidenceGuard, T> transaction);

  void initializeMetadata(
      TradingLabReportWriteFence fence,
      Object metadata);

  void appendSingleton(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section,
      Object value);

  /**
   * Appends an array value by durable source identity.
   *
   * <p>{@code -1} is the single exact-replay {@link TradingLabReportSection#API_TRACE} preamble
   * identity; non-negative values are journal source sequences and advance the recovery cursor.</p>
   */
  void appendEvent(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section,
      long sourceSequence,
      Object value);

  long highestDurableSourceSequence(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section);

  void flush(TradingLabReportWriteFence fence);

  void complete(TradingLabReportWriteFence fence);

  void fail(
      TradingLabReportWriteFence fence,
      String failureCode,
      String failureMessage);

  void cancel(TradingLabReportWriteFence fence, String reason);
}
