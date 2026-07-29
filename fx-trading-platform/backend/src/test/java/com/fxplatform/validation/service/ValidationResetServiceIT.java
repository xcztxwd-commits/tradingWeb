package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationResetOperations.DatabaseInspection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class ValidationResetServiceIT {

  private static final Instant WALL_CLOCK_START = Instant.parse("2026-07-23T00:00:00Z");
  private static final String FAILURE_SECRET =
      "do-not-leak-validation-reset-secret-0123456789";

  private static final List<String> RESET_SCHEMAS = List.of(
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

  private static final Set<String> RESET_SCHEMA_INVENTORY = allowedSchemaInventory();

  @Test
  void destructiveResetTypesExistOnlyInTheValidationProfile() {
    assertValidationProfile(ValidationResetService.class);
    assertValidationProfile(ValidationAdministrativeDatabase.class);
    assertValidationProfile(ValidationRedisResetter.class);
  }

  @Test
  void unsafeActualDatabaseNameFailsBeforeEveryDestructiveOrMemoryMutation() {
    RecordingOperations operations = new RecordingOperations();
    operations.databaseName = "fx_platform";
    ValidationResetGate gate = new ValidationResetGate();

    ValidationResetReceipt receipt = service(operations, gate).reset();

    assertThat(receipt.status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
    assertThat(receipt.errorCode()).isEqualTo("UNSAFE_DATABASE");
    assertThat(receipt.databaseName()).isEqualTo("fx_platform");
    assertThat(receipt.redisGeneration()).isNull();
    assertThat(receipt.memoryGeneration()).isNull();
    assertThat(operations.calls).containsExactly("inspectDatabase");
    assertStepStatuses(receipt,
        ValidationResetReceipt.StepStatus.FAILED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED);
    assertThat(receipt.steps().getFirst().errorCode()).isEqualTo("UNSAFE_DATABASE");
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
    assertThatThrownBy(() -> gate.requireReadyGeneration(0L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void emptyValidationPrefixIsNotAValidDatabaseName() {
    RecordingOperations operations = new RecordingOperations();
    operations.databaseName = "fx_validation_";
    ValidationResetGate gate = new ValidationResetGate();

    ValidationResetReceipt receipt = service(operations, gate).reset();

    assertThat(receipt.status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
    assertThat(receipt.errorCode()).isEqualTo("UNSAFE_DATABASE");
    assertThat(operations.calls).containsExactly("inspectDatabase");
  }

  @Test
  void anUnexpectedApplicationSchemaFailsClosedBeforeFlywayOrRedis() {
    RecordingOperations operations = new RecordingOperations();
    operations.applicationSchemas = new LinkedHashSet<>(RESET_SCHEMA_INVENTORY);
    operations.applicationSchemas.add("customer_production_shadow");
    ValidationResetGate gate = new ValidationResetGate();

    ValidationResetReceipt receipt = service(operations, gate).reset();

    assertThat(receipt.status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
    assertThat(receipt.errorCode()).isEqualTo("UNEXPECTED_DATABASE_SCHEMA");
    assertThat(operations.calls).containsExactly("inspectDatabase");
    assertThat(operations.cleanedSchemas).isNull();
    assertThat(receipt.toString()).doesNotContain("customer_production_shadow");
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
  }

  @Test
  void successfulResetUsesTheFixedSchemaAllowlistAndPublishesOneGenerationFence() {
    RecordingOperations operations = new RecordingOperations();
    operations.nextRedisGeneration = 41L;
    ValidationResetGate gate = new ValidationResetGate();

    ValidationResetReceipt receipt = service(operations, gate).reset();

    assertThat(receipt.status()).isEqualTo(ValidationResetReceipt.Status.SUCCEEDED);
    assertThat(receipt.errorCode()).isNull();
    assertThat(receipt.databaseName()).isEqualTo("fx_validation_lab");
    assertThat(receipt.redisGeneration()).isEqualTo(41L);
    assertThat(receipt.memoryGeneration()).isEqualTo(41L);
    assertThat(receipt.startedAt()).isEqualTo(WALL_CLOCK_START);
    assertThat(receipt.finishedAt()).isAfter(receipt.startedAt());
    assertThat(operations.calls).containsExactly(
        "inspectDatabase",
        "cleanAndMigrate",
        "flushRedisDatabaseAndAdvanceGeneration",
        "clearMarketState:41",
        "resetVirtualClock:41",
        "resetExecutionPolicy:41",
        "clearRuntimeRegistry:41",
        "publishMemoryGeneration:41");
    assertThat(operations.cleanedSchemas).containsExactlyElementsOf(RESET_SCHEMAS);
    assertThat(operations.observedGenerations).containsOnly(41L);
    assertStepStatuses(receipt,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED);
    assertThat(receipt.steps())
        .extracting(ValidationResetReceipt.StepResult::step)
        .containsExactly(
            ValidationResetReceipt.Step.DATABASE,
            ValidationResetReceipt.Step.REDIS,
            ValidationResetReceipt.Step.MARKET_STATE,
            ValidationResetReceipt.Step.VIRTUAL_CLOCK,
            ValidationResetReceipt.Step.EXECUTION_POLICY,
            ValidationResetReceipt.Step.RUNTIME_REGISTRY,
            ValidationResetReceipt.Step.MEMORY_GENERATION);
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.READY);
    assertThat(gate.generation()).isEqualTo(41L);
    assertThatCode(() -> gate.requireReadyGeneration(41L)).doesNotThrowAnyException();
    assertThatThrownBy(() -> gate.requireReadyGeneration(40L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exactIdentityBoundReplayReturnsTheDurableReceiptWithoutCleaningAgain() {
    RecordingOperations operations = new RecordingOperations();
    operations.nextRedisGeneration = 1L;
    ValidationResetService service = service(operations, new ValidationResetGate());
    ValidationResetCommand command = new ValidationResetCommand(
        UUID.fromString("00000000-0000-0000-0000-000000000701"),
        UUID.fromString("00000000-0000-0000-0000-000000000702"),
        ValidationResetCommand.Mode.INITIAL,
        0L);

    ValidationResetReceipt first = service.reset(command);
    operations.calls.clear();
    ValidationResetReceipt replay = service.reset(command);

    assertThat(first.status()).isEqualTo(ValidationResetReceipt.Status.SUCCEEDED);
    assertThat(replay).isEqualTo(first);
    assertThat(operations.calls).isEmpty();
    assertThat(operations.databaseCleanCount).isEqualTo(1);
  }

  @Test
  void interruptedDurableResetRecoversTheExactOperationBeforeServingNewWork() {
    RecordingOperations operations = new RecordingOperations();
    ValidationResetCommand interrupted = new ValidationResetCommand(
        UUID.fromString("00000000-0000-0000-0000-000000000703"),
        UUID.fromString("00000000-0000-0000-0000-000000000704"),
        ValidationResetCommand.Mode.FINAL,
        73L);
    operations.interruptedCommand = interrupted;
    operations.nextRedisGeneration = 74L;
    ValidationResetGate gate = new ValidationResetGate();

    Optional<ValidationResetReceipt> recovered =
        service(operations, gate).recoverInterruptedReset();

    assertThat(recovered).isPresent();
    assertThat(recovered.orElseThrow().status())
        .isEqualTo(ValidationResetReceipt.Status.SUCCEEDED);
    assertThat(operations.recoveredCommand).isEqualTo(interrupted);
    assertThat(operations.calls).containsExactly(
        "inspectDatabase",
        "recoverResetting:" + interrupted.operationId(),
        "cleanAndMigrate",
        "flushRedisDatabaseAndAdvanceGeneration",
        "clearMarketState:74",
        "resetVirtualClock:74",
        "resetExecutionPolicy:74",
        "clearRuntimeRegistry:74",
        "publishMemoryGeneration:74");
    assertThat(operations.databaseCleanCount).isEqualTo(1);
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.READY);
    assertThatCode(() -> gate.requireReadyGeneration(74L)).doesNotThrowAnyException();
  }

  @Test
  void reusingAnOperationIdWithAnotherResetTupleFailsBeforeCleaning() {
    RecordingOperations operations = new RecordingOperations();
    operations.nextRedisGeneration = 1L;
    ValidationResetService service = service(operations, new ValidationResetGate());
    UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000711");
    UUID operationId = UUID.fromString("00000000-0000-0000-0000-000000000712");
    service.reset(new ValidationResetCommand(
        runId,
        operationId,
        ValidationResetCommand.Mode.INITIAL,
        0L));
    operations.calls.clear();

    assertThatThrownBy(() -> service.reset(new ValidationResetCommand(
        runId,
        operationId,
        ValidationResetCommand.Mode.FINAL,
        1L)))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getCode())
        .isEqualTo("VALIDATION_RESET_IDEMPOTENCY_CONFLICT");
    assertThat(operations.calls).isEmpty();
    assertThat(operations.databaseCleanCount).isEqualTo(1);
  }

  @Test
  void partialFailureNeverPublishesReadinessAndAFullReplayCanRecover() {
    RecordingOperations operations = new RecordingOperations();
    operations.nextRedisGeneration = 71L;
    operations.failOnceAt = ValidationResetReceipt.Step.MARKET_STATE;
    ValidationResetGate gate = new ValidationResetGate();
    ValidationResetService service = service(operations, gate);

    ValidationResetReceipt failed = service.reset();

    assertThat(failed.status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
    assertThat(failed.errorCode()).isEqualTo("MARKET_STATE_RESET_FAILED");
    assertThat(failed.redisGeneration()).isEqualTo(71L);
    assertThat(failed.memoryGeneration()).isNull();
    assertThat(failed.toString()).doesNotContain(FAILURE_SECRET);
    assertThat(operations.calls).containsExactly(
        "inspectDatabase",
        "cleanAndMigrate",
        "flushRedisDatabaseAndAdvanceGeneration",
        "clearMarketState:71");
    assertStepStatuses(failed,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.SUCCEEDED,
        ValidationResetReceipt.StepStatus.FAILED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED,
        ValidationResetReceipt.StepStatus.SKIPPED);
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
    assertThatThrownBy(() -> gate.requireReadyGeneration(71L))
        .isInstanceOf(IllegalStateException.class);

    operations.calls.clear();
    ValidationResetReceipt replay = service.reset();

    assertThat(replay.status()).isEqualTo(ValidationResetReceipt.Status.SUCCEEDED);
    assertThat(replay.redisGeneration()).isEqualTo(72L);
    assertThat(replay.memoryGeneration()).isEqualTo(72L);
    assertThat(operations.calls).containsExactly(
        "inspectDatabase",
        "cleanAndMigrate",
        "flushRedisDatabaseAndAdvanceGeneration",
        "clearMarketState:72",
        "resetVirtualClock:72",
        "resetExecutionPolicy:72",
        "clearRuntimeRegistry:72",
        "publishMemoryGeneration:72");
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.READY);
    assertThatCode(() -> gate.requireReadyGeneration(72L)).doesNotThrowAnyException();
    assertThatThrownBy(() -> gate.requireReadyGeneration(71L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void memoryGenerationMismatchFailsClosedInsteadOfPublishingASplitFence() {
    RecordingOperations operations = new RecordingOperations();
    operations.nextRedisGeneration = 91L;
    operations.publishedMemoryGeneration = 90L;
    ValidationResetGate gate = new ValidationResetGate();

    ValidationResetReceipt receipt = service(operations, gate).reset();

    assertThat(receipt.status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
    assertThat(receipt.errorCode()).isEqualTo("GENERATION_MISMATCH");
    assertThat(receipt.redisGeneration()).isEqualTo(91L);
    assertThat(receipt.memoryGeneration()).isEqualTo(90L);
    assertThat(gate.state()).isEqualTo(ValidationResetGate.State.FAILED);
    assertThatThrownBy(() -> gate.requireReadyGeneration(90L))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> gate.requireReadyGeneration(91L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aConcurrentResetFailsFastWithoutJoiningTheActiveResetOrChangingItsGate() throws Exception {
    RecordingOperations operations = new RecordingOperations();
    operations.blockDatabaseReset = true;
    operations.nextRedisGeneration = 101L;
    ValidationResetGate gate = new ValidationResetGate();
    ValidationResetService service = service(operations, gate);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<ValidationResetReceipt> active = executor.submit(
        (Callable<ValidationResetReceipt>) service::reset);

    try {
      assertThat(operations.databaseResetEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(gate.state()).isEqualTo(ValidationResetGate.State.RESETTING);

      ValidationResetReceipt rejected = service.reset();

      assertThat(rejected.status()).isEqualTo(ValidationResetReceipt.Status.FAILED);
      assertThat(rejected.errorCode()).isEqualTo("RESET_ALREADY_IN_PROGRESS");
      assertThat(rejected.steps())
          .extracting(ValidationResetReceipt.StepResult::status)
          .containsOnly(ValidationResetReceipt.StepStatus.SKIPPED);
      assertThat(operations.calls.stream().filter("inspectDatabase"::equals).count())
          .isEqualTo(1L);
      assertThat(gate.state()).isEqualTo(ValidationResetGate.State.RESETTING);

      operations.allowDatabaseReset.countDown();
      ValidationResetReceipt completed = active.get(5, TimeUnit.SECONDS);
      assertThat(completed.status()).isEqualTo(ValidationResetReceipt.Status.SUCCEEDED);
      assertThat(gate.state()).isEqualTo(ValidationResetGate.State.READY);
      assertThat(gate.generation()).isEqualTo(101L);
    } finally {
      operations.allowDatabaseReset.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void localResetAndPassiveAdoptionAreMutuallyExclusive() {
    ValidationResetGate adopting = new ValidationResetGate();
    adopting.hydrateReady(41L);
    assertThat(adopting.tryBeginAdoption(42L)).isTrue();
    assertThat(adopting.tryBeginReset()).isFalse();
    adopting.failAdoption();
    assertThat(adopting.tryBeginReset()).isTrue();

    ValidationResetGate resetting = new ValidationResetGate();
    resetting.hydrateReady(41L);
    assertThat(resetting.tryBeginReset()).isTrue();
    assertThat(resetting.tryBeginAdoption(42L)).isFalse();
    assertThat(resetting.state()).isEqualTo(ValidationResetGate.State.RESETTING);
  }

  @Test
  void databaseAndRedisAdaptersPinActualIdentityFlywayAndFlushDbOnly() throws Exception {
    String database = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/service/ValidationAdministrativeDatabase.java"));
    String redis = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/service/ValidationRedisResetter.java"));

    assertThat(database)
        .contains("SELECT current_database()")
        .contains("information_schema.schemata")
        .contains("classpath:db/migration")
        .contains("cleanDisabled(false)")
        .contains(".clean()")
        .contains(".migrate()")
        .doesNotContain("DROP DATABASE", "TRUNCATE");
    assertThat(redis)
        .contains("flushDb")
        .doesNotContain("flushAll", "FLUSHALL");
  }

  private static ValidationResetService service(
      RecordingOperations operations,
      ValidationResetGate gate
  ) {
    return new ValidationResetService(operations, gate, new IncrementingClock());
  }

  private static void assertStepStatuses(
      ValidationResetReceipt receipt,
      ValidationResetReceipt.StepStatus... statuses
  ) {
    assertThat(receipt.steps())
        .extracting(ValidationResetReceipt.StepResult::status)
        .containsExactly(statuses);
  }

  private static void assertValidationProfile(Class<?> type) {
    Profile profile = type.getAnnotation(Profile.class);
    assertThat(profile).as(type.getName() + " profile").isNotNull();
    assertThat(profile.value()).containsExactly("validation");
  }

  private static Set<String> allowedSchemaInventory() {
    LinkedHashSet<String> schemas = new LinkedHashSet<>(RESET_SCHEMAS);
    // The generation/reset fence survives business-schema clean and is never a Flyway clean target.
    schemas.add("validation_control");
    return Set.copyOf(schemas);
  }

  private static final class IncrementingClock extends Clock {

    private final AtomicLong seconds = new AtomicLong();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return WALL_CLOCK_START.plusSeconds(seconds.getAndIncrement());
    }
  }

  private static final class RecordingOperations implements ValidationResetOperations {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<Long> observedGenerations = new CopyOnWriteArrayList<>();
    private final CountDownLatch databaseResetEntered = new CountDownLatch(1);
    private final CountDownLatch allowDatabaseReset = new CountDownLatch(1);
    private final Map<UUID, StoredReset> strongReceipts = new ConcurrentHashMap<>();

    private String databaseName = "fx_validation_lab";
    private Set<String> applicationSchemas = new LinkedHashSet<>(RESET_SCHEMA_INVENTORY);
    private long nextRedisGeneration = 1L;
    private long publishedMemoryGeneration = Long.MIN_VALUE;
    private ValidationResetReceipt.Step failOnceAt;
    private boolean blockDatabaseReset;
    private volatile List<String> cleanedSchemas;
    private volatile ValidationResetCommand activeCommand;
    private volatile ValidationResetCommand interruptedCommand;
    private volatile ValidationResetCommand recoveredCommand;
    private volatile int databaseCleanCount;

    @Override
    public DatabaseInspection inspectDatabase() {
      calls.add("inspectDatabase");
      return new DatabaseInspection(databaseName, Set.copyOf(applicationSchemas));
    }

    @Override
    public ResetCommandResolution resolveResetCommand(ValidationResetCommand command) {
      StoredReset stored = strongReceipts.get(command.operationId());
      if (stored == null) {
        return ResetCommandResolution.start();
      }
      if (!stored.command().equals(command)) {
        throw new BusinessException(
            "VALIDATION_RESET_IDEMPOTENCY_CONFLICT",
            "Validation reset command conflicts with a durable operation");
      }
      return ResetCommandResolution.replay(stored.receipt());
    }

    @Override
    public Optional<ValidationResetCommand> interruptedResetCommand() {
      return Optional.ofNullable(interruptedCommand);
    }

    @Override
    public void markResetting(
        ValidationResetCommand command,
        String resetDatabaseName,
        Instant startedAt
    ) {
      activeCommand = command;
    }

    @Override
    public void recoverResetting(
        ValidationResetCommand command,
        String resetDatabaseName,
        Instant startedAt
    ) {
      calls.add("recoverResetting:" + command.operationId());
      recoveredCommand = command;
      activeCommand = command;
    }

    @Override
    public void cleanAndMigrate(List<String> schemaAllowlist) {
      calls.add("cleanAndMigrate");
      databaseCleanCount++;
      cleanedSchemas = List.copyOf(schemaAllowlist);
      databaseResetEntered.countDown();
      if (blockDatabaseReset) {
        await(allowDatabaseReset);
      }
      failIfRequested(ValidationResetReceipt.Step.DATABASE);
    }

    @Override
    public long flushRedisDatabaseAndAdvanceGeneration() {
      calls.add("flushRedisDatabaseAndAdvanceGeneration");
      failIfRequested(ValidationResetReceipt.Step.REDIS);
      return nextRedisGeneration++;
    }

    @Override
    public void clearMarketState(long generation) {
      recordGeneration("clearMarketState", generation);
      failIfRequested(ValidationResetReceipt.Step.MARKET_STATE);
    }

    @Override
    public void resetVirtualClock(long generation) {
      recordGeneration("resetVirtualClock", generation);
      failIfRequested(ValidationResetReceipt.Step.VIRTUAL_CLOCK);
    }

    @Override
    public void resetExecutionPolicy(long generation) {
      recordGeneration("resetExecutionPolicy", generation);
      failIfRequested(ValidationResetReceipt.Step.EXECUTION_POLICY);
    }

    @Override
    public void clearRuntimeRegistry(long generation) {
      recordGeneration("clearRuntimeRegistry", generation);
      failIfRequested(ValidationResetReceipt.Step.RUNTIME_REGISTRY);
    }

    @Override
    public long publishMemoryGeneration(long generation) {
      recordGeneration("publishMemoryGeneration", generation);
      failIfRequested(ValidationResetReceipt.Step.MEMORY_GENERATION);
      return publishedMemoryGeneration == Long.MIN_VALUE
          ? generation
          : publishedMemoryGeneration;
    }

    @Override
    public void persistResetReceipt(
        ValidationResetCommand command,
        ValidationResetReceipt receipt
    ) {
      if (!command.equals(activeCommand)) {
        throw new IllegalStateException("Strong reset command changed");
      }
      strongReceipts.put(command.operationId(), new StoredReset(command, receipt));
      activeCommand = null;
    }

    private void recordGeneration(String operation, long generation) {
      calls.add(operation + ":" + generation);
      observedGenerations.add(generation);
    }

    private void failIfRequested(ValidationResetReceipt.Step step) {
      if (failOnceAt == step) {
        failOnceAt = null;
        throw new IllegalStateException(FAILURE_SECRET);
      }
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting for reset test latch");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for reset test latch", exception);
      }
    }

    private record StoredReset(
        ValidationResetCommand command,
        ValidationResetReceipt receipt
    ) {
    }
  }
}
