package com.fxplatform.tradinglab.environment;

/**
 * Shared PostgreSQL transaction gate for run creation and environment mutation.
 *
 * <p>Every method requires an existing transaction. The transaction remains the lock lifetime.</p>
 */
public interface TradingLabOperationGate {

  void awaitRunCreationPermit();

  void acquireEnvironmentMutationPermit();

  boolean hasNonTerminalRuns();
}
