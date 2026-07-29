package com.fxplatform.validation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.validation.service.ValidationLoopbackHttpClient;
import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationRunEngine;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunRuntimeStore;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationRunHardeningContractTest {

  @Test
  void engineHasExactlyTheFourFrozenControlPlaneDependencies() {
    Constructor<?>[] publicConstructors = Arrays.stream(ValidationRunEngine.class.getConstructors())
        .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
        .toArray(Constructor<?>[]::new);

    assertThat(publicConstructors).singleElement().satisfies(constructor ->
        assertThat(constructor.getParameterTypes()).containsExactly(
            ValidationLoopbackHttpClient.class,
            ValidationRunRuntimeStore.class,
            ValidationMarketClock.class,
            ValidationRunEventStore.class));
  }

  @Test
  void frozenRunCarriesBalancesAndAccountSettingsAndRejectsUnscheduledActions() {
    Set<String> fields = Arrays.stream(StartRequest.class.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    assertThat(fields).contains("initialBalances", "accountSettings");

    StartRequest valid = ValidationRunEngineIT.request();
    PublicAction outsidePath = new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000099"),
        2L,
        1L,
        PublicActionType.CANCEL_ALL,
        "outside-path",
        Map.of());

    assertThatThrownBy(() -> new StartRequest(
        valid.runId(),
        valid.generation(),
        valid.requestFingerprint(),
        valid.seed(),
        valid.virtualStart(),
        valid.executionPolicy(),
        valid.ticks(),
        List.of(outsidePath),
        valid.speedMultiplier()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tick");
  }

  @Test
  void publicActionRejectsNetworkSensitiveAndEngineOwnedFieldsBeforePersistence() {
    Map<String, Object> malicious = Map.of(
        "symbol", "BTCUSDT",
        "url", "https://attacker.invalid",
        "headers", Map.of("Authorization", "secret"),
        "accountId", UUID.randomUUID(),
        "nested", Map.of("apiToken", "secret"));

    assertThatThrownBy(() -> new PublicAction(
        UUID.fromString("00000000-0000-0000-0000-000000000098"),
        1L,
        1L,
        PublicActionType.PLACE_ORDER,
        "malicious-action",
        malicious))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void frozenRunRejectsAnIncompleteMarketPathBeforeItCanBePersisted() {
    StartRequest valid = ValidationRunEngineIT.request();
    CompositeTick incomplete = new CompositeTick(
        valid.runId(),
        valid.generation(),
        1L,
        valid.virtualStart().plusSeconds(1),
        "incomplete-tick",
        List.of(),
        List.of());

    assertThatThrownBy(() -> new StartRequest(
        valid.runId(),
        valid.generation(),
        valid.requestFingerprint(),
        valid.seed(),
        valid.virtualStart(),
        valid.executionPolicy(),
        valid.initialBalances(),
        valid.accountSettings(),
        List.of(incomplete),
        List.of(),
        valid.speedMultiplier()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Tick");
  }

  @Test
  void runtimeContractReturnsALeaseAndFencesEveryWorkerMutation() throws Exception {
    Method claim = ValidationRunRuntimeStore.class.getMethod("claim", UUID.class);
    assertThat(claim.getReturnType().getSimpleName()).isEqualTo("LeaseClaim");

    Set<String> intentFields = Arrays.stream(
            ValidationRunRuntimeStore.OperationIntent.class.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    assertThat(intentFields).contains("state", "result");

    for (String mutation : List.of(
        "persistIntent", "completeIntent", "completeBoundary", "transition")) {
      assertThat(Arrays.stream(ValidationRunRuntimeStore.class.getMethods())
          .filter(method -> method.getName().equals(mutation))
          .flatMap(method -> Arrays.stream(method.getParameterTypes()))
          .map(Class::getSimpleName))
          .as("%s must be lease fenced", mutation)
          .contains("LeaseClaim");
    }
  }

  @Test
  void migrationStoresRunIdentityAndKeepsBlockedRunsGenerationExclusive() throws Exception {
    String migration = Files.readString(Path.of(
        "src/main/resources/db/migration/V66__validation_runtime.sql"));

    assertThat(migration)
        .contains("user_id UUID")
        .contains("account_id UUID")
        .contains("UNIQUE (generation)")
        .contains("'RECOVERY_BLOCKED'")
        .contains("CHECK (sequence > 0)")
        .contains("CHECK (tick_sequence > 0)");
  }

  @Test
  void eventPagesCannotReadPastTheirReportedHighWatermark() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/persistence/JdbcValidationRunEventStore.java"));
    assertThat(source)
        .contains("sequence <= ?")
        .contains("status.highWatermark()")
        .contains("limit + 1");
  }

  @Test
  void pauseDoesNotPretendToControlStatesWithoutAWorker() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/persistence/JdbcValidationRunRuntimeStore.java"));
    String pause = source.substring(
        source.indexOf("public void requestPause"),
        source.indexOf("public void requestResume"));

    assertThat(pause)
        .contains("PAUSABLE_STATES")
        .doesNotContain("CONTROLLABLE_STATES");
  }
}
