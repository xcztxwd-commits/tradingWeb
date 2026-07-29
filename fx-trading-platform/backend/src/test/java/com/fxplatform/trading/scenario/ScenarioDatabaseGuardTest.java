package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ScenarioDatabaseGuardTest {

  private static final String SAFE_DATABASE = "fx_scenario_it_20260716_123456_abcdef12";
  private static final String SAFE_URL =
      "jdbc:postgresql://localhost:5432/" + SAFE_DATABASE;

  @Test
  void acceptsTheExactOwnedLocalScenarioDatabase() {
    assertThatCode(() -> ScenarioDatabaseGuard.validateBeforeStartup(
        new String[]{"scenario-it"}, SAFE_URL, SAFE_DATABASE))
        .doesNotThrowAnyException();

    assertThatCode(() -> ScenarioDatabaseGuard.validateConnectedDatabase(
        SAFE_DATABASE, SAFE_DATABASE))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsTheExistingApplicationDatabaseBeforeFlywayCanRun() {
    assertThatThrownBy(() -> ScenarioDatabaseGuard.validateBeforeStartup(
        new String[]{"scenario-it"},
        "jdbc:postgresql://localhost:5432/fx_platform",
        "fx_platform"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refusing unsafe scenario database");
  }

  @Test
  void rejectsAConnectionWithoutTheScenarioProfile() {
    assertThatThrownBy(() -> ScenarioDatabaseGuard.validateBeforeStartup(
        new String[]{"test"}, SAFE_URL, SAFE_DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("scenario-it");
  }

  @Test
  void rejectsRemoteOrMismatchedDatabaseTargets() {
    assertThatThrownBy(() -> ScenarioDatabaseGuard.validateBeforeStartup(
        new String[]{"scenario-it"},
        "jdbc:postgresql://db.example.test:5432/" + SAFE_DATABASE,
        SAFE_DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("localhost");

    assertThatThrownBy(() -> ScenarioDatabaseGuard.validateBeforeStartup(
        new String[]{"scenario-it"}, SAFE_URL,
        "fx_scenario_it_20260716_123456_deadbeef"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void rejectsAConnectedDatabaseDifferentFromTheOwnedName() {
    assertThatThrownBy(() -> ScenarioDatabaseGuard.validateConnectedDatabase(
        SAFE_DATABASE, "postgres"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connected database");
  }

  @Test
  void scenarioProfileUsesOnlyInjectedDatabaseAndDisablesBackgroundWork()
      throws IOException {
    String profile = Files.readString(
        Path.of("src/test/resources/application-scenario-it.yml"),
        StandardCharsets.UTF_8);

    org.assertj.core.api.Assertions.assertThat(profile)
        .contains(
            "name: ${SCENARIO_DATABASE_NAME}",
            "url: ${SCENARIO_DATABASE_URL}",
            "username: ${SCENARIO_DATABASE_USERNAME:postgres}",
            "password: ${SCENARIO_DATABASE_PASSWORD}",
            "mode: demo",
            "pending-order-execution-enabled: false",
            "protective-order-execution-enabled: false")
        .containsSubsequence(
            "funding:",
            "enabled: false",
            "fx-financing:",
            "enabled: false",
            "liquidation:",
            "enabled: false")
        .doesNotContain("fx_platform", "execution.mode=live");
  }
}
