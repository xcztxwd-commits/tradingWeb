package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.validation.persistence.JdbcValidationRunEventStore;
import com.fxplatform.validation.persistence.JdbcValidationRunRuntimeStore;
import com.fxplatform.validation.service.ValidationAdministrativeDatabase;
import com.fxplatform.validation.service.ValidationDatabaseFence;
import com.fxplatform.validation.service.ValidationDemoExecutionPolicyProvider;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationMarketState;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationLoopbackRequestActivityBarrier;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationResetReceipt;
import com.fxplatform.validation.service.ValidationResetReceipt.Step;
import com.fxplatform.validation.service.ValidationResetReceipt.StepResult;
import com.fxplatform.validation.service.ValidationResetReceipt.StepStatus;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore.EventPage;
import com.fxplatform.validation.service.ValidationRunEventStore.EventWrite;
import com.fxplatform.validation.service.ValidationRunRuntimeStore;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Boundary;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Completion;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.LeaseClaim;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationIntent;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationState;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL 16 contract probe for the durable validation runtime introduced by V66.
 *
 * <p>The stores are obtained through Spring transaction proxies so event conflicts exercise the
 * same transaction boundary as the validation profile, rather than a test-only TransactionTemplate.
 */
class ValidationRuntimePostgresIT {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000566");
  private static final long GENERATION = 66L;
  private static final Instant START = Instant.parse("2026-07-23T06:00:00Z");

  @Test
  void v66RuntimeIsGenerationFencedLeaseFencedAtomicAndReplaySafeOnPostgres16() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareReadyGeneration(jdbc);
      assertV66SchemaContracts(jdbc);

      ValidationResetGate gate = new ValidationResetGate();
      gate.hydrateReady(GENERATION);
      ValidationMarketState marketState = new ValidationMarketState();
      marketState.reset(GENERATION);
      ValidationDemoExecutionPolicyProvider policies =
          new ValidationDemoExecutionPolicyProvider();
      policies.reset(GENERATION);

