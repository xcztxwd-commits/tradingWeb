package com.fxplatform.tradinglab.sse;

import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import com.fxplatform.tradinglab.repository.TradingLabRunEventRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Durable Admin SSE hub. The database journal is the only event truth; the in-memory map contains
 * only connection cursors and lifecycle callbacks.
 */
@Service
public class TradingLabSseService {

  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int DEFAULT_MAX_PAGES_PER_PASS = 4;
  private static final Duration DEFAULT_POLL_DELAY = Duration.ofMillis(500);
  private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(15);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

  private final TradingLabRunEventRepository events;
  private final TradingLabSseEventProjector projector;
  private final TransactionOperations committedReads;
  private final TaskScheduler scheduler;
  private final Clock clock;
  private final EmitterFactory emitters;
  private final int pageSize;
  private final int maxPagesPerPass;
  private final Duration pollDelay;
  private final Duration heartbeatInterval;
  private final Duration emitterTimeout;
  private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();
  private final Object hubMonitor = new Object();

  private ScheduledFuture<?> hubTask;

  @Autowired
  public TradingLabSseService(
      TradingLabRunEventRepository events,
      TradingLabSseEventProjector projector,
      PlatformTransactionManager transactionManager,
      @Qualifier("taskScheduler") TaskScheduler scheduler
  ) {
    this(
        events,
        projector,
        committedReadTransactions(transactionManager),
        scheduler,
        Clock.systemUTC(),
        DefaultEmitter::new,
        DEFAULT_PAGE_SIZE,
        DEFAULT_MAX_PAGES_PER_PASS,
        DEFAULT_POLL_DELAY,
        DEFAULT_HEARTBEAT,
        DEFAULT_TIMEOUT);
  }

  TradingLabSseService(
      TradingLabRunEventRepository events,
      TradingLabSseEventProjector projector,
      TransactionOperations committedReads,
      TaskScheduler scheduler,
      Clock clock,
      EmitterFactory emitters,
      int pageSize,
      int maxPagesPerPass,
      Duration pollDelay,
      Duration heartbeatInterval,
      Duration emitterTimeout
  ) {
    this.events = Objects.requireNonNull(events, "events");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.committedReads = Objects.requireNonNull(committedReads, "committedReads");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.emitters = Objects.requireNonNull(emitters, "emitters");
    if (pageSize < 1
        || pageSize > 1_000
        || maxPagesPerPass < 1
        || maxPagesPerPass > 100
        || !positive(pollDelay)
        || !positive(heartbeatInterval)
        || !positive(emitterTimeout)) {
      throw new IllegalArgumentException("Invalid Trading Lab SSE bounds");
    }
    this.pageSize = pageSize;
    this.maxPagesPerPass = maxPagesPerPass;
    this.pollDelay = pollDelay;
    this.heartbeatInterval = heartbeatInterval;
    this.emitterTimeout = emitterTimeout;
  }

  public SseEmitter events(UUID runId, String lastEventId) {
    return connect(runId, lastEventId);
  }

  SseEmitter connect(UUID runId, String lastEventId) {
    Objects.requireNonNull(runId, "runId");
    long cursor = parseLastEventId(lastEventId);
    String initialState = requireRunState(runId);

    Emitter emitter = emitters.create(emitterTimeout);
    Subscription subscription = new Subscription(
        UUID.randomUUID(), runId, emitter, cursor, clock.instant());
    emitter.onCompletion(() -> disconnect(subscription));
    emitter.onTimeout(() -> disconnect(subscription));
    emitter.onError(ignored -> disconnect(subscription));

    catchUp(subscription, initialState, false);
    if (subscription.closed.get()) {
      return emitter.response();
    }

    subscriptions.put(subscription.id, subscription);
    try {
      ensureHub();
    } catch (RuntimeException schedulingFailure) {
      fail(subscription, schedulingFailure);
      return emitter.response();
    }

    // Register first, then immediately catch up again. Any commit between the initial replay and
    // registration is therefore observed here or by the already-attached polling hub.
    pollOne(subscription);
    return emitter.response();
  }

  static long parseLastEventId(String raw) {
    if (raw == null) {
      return -1L;
    }
    if (raw.isEmpty() || !raw.chars().allMatch(character ->
        character >= '0' && character <= '9')) {
      throw TradingLabSseRequestException.badLastEventId();
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException overflow) {
      throw TradingLabSseRequestException.badLastEventId();
    }
  }

