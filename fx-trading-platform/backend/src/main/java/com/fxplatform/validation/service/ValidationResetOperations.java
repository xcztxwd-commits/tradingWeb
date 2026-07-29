package com.fxplatform.validation.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Destructive and in-memory operations orchestrated by one validation reset. */
public interface ValidationResetOperations {

  List<String> RESET_SCHEMAS = List.of(
      "public",
      "admin",
      "audit",
      "auth",
      "config",
      "content",
      "core",
      "finance",
      "ledger",
      "market",
      "risk",
      "trading",
      "trading_lab",
      "validation_runtime");

  Set<String> ALLOWED_SCHEMA_INVENTORY = Set.of(
      "public",
      "admin",
      "audit",
      "auth",
      "config",
      "content",
      "core",
      "finance",
      "ledger",
      "market",
      "risk",
      "trading",
      "trading_lab",
      "validation_control",
      "validation_runtime");

  DatabaseInspection inspectDatabase();

  /**
   * Resolves a durable command before the process-local gate is changed. Exact terminal replay
   * returns its receipt; new commands return {@link ResetCommandResolution#start()}.
   */
  default ResetCommandResolution resolveResetCommand(ValidationResetCommand command) {
    return ResetCommandResolution.start();
  }

  /**
   * Finds the exact durable reset operation left in progress by an interrupted validation
   * process. This is a read-only discovery step; ownership is acquired by
   * {@link #recoverResetting(ValidationResetCommand, String, Instant)}.
   */
  default Optional<ValidationResetCommand> interruptedResetCommand() {
    return Optional.empty();
  }

  /**
   * Persists RESETTING after identity/inventory validation and before the destructive clean.
   * Production implementations must override this lifecycle callback.
   */
  default void markResetting(String databaseName, Instant startedAt) {
  }

  /**
   * Atomically admits the identity-bound command and persists RESETTING before destructive clean.
   */
  default void markResetting(
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    markResetting(databaseName, startedAt);
  }

  /**
   * Reclaims the durable fence for an exact interrupted operation without admitting a new reset
   * or advancing its generation/epoch.
   */
  default void recoverResetting(
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    throw new IllegalStateException("Interrupted validation reset recovery is unavailable");
  }

  void cleanAndMigrate(List<String> schemaAllowlist);

  long flushRedisDatabaseAndAdvanceGeneration();

  void clearMarketState(long generation);

  void resetVirtualClock(long generation);

  void resetExecutionPolicy(long generation);

  void clearRuntimeRegistry(long generation);

  long publishMemoryGeneration(long generation);

  /**
   * Persists the sanitized terminal receipt. READY persistence must complete before memory READY.
   * Production implementations must override this lifecycle callback.
   */
  default void persistResetReceipt(ValidationResetReceipt receipt) {
  }

  /** Atomically persists both the reset fence and the terminal identity-bound receipt. */
  default void persistResetReceipt(
      ValidationResetCommand command,
      ValidationResetReceipt receipt
  ) {
    persistResetReceipt(receipt);
  }

  /** Actual database identity and non-system schema inventory observed on the same datasource. */
  record DatabaseInspection(String databaseName, Set<String> applicationSchemas) {

    public DatabaseInspection {
      applicationSchemas = applicationSchemas == null
          ? Set.of()
          : Set.copyOf(applicationSchemas);
    }
  }

  /** Result of resolving one durable reset command. */
  record ResetCommandResolution(ValidationResetReceipt replayReceipt) {

    public static ResetCommandResolution start() {
      return new ResetCommandResolution(null);
    }

    public static ResetCommandResolution replay(ValidationResetReceipt receipt) {
      if (receipt == null) {
        throw new IllegalArgumentException("Replay receipt is required");
      }
      return new ResetCommandResolution(receipt);
    }

    public boolean isReplay() {
      return replayReceipt != null;
    }
  }
}
