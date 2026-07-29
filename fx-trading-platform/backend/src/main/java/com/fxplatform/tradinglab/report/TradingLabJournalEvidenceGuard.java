package com.fxplatform.tradinglab.report;

/** Scans one fully materialized journal value against the run-lifetime secret registry. */
@FunctionalInterface
public interface TradingLabJournalEvidenceGuard {

  void requireSafe(Object value);
}