  void pollNow() {
    for (Subscription subscription : List.copyOf(subscriptions.values())) {
      pollOne(subscription);
    }
  }

  int activeEmitterCount() {
    return subscriptions.size();
  }

  private void pollOne(Subscription subscription) {
    if (subscription.closed.get()) {
      return;
    }
    try {
      Optional<String> state = readRunState(subscription.runId);
      if (state.isEmpty()) {
        fail(subscription, TradingLabSseRequestException.runNotFound());
        return;
      }
      catchUp(subscription, state.orElseThrow(), true);
      if (!subscription.closed.get()
          && Duration.between(subscription.lastActivity, clock.instant())
              .compareTo(heartbeatInterval) >= 0) {
        heartbeat(subscription);
      }
    } catch (RuntimeException readOrProjectionFailure) {
      fail(subscription, readOrProjectionFailure);
    }
  }

  private void catchUp(
      Subscription subscription,
      String runState,
      boolean terminalCompletionAllowed
  ) {
    subscription.lock.lock();
    try {
      if (subscription.closed.get()) {
        return;
      }
      boolean drained = false;
      for (int pageNumber = 0; pageNumber < maxPagesPerPass; pageNumber++) {
        List<TradingLabRunEventEntity> page =
            readPage(subscription.runId, subscription.cursor);
        if (page.isEmpty()) {
          drained = true;
          break;
        }
        for (TradingLabRunEventEntity event : page) {
          if (subscription.closed.get()) {
            return;
          }
          long sequence = requireSequence(subscription, event);
          if (sequence <= subscription.cursor) {
            continue;
          }
          if (subscription.cursor != Long.MAX_VALUE
              && sequence != subscription.cursor + 1L) {
            throw new IllegalStateException(
                "Trading Lab SSE journal replay is not contiguous");
          }
          TradingLabSseEventProjector.PublicEvent projected = projector.project(event);
          if (!sendEvent(
              subscription,
              sequence,
              projected.name(),
              projected.data())) {
            return;
          }
          subscription.cursor = sequence;
        }
        if (page.size() < pageSize) {
          drained = true;
          break;
        }
      }
      if (terminalCompletionAllowed && drained && terminal(runState)) {
        complete(subscription, runState);
      }
    } finally {
      subscription.lock.unlock();
    }
  }

  private long requireSequence(
      Subscription subscription,
      TradingLabRunEventEntity event
  ) {
    if (event == null
        || !subscription.runId.equals(event.getRunId())
        || event.getSequence() == null
        || event.getSequence() < 0L) {
      throw new IllegalStateException("Trading Lab SSE journal identity is corrupt");
    }
    return event.getSequence();
  }

  private boolean sendEvent(
      Subscription subscription,
      Long id,
      String name,
      Map<String, Object> data
  ) {
    try {
      subscription.emitter.event(id, name, data);
      subscription.lastActivity = clock.instant();
      return true;
    } catch (IOException | IllegalStateException sendFailure) {
      fail(subscription, sendFailure);
      return false;
    }
  }

  private void heartbeat(Subscription subscription) {
    subscription.lock.lock();
    try {
      if (subscription.closed.get()) {
        return;
      }
      try {
        subscription.emitter.comment("heartbeat");
        subscription.lastActivity = clock.instant();
      } catch (IOException | IllegalStateException sendFailure) {
        fail(subscription, sendFailure);
      }
    } finally {
      subscription.lock.unlock();
    }
  }

  private void complete(Subscription subscription, String state) {
    if (!subscription.completeSent.compareAndSet(false, true)) {
      return;
    }
    Map<String, Object> data = Collections.unmodifiableMap(
        new LinkedHashMap<>(Map.of("state", state)));
    // Completion is a terminal control frame, not another journal row. It deliberately has no id:
    // every id-bearing frame remains exactly one durable run_events.sequence, and reconnecting
    // with the last durable id can still receive completion if the prior socket died in between.
    if (!sendEvent(subscription, null, "complete", data)) {
      return;
    }
    remove(subscription);
    try {
      subscription.emitter.complete();
    } catch (IllegalStateException ignored) {
      // The container may have completed concurrently; removal above is the durable cleanup.
    }
  }

  private void disconnect(Subscription subscription) {
    remove(subscription);
  }

  private void fail(Subscription subscription, Throwable failure) {
    if (!remove(subscription)) {
      return;
    }
    try {
      subscription.emitter.completeWithError(failure);
    } catch (IllegalStateException ignored) {
      // A disconnect can win concurrently with the send failure.
    }
  }