      try (AnnotationConfigApplicationContext context = transactionalContext(
          jdbc.getDataSource(), gate, marketState, policies)) {
        JdbcValidationRunEventStore events =
            context.getBean(JdbcValidationRunEventStore.class);
        JdbcValidationRunRuntimeStore runtime =
            context.getBean(JdbcValidationRunRuntimeStore.class);
        assertThat(AopUtils.isAopProxy(events)).isTrue();
        assertThat(AopUtils.isAopProxy(runtime)).isTrue();

        StartRequest original = request(RUN_ID, GENERATION, "1.0", "0.0002");
        EventWrite accepted = event("run:accepted", "accepted-v1", "RUN_ACCEPTED", Map.of());
        assertThat(runtime.accept(original, accepted)).isTrue();

        StartRequest numericScaleReplay =
            request(RUN_ID, GENERATION, "1.00", "0.000200");
        assertThat(runtime.accept(numericScaleReplay, accepted)).isFalse();
        assertThat(count(jdbc, "validation_runtime.run_executions")).isEqualTo(1L);
        assertThat(count(jdbc, "validation_runtime.run_events")).isEqualTo(1L);

        UUID secondRun = UUID.fromString("00000000-0000-0000-0000-000000000567");
        assertBusinessCode(
            () -> runtime.accept(
                request(secondRun, GENERATION, "1.0", "0.0002"),
                new EventWrite(
                    secondRun,
                    "run:accepted",
                    "accepted-v1",
                    "RUN_ACCEPTED",
                    START,
                    null,
                    Map.of())),
            "VALIDATION_RUN_GENERATION_OCCUPIED");
        assertThat(count(jdbc, "validation_runtime.run_executions")).isEqualTo(1L);

        LeaseClaim firstLease = runtime.claim(RUN_ID);
        LeaseClaim forgedLease =
            new LeaseClaim(RUN_ID, GENERATION, firstLease.owner(), UUID.randomUUID());
        assertBusinessCode(() -> runtime.heartbeat(forgedLease), "VALIDATION_RUN_LEASE_LOST");
        assertThatCode(() -> runtime.heartbeat(firstLease)).doesNotThrowAnyException();
        jdbc.update(
            """
                UPDATE validation_runtime.run_executions
                   SET lease_until = now() - interval '1 second'
                 WHERE id = ?
            """,
            RUN_ID);
        assertThat(runtime.takeOverRecoverableRunIds()).containsExactly(RUN_ID);
        try (AnnotationConfigApplicationContext competingContext = transactionalContext(
            jdbc.getDataSource(), readyGate(), readyMarketState(), readyPolicies())) {
          JdbcValidationRunRuntimeStore competitor =
              competingContext.getBean(JdbcValidationRunRuntimeStore.class);
          assertBusinessCode(
              () -> competitor.claim(RUN_ID),
              "VALIDATION_RUN_NOT_CLAIMABLE");
        }
        assertThatCode(() -> runtime.heartbeat(firstLease)).doesNotThrowAnyException();
        runtime.releaseLease(firstLease);
        assertBusinessCode(() -> runtime.heartbeat(firstLease), "VALIDATION_RUN_LEASE_LOST");
        LeaseClaim currentLease = runtime.claim(RUN_ID);
        assertThat(currentLease.token()).isNotEqualTo(firstLease.token());

        Command firstCommand = command(new BigDecimal("1.0"));
        OperationIntent intent = runtime.persistIntent(currentLease, firstCommand);
        assertThat(intent.state()).isEqualTo(OperationState.NEW);
        assertThat(runtime.persistIntent(
            currentLease,
            command(new BigDecimal("1.00"))).state()).isEqualTo(OperationState.UNFINISHED);

        EventWrite reservedOperationEvent =
            event("operation:complete", "operation-event-v1", "OPERATION_COMPLETED", Map.of());
        events.append(reservedOperationEvent);
        HttpResult frozenResult =
            new HttpResult(204, "correlation-204", Map.of("filled", new BigDecimal("1.0")));
        EventWrite conflictingOperationEvent =
            event("operation:complete", "operation-event-v2", "OPERATION_COMPLETED", Map.of());
        assertBusinessCode(
            () -> runtime.completeIntent(
                currentLease,
                intent.id(),
                Completion.SUCCEEDED,
                frozenResult,
                List.of(conflictingOperationEvent)),
            "VALIDATION_EVENT_CONFLICT");
        assertThat(runtime.persistIntent(currentLease, firstCommand).state())
            .isEqualTo(OperationState.UNFINISHED);

        EventWrite completedOperationEvent =
            event("operation:completed:2", "operation-completed-v1", "OPERATION_COMPLETED",
                Map.of("status", 204));
        runtime.completeIntent(
            currentLease,
            intent.id(),
            Completion.SUCCEEDED,
            frozenResult,
            List.of(completedOperationEvent));
        OperationIntent completed =
            runtime.persistIntent(currentLease, command(new BigDecimal("1.000")));
        assertThat(completed.state()).isEqualTo(OperationState.COMPLETED);
        assertThat(completed.result().status()).isEqualTo(204);
        assertThat(completed.result().correlationId()).isEqualTo("correlation-204");
        assertThat(new BigDecimal(completed.result().body().get("filled").toString()))
            .isEqualByComparingTo("1.0");
        runtime.completeIntent(
            currentLease,
            intent.id(),
            Completion.SUCCEEDED,
            new HttpResult(
                204,
                "correlation-204",
                Map.of("filled", new BigDecimal("1.0000"))),
            List.of());
        assertBusinessCode(
            () -> runtime.completeIntent(
                currentLease,
                intent.id(),
                Completion.SUCCEEDED,
                new HttpResult(200, "correlation-204", Map.of("filled", 1)),
                List.of()),
            "VALIDATION_OPERATION_CONFLICT");

        Command uncertainCommand = command(
            "operation:uncertain:1",
            "operation-uncertain-v1",
            new BigDecimal("2.0"));
        OperationIntent uncertain = runtime.persistIntent(currentLease, uncertainCommand);
        HttpResult firstObservation = new HttpResult(
            500,
            "correlation-500",
            Map.of("code", "UPSTREAM_FAILURE", "message", "sanitized"));
        EventWrite observationEvent = new EventWrite(
            RUN_ID,
            "api-observation:uncertain-1",
            "api-observation-v1",
            "API_TRACE",
            START.plusSeconds(1),
            firstObservation.correlationId(),
            Map.of(
                "operation", "PUBLIC_ACTION",
                "outcome", "UNCERTAIN_HTTP_RESPONSE",
                "status", 500,
                "response", firstObservation.body()));
        runtime.recordFirstHttpObservation(
            currentLease,
            uncertain.id(),
            firstObservation,
            observationEvent);
        assertThat(jdbc.queryForObject(
            "SELECT http_status FROM validation_runtime.operations WHERE id = ?",
            Integer.class,
            uncertain.id())).isEqualTo(500);
        assertThat(jdbc.queryForObject(
            "SELECT correlation_id FROM validation_runtime.operations WHERE id = ?",
            String.class,
            uncertain.id())).isEqualTo("correlation-500");
        assertThat(jdbc.queryForObject(
            "SELECT response_json::text FROM validation_runtime.operations WHERE id = ?",
            String.class,
            uncertain.id())).contains("UPSTREAM_FAILURE");

        long eventsAfterFirstObservation = count(jdbc, "validation_runtime.run_events");
        assertThatCode(() -> runtime.recordFirstHttpObservation(
            currentLease,
            uncertain.id(),
            new HttpResult(
                503,
                "correlation-503",
                Map.of("code", "DIFFERENT_UPSTREAM_FAILURE")),
            new EventWrite(
                RUN_ID,
                "api-observation:uncertain-1",
                "api-observation-v2",
                "API_TRACE",
                START.plusSeconds(1),
                "correlation-503",
                Map.of("status", 503))))
            .doesNotThrowAnyException();
        assertThat(count(jdbc, "validation_runtime.run_events"))
            .isEqualTo(eventsAfterFirstObservation);
        assertThat(jdbc.queryForObject(
            "SELECT http_status FROM validation_runtime.operations WHERE id = ?",
            Integer.class,
            uncertain.id())).isEqualTo(500);

        HttpResult recoveredResult =
            new HttpResult(200, "correlation-recovered", Map.of("recovered", true));
        runtime.completeIntent(
            currentLease,
            uncertain.id(),
            Completion.RECOVERED,
            recoveredResult,
            List.of(event(
                "api:uncertain-final",
                "api-uncertain-final-v1",
                "API_TRACE",
                Map.of("status", 200))));
        OperationIntent recoveredOperation =
            runtime.persistIntent(currentLease, uncertainCommand);
        assertThat(recoveredOperation.state()).isEqualTo(OperationState.RECOVERED);
        assertThat(recoveredOperation.result()).isEqualTo(recoveredResult);

        Command failedCommand = command(
            "operation:failed:1",
            "operation-failed-v1",
            new BigDecimal("3.0"));
        OperationIntent failing = runtime.persistIntent(currentLease, failedCommand);
        HttpResult rejection = new HttpResult(
            422,
            "correlation-422",
            Map.of("code", "ORDER_REJECTED", "message", "sanitized"));
        EventWrite failedEvent = event(
            "api:failed:1",
            "api-failed-v1",
            "API_TRACE",
            Map.of("status", 422, "response", rejection.body()));
        runtime.failIntent(
            currentLease,
            failing.id(),
            "VALIDATION_LOOPBACK_FAILED",
            "VALIDATION_LOOPBACK_FAILED",
            rejection,
            List.of(failedEvent));
        assertThat(runtime.persistIntent(currentLease, failedCommand).state())
            .isEqualTo(OperationState.FAILED);
        assertThat(jdbc.queryForObject(
            "SELECT http_status FROM validation_runtime.operations WHERE id = ?",
            Integer.class,
            failing.id())).isEqualTo(422);
        assertThat(jdbc.queryForObject(
            "SELECT correlation_id FROM validation_runtime.operations WHERE id = ?",
            String.class,
            failing.id())).isEqualTo("correlation-422");
        assertThat(jdbc.queryForObject(
            "SELECT response_json::text FROM validation_runtime.operations WHERE id = ?",
            String.class,
            failing.id())).contains("ORDER_REJECTED");
        assertThatCode(() -> runtime.failIntent(
            currentLease,
            failing.id(),
            "VALIDATION_LOOPBACK_FAILED",
            "VALIDATION_LOOPBACK_FAILED",
            rejection,
            List.of(failedEvent))).doesNotThrowAnyException();
        assertBusinessCode(
            () -> runtime.failIntent(
                currentLease,
                failing.id(),
                "VALIDATION_LOOPBACK_FAILED",
                "VALIDATION_LOOPBACK_FAILED",
                new HttpResult(
                    409,
                    "correlation-409",
                    Map.of("code", "DIFFERENT_REJECTION")),
                List.of(failedEvent)),
            "VALIDATION_OPERATION_CONFLICT");

        EventWrite numericEvent =
            event("event:numeric", "numeric-v1", "NUMERIC_EVENT",
                Map.of("amount", new BigDecimal("2.0")));
        long numericSequence = events.append(numericEvent).sequence();
        assertThat(events.append(event(
            "event:numeric",
            "numeric-v1",
            "NUMERIC_EVENT",
            Map.of("amount", new BigDecimal("2.00")))).sequence()).isEqualTo(numericSequence);
        assertBusinessCode(
            () -> events.append(event(
                "event:numeric",
                "numeric-v2",
                "NUMERIC_EVENT",
                Map.of("amount", new BigDecimal("2.00")))),
            "VALIDATION_EVENT_CONFLICT");

        long beforeBoundarySequence = jdbc.queryForObject(
            "SELECT next_event_sequence FROM validation_runtime.run_executions WHERE id = ?",
            Long.class,
            RUN_ID);
        EventWrite reservedBoundaryEvent =
            event("boundary:1", "boundary-v1", "BOUNDARY_COMPLETED", Map.of());
        events.append(reservedBoundaryEvent);
        Boundary boundary = new Boundary(
            RUN_ID,
            GENERATION,
            1L,
            START.plusSeconds(1),
            "tick-1",
            Integer.toHexString(original.executionPolicy().hashCode()));
        assertBusinessCode(
            () -> runtime.completeBoundary(
                currentLease,
                boundary,
                List.of(event(
                    "boundary:1",
                    "boundary-v2",
                    "BOUNDARY_COMPLETED",
                    Map.of()))),
            "VALIDATION_EVENT_CONFLICT");
        assertThat(jdbc.queryForObject(
            """
                SELECT last_completed_tick_sequence
                  FROM validation_runtime.run_executions
                 WHERE id = ?
                """,
            Long.class,
            RUN_ID)).isEqualTo(-1L);
        assertThat(jdbc.queryForObject(
            "SELECT next_event_sequence FROM validation_runtime.run_executions WHERE id = ?",
            Long.class,
            RUN_ID)).isEqualTo(beforeBoundarySequence + 1L);

        runtime.completeBoundary(
            currentLease,
            boundary,
            List.of(event(
                "boundary:completed:1",
                "boundary-completed-v1",
                "BOUNDARY_COMPLETED",
                Map.of("tickSequence", 1))));
        Boundary recovered = runtime.lastCompletedBoundary(currentLease).orElseThrow();
        assertThat(recovered).isEqualTo(boundary);
        runtime.restore(currentLease, recovered);
        assertThat(marketState.current()).contains(original.ticks().getFirst());
        assertThat(policies.current()).isEqualTo(original.executionPolicy());

        List<Long> durableSequences = jdbc.queryForList(
            """
                SELECT sequence
                  FROM validation_runtime.run_events
                 WHERE run_id = ?
                 ORDER BY sequence
                """,
            Long.class,
            RUN_ID);
        assertThat(durableSequences)
            .containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1L, durableSequences.size())
                    .boxed()
                    .toList());

        long highWatermark = jdbc.queryForObject(
            "SELECT next_event_sequence FROM validation_runtime.run_executions WHERE id = ?",
            Long.class,
            RUN_ID);
        jdbc.update(
            """
                INSERT INTO validation_runtime.run_events (
                  run_id, generation, sequence, durable_event_key, request_fingerprint,
                  event_type, payload_json
                ) VALUES (?, ?, ?, 'rogue-future', 'rogue-v1', 'ROGUE', '{}'::jsonb)
                """,
            RUN_ID,
            GENERATION,
            highWatermark + 100L);
        EventPage bounded = events.eventsAfter(RUN_ID, 0L, 200);
        assertThat(bounded.highWatermark()).isEqualTo(highWatermark);
        assertThat(bounded.events())
            .extracting(com.fxplatform.validation.service.ValidationRunEventStore.RunEvent::sequence)
            .allMatch(sequence -> sequence <= bounded.highWatermark())
            .doesNotContain(highWatermark + 100L);

        runtime.transition(
            currentLease,
            State.RECOVERY_BLOCKED,
            "UNCERTAIN_OPERATION_OUTCOME",
            event(
                "run:recovery-blocked",
                "recovery-blocked-v1",
                "RUN_STATE_CHANGED",
                Map.of("state", "RECOVERY_BLOCKED")));
        assertBusinessCode(
            () -> runtime.requestPause(RUN_ID),
            "VALIDATION_RUN_CONTROL_REJECTED");
        assertThat(jdbc.queryForObject(
            "SELECT pause_requested FROM validation_runtime.run_executions WHERE id = ?",
            Boolean.class,
            RUN_ID)).isFalse();
      }

      assertV66ChecksRejectInvalidRows(jdbc);
    }
  }

  @Test
  void nanosecondVirtualTimeHasExactEventReplayAfterPostgresRoundTrip() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareReadyGeneration(jdbc);
      ValidationResetGate gate = readyGate();
      ValidationMarketState marketState = readyMarketState();
      ValidationDemoExecutionPolicyProvider policies = readyPolicies();
      UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000568");
      Instant nanosecondStart = Instant.parse("2026-07-23T06:00:00.123456789Z");

      try (AnnotationConfigApplicationContext context = transactionalContext(
          jdbc.getDataSource(), gate, marketState, policies)) {
        JdbcValidationRunRuntimeStore runtime =
            context.getBean(JdbcValidationRunRuntimeStore.class);
        StartRequest request = requestAt(runId, nanosecondStart, 1);
        EventWrite accepted = eventAt(
            runId,
            nanosecondStart,
            "run:accepted",
            "accepted-nanosecond-v1",
            "RUN_ACCEPTED",
            Map.of());

        assertThat(runtime.accept(request, accepted)).isTrue();
        assertThat(runtime.accept(request, accepted)).isFalse();
        assertThat(count(jdbc, "validation_runtime.run_events")).isEqualTo(1L);
      }
    }
  }

  @Test
  void nanosecondBoundaryRecoveryContinuesFromTheFrozenNextTick() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareReadyGeneration(jdbc);
      ValidationResetGate gate = readyGate();
      ValidationMarketState marketState = readyMarketState();
      ValidationDemoExecutionPolicyProvider policies = readyPolicies();
      UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000569");
      Instant nanosecondStart = Instant.parse("2026-07-23T06:00:00.123456789Z");

      try (AnnotationConfigApplicationContext context = transactionalContext(
          jdbc.getDataSource(), gate, marketState, policies)) {
        JdbcValidationRunRuntimeStore runtime =
            context.getBean(JdbcValidationRunRuntimeStore.class);
        StartRequest request = requestAt(runId, nanosecondStart, 2);
        runtime.accept(
            request,
            eventAt(
                runId,
                nanosecondStart,
                "run:accepted",
                "accepted-boundary-nanosecond-v1",
                "RUN_ACCEPTED",
                Map.of()));
        LeaseClaim lease = runtime.claim(runId);
        CompositeTick completedTick = request.ticks().getFirst();
        Boundary boundary = new Boundary(
            runId,
            GENERATION,
            completedTick.sequence(),
            completedTick.virtualTime(),
            completedTick.fingerprint(),
            Integer.toHexString(request.executionPolicy().hashCode()));
        runtime.completeBoundary(
            lease,
            boundary,
            List.of(eventAt(
                runId,
                completedTick.virtualTime(),
                "boundary:nanosecond:1",
                "boundary-nanosecond-v1",
                "BOUNDARY_COMPLETED",
                Map.of("tickSequence", 1))));

        Boundary recovered = runtime.lastCompletedBoundary(lease).orElseThrow();
        runtime.restore(lease, recovered);
        ValidationMarketClock recoveredClock = new ValidationMarketClock();
        recoveredClock.reset(GENERATION);
        recoveredClock.restore(
            recovered.runId(),
            recovered.generation(),
            recovered.tickSequence(),
            recovered.virtualTime());
        ValidationMarketClock.Tick next = recoveredClock.advance(runId, GENERATION);

        SoftAssertions.assertSoftly(softly -> {
          softly.assertThat(recovered.virtualTime())
              .isEqualTo(completedTick.virtualTime());
          softly.assertThat(next.virtualTime())
              .isEqualTo(request.ticks().get(1).virtualTime());
          softly.assertThat(marketState.current()).contains(completedTick);
        });
      }
    }
  }

  @Test
  void durableResetOwnershipRejectsASecondInstanceAndNonOwnerCompletion() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      DataSource dataSource = jdbc.getDataSource();
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      ValidationAdministrativeDatabase first =
          new ValidationAdministrativeDatabase(dataSource, objectMapper);
      ValidationAdministrativeDatabase second =
          new ValidationAdministrativeDatabase(dataSource, objectMapper);
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
      Instant startedAt = Instant.parse("2026-07-23T06:00:00Z");

      first.markResetting(databaseName, startedAt);
      UUID firstOwner = jdbc.queryForObject(
          "SELECT reset_id FROM validation_control.reset_state WHERE singleton_key = 1",
          UUID.class);

      assertThatThrownBy(() -> second.markResetting(databaseName, startedAt.plusSeconds(1)))
          .isInstanceOf(RuntimeException.class);
      assertThat(jdbc.queryForObject(
          "SELECT reset_id FROM validation_control.reset_state WHERE singleton_key = 1",
          UUID.class)).isEqualTo(firstOwner);
      assertThatThrownBy(() -> second.persistResetReceipt(successfulResetReceipt(
          databaseName,
          startedAt.plusSeconds(1),
          GENERATION + 1L)))
          .isInstanceOf(RuntimeException.class);

      first.persistResetReceipt(successfulResetReceipt(
          databaseName,
          startedAt,
          first.currentResetPlan().targetGeneration()));
      assertThat(jdbc.queryForMap(
          """
              SELECT state, generation, reset_id
                FROM validation_control.reset_state
               WHERE singleton_key = 1
              """))
          .containsEntry("state", "READY")
          .containsEntry("generation", 1L)
          .containsEntry("reset_id", firstOwner);
    }
  }

  @Test
  void crashedResetOwnerCanBeReplacedImmediatelyWithoutManualDatabaseRepair() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      DataSource dataSource = jdbc.getDataSource();
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      ValidationAdministrativeDatabase first =
          new ValidationAdministrativeDatabase(dataSource, objectMapper);
      ValidationAdministrativeDatabase replacement =
          new ValidationAdministrativeDatabase(dataSource, objectMapper);
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
      Instant startedAt = Instant.parse("2026-07-23T06:00:00Z");

      first.markResetting(databaseName, startedAt);
      ValidationAdministrativeDatabase.ResetPlan crashedPlan = first.currentResetPlan();
      int crashedBackendPid = first.resetOwnerBackendPid();
      UUID crashedOwner = jdbc.queryForObject(
          "SELECT reset_id FROM validation_control.reset_state WHERE singleton_key = 1",
          UUID.class);
      assertThat(jdbc.queryForObject(
          "SELECT pg_terminate_backend(?)",
          Boolean.class,
          crashedBackendPid)).isTrue();
      assertThatThrownBy(() -> first.cleanAndMigrate(
          com.fxplatform.validation.service.ValidationResetOperations.RESET_SCHEMAS))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> first.persistResetReceipt(successfulResetReceipt(
          databaseName,
          startedAt,
          crashedPlan.targetGeneration())))
          .isInstanceOf(RuntimeException.class);
      first.close();

      replacement.markResetting(databaseName, startedAt.plusSeconds(1));
      ValidationAdministrativeDatabase.ResetPlan replacementPlan =
          replacement.currentResetPlan();
      UUID replacementOwner = jdbc.queryForObject(
          "SELECT reset_id FROM validation_control.reset_state WHERE singleton_key = 1",
          UUID.class);
      assertThat(replacementOwner).isNotEqualTo(crashedOwner);
      assertThat(replacementPlan.targetGeneration()).isEqualTo(crashedPlan.targetGeneration());
      assertThat(replacementPlan.epoch()).isGreaterThan(crashedPlan.epoch());
      replacement.persistResetReceipt(successfulResetReceipt(
          databaseName,
          startedAt.plusSeconds(1),
          replacementPlan.targetGeneration()));
      assertThat(jdbc.queryForObject(
          "SELECT state FROM validation_control.reset_state WHERE singleton_key = 1",
          String.class)).isEqualTo("READY");
    }
  }

  @Test
  void terminatedResetOwnerIsAlwaysRecycledByHikariPool() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate administrativeJdbc = PostgresMigrationTestSupport.jdbc(postgres);
      String databaseName = administrativeJdbc.queryForObject(
          "SELECT current_database()",
          String.class);
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(postgres.getJdbcUrl());
      config.setUsername(postgres.getUsername());
      config.setPassword(postgres.getPassword());
      config.setMaximumPoolSize(2);
      config.setMinimumIdle(0);
      config.setConnectionTimeout(500L);
      config.setPoolName("validation-reset-owner-recycle");

      try (HikariDataSource pooled = new HikariDataSource(config)) {
        JdbcTemplate pooledJdbc = new JdbcTemplate(pooled);
        for (int attempt = 0; attempt < 3; attempt++) {
          ValidationAdministrativeDatabase owner = new ValidationAdministrativeDatabase(
              pooled,
              new ObjectMapper().findAndRegisterModules(),
              new ValidationDatabaseFence(pooled, pooled));
          try {
            owner.markResetting(
                databaseName,
                Instant.parse("2026-07-23T06:00:00Z").plusSeconds(attempt));
            int backendPid = owner.resetOwnerBackendPid();
            assertThat(administrativeJdbc.queryForObject(
                "SELECT pg_terminate_backend(?)",
                Boolean.class,
                backendPid)).isTrue();
          } finally {
            owner.close();
          }

          assertThat(pooled.getHikariPoolMXBean().getActiveConnections()).isZero();
          assertThat(pooledJdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }
      }
    }
  }

  @Test
  void requestFencesDoNotConsumeTheBusinessHikariPool() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate administrativeJdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareReadyGeneration(administrativeJdbc);
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(postgres.getJdbcUrl());
      config.setUsername(postgres.getUsername());
      config.setPassword(postgres.getPassword());
      config.setMaximumPoolSize(2);
      config.setMinimumIdle(0);
      config.setConnectionTimeout(500L);
      config.setPoolName("validation-business-pool-isolation");

      try (HikariDataSource businessPool = new HikariDataSource(config)) {
        ValidationDatabaseFence fence = new ValidationDatabaseFence(businessPool);
        ValidationDatabaseFence.RequestFence first =
            fence.tryEnterRequest(GENERATION).orElseThrow();
        ValidationDatabaseFence.RequestFence second =
            fence.tryEnterRequest(GENERATION).orElseThrow();
        try {
          assertThat(businessPool.getHikariPoolMXBean().getActiveConnections()).isZero();
          assertThat(new JdbcTemplate(businessPool).queryForObject("SELECT 1", Integer.class))
              .isEqualTo(1);
        } finally {
          second.close();
          first.close();
        }
      }
    }
  }

  @Test
  void stalledRemoteActivityTimesOutWithoutStrandingResetOwnership() throws Exception {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareReadyGeneration(jdbc);
      DataSource dataSource = jdbc.getDataSource();
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      ValidationDatabaseFence passiveFence = new ValidationDatabaseFence(dataSource);
      ValidationResetGate passiveGate = new ValidationResetGate(passiveFence);
      passiveGate.hydrateReady(GENERATION);
      ValidationLoopbackRequestActivityBarrier passiveBarrier =
          new ValidationLoopbackRequestActivityBarrier(passiveFence, passiveGate);
      ValidationAdministrativeDatabase timedOwner = new ValidationAdministrativeDatabase(
          dataSource,
          objectMapper,
          new ValidationDatabaseFence(dataSource),
          Duration.ofMillis(100L));
      ValidationAdministrativeDatabase replacement =
          new ValidationAdministrativeDatabase(dataSource, objectMapper);
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
      Instant startedAt = Instant.parse("2026-07-23T06:00:00Z");

      try (ValidationLoopbackRequestActivityBarrier.Activity ignored =
               passiveBarrier.tryEnter().orElseThrow()) {
        long beganAt = System.nanoTime();
        assertThatThrownBy(() -> timedOwner.markResetting(databaseName, startedAt))
            .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - beganAt))
            .isLessThan(Duration.ofSeconds(2L));
        assertThat(jdbc.queryForObject(
            "SELECT state FROM validation_control.reset_state WHERE singleton_key = 1",
            String.class)).isEqualTo("READY");
      } finally {
        timedOwner.close();
      }

      replacement.markResetting(databaseName, startedAt.plusSeconds(1L));
      replacement.persistResetReceipt(successfulResetReceipt(
          databaseName,
          startedAt.plusSeconds(1L),
          GENERATION + 1L));
    }
  }

  @Test
  void flywayResetUsesOnlyThePhysicalResetOwnerSession() throws Exception {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      DataSource ownerDataSource = spy(jdbc.getDataSource());
      ValidationAdministrativeDatabase owner = new ValidationAdministrativeDatabase(
          ownerDataSource,
          new ObjectMapper().findAndRegisterModules());
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);

      try {
        owner.markResetting(databaseName, Instant.parse("2026-07-23T06:00:00Z"));
        owner.cleanAndMigrate(com.fxplatform.validation.service.ValidationResetOperations.RESET_SCHEMAS);

        verify(ownerDataSource, times(1)).getConnection();
      } finally {
        owner.close();
      }
    }
  }

  @Test
  void remoteLoopbackActivityDrainsBeforeResetAndDurableStateFencesPassiveInstance()
      throws Exception {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      prepareReadyGeneration(jdbc);
      DataSource dataSource = jdbc.getDataSource();
      ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
      ValidationDatabaseFence passiveFence = new ValidationDatabaseFence(dataSource);
      ValidationResetGate passiveGate = new ValidationResetGate(passiveFence);
      passiveGate.hydrateReady(GENERATION);
      ValidationLoopbackRequestActivityBarrier passiveBarrier =
          new ValidationLoopbackRequestActivityBarrier(passiveFence, passiveGate);
      ValidationAdministrativeDatabase resetOwner =
          new ValidationAdministrativeDatabase(dataSource, objectMapper);
      String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
      Instant startedAt = Instant.parse("2026-07-23T06:00:00Z");
      ValidationLoopbackRequestActivityBarrier.Activity active =
          passiveBarrier.tryEnter().orElseThrow();

      try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
        Future<?> resetting = executor.submit(() -> resetOwner.markResetting(
            databaseName,
            startedAt));
        try {
          resetting.get(200L, TimeUnit.MILLISECONDS);
          throw new AssertionError("reset crossed a remote active-request fence");
        } catch (TimeoutException expected) {
          assertThat(jdbc.queryForObject(
              "SELECT state FROM validation_control.reset_state WHERE singleton_key = 1",
              String.class)).isEqualTo("READY");
        }

        active.close();
        resetting.get(5L, TimeUnit.SECONDS);
        assertThat(passiveBarrier.tryEnter()).isEmpty();
        assertThatThrownBy(() -> passiveGate.requireReadyGeneration(GENERATION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not ready");

        resetOwner.persistResetReceipt(successfulResetReceipt(
            databaseName,
            startedAt,
            GENERATION + 1L));
        assertThatThrownBy(() -> passiveGate.requireReadyGeneration(GENERATION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not ready");
      } finally {
        active.close();
        resetOwner.close();
      }
    }
  }

  private static AnnotationConfigApplicationContext transactionalContext(
      DataSource dataSource,
      ValidationResetGate gate,
      ValidationMarketState marketState,
      ValidationDemoExecutionPolicyProvider policies
  ) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("validation");
    context.register(TransactionConfiguration.class);
    context.registerBean(
        "transactionManager",
        PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(dataSource));
    context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
    context.registerBean(ValidationResetGate.class, () -> gate);
    context.registerBean(ValidationMarketState.class, () -> marketState);
    context.registerBean(ValidationDemoExecutionPolicyProvider.class, () -> policies);
    context.registerBean(
        JdbcValidationRunEventStore.class,
        () -> new JdbcValidationRunEventStore(
            context.getBean(JdbcTemplate.class),
            new ObjectMapper().findAndRegisterModules(),
            gate));
    context.registerBean(
        JdbcValidationRunRuntimeStore.class,
        () -> new JdbcValidationRunRuntimeStore(
            context.getBean(JdbcTemplate.class),
            new ObjectMapper().findAndRegisterModules(),
            gate,
            marketState,
            policies,
            context.getBean(JdbcValidationRunEventStore.class)));
    context.refresh();
    return context;
  }

  private static ValidationResetGate readyGate() {
    ValidationResetGate gate = new ValidationResetGate();
    gate.hydrateReady(GENERATION);
    return gate;
  }

  private static ValidationMarketState readyMarketState() {
    ValidationMarketState marketState = new ValidationMarketState();
    marketState.reset(GENERATION);
    return marketState;
  }

  private static ValidationDemoExecutionPolicyProvider readyPolicies() {
    ValidationDemoExecutionPolicyProvider policies =
        new ValidationDemoExecutionPolicyProvider();
    policies.reset(GENERATION);
    return policies;
  }

  private static void prepareReadyGeneration(JdbcTemplate jdbc) {
    jdbc.update(
        """
            UPDATE validation_control.reset_state
               SET generation = ?, state = 'READY', redis_generation = ?,
                   memory_generation = ?, updated_at = now()
             WHERE singleton_key = 1
            """,
        GENERATION,
        GENERATION,
        GENERATION);
  }

  private static void assertV66SchemaContracts(JdbcTemplate jdbc) {
    Set<String> constraints = Set.copyOf(jdbc.queryForList(
        """
            SELECT conname
              FROM pg_constraint
             WHERE connamespace IN (
               'validation_control'::regnamespace,
               'validation_runtime'::regnamespace
             )
            """,
        String.class));
    assertThat(constraints).contains(
        "ux_validation_runtime_one_run_per_generation",
        "ck_validation_runtime_run_generation",
        "ck_validation_runtime_run_lease",
        "ck_validation_runtime_run_event_sequence",
        "ck_validation_runtime_operation_http_status",
        "ck_validation_runtime_system_step_sequence");

    Map<String, String> identityColumns = jdbc.query(
        """
            SELECT column_name, data_type
              FROM information_schema.columns
             WHERE table_schema = 'validation_runtime'
               AND table_name = 'run_executions'
               AND column_name IN ('user_id', 'account_id')
             ORDER BY column_name
            """,
        resultSet -> {
          java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
          while (resultSet.next()) {
            result.put(resultSet.getString(1), resultSet.getString(2));
          }
          return result;
        });
    assertThat(identityColumns)
        .containsEntry("account_id", "uuid")
        .containsEntry("user_id", "uuid");

    String activePredicate = jdbc.queryForObject(
        """
            SELECT pg_get_expr(index.indpred, index.indrelid)
              FROM pg_index index
              JOIN pg_class class ON class.oid = index.indexrelid
             WHERE class.relname = 'ux_validation_runtime_single_active_run'
            """,
        String.class);
    assertThat(activePredicate).contains("RECOVERY_BLOCKED");
  }

  private static void assertV66ChecksRejectInvalidRows(JdbcTemplate jdbc) {
    assertThatThrownBy(() -> jdbc.update(
        """
            INSERT INTO validation_runtime.run_events (
              run_id, generation, sequence, durable_event_key, request_fingerprint,
              event_type, payload_json
            ) VALUES (?, ?, 0, 'invalid-zero-event', 'invalid', 'INVALID', '{}'::jsonb)
            """,
        RUN_ID,
        GENERATION))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_validation_runtime_run_event_sequence");

    assertThatThrownBy(() -> jdbc.update(
        """
            INSERT INTO validation_runtime.system_step_receipts (
              run_id, generation, tick_sequence, phase, request_fingerprint,
              virtual_time, receipt_json
            ) VALUES (?, ?, 0, 'PRE_ACTIONS', 'invalid', ?, '{}'::jsonb)
            """,
        RUN_ID,
        GENERATION,
        Timestamp.from(START)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_validation_runtime_system_step_sequence");
  }

  private static StartRequest request(
      UUID runId,
      long generation,
      String speedMultiplier,
      String makerFeeRate
  ) {
    DemoExecutionPolicy defaults = DemoExecutionPolicy.defaults();
    DemoExecutionPolicy policy = new DemoExecutionPolicy(
        defaults.matchingMode(),
        new BigDecimal(makerFeeRate),
        defaults.takerFeeRate(),
        defaults.liquidationFeeRate(),
        defaults.slippageRate(),
        defaults.bids(),
        defaults.asks(),
        defaults.maxFillQuantityPerTick());
    return new StartRequest(
        runId,
        generation,
        "run-request-v1",
        "seed-validation-runtime",
        START,
        policy,
        List.of(tick(runId, generation)),
        List.of(),
        new BigDecimal(speedMultiplier));
  }

  private static StartRequest requestAt(UUID runId, Instant start, int tickCount) {
    return new StartRequest(
        runId,
        GENERATION,
        "run-request-nanosecond-v1",
        "seed-validation-runtime",
        start,
        DemoExecutionPolicy.defaults(),
        java.util.stream.LongStream.rangeClosed(1L, tickCount)
            .mapToObj(sequence -> tickAt(runId, GENERATION, start, sequence))
            .toList(),
        List.of(),
        BigDecimal.ONE);
  }

  private static CompositeTick tick(UUID runId, long generation) {
    return tickAt(runId, generation, START, 1L);
  }

  private static CompositeTick tickAt(
      UUID runId,
      long generation,
      Instant start,
      long sequence
  ) {
    Instant time = start.plusSeconds(sequence).truncatedTo(ChronoUnit.MICROS);
    BigDecimal last = new BigDecimal("50001.0");
    Instant expiresAt = time.plusSeconds(2);
    MarketDepthResponse depth = new MarketDepthResponse(
        "BTCUSDT",
        time.toEpochMilli(),
        List.of(new MarketDepthLevelResponse(last.subtract(BigDecimal.ONE), BigDecimal.TEN)),
        List.of(new MarketDepthLevelResponse(last.add(BigDecimal.ONE), BigDecimal.TEN)),
        "validation",
        "BTCUSDT",
        MarketSourceMode.LOCAL_SIMULATED,
        time,
        expiresAt,
        false);
    SpotMarketBundle spot = new SpotMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        last.subtract(BigDecimal.ONE),
        last.add(BigDecimal.ONE),
        last,
        depth,
        List.of(new RecentTradeResponse(
            "trade-1",
            "BTCUSDT",
            last,
            BigDecimal.ONE,
            "BUY",
            time.toEpochMilli(),
            "validation",
            "BTCUSDT",
            MarketSourceMode.LOCAL_SIMULATED,
            time,
            expiresAt,
            false)),
        List.of(new CandleResponse(
            time.toEpochMilli(),
            last,
            last.add(BigDecimal.ONE),
            last.subtract(BigDecimal.ONE),
            last,
            BigDecimal.TEN,
            "validation",
            "BTCUSDT",
            MarketSourceMode.LOCAL_SIMULATED,
            time,
            expiresAt,
            false)),
        time,
        expiresAt);
    return new CompositeTick(
        runId,
        generation,
        sequence,
        time,
        "tick-" + sequence,
        List.of(spot),
        List.of());
  }

  private static Command command(BigDecimal quantity) {
    return command("operation:place:1", "operation-request-v1", quantity);
  }

  private static Command command(
      String idempotencyKey,
      String requestFingerprint,
      BigDecimal quantity
  ) {
    return new Command(
        Operation.PUBLIC_ACTION,
        RUN_ID,
        GENERATION,
        1L,
        idempotencyKey,
        requestFingerprint,
        Map.of("symbol", "BTCUSDT", "quantity", quantity));
  }

  private static EventWrite event(
      String durableKey,
      String fingerprint,
      String type,
      Map<String, Object> payload
  ) {
    return eventAt(
        RUN_ID,
        START.plusSeconds(1),
        durableKey,
        fingerprint,
        type,
        payload);
  }

  private static EventWrite eventAt(
      UUID runId,
      Instant virtualTime,
      String durableKey,
      String fingerprint,
      String type,
      Map<String, Object> payload
  ) {
    return new EventWrite(
        runId,
        durableKey,
        fingerprint,
        type,
        virtualTime,
        null,
        payload);
  }

  private static ValidationResetReceipt successfulResetReceipt(
      String databaseName,
      Instant startedAt,
      long generation
  ) {
    Instant finishedAt = startedAt.plusSeconds(1);
    List<StepResult> steps = java.util.Arrays.stream(Step.values())
        .map(step -> new StepResult(
            step,
            StepStatus.SUCCEEDED,
            startedAt,
            finishedAt,
            null))
        .toList();
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.SUCCEEDED,
        databaseName,
        generation,
        generation,
        startedAt,
        finishedAt,
        steps,
        null);
  }

  private static long count(JdbcTemplate jdbc, String table) {
    Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? 0L : count;
  }

  private static void assertBusinessCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TransactionConfiguration {
  }
}
