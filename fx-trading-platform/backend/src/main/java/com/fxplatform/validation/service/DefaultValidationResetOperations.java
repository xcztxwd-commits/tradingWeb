package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationResetOperations.DatabaseInspection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production reset composition; destructive adapters stay behind this validation-only port. */
@Profile("validation")
@Component
public class DefaultValidationResetOperations implements ValidationResetOperations {

  private final ValidationAdministrativeDatabase administrativeDatabase;
  private final ValidationRedisResetter redisResetter;
  private final ValidationMarketState marketState;
  private final ValidationMarketClock marketClock;
  private final ValidationDemoExecutionPolicyProvider executionPolicyProvider;
  private final DefaultValidationRunPacer runPacer;
  private final ValidationLoopbackRequestActivityBarrier loopbackRequestBarrier;
  private final ValidationRunOrchestrator runOrchestrator;
  private final List<ValidationRuntimeResetParticipant> resetParticipants;

  public DefaultValidationResetOperations(
      ValidationAdministrativeDatabase administrativeDatabase,
      ValidationRedisResetter redisResetter,
      ValidationMarketState marketState,
      ValidationMarketClock marketClock,
      ValidationDemoExecutionPolicyProvider executionPolicyProvider,
      DefaultValidationRunPacer runPacer,
      ValidationLoopbackRequestActivityBarrier loopbackRequestBarrier,
      ValidationRunOrchestrator runOrchestrator,
      List<ValidationRuntimeResetParticipant> resetParticipants
  ) {
    this.administrativeDatabase = administrativeDatabase;
    this.redisResetter = redisResetter;
    this.marketState = marketState;
    this.marketClock = marketClock;
    this.executionPolicyProvider = executionPolicyProvider;
    this.runPacer = runPacer;
    this.loopbackRequestBarrier = loopbackRequestBarrier;
    this.runOrchestrator = runOrchestrator;
    this.resetParticipants = List.copyOf(resetParticipants);
  }

  @Override
  public DatabaseInspection inspectDatabase() {
    return administrativeDatabase.inspect();
  }

  @Override
  public ResetCommandResolution resolveResetCommand(ValidationResetCommand command) {
    return administrativeDatabase.resolveResetCommand(command);
  }

  @Override
  public Optional<ValidationResetCommand> interruptedResetCommand() {
    return administrativeDatabase.interruptedResetCommand();
  }

  @Override
  public void markResetting(String databaseName, Instant startedAt) {
    runPacer.quiesceAndJoin();
    loopbackRequestBarrier.quiesceAndAwait();
    administrativeDatabase.markResetting(databaseName, startedAt);
  }

  @Override
  public void markResetting(
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    runPacer.quiesceAndJoin();
    loopbackRequestBarrier.quiesceAndAwait();
    administrativeDatabase.markResetting(command, databaseName, startedAt);
  }

  @Override
  public void recoverResetting(
      ValidationResetCommand command,
      String databaseName,
      Instant startedAt
  ) {
    runPacer.quiesceAndJoin();
    loopbackRequestBarrier.quiesceAndAwait();
    administrativeDatabase.recoverResetting(command, databaseName, startedAt);
  }

  @Override
  public void cleanAndMigrate(List<String> schemaAllowlist) {
    administrativeDatabase.cleanAndMigrate(schemaAllowlist);
  }

  @Override
  public long flushRedisDatabaseAndAdvanceGeneration() {
    ValidationAdministrativeDatabase.ResetPlan plan =
        administrativeDatabase.currentResetPlan();
    return redisResetter.resetToGeneration(plan.targetGeneration(), plan.epoch());
  }

  @Override
  public void clearMarketState(long generation) {
    administrativeDatabase.requireResetOwnership();
    marketState.reset(generation);
  }

  @Override
  public void resetVirtualClock(long generation) {
    administrativeDatabase.requireResetOwnership();
    marketClock.reset(generation);
  }

  @Override
  public void resetExecutionPolicy(long generation) {
    administrativeDatabase.requireResetOwnership();
    executionPolicyProvider.reset(generation);
  }

  @Override
  public void clearRuntimeRegistry(long generation) {
    administrativeDatabase.requireResetOwnership();
    resetParticipants.forEach(participant -> participant.clearForGeneration(generation));
    runPacer.reset(generation);
    loopbackRequestBarrier.reopen();
    runOrchestrator.startRecoveryLoop();
  }

  @Override
  public long publishMemoryGeneration(long generation) {
    administrativeDatabase.requireResetOwnership();
    return runPacer.generation();
  }

  @Override
  public void persistResetReceipt(ValidationResetReceipt receipt) {
    administrativeDatabase.persistResetReceipt(receipt);
  }

  @Override
  public void persistResetReceipt(
      ValidationResetCommand command,
      ValidationResetReceipt receipt
  ) {
    administrativeDatabase.persistResetReceipt(command, receipt);
  }
}
