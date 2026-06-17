package com.fxplatform.market.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BinanceRealtimeClient implements BinanceRealtimeControlClient {

  private static final Duration ROTATE_AFTER = Duration.ofHours(23).plusMinutes(50);

  private final MarketRealtimeProperties properties;
  private final BinanceRealtimeConnection connection;
  private final BinanceStreamMessageParser parser;
  private final RealtimeDeduplicationState deduplicationState;
  private final RealtimeQuoteSink sink;
  private final BinanceSubscriptionManager subscriptionManager;
  private final RealtimeBackfillService backfillService;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final LongUnaryOperator jitterOffset;
  private final AtomicLong commandIds = new AtomicLong();
  private final AtomicLong parseFailureCount = new AtomicLong();
  private final AtomicLong connectionIds = new AtomicLong();
  private volatile boolean connected;
  private volatile boolean reconnectScheduled;
  private volatile long activeConnectionId;
  private volatile long connectedAt = Long.MIN_VALUE;
  private volatile long lastMessageAt = Long.MIN_VALUE;
  private volatile long lastDisconnectAt = Long.MIN_VALUE;
  private volatile int reconnectAttempt;

  @Autowired
  public BinanceRealtimeClient(
      MarketRealtimeProperties properties,
      BinanceRealtimeConnection connection,
      BinanceStreamMessageParser parser,
      RealtimeDeduplicationState deduplicationState,
      RealtimeQuoteSink sink,
      BinanceSubscriptionManager subscriptionManager,
      RealtimeBackfillService backfillService,
      ObjectMapper objectMapper
  ) {
    this(
        properties,
        connection,
        parser,
        deduplicationState,
        sink,
        subscriptionManager,
        backfillService,
        objectMapper,
        Executors.newSingleThreadScheduledExecutor(),
        Clock.systemUTC(),
        BinanceRealtimeClient::randomJitterOffset);
  }

  BinanceRealtimeClient(
      MarketRealtimeProperties properties,
      BinanceRealtimeConnection connection,
      BinanceStreamMessageParser parser,
      RealtimeDeduplicationState deduplicationState,
      RealtimeQuoteSink sink,
      BinanceSubscriptionManager subscriptionManager,
      RealtimeBackfillService backfillService,
      ObjectMapper objectMapper,
      ScheduledExecutorService scheduler,
      Clock clock
  ) {
    this(
        properties,
        connection,
        parser,
        deduplicationState,
        sink,
        subscriptionManager,
        backfillService,
        objectMapper,
        scheduler,
        clock,
        BinanceRealtimeClient::randomJitterOffset);
  }

  BinanceRealtimeClient(
      MarketRealtimeProperties properties,
      BinanceRealtimeConnection connection,
      BinanceStreamMessageParser parser,
      RealtimeDeduplicationState deduplicationState,
      RealtimeQuoteSink sink,
      BinanceSubscriptionManager subscriptionManager,
      RealtimeBackfillService backfillService,
      ObjectMapper objectMapper,
      ScheduledExecutorService scheduler,
      Clock clock,
      LongUnaryOperator jitterOffset
  ) {
    this.properties = properties;
    this.connection = connection;
    this.parser = parser;
    this.deduplicationState = deduplicationState;
    this.sink = sink;
    this.subscriptionManager = subscriptionManager;
    this.backfillService = backfillService;
    this.objectMapper = objectMapper;
    this.scheduler = scheduler;
    this.clock = clock;
    this.jitterOffset = jitterOffset;
  }

  public void connect() {
    long connectionId = connectionIds.incrementAndGet();
    synchronized (this) {
      activeConnectionId = connectionId;
      reconnectScheduled = false;
    }
    connection.connect(URI.create(properties.websocketBaseUrl()), new ConnectionListener(connectionId));
  }

  @Override
  public void sendControlMessage(String method, List<?> params) {
    try {
      connection.sendText(objectMapper.writeValueAsString(new ControlMessage(method, params, commandIds.incrementAndGet())));
    } catch (JsonProcessingException ex) {
      parseFailureCount.incrementAndGet();
    }
  }

  public boolean connected() {
    return connected;
  }

  public OptionalLong connectedAt() {
    return connectedAt == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(connectedAt);
  }

  public OptionalLong lastMessageAt() {
    return lastMessageAt == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(lastMessageAt);
  }

  public OptionalLong lastDisconnectAt() {
    return lastDisconnectAt == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(lastDisconnectAt);
  }

  public int reconnectAttempt() {
    return reconnectAttempt;
  }

  public long parseFailureCount() {
    return parseFailureCount.get();
  }

  @Scheduled(fixedDelayString = "${market.realtime.rotation-check-ms:60000}")
  void runMaintenance() {
    if (connected && connectedAt().isPresent()
        && clock.instant().toEpochMilli() - connectedAt > ROTATE_AFTER.toMillis()) {
      requestReconnect(activeConnectionId, Duration.ZERO, true, false);
    }
  }

  @PreDestroy
  public void shutdown() {
    synchronized (this) {
      connected = false;
      reconnectScheduled = false;
    }
    connection.close();
    scheduler.shutdownNow();
  }

  private synchronized boolean isActiveConnection(long connectionId) {
    return connectionId == activeConnectionId;
  }

  private void handleOpen(long connectionId) {
    synchronized (this) {
      if (!isActiveConnection(connectionId)) {
        return;
      }
      connected = true;
      connectedAt = clock.instant().toEpochMilli();
      reconnectScheduled = false;
      reconnectAttempt = 0;
    }
    sendControlMessage("SET_PROPERTY", List.of("combined", true));
    subscriptionManager.onConnected();
    if (properties.backfillEnabled()) {
      backfillService.backfill(subscriptionManager.activeSymbols());
    }
  }

  private void handleText(long connectionId, String payload) {
    if (!isActiveConnection(connectionId)) {
      return;
    }
    lastMessageAt = clock.instant().toEpochMilli();
    try {
      parser.parseRequired(payload).ifPresent(event -> {
        if (event instanceof RealtimeMarketEvent.ServerShutdown) {
          requestReconnect(connectionId, Duration.ZERO, true, false);
          return;
        }
        if (deduplicationState.shouldProcess(event)) {
          sink.process(event);
        }
      });
    } catch (RuntimeException | IOException ex) {
      parseFailureCount.incrementAndGet();
    }
  }

  private void handleClose(long connectionId) {
    requestReconnect(connectionId, properties.reconnectInitial(), false, true);
  }

  private void requestReconnect(long connectionId, Duration delay, boolean closeConnection, boolean countAttempt) {
    Duration reconnectDelay;
    synchronized (this) {
      if (connectionId != activeConnectionId || reconnectScheduled) {
        return;
      }
      connected = false;
      lastDisconnectAt = clock.instant().toEpochMilli();
      if (countAttempt) {
        reconnectAttempt++;
        reconnectDelay = nextBackoff();
      } else {
        reconnectDelay = delay;
      }
      reconnectScheduled = true;
    }
    if (closeConnection) {
      try {
        connection.close();
      } catch (RuntimeException ignored) {
      }
    }
    scheduleReconnect(reconnectDelay);
  }

  private void scheduleReconnect(Duration delay) {
    scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private Duration nextBackoff() {
    long baseMillis = properties.reconnectInitial().toMillis();
    long maxMillis = properties.reconnectMax().toMillis();
    long factor = 1L << Math.min(30, Math.max(0, reconnectAttempt - 1));
    long jitterMillis = Math.max(0L, properties.reconnectJitter().toMillis());
    long delayMillis = Math.min(maxMillis, baseMillis * factor) + jitterOffset.applyAsLong(jitterMillis);
    return Duration.ofMillis(Math.max(0L, Math.min(maxMillis, delayMillis)));
  }

  private static long randomJitterOffset(long jitterMillis) {
    if (jitterMillis <= 0L) {
      return 0L;
    }
    return ThreadLocalRandom.current().nextLong(-jitterMillis, jitterMillis + 1L);
  }

  private record ControlMessage(String method, List<?> params, long id) {
  }

  private class ConnectionListener implements BinanceRealtimeConnection.Listener {

    private final long connectionId;

    private ConnectionListener(long connectionId) {
      this.connectionId = connectionId;
    }

    @Override
    public void onOpen() {
      handleOpen(connectionId);
    }

    @Override
    public void onText(String payload) {
      handleText(connectionId, payload);
    }

    @Override
    public void onPing(ByteBuffer payload) {
      connection.sendPong(payload);
    }

    @Override
    public void onClose(int statusCode, String reason) {
      handleClose(connectionId);
    }

    @Override
    public void onError(Throwable error) {
      handleClose(connectionId);
    }
  }
}
