package com.fxplatform.tradinglab.sse;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.mybatis.MybatisPlusConfig;
import com.fxplatform.tradinglab.repository.TradingLabRunEventRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TradingLabSsePostgresIT.TestApplication.class)
@ActiveProfiles("database-it")
@Testcontainers
class TradingLabSsePostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TradingLabRunEventRepository eventRepository;
  @Autowired private TradingLabSseEventProjector projector;

  private UUID actorId;
  private UUID scenarioId;
  private UUID runId;

  @BeforeEach
  void createRun() {
    actorId = UUID.randomUUID();
    scenarioId = UUID.randomUUID();
    runId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'task8-sse-it-hash', 'ACTIVE', 'ADMIN')
        """, actorId, "actor-" + actorId + "@task8-sse-it.test");
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version)
        values (
          ?, 'Task 8 SSE IT', 'FROZEN', false, 'task8-sse-seed', 'task8-model',
          '{"scenario":"task8-sse","seed":"task8-sse-seed"}'::jsonb,
          '{"config":"task8-sse"}'::jsonb, ?,
          'task8-symbols', 'task8-code', ?, ?, 0)
        """, scenarioId, "a".repeat(64), actorId, actorId);
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version, created_by, version)
        values (
          ?, ?, 'RUNNING',
          '{"scenario":"task8-sse"}'::jsonb, '{"config":"task8-sse"}'::jsonb, ?,
          'task8-model', 'task8-symbols', 'task8-code', ?, 0)
        """, runId, scenarioId, "b".repeat(64), actorId);
    insertEvent(0L, 1L, "RUN_ACCEPTED", Map.of("tickCount", 1));
  }

  @AfterEach
  void deleteRun() {
    if (runId != null) {
      jdbcTemplate.update("delete from trading_lab.runs where id = ?", runId);
    }
    if (scenarioId != null) {
      jdbcTemplate.update("delete from trading_lab.scenarios where id = ?", scenarioId);
    }
    if (actorId != null) {
      jdbcTemplate.update("delete from auth.users where id = ?", actorId);
    }
  }

  @Test
  @Timeout(30)
  void uncommittedRowsAreInvisibleAndTerminalReplaySurvivesAFreshService()
      throws Exception {
    CapturingEmitterFactory firstEmitters = new CapturingEmitterFactory();
    TradingLabSseService first = service(firstEmitters);
    first.connect(runId, null);
    assertThat(firstEmitters.only().events)
        .extracting(CapturedEvent::id)
        .containsExactly(0L);

    CountDownLatch insertedButUncommitted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> writer = executor.submit(() -> {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(ignored -> {
          insertEvent(1L, 2L, "MARKET_TICK", Map.of("tickSequence", 1));
          insertedButUncommitted.countDown();
          await(allowCommit);
        });
      });

      assertThat(insertedButUncommitted.await(10, SECONDS)).isTrue();
      first.pollNow();
      assertThat(firstEmitters.only().events)
          .extracting(CapturedEvent::id)
          .containsExactly(0L);

      allowCommit.countDown();
      writer.get(10, SECONDS);
    }

    first.pollNow();
    assertThat(firstEmitters.only().events)
        .extracting(CapturedEvent::id)
        .containsExactly(0L, 1L);

    jdbcTemplate.update(
        "update trading_lab.runs set state = 'COMPLETED' where id = ?",
        runId);
    first.pollNow();
    assertThat(firstEmitters.only().events)
        .extracting(CapturedEvent::name, CapturedEvent::id)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("state", 0L),
            org.assertj.core.groups.Tuple.tuple("tick", 1L),
            org.assertj.core.groups.Tuple.tuple("complete", null));
    assertThat(first.activeEmitterCount()).isZero();

    CapturingEmitterFactory recoveredEmitters = new CapturingEmitterFactory();
    TradingLabSseService recovered = service(recoveredEmitters);
    recovered.connect(runId, "0");

    assertThat(recoveredEmitters.only().events)
        .extracting(CapturedEvent::name, CapturedEvent::id)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("tick", 1L),
            org.assertj.core.groups.Tuple.tuple("complete", null));
    assertThat(recoveredEmitters.only().completed).isTrue();
    assertThat(recovered.activeEmitterCount()).isZero();
  }

  private TradingLabSseService service(CapturingEmitterFactory emitters) {
    TaskScheduler scheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
    when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
        .thenAnswer(ignored -> scheduled);
    return new TradingLabSseService(
        eventRepository,
        projector,
        committedReads(),
        scheduler,
        Clock.systemUTC(),
        emitters,
        2,
        2,
        Duration.ofSeconds(1),
        Duration.ofSeconds(15),
        Duration.ofMinutes(1));
  }

  private TransactionOperations committedReads() {
    TransactionTemplate reads = new TransactionTemplate(transactionManager);
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    reads.setReadOnly(true);
    reads.setTimeout(5);
    return reads;
  }

  private void insertEvent(
      long journalSequence,
      long validationSequence,
      String type,
      Map<String, Object> payload
  ) {
    Map<String, Object> evidence = Map.of(
        "runId", runId.toString(),
        "sequence", validationSequence,
        "durableKey", "validation-" + validationSequence,
        "fingerprint", "fingerprint-" + validationSequence,
        "type", type,
        "virtualTime", "2026-07-24T00:00:00Z",
        "correlationId", "correlation-" + validationSequence,
        "payload", payload);
    Map<String, Object> envelope = Map.of(
        "sourceKind", "VALIDATION_EVENT",
        "sourceKey", "validation:" + validationSequence,
        "sourceFingerprint", "source-fingerprint-" + validationSequence,
        "validationSequence", validationSequence,
        "durableKey", "validation-" + validationSequence,
        "validationFingerprint", "fingerprint-" + validationSequence,
        "evidence", evidence);
    jdbcTemplate.update("""
        insert into trading_lab.run_events (
          id, run_id, sequence, event_type, virtual_time, real_time,
          correlation_id, payload_json)
        values (?, ?, ?, 'VALIDATION_EVENT',
          '2026-07-24T00:00:00Z'::timestamptz, clock_timestamp(), ?, ?::jsonb)
        """,
        UUID.randomUUID(),
        runId,
        journalSequence,
        "journal-correlation-" + journalSequence,
        json(envelope));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, SECONDS)) {
        throw new AssertionError("Timed out waiting to commit SSE evidence");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static final class CapturingEmitterFactory
      implements TradingLabSseService.EmitterFactory {

    private final List<CapturingEmitter> emitters = new ArrayList<>();

    @Override
    public TradingLabSseService.Emitter create(Duration timeout) {
      CapturingEmitter emitter = new CapturingEmitter();
      emitters.add(emitter);
      return emitter;
    }

    private CapturingEmitter only() {
      assertThat(emitters).hasSize(1);
      return emitters.getFirst();
    }
  }

  private static final class CapturingEmitter implements TradingLabSseService.Emitter {

    private final SseEmitter response = new SseEmitter();
    private final List<CapturedEvent> events = new ArrayList<>();
    private boolean completed;

    @Override
    public SseEmitter response() {
      return response;
    }

    @Override
    public void event(Long id, String name, Map<String, Object> data) {
      events.add(new CapturedEvent(id, name, data));
    }

    @Override
    public void comment(String comment) {
      // Heartbeats are covered by the deterministic unit suite.
    }

    @Override
    public void complete() {
      completed = true;
    }

    @Override
    public void completeWithError(Throwable failure) {
      throw new AssertionError(failure);
    }

    @Override
    public void onCompletion(Runnable callback) {
      // Container lifecycle is covered by the deterministic unit suite.
    }

    @Override
    public void onTimeout(Runnable callback) {
      // Container lifecycle is covered by the deterministic unit suite.
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
      // Container lifecycle is covered by the deterministic unit suite.
    }
  }

  private record CapturedEvent(Long id, String name, Map<String, Object> data) {
  }

  @SpringBootConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({MybatisPlusConfig.class, TradingLabSseEventProjector.class})
  static class TestApplication {

    @Bean
    MapperFactoryBean<TradingLabRunEventRepository> tradingLabRunEventRepository(
        SqlSessionFactory sqlSessionFactory
    ) {
      MapperFactoryBean<TradingLabRunEventRepository> mapper =
          new MapperFactoryBean<>(TradingLabRunEventRepository.class);
      mapper.setSqlSessionFactory(sqlSessionFactory);
      return mapper;
    }
  }
}
