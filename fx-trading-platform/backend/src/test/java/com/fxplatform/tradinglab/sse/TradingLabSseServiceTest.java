package com.fxplatform.tradinglab.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import com.fxplatform.tradinglab.repository.TradingLabRunEventRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TradingLabSseServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final UUID RUN_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000008");

  @Test
  void productionSchedulerInjectionTargetsTheApplicationScheduler() throws Exception {
    Qualifier qualifier = TradingLabSseService.class
        .getConstructor(
            TradingLabRunEventRepository.class,
            TradingLabSseEventProjector.class,
            PlatformTransactionManager.class,
            TaskScheduler.class)
        .getParameters()[3]
        .getAnnotation(Qualifier.class);

    assertThat(qualifier).isNotNull();
    assertThat(qualifier.value()).isEqualTo("taskScheduler");
  }

  @Test
  void lastEventIdIsStrictUnsignedBaseTenLongAndAbsentMeansBeforeSequenceZero() {
    assertThat(TradingLabSseService.parseLastEventId(null)).isEqualTo(-1L);
    assertThat(TradingLabSseService.parseLastEventId("0")).isZero();
    assertThat(TradingLabSseService.parseLastEventId("00017")).isEqualTo(17L);
    assertThat(TradingLabSseService.parseLastEventId(Long.toString(Long.MAX_VALUE)))
        .isEqualTo(Long.MAX_VALUE);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", " ", "+1", "-1", " 1", "1 ", "1.0", "1e2", "0x10",
      "9223372036854775808", "18446744073709551615"
  })
  void malformedLastEventIdFailsBeforeAnyRunOrJournalRead(String value) {
    Harness harness = new Harness(2, 2);

    assertThatThrownBy(() -> harness.service.connect(RUN_ID, value))
        .isInstanceOf(TradingLabSseRequestException.class)
        .extracting(error -> ((TradingLabSseRequestException) error).status().value())
        .isEqualTo(400);

    verify(harness.repository, never()).findAdminSseRunState(any());
    verify(harness.repository, never()).findAdminSseAfter(any(), anyLong(), anyInt());
  }

  @Test
  void missingRunFailsSynchronouslyBeforeAnEmitterOrResponseCanBeCreated() {
    Harness harness = new Harness(2, 2);
    harness.runState.set(null);

    assertThatThrownBy(() -> harness.service.connect(RUN_ID, null))
        .isInstanceOf(TradingLabSseRequestException.class)
        .extracting(error -> ((TradingLabSseRequestException) error).status().value())
        .isEqualTo(404);

    assertThat(harness.emitters.created).isEmpty();
  }

  @Test
  void replayUsesAscendingBoundedPagesAndTheMainJournalSequenceAsSseId() {
    Harness harness = new Harness(2, 2);
    harness.committed.set(List.of(
        validationEvent(0L, 1L, "RUN_ACCEPTED", Map.of("tickCount", 3)),
        validationEvent(1L, 2L, "MARKET_TICK", Map.of("tickSequence", 1)),
        validationEvent(2L, 3L, "CHECKPOINT", Map.of("tickSequence", 1)),
        validationEvent(3L, 4L, "API_TRACE", Map.of("operation", "QUERY_STATE")),
        validationEvent(4L, 5L, "RUN_STATE_CHANGED", Map.of("state", "RUNNING"))));

    harness.service.connect(RUN_ID, null);

    FakeEmitter emitter = harness.emitters.only();
    assertThat(emitter.dataFrames())
        .extracting(Frame::id)
        .containsExactly(0L, 1L, 2L, 3L, 4L);
    assertThat(emitter.dataFrames())
        .extracting(Frame::name)
        .containsExactly("state", "tick", "checkpoint", "api-trace", "state");
    assertThat(harness.afterSequences)
        .startsWith(-1L, 1L, 3L);
    assertThat(harness.requestedLimits).containsOnly(2);
  }

  @Test
  void terminalRunReplaysEveryDurableRowThenEmitsExactlyOneCompleteAndCleansUp() {
    Harness harness = new Harness(2, 2);
    harness.runState.set("COMPLETED");
    harness.committed.set(List.of(
        validationEvent(0L, 1L, "RUN_ACCEPTED", Map.of("tickCount", 1)),
        validationEvent(1L, 2L, "RUN_STATE_CHANGED", Map.of("state", "COMPLETED"))));

    harness.service.connect(RUN_ID, "0");
    harness.service.pollNow();

    FakeEmitter emitter = harness.emitters.only();
    assertThat(emitter.dataFrames())
        .extracting(Frame::name, Frame::id)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("state", 1L),
            org.assertj.core.groups.Tuple.tuple("complete", null));
    assertThat(emitter.dataFrames().stream()
        .filter(frame -> "complete".equals(frame.name())))
        .hasSize(1);
    assertThat(emitter.completed).isTrue();
    assertThat(harness.service.activeEmitterCount()).isZero();
  }

  @Test
  void reconnectAtHighestDurableCursorStillGetsOneUnnumberedTerminalControlFrame() {
    Harness harness = new Harness(2, 2);
    harness.runState.set("COMPLETED");
    harness.committed.set(List.of(
        validationEvent(0L, 1L, "RUN_ACCEPTED", Map.of("tickCount", 1)),
        validationEvent(1L, 2L, "RUN_STATE_CHANGED", Map.of("state", "COMPLETED"))));

    harness.service.connect(RUN_ID, "1");
    FakeEmitter emitter = harness.emitters.only();

    assertThat(emitter.dataFrames())
        .extracting(Frame::name, Frame::id)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("complete", null));
    assertThat(emitter.completed).isTrue();
    assertThat(harness.afterSequences).containsOnly(1L);
  }

  @Test
  void registrationIsImmediatelyFollowedByASecondCatchUpThatClosesTheReplayRace() {
    Harness harness = new Harness(2, 2);
    TradingLabRunEventEntity raced =
        validationEvent(0L, 1L, "MARKET_TICK", Map.of("tickSequence", 1));
    AtomicInteger pages = new AtomicInteger();
    when(harness.repository.findAdminSseAfter(eq(RUN_ID), anyLong(), eq(2)))
        .thenAnswer(invocation -> {
          long after = invocation.getArgument(1);
          harness.requireCommittedRead();
          harness.afterSequences.add(after);
          harness.requestedLimits.add(invocation.getArgument(2));
          if (pages.getAndIncrement() == 0) {
            harness.committed.set(List.of(raced));
            return List.of();
          }
          return harness.page(after, 2);
        });

    harness.service.connect(RUN_ID, null);

    assertThat(harness.emitters.only().dataFrames())
        .extracting(Frame::id)
        .containsExactly(0L);
    assertThat(harness.afterSequences).startsWith(-1L, -1L);
  }

  @Test
  void perEmitterCursorSuppressesAnOverlappingReplayRowWithoutSuppressingTheNewRow() {
    Harness harness = new Harness(2, 2);
    TradingLabRunEventEntity first =
        validationEvent(0L, 1L, "MARKET_TICK", Map.of("tickSequence", 1));
    TradingLabRunEventEntity second =
        validationEvent(1L, 2L, "MARKET_TICK", Map.of("tickSequence", 2));
    AtomicInteger pages = new AtomicInteger();
    when(harness.repository.findAdminSseAfter(eq(RUN_ID), anyLong(), eq(2)))
        .thenAnswer(invocation -> {
          harness.requireCommittedRead();
          long after = invocation.getArgument(1);
          harness.afterSequences.add(after);
          harness.requestedLimits.add(invocation.getArgument(2));
          return switch (pages.getAndIncrement()) {
            case 0 -> List.of(first);
            case 1 -> List.of(first, second);
            default -> List.of(second);
          };
        });

    harness.service.connect(RUN_ID, null);
    harness.service.pollNow();

    assertThat(harness.emitters.only().dataFrames())
        .extracting(Frame::id)
        .containsExactly(0L, 1L);
  }

  @Test
  void eachReplayAndPollPassHasBoundedWorkWhileLaterPollsContinueFromTheCursor() {
    Harness harness = new Harness(2, 1);
    harness.committed.set(List.of(
        validationEvent(0L, 1L, "MARKET_TICK", Map.of("tickSequence", 1)),
        validationEvent(1L, 2L, "MARKET_TICK", Map.of("tickSequence", 2)),
        validationEvent(2L, 3L, "MARKET_TICK", Map.of("tickSequence", 3)),
        validationEvent(3L, 4L, "MARKET_TICK", Map.of("tickSequence", 4)),
        validationEvent(4L, 5L, "MARKET_TICK", Map.of("tickSequence", 5)),
        validationEvent(5L, 6L, "MARKET_TICK", Map.of("tickSequence", 6))));

    harness.service.connect(RUN_ID, null);
    assertThat(harness.emitters.only().dataFrames()).hasSize(4);

    harness.service.pollNow();
    assertThat(harness.emitters.only().dataFrames())
        .extracting(Frame::id)
        .containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
  }

  @Test
  void durablePollReadsOnlyInsideIndependentCommittedReadOperations() {
    Harness harness = new Harness(2, 2);
    harness.service.connect(RUN_ID, null);
    assertThat(harness.emitters.only().dataFrames()).isEmpty();

    harness.committed.set(List.of(
        validationEvent(0L, 1L, "CHECKPOINT", Map.of("tickSequence", 1))));
    harness.service.pollNow();

    assertThat(harness.emitters.only().dataFrames())
        .extracting(Frame::id)
        .containsExactly(0L);
    assertThat(harness.committedReadExecutions.get()).isGreaterThanOrEqualTo(5);
    assertThat(harness.readOutsideCommittedTransaction).isFalse();
  }

  @Test
  void idleConnectionGetsAHeartbeatCommentWithoutIdAndTimeoutDisconnectAndCompletionCleanUp() {
    Harness harness = new Harness(2, 2);

    harness.service.connect(RUN_ID, null);
    harness.service.connect(RUN_ID, null);
    harness.service.connect(RUN_ID, null);
    assertThat(harness.service.activeEmitterCount()).isEqualTo(3);

    harness.clock.advance(Duration.ofSeconds(16));
    harness.service.pollNow();
    for (FakeEmitter emitter : harness.emitters.created) {
      assertThat(emitter.frames)
          .anySatisfy(frame -> {
            assertThat(frame.comment()).isEqualTo("heartbeat");
            assertThat(frame.id()).isNull();
            assertThat(frame.name()).isNull();
          });
    }

    harness.emitters.created.get(0).timeout.run();
    harness.emitters.created.get(1).error.accept(new IOException("client disconnected"));
    harness.emitters.created.get(2).completion.run();

    assertThat(harness.service.activeEmitterCount()).isZero();
    verify(harness.hubFuture).cancel(false);
  }

  @Test
  void sendFailureRemovesEmitterAndCancelsItsHubWork() {
    Harness harness = new Harness(2, 2);
    harness.committed.set(List.of(
        validationEvent(0L, 1L, "MARKET_TICK", Map.of("tickSequence", 1))));
    harness.emitters.failNextCreatedSend.set(true);

    harness.service.connect(RUN_ID, null);

    FakeEmitter emitter = harness.emitters.only();
    assertThat(emitter.failed).isNotNull();
    assertThat(harness.service.activeEmitterCount()).isZero();
  }

  @Test
  void publicProjectionNeverLeaksJournalEnvelopeLogicalKeysFingerprintsOrSecrets() {
    Harness harness = new Harness(2, 2);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("safeVisibleField", "visible-value");
    request.put("requestFingerprint", "forbidden-fingerprint");
    request.put("internalToken", "forbidden-token");
    request.put("secretRegistry", List.of("forbidden-secret"));
    request.put("rawBody", "forbidden-raw-body");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceKind", "HTTP_RESULT");
    result.put("sourceKey", "forbidden-source-key");
    result.put("sourceFingerprint", "forbidden-source-fingerprint");
    result.put("evidence", Map.ofEntries(
        Map.entry("sequence", 0L),
        Map.entry("environment", "validation"),
        Map.entry("method", "POST"),
        Map.entry("url", "http://127.0.0.1:18087/internal/validation/runs"),
        Map.entry("virtualTime", "2026-07-24T00:00:00Z"),
        Map.entry("realTime", "2026-07-24T00:00:00Z"),
        Map.entry("status", 202),
        Map.entry("duration", "PT0.01S"),
        Map.entry("sanitizedRequest", request),
        Map.entry("sanitizedResponse", Map.of("accepted", true)),
        Map.entry("traceId", "trace-1"),
        Map.entry("correlationId", "correlation-1"),
        Map.entry("exception", Map.of())));
    harness.committed.set(List.of(event(0L, "VALIDATION_HTTP_RESULT", result)));

    harness.service.connect(RUN_ID, null);

    Frame frame = harness.emitters.only().dataFrames().getFirst();
    assertThat(frame.name()).isEqualTo("api-trace");
    assertThat(frame.id()).isZero();
    String publicJson = json(frame.data());
    assertThat(publicJson)
        .contains("safeVisibleField", "visible-value")
        .doesNotContain(
            "sourceKey",
            "sourceFingerprint",
            "requestFingerprint",
            "internalToken",
            "secretRegistry",
            "rawBody",
            "forbidden-fingerprint",
            "forbidden-token",
            "forbidden-secret",
            "forbidden-raw-body");
  }

  @Test
  void publicEventNamesAreProjectedFromDurableJournalRowsWithoutPublishingInnerIdentity() {
    Harness harness = new Harness(20, 2);
    harness.committed.set(List.of(
        event(0L, "VALIDATION_HTTP_INTENT", Map.of(
            "sourceKind", "HTTP_INTENT",
            "sourceKey", "intent:secret-logical-key",
            "sourceFingerprint", "secret-fingerprint",
            "evidence", Map.of("requestFingerprint", "secret-request-fingerprint"))),
        validationEvent(1L, 1L, "RUN_ACCEPTED", Map.of("tickCount", 1)),
        validationEvent(2L, 2L, "MARKET_TICK", Map.of("tickSequence", 1)),
        validationEvent(3L, 3L, "CHECKPOINT", Map.of("tickSequence", 1)),
        validationEvent(4L, 4L, "API_TRACE", Map.of("operation", "QUERY_STATE")),
        validationEvent(
            5L, 5L, "RECOVERY_RECONCILIATION", Map.of("outcome", "OBSERVED")),
        event(6L, "VALIDATION_HIGH_WATERMARK", Map.of(
            "sourceKey", "watermark:1:6",
            "sourceFingerprint", "secret",
            "validationGeneration", 1L,
            "highWatermark", 6L)),
        validationEvent(7L, 6L, "PERSISTED_FAILURE", Map.of("code", "CONTROLLED"))));

    harness.service.connect(RUN_ID, null);

    assertThat(harness.emitters.only().dataFrames())
        .extracting(Frame::name)
        .containsExactly(
            "api-trace",
            "state",
            "tick",
            "checkpoint",
            "api-trace",
            "warning",
            "progress",
            "error");
    assertThat(json(harness.emitters.only().dataFrames()))
        .doesNotContain(
            "sourceKey",
            "sourceFingerprint",
            "durableKey",
            "fingerprint",
            "secret-logical-key",
            "secret-request-fingerprint");
  }

  @Test
  void marketTickProjectionPublishesActualInstrumentPricesWithoutInnerIdentity() {
    Harness harness = new Harness(20, 2);
    harness.committed.set(List.of(validationEvent(
        0L,
        1L,
        "MARKET_TICK",
        Map.of(
            "tickSequence", 1L,
            "instruments", List.of(
                Map.of(
                    "productType", "CRYPTO_SPOT",
                    "symbol", "BTCUSDT",
                    "bid", "50000",
                    "ask", "50002",
                    "last", "50001"),
                Map.of(
                    "productType", "LINEAR_PERP",
                    "symbol", "ETHUSDT",
                    "bid", "2999.10",
                    "ask", "3001.10",
                    "last", "3000.10",
                    "mark", "3000.20",
                    "index", "3000.30"))))));

    harness.service.connect(RUN_ID, null);

    Frame frame = harness.emitters.only().dataFrames().getFirst();
    assertThat(frame.name()).isEqualTo("tick");
    assertThat(frame.data())
        .containsEntry("virtualTime", "2026-07-24T00:00:00Z")
        .containsEntry("validationSequence", 1L);
    assertThat(frame.data().get("payload")).isEqualTo(Map.of(
        "tickSequence", 1,
        "instruments", List.of(
            Map.of(
                "productType", "CRYPTO_SPOT",
                "symbol", "BTCUSDT",
                "bid", "50000",
                "ask", "50002",
                "last", "50001"),
            Map.of(
                "productType", "LINEAR_PERP",
                "symbol", "ETHUSDT",
                "bid", "2999.10",
                "ask", "3001.10",
                "last", "3000.10",
                "mark", "3000.20",
                "index", "3000.30"))));
    assertThat(json(frame.data())).doesNotContain(
        "sourceKey",
        "sourceFingerprint",
        "durableKey",
        "fingerprint",
        "internal-durable",
        "internal-fingerprint");
  }

  private static TradingLabRunEventEntity validationEvent(
      long journalSequence,
      long validationSequence,
      String type,
      Map<String, Object> publicPayload
  ) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("runId", RUN_ID.toString());
    evidence.put("sequence", validationSequence);
    evidence.put("durableKey", "internal-durable-" + validationSequence);
    evidence.put("fingerprint", "internal-fingerprint-" + validationSequence);
    evidence.put("type", type);
    evidence.put("virtualTime", "2026-07-24T00:00:00Z");
    evidence.put("correlationId", "correlation-" + validationSequence);
    evidence.put("payload", publicPayload);

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("sourceKind", "VALIDATION_EVENT");
    envelope.put("sourceKey", "validation:" + validationSequence);
    envelope.put("sourceFingerprint", "internal-source-fingerprint-" + validationSequence);
    envelope.put("validationSequence", validationSequence);
    envelope.put("durableKey", "internal-durable-" + validationSequence);
    envelope.put("validationFingerprint", "internal-fingerprint-" + validationSequence);
    envelope.put("evidence", evidence);
    return event(journalSequence, "VALIDATION_EVENT", envelope);
  }

  private static TradingLabRunEventEntity event(
      long sequence,
      String eventType,
      Map<String, Object> payload
  ) {
    TradingLabRunEventEntity entity = new TradingLabRunEventEntity();
    entity.setId(UUID.nameUUIDFromBytes((RUN_ID + ":" + sequence).getBytes()));
    entity.setRunId(RUN_ID);
    entity.setSequence(sequence);
    entity.setEventType(eventType);
    entity.setVirtualTime(Instant.parse("2026-07-24T00:00:00Z"));
    entity.setRealTime(Instant.parse("2026-07-24T00:00:01Z"));
    entity.setCorrelationId("journal-correlation-" + sequence);
    try {
      entity.setPayloadJson(JSON.writeValueAsString(payload));
    } catch (JsonProcessingException exception) {
      throw new AssertionError(exception);
    }
    return entity;
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class Harness {

    private final TradingLabRunEventRepository repository =
        mock(TradingLabRunEventRepository.class);
    private final AtomicReference<String> runState = new AtomicReference<>("RUNNING");
    private final AtomicReference<List<TradingLabRunEventEntity>> committed =
        new AtomicReference<>(List.of());
    private final List<Long> afterSequences = new ArrayList<>();
    private final List<Integer> requestedLimits = new ArrayList<>();
    private final AtomicInteger committedReadExecutions = new AtomicInteger();
    private final AtomicBoolean insideCommittedRead = new AtomicBoolean();
    private final FakeEmitterFactory emitters = new FakeEmitterFactory();
    private final MutableClock clock = new MutableClock(
        Instant.parse("2026-07-24T00:00:00Z"));
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> hubFuture = mock(ScheduledFuture.class);
    private final AtomicReference<Runnable> scheduledHub = new AtomicReference<>();
    private final TradingLabSseService service;
    private boolean readOutsideCommittedTransaction;

    private Harness(int pageSize, int maxPagesPerPass) {
      when(repository.findAdminSseRunState(RUN_ID)).thenAnswer(invocation -> {
        requireCommittedRead();
        return Optional.ofNullable(runState.get());
      });
      when(repository.findAdminSseAfter(eq(RUN_ID), anyLong(), eq(pageSize)))
          .thenAnswer(invocation -> {
            requireCommittedRead();
            long after = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            afterSequences.add(after);
            requestedLimits.add(limit);
            return page(after, limit);
          });
      when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
          .thenAnswer(invocation -> {
            scheduledHub.set(invocation.getArgument(0));
            return hubFuture;
          });

      TransactionOperations reads = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          committedReadExecutions.incrementAndGet();
          assertThat(insideCommittedRead.compareAndSet(false, true)).isTrue();
          try {
            return action.doInTransaction(null);
          } finally {
            insideCommittedRead.set(false);
          }
        }
      };
      service = new TradingLabSseService(
          repository,
          new TradingLabSseEventProjector(JSON),
          reads,
          scheduler,
          clock,
          emitters,
          pageSize,
          maxPagesPerPass,
          Duration.ofMillis(250),
          Duration.ofSeconds(15),
          Duration.ofMinutes(30));
    }

    private List<TradingLabRunEventEntity> page(long after, int limit) {
      return committed.get().stream()
          .filter(event -> event.getSequence() > after)
          .sorted(Comparator.comparingLong(TradingLabRunEventEntity::getSequence))
          .limit(limit)
          .toList();
    }

    private void requireCommittedRead() {
      if (!insideCommittedRead.get()) {
        readOutsideCommittedTransaction = true;
        throw new AssertionError("repository read escaped committed read transaction");
      }
    }
  }

  private static final class FakeEmitterFactory
      implements TradingLabSseService.EmitterFactory {

    private final List<FakeEmitter> created = new ArrayList<>();
    private final AtomicBoolean failNextCreatedSend = new AtomicBoolean();

    @Override
    public TradingLabSseService.Emitter create(Duration timeout) {
      FakeEmitter emitter = new FakeEmitter();
      emitter.failNextSend = failNextCreatedSend.getAndSet(false);
      created.add(emitter);
      return emitter;
    }

    private FakeEmitter only() {
      assertThat(created).hasSize(1);
      return created.getFirst();
    }
  }

  private static final class FakeEmitter implements TradingLabSseService.Emitter {

    private final SseEmitter response = new SseEmitter();
    private final List<Frame> frames = new ArrayList<>();
    private Runnable completion = () -> { };
    private Runnable timeout = () -> { };
    private Consumer<Throwable> error = ignored -> { };
    private boolean failNextSend;
    private boolean completed;
    private Throwable failed;

    @Override
    public SseEmitter response() {
      return response;
    }

    @Override
    public void event(Long id, String name, Map<String, Object> data) throws IOException {
      if (failNextSend) {
        failNextSend = false;
        throw new IOException("controlled send failure");
      }
      frames.add(new Frame(id, name, data, null));
    }

    @Override
    public void comment(String comment) throws IOException {
      frames.add(new Frame(null, null, null, comment));
    }

    @Override
    public void complete() {
      completed = true;
    }

    @Override
    public void completeWithError(Throwable failure) {
      failed = failure;
    }

    @Override
    public void onCompletion(Runnable callback) {
      completion = callback;
    }

    @Override
    public void onTimeout(Runnable callback) {
      timeout = callback;
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
      error = callback;
    }

    private List<Frame> dataFrames() {
      return frames.stream().filter(frame -> frame.name() != null).toList();
    }
  }

  private record Frame(
      Long id,
      String name,
      Map<String, Object> data,
      String comment
  ) {
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

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
      return instant;
    }
  }
}
