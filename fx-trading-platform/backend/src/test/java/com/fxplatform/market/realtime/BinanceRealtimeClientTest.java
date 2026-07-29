package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.adapter.binance.BinanceSpotMarketDataProvider;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BinanceRealtimeClientTest {

  private final MarketRealtimeProperties properties = new MarketRealtimeProperties();
  private final FakeConnection connection = new FakeConnection();
  private final BinanceStreamMessageParser parser = new BinanceStreamMessageParser(new ObjectMapper());
  private final RealtimeDeduplicationState deduplicationState = new RealtimeDeduplicationState();
  private final FakeBackfillService backfillService = new FakeBackfillService();
  private final FakeScheduler scheduler = new FakeScheduler();
  private final BinanceStreamCommandLimiterTest.MutableClock clock = new BinanceStreamCommandLimiterTest.MutableClock();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock
  private RealtimeQuoteSink sink;

  @Test
  void openSendsCombinedModeBeforeReplayingSubscriptions() throws Exception {
    FakeSubscriptionManager subscriptionManager = subscriptionManager(Set.of("BTCUSDT"));
    BinanceRealtimeClient client = client(subscriptionManager);

    client.connect();
    connection.open();

    assertThat(connection.uri).isEqualTo(URI.create(properties.websocketBaseUrl()));
    assertThat(connection.events.getFirst()).startsWith("TEXT:");
    assertThat(objectMapper.readTree(connection.events.getFirst().substring("TEXT:".length())).path("method").asText())
        .isEqualTo("SET_PROPERTY");
    assertThat(connection.events.getFirst()).contains("combined");
    assertThat(subscriptionManager.connectedCount).isEqualTo(1);
  }

  @Test
  void acceptedTextPayloadReachesSink() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();

    connection.text("""
        {"u":400900217,"s":"BTCUSDT","b":"100.00","B":"1.2","a":"101.00","A":"2.4"}
        """);

    verify(sink).process(org.mockito.ArgumentMatchers.isA(RealtimeMarketEvent.Quote.class));
    assertThat(client.lastMessageAt()).hasValue(clock.instant().toEpochMilli());
  }

  @Test
  void parseErrorIsCountedWithoutThrowingFromReceiveLoop() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();

    connection.text("{not-json");

    assertThat(client.parseFailureCount()).isEqualTo(1);
    assertThat(client.lastMessageAt()).hasValue(clock.instant().toEpochMilli());
  }

  @Test
  void serverShutdownSchedulesImmediateReconnectAndReplayAfterOpen() {
    FakeSubscriptionManager subscriptionManager = subscriptionManager(Set.of("BTCUSDT"));
    BinanceRealtimeClient client = client(subscriptionManager);
    client.connect();
    connection.open();
    connection.events.clear();

    connection.text("""
        {"e":"serverShutdown","E":123456789}
        """);

    assertThat(scheduler.tasks).hasSize(1);
    assertThat(scheduler.tasks.getFirst().delayMillis).isZero();
    assertThat(connection.closed).isTrue();

    connection.closeFromServer();
    assertThat(scheduler.tasks).hasSize(1);

    scheduler.runNext();
    connection.open();

    assertThat(subscriptionManager.connectedCount).isEqualTo(2);
    assertThat(connection.events).anySatisfy(event -> assertThat(event).contains("SET_PROPERTY"));
  }

  @Test
  void closeAndErrorUseExponentialReconnectBackoffCappedAtThirtySeconds() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();

    List<Long> delays = new ArrayList<>();
    connection.closeFromServer();
    delays.add(scheduler.tasks.getLast().delayMillis);
    scheduler.runNext();
    connection.error(new RuntimeException("network"));
    delays.add(scheduler.tasks.getLast().delayMillis);
    scheduler.runNext();
    connection.error(new RuntimeException("network"));
    delays.add(scheduler.tasks.getLast().delayMillis);
    for (int index = 0; index < 10; index++) {
      scheduler.runNext();
      connection.error(new RuntimeException("network"));
      delays.add(scheduler.tasks.getLast().delayMillis);
    }

    assertThat(delays).startsWith(1000L, 2000L, 4000L);
    assertThat(delays).allMatch(delay -> delay <= 30000L);
  }

  @Test
  void reconnectBackoffIncludesConfiguredJitter() {
    properties.setReconnectJitter(Duration.ofMillis(250));
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()), jitterMillis -> 125L);
    client.connect();
    connection.open();

    connection.closeFromServer();

    assertThat(scheduler.tasks.getFirst().delayMillis).isEqualTo(1125L);
  }

  @Test
  void maintenanceRotationIsScheduledOutsideValidation() throws IOException {
    String source = Files.readString(
        Path.of("src/main/java/com/fxplatform/market/realtime/BinanceRealtimeRotationScheduler.java"));

    assertThat(source).contains("@Profile(\"!validation\")");
    assertThat(source).contains("@Scheduled");
    assertThat(source).contains("market.realtime.rotation-check-ms");
    assertThat(source).contains("binanceRealtimeClient.runMaintenance()");
  }

  @Test
  void repeatedCloseAndErrorBeforeReconnectAreIgnored() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();

    connection.closeFromServer();
    connection.error(new RuntimeException("same-disconnect"));
    connection.closeFromServer();

    assertThat(scheduler.tasks).hasSize(1);
    assertThat(scheduler.tasks.getFirst().delayMillis).isEqualTo(1000L);
  }

  @Test
  void connectFailureBeforeOpenSchedulesReconnectBackoff() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));

    client.connect();
    connection.error(new RuntimeException("handshake"));

    assertThat(client.connected()).isFalse();
    assertThat(client.lastDisconnectAt()).hasValue(clock.instant().toEpochMilli());
    assertThat(scheduler.tasks).hasSize(1);
    assertThat(scheduler.tasks.getFirst().delayMillis).isEqualTo(1000L);
  }

  @Test
  void openTriggersBackfillForActiveSymbols() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of("BTCUSDT")));

    client.connect();
    connection.open();

    assertThat(backfillService.symbolBatches).containsExactly(Set.of("BTCUSDT"));
  }

  @Test
  void pingPongIsSentBeforeQueuedJsonControlCommands() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();
    connection.events.clear();

    connection.ping(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    client.sendControlMessage("SUBSCRIBE", List.of("btcusdt@bookTicker"));

    assertThat(connection.events.get(0)).isEqualTo("PONG:3");
    assertThat(connection.events.get(1)).startsWith("TEXT:");
  }

  @Test
  void activeConnectionRotatesAfterTwentyThreeHoursFiftyMinutes() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();

    clock.advanceMillis(Duration.ofHours(23).plusMinutes(50).plusMillis(1).toMillis());
    client.runMaintenance();

    assertThat(connection.closed).isTrue();
    connection.closeFromServer();
    assertThat(scheduler.tasks).hasSize(1);
    assertThat(scheduler.tasks.getFirst().delayMillis).isZero();
  }

  @Test
  void shutdownClosesConnectionAndStopsScheduler() {
    BinanceRealtimeClient client = client(subscriptionManager(Set.of()));
    client.connect();
    connection.open();

    client.shutdown();

    assertThat(connection.closed).isTrue();
    assertThat(scheduler.shutdownNowCalled).isTrue();
    assertThat(client.connected()).isFalse();
  }

  private BinanceRealtimeClient client(FakeSubscriptionManager subscriptionManager) {
    return client(subscriptionManager, jitterMillis -> 0L);
  }

  private BinanceRealtimeClient client(FakeSubscriptionManager subscriptionManager, java.util.function.LongUnaryOperator jitterOffset) {
    return new BinanceRealtimeClient(
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
        jitterOffset);
  }

  private FakeSubscriptionManager subscriptionManager(Set<String> activeSymbols) {
    return new FakeSubscriptionManager(properties, activeSymbols, clock);
  }

  private static class FakeConnection implements BinanceRealtimeConnection {

    private final List<String> events = new ArrayList<>();
    private URI uri;
    private Listener listener;
    private boolean closed;

    @Override
    public void connect(URI uri, Listener listener) {
      this.uri = uri;
      this.listener = listener;
    }

    @Override
    public void sendText(String text) {
      events.add("TEXT:" + text);
    }

    @Override
    public void sendPong(ByteBuffer payload) {
      events.add("PONG:" + payload.remaining());
    }

    @Override
    public void close() {
      closed = true;
    }

    void open() {
      listener.onOpen();
    }

    void text(String payload) {
      listener.onText(payload);
    }

    void ping(ByteBuffer payload) {
      listener.onPing(payload);
    }

    void closeFromServer() {
      listener.onClose(1000, "closed");
    }

    void error(Throwable throwable) {
      listener.onError(throwable);
    }
  }

  private static class FakeSubscriptionManager extends BinanceSubscriptionManager {

    private final Set<String> activeSymbols;
    private int connectedCount;

    FakeSubscriptionManager(MarketRealtimeProperties properties, Set<String> activeSymbols, Clock clock) {
      super(properties, (method, params) -> {
      }, clock);
      this.activeSymbols = activeSymbols;
    }

    @Override
    public synchronized void onConnected() {
      connectedCount++;
    }

    @Override
    public synchronized Set<String> activeSymbols() {
      return activeSymbols;
    }
  }

  private static class FakeBackfillService extends RealtimeBackfillService {

    private final List<Set<String>> symbolBatches = new ArrayList<>();

    FakeBackfillService() {
      super(
          new MarketRealtimeProperties(),
          org.mockito.Mockito.mock(BinanceSpotMarketDataProvider.class),
          org.mockito.Mockito.mock(RealtimeCandleRepository.class),
          Clock.systemUTC());
    }

    @Override
    public void backfill(Collection<String> symbols) {
      symbolBatches.add(Set.copyOf(symbols));
    }
  }

  private static class FakeScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final List<ScheduledTask> tasks = new ArrayList<>();
    private boolean shutdownNowCalled;

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      ScheduledTask task = new ScheduledTask(command, unit.toMillis(delay));
      tasks.add(task);
      return task;
    }

    void runNext() {
      tasks.removeFirst().run();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdownNowCalled = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  private record ScheduledTask(Runnable runnable, long delayMillis) implements ScheduledFuture<Object> {

    void run() {
      runnable.run();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
      return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      return null;
    }
  }
}
