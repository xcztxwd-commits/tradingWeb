package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fxplatform.market.model.MarketSourceMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarketSourceSelectionTrackerTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void firstSelectionIsSilentAndFallbackAndRecoveryEmitOnceEach() {
    List<Object> events = new CopyOnWriteArrayList<>();
    MarketSourceSelectionTracker tracker = new MarketSourceSelectionTracker(events::add, CLOCK);

    tracker.recordSelection("BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL);
    tracker.recordSelection("BTCUSDT", "okx", MarketSourceMode.PUBLIC_EXTERNAL);
    tracker.recordSelection("BTCUSDT", "okx", MarketSourceMode.PUBLIC_EXTERNAL);
    tracker.recordSelection("BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL);

    assertThat(events).hasSize(2);
    assertThat(events).allSatisfy(event ->
        assertThat(event).isInstanceOf(MarketSourceSelectionTracker.MarketSourceChangedEvent.class));
    var fallback = (MarketSourceSelectionTracker.MarketSourceChangedEvent) events.get(0);
    var recovery = (MarketSourceSelectionTracker.MarketSourceChangedEvent) events.get(1);
    assertThat(fallback.previousProviderCode()).isEqualTo("binance");
    assertThat(fallback.providerCode()).isEqualTo("okx");
    assertThat(recovery.previousProviderCode()).isEqualTo("okx");
    assertThat(recovery.providerCode()).isEqualTo("binance");
  }

  @Test
  void concurrentSameFallbackPublishesOnlyOneEvent() throws Exception {
    List<Object> events = new CopyOnWriteArrayList<>();
    MarketSourceSelectionTracker tracker = new MarketSourceSelectionTracker(events::add, CLOCK);
    tracker.recordSelection("BTCUSDT-PERP", "binance-usdm", MarketSourceMode.PUBLIC_EXTERNAL);
    int workers = 24;
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(workers)) {
      for (int index = 0; index < workers; index += 1) {
        executor.submit(() -> {
          ready.countDown();
          start.await();
          tracker.recordSelection("BTCUSDT-PERP", "local-perp", MarketSourceMode.LOCAL_SIMULATED);
          return null;
        });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(events).hasSize(1);
    var event = (MarketSourceSelectionTracker.MarketSourceChangedEvent) events.getFirst();
    assertThat(event.previousProviderCode()).isEqualTo("binance-usdm");
    assertThat(event.providerCode()).isEqualTo("local-perp");
    assertThat(event.sourceMode()).isEqualTo(MarketSourceMode.LOCAL_SIMULATED);
  }

  @Test
  void failingInternalListenerDoesNotMakeASelectedMarketBundleUnavailable() {
    AtomicInteger attempts = new AtomicInteger();
    MarketSourceSelectionTracker tracker = new MarketSourceSelectionTracker(event -> {
      attempts.incrementAndGet();
      throw new IllegalStateException("listener failed");
    }, CLOCK);
    tracker.recordSelection("BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL);

    assertThatCode(() ->
        tracker.recordSelection("BTCUSDT", "okx", MarketSourceMode.PUBLIC_EXTERNAL)).doesNotThrowAnyException();
    assertThatCode(() ->
        tracker.recordSelection("BTCUSDT", "okx", MarketSourceMode.PUBLIC_EXTERNAL)).doesNotThrowAnyException();

    assertThat(attempts).hasValue(1);
  }

  @Test
  void concurrentFallbackAndRecoveryArePublishedInSelectionOrder() throws Exception {
    List<MarketSourceSelectionTracker.MarketSourceChangedEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch firstPublishEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstPublish = new CountDownLatch(1);
    CountDownLatch secondPublishEntered = new CountDownLatch(1);
    AtomicInteger publishCalls = new AtomicInteger();
    AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
    MarketSourceSelectionTracker tracker = new MarketSourceSelectionTracker(event -> {
      try {
        int call = publishCalls.incrementAndGet();
        if (call == 1) {
          firstPublishEntered.countDown();
          releaseFirstPublish.await(5, TimeUnit.SECONDS);
        } else {
          secondPublishEntered.countDown();
        }
        events.add((MarketSourceSelectionTracker.MarketSourceChangedEvent) event);
      } catch (Throwable failure) {
        listenerFailure.set(failure);
      }
    }, CLOCK);
    tracker.recordSelection("BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var fallback = executor.submit(() ->
          tracker.recordSelection("BTCUSDT", "okx", MarketSourceMode.PUBLIC_EXTERNAL));
      assertThat(firstPublishEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var recovery = executor.submit(() ->
          tracker.recordSelection("BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL));

      secondPublishEntered.await(1, TimeUnit.SECONDS);
      releaseFirstPublish.countDown();
      fallback.get(5, TimeUnit.SECONDS);
      recovery.get(5, TimeUnit.SECONDS);
    }

    assertThat(listenerFailure.get()).isNull();
    assertThat(events).extracting(MarketSourceSelectionTracker.MarketSourceChangedEvent::providerCode)
        .containsExactly("okx", "binance");
    assertThat(events.get(1).previousProviderCode()).isEqualTo("okx");
  }
}
