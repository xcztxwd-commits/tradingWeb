package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.validation.service.ValidationAdministrativeDatabase.ResetControlState;
import com.fxplatform.validation.service.ValidationMarketClock.Tick;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ValidationStateServiceTest {

  private static final long GENERATION = 19L;
  private static final Instant NOW = Instant.parse("2026-07-23T03:00:00Z");

  @Test
  void scopesLatestRunQueryAndProcessLocalStateToTheReadyDurableGeneration() {
    UUID runId = UUID.randomUUID();
    Fixture fixture = readyFixture();
    ValidationStateService.RunSnapshot run = run(runId, GENERATION);
    when(fixture.jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        anyLong()))
        .thenReturn(List.of(run));
    Tick tick = new Tick(runId, GENERATION, 3L, NOW);
    CompositeTick market = tick(runId, GENERATION, 3L);
    when(fixture.marketClock.current()).thenReturn(Optional.of(tick));
    when(fixture.marketState.current()).thenReturn(Optional.of(market));
    when(fixture.policyProvider.generation()).thenReturn(GENERATION);
    when(fixture.policyProvider.current()).thenReturn(mock(DemoExecutionPolicy.class));

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(null);

    assertThat(snapshot.run()).isEqualTo(run);
    assertThat(snapshot.clock()).isEqualTo(tick);
    assertThat(snapshot.marketTickSequence()).isEqualTo(3L);
    assertThat(snapshot.executionPolicyFrozen()).isTrue();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(fixture.jdbc).query(
        sql.capture(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        org.mockito.ArgumentMatchers.eq(GENERATION));
    assertThat(sql.getValue()).contains("WHERE generation = ?");
  }

  @Test
  void requestedRunCannotEscapeTheReadyDurableGeneration() {
    UUID requestedRunId = UUID.randomUUID();
    Fixture fixture = readyFixture();
    when(fixture.jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        any(UUID.class),
        anyLong()))
        .thenReturn(List.of(run(requestedRunId, GENERATION - 1)));

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(requestedRunId);

    assertThat(snapshot.run()).isNull();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(fixture.jdbc).query(
        sql.capture(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        org.mockito.ArgumentMatchers.eq(requestedRunId),
        org.mockito.ArgumentMatchers.eq(GENERATION));
    assertThat(sql.getValue()).contains("id = ?", "generation = ?");
  }

  @Test
  void hidesRuntimeStateWhenTheExactDurableFenceCannotBeRetained() {
    Fixture fixture = readyFixture();
    when(fixture.databaseFence.tryEnterRequest(GENERATION)).thenReturn(Optional.empty());

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(null);

    assertThat(snapshot.generationCoherent()).isFalse();
    assertThat(snapshot.run()).isNull();
    assertThat(snapshot.clock()).isNull();
    assertThat(snapshot.marketTickSequence()).isNull();
    verify(fixture.jdbc, never()).query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        anyLong());
  }

  @Test
  void rechecksLocalCoherenceAfterRetainingTheDurableFence() {
    Fixture fixture = readyFixture();
    when(fixture.resetGate.state())
        .thenReturn(ValidationResetGate.State.READY, ValidationResetGate.State.RESETTING);

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(null);

    assertThat(snapshot.memoryResetState()).isEqualTo(ValidationResetGate.State.RESETTING);
    assertThat(snapshot.generationCoherent()).isFalse();
    assertThat(snapshot.run()).isNull();
    verify(fixture.jdbc, never()).query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        anyLong());
    verify(fixture.requestFence).close();
  }

  @Test
  void hidesRunAndProcessLocalStateWhenResetGenerationsAreNotCoherent() {
    UUID staleRunId = UUID.randomUUID();
    Fixture fixture = fixture(
        new ResetControlState(GENERATION, "READY", GENERATION, GENERATION - 1),
        ValidationResetGate.State.READY,
        GENERATION);
    when(fixture.marketClock.current())
        .thenReturn(Optional.of(new Tick(staleRunId, GENERATION - 1, 4L, NOW)));
    when(fixture.marketState.current())
        .thenReturn(Optional.of(tick(staleRunId, GENERATION - 1, 4L)));
    when(fixture.policyProvider.generation()).thenReturn(GENERATION - 1);
    when(fixture.policyProvider.current()).thenReturn(mock(DemoExecutionPolicy.class));

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(null);

    assertThat(snapshot.run()).isNull();
    assertThat(snapshot.clock()).isNull();
    assertThat(snapshot.marketTickSequence()).isNull();
    assertThat(snapshot.executionPolicyFrozen()).isFalse();
  }

  @Test
  void readyStateRequiresTheLiveRedisGenerationMarker() throws Exception {
    String source = java.nio.file.Files.readString(java.nio.file.Path.of(
        "src/main/java/com/fxplatform/validation/service/ValidationStateService.java"));

    assertThat(source)
        .contains("ValidationRedisResetter")
        .contains("currentGeneration()")
        .contains("observedRedisGeneration");
  }

  @Test
  void readyRuntimeQueryMustRetainTheExactDurableGenerationFence() throws Exception {
    String source = java.nio.file.Files.readString(java.nio.file.Path.of(
        "src/main/java/com/fxplatform/validation/service/ValidationStateService.java"));

    assertThat(source)
        .contains("databaseFence.tryEnterRequest(durable.generation())")
        .contains("try (ValidationDatabaseFence.RequestFence");
  }

  @Test
  void hidesRunAndProcessLocalStateWhenTheObservedRedisMarkerDrifts() {
    Fixture fixture = readyFixture();
    when(fixture.redisResetter.currentGeneration()).thenReturn(GENERATION + 1L);

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(null);

    assertThat(snapshot.generationCoherent()).isFalse();
    assertThat(snapshot.observedRedisGeneration()).isEqualTo(GENERATION + 1L);
    assertThat(snapshot.run()).isNull();
    assertThat(snapshot.clock()).isNull();
    assertThat(snapshot.marketTickSequence()).isNull();
    assertThat(snapshot.executionPolicyFrozen()).isFalse();
    verify(fixture.jdbc, never()).query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        anyLong());
  }

  @Test
  void hidesClockMarketAndPolicyThatBelongToAnotherRun() {
    UUID selectedRunId = UUID.randomUUID();
    UUID otherRunId = UUID.randomUUID();
    Fixture fixture = readyFixture();
    when(fixture.jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<ValidationStateService.RunSnapshot>>any(),
        anyLong()))
        .thenReturn(List.of(run(selectedRunId, GENERATION)));
    when(fixture.marketClock.current())
        .thenReturn(Optional.of(new Tick(otherRunId, GENERATION, 5L, NOW)));
    when(fixture.marketState.current())
        .thenReturn(Optional.of(tick(otherRunId, GENERATION, 5L)));
    when(fixture.policyProvider.generation()).thenReturn(GENERATION);
    when(fixture.policyProvider.current()).thenReturn(mock(DemoExecutionPolicy.class));

    ValidationStateService.Snapshot snapshot = fixture.service.snapshot(null);

    assertThat(snapshot.run().runId()).isEqualTo(selectedRunId);
    assertThat(snapshot.clock()).isNull();
    assertThat(snapshot.marketTickSequence()).isNull();
    assertThat(snapshot.executionPolicyFrozen()).isFalse();
  }

  private static Fixture readyFixture() {
    return fixture(
        new ResetControlState(GENERATION, "READY", GENERATION, GENERATION),
        ValidationResetGate.State.READY,
        GENERATION);
  }

  private static Fixture fixture(
      ResetControlState durable,
      ValidationResetGate.State memoryState,
      long memoryGeneration
  ) {
    ValidationAdministrativeDatabase administrativeDatabase =
        mock(ValidationAdministrativeDatabase.class);
    ValidationResetGate resetGate = mock(ValidationResetGate.class);
    ValidationMarketClock marketClock = mock(ValidationMarketClock.class);
    ValidationMarketState marketState = mock(ValidationMarketState.class);
    ValidationDemoExecutionPolicyProvider policyProvider =
        mock(ValidationDemoExecutionPolicyProvider.class);
    ValidationRedisResetter redisResetter = mock(ValidationRedisResetter.class);
    ValidationDatabaseFence databaseFence = mock(ValidationDatabaseFence.class);
    ValidationDatabaseFence.RequestFence requestFence =
        mock(ValidationDatabaseFence.RequestFence.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(administrativeDatabase.currentResetState()).thenReturn(durable);
    when(resetGate.state()).thenReturn(memoryState);
    when(resetGate.generation()).thenReturn(memoryGeneration);
    when(redisResetter.currentGeneration()).thenReturn(durable.generation());
    when(databaseFence.tryEnterRequest(durable.generation()))
        .thenReturn(Optional.of(requestFence));
    return new Fixture(
        new ValidationStateService(
            administrativeDatabase,
            resetGate,
            marketClock,
            marketState,
            policyProvider,
            redisResetter,
            databaseFence,
            jdbc),
        resetGate,
        marketClock,
        marketState,
        policyProvider,
        redisResetter,
        databaseFence,
        requestFence,
        jdbc);
  }

  private static ValidationStateService.RunSnapshot run(UUID runId, long generation) {
    return new ValidationStateService.RunSnapshot(
        runId,
        generation,
        "request-fingerprint",
        ValidationRunRuntimeStore.State.RUNNING,
        false,
        false,
        2L,
        NOW,
        12L,
        null,
        NOW,
        false);
  }

  private static CompositeTick tick(UUID runId, long generation, long sequence) {
    return new CompositeTick(
        runId,
        generation,
        sequence,
        NOW,
        "fingerprint-" + sequence,
        List.of(),
        List.of());
  }

  private record Fixture(
      ValidationStateService service,
      ValidationResetGate resetGate,
      ValidationMarketClock marketClock,
      ValidationMarketState marketState,
      ValidationDemoExecutionPolicyProvider policyProvider,
      ValidationRedisResetter redisResetter,
      ValidationDatabaseFence databaseFence,
      ValidationDatabaseFence.RequestFence requestFence,
      JdbcTemplate jdbc
  ) {
  }
}