  private boolean remove(Subscription subscription) {
    if (!subscription.closed.compareAndSet(false, true)) {
      return false;
    }
    subscriptions.remove(subscription.id, subscription);
    stopHubIfIdle();
    return true;
  }

  private void ensureHub() {
    synchronized (hubMonitor) {
      if (hubTask != null && !hubTask.isCancelled() && !hubTask.isDone()) {
        return;
      }
      ScheduledFuture<?> scheduled =
          scheduler.scheduleWithFixedDelay(this::pollNow, pollDelay);
      if (scheduled == null) {
        throw new IllegalStateException("Trading Lab SSE polling hub was not scheduled");
      }
      hubTask = scheduled;
    }
  }

  private void stopHubIfIdle() {
    synchronized (hubMonitor) {
      if (!subscriptions.isEmpty() || hubTask == null) {
        return;
      }
      hubTask.cancel(false);
      hubTask = null;
    }
  }

  private String requireRunState(UUID runId) {
    return readRunState(runId)
        .orElseThrow(TradingLabSseRequestException::runNotFound);
  }

  private Optional<String> readRunState(UUID runId) {
    Optional<String> state = committedReads.execute(
        ignored -> events.findAdminSseRunState(runId));
    return Objects.requireNonNull(state, "Trading Lab SSE run state read");
  }

  private List<TradingLabRunEventEntity> readPage(UUID runId, long cursor) {
    List<TradingLabRunEventEntity> page = committedReads.execute(
        ignored -> events.findAdminSseAfter(runId, cursor, pageSize));
    return List.copyOf(Objects.requireNonNull(page, "Trading Lab SSE journal page"));
  }

  private static TransactionTemplate committedReadTransactions(
      PlatformTransactionManager transactionManager
  ) {
    TransactionTemplate transactions = new TransactionTemplate(
        Objects.requireNonNull(transactionManager, "transactionManager"));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setReadOnly(true);
    transactions.setTimeout(5);
    return transactions;
  }

  private static boolean terminal(String state) {
    return "COMPLETED".equals(state)
        || "FAILED".equals(state)
        || "CANCELLED".equals(state);
  }

  private static boolean positive(Duration duration) {
    return duration != null && !duration.isZero() && !duration.isNegative();
  }

  interface EmitterFactory {

    Emitter create(Duration timeout);
  }

  interface Emitter {

    SseEmitter response();

    void event(Long id, String name, Map<String, Object> data) throws IOException;

    void comment(String comment) throws IOException;

    void complete();

    void completeWithError(Throwable failure);

    void onCompletion(Runnable callback);

    void onTimeout(Runnable callback);

    void onError(Consumer<Throwable> callback);
  }

  private static final class DefaultEmitter implements Emitter {

    private final SseEmitter delegate;

    private DefaultEmitter(Duration timeout) {
      delegate = new SseEmitter(timeout.toMillis());
    }

    @Override
    public SseEmitter response() {
      return delegate;
    }

    @Override
    public void event(Long id, String name, Map<String, Object> data) throws IOException {
      SseEmitter.SseEventBuilder builder = SseEmitter.event()
          .name(name)
          .data(data, MediaType.APPLICATION_JSON);
      if (id != null) {
        builder.id(Long.toString(id));
      }
      delegate.send(builder);
    }

    @Override
    public void comment(String comment) throws IOException {
      delegate.send(SseEmitter.event().comment(comment));
    }

    @Override
    public void complete() {
      delegate.complete();
    }

    @Override
    public void completeWithError(Throwable failure) {
      delegate.completeWithError(failure);
    }

    @Override
    public void onCompletion(Runnable callback) {
      delegate.onCompletion(callback);
    }

    @Override
    public void onTimeout(Runnable callback) {
      delegate.onTimeout(callback);
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
      delegate.onError(callback);
    }
  }

  private static final class Subscription {

    private final UUID id;
    private final UUID runId;
    private final Emitter emitter;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean completeSent = new AtomicBoolean();
    private long cursor;
    private Instant lastActivity;

    private Subscription(
        UUID id,
        UUID runId,
        Emitter emitter,
        long cursor,
        Instant lastActivity
    ) {
      this.id = Objects.requireNonNull(id, "id");
      this.runId = Objects.requireNonNull(runId, "runId");
      this.emitter = Objects.requireNonNull(emitter, "emitter");
      this.cursor = cursor;
      this.lastActivity = Objects.requireNonNull(lastActivity, "lastActivity");
    }
  }
}
