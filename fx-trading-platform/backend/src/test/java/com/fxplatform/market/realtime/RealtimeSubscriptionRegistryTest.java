package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RealtimeSubscriptionRegistryTest {

  private final RealtimeSubscriptionRegistry registry = new RealtimeSubscriptionRegistry();

  @Test
  void countsUniqueSessionSymbolDemandInsteadOfTopicCount() {
    RealtimeSubscriptionRegistry.SymbolDemandChange quoteChange = registry.subscribe(
        "session-1",
        "quote-1",
        "/topic/market/quotes/BTCUSDT").orElseThrow();
    RealtimeSubscriptionRegistry.SymbolDemandChange orderBookChange = registry.subscribe(
        "session-1",
        "book-1",
        "/topic/market/order-book/BTCUSDT").orElseThrow();

    assertThat(quoteChange.activated()).isTrue();
    assertThat(quoteChange.currentRefCount()).isEqualTo(1);
    assertThat(orderBookChange.activated()).isFalse();
    assertThat(orderBookChange.previousRefCount()).isEqualTo(1);
    assertThat(orderBookChange.currentRefCount()).isEqualTo(1);
  }

  @Test
  void twoSessionsSameSymbolCountAsTwoRefs() {
    registry.subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT");

    RealtimeSubscriptionRegistry.SymbolDemandChange change = registry.subscribe(
        "session-2",
        "quote-2",
        "/topic/market/quotes/BTCUSDT").orElseThrow();

    assertThat(change.previousRefCount()).isEqualTo(1);
    assertThat(change.currentRefCount()).isEqualTo(2);
    assertThat(change.activated()).isFalse();
  }

  @Test
  void unsubscribeOneSubscriptionLeavesOtherSessionActive() {
    registry.subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT");
    registry.subscribe("session-2", "quote-2", "/topic/market/quotes/BTCUSDT");

    RealtimeSubscriptionRegistry.SymbolDemandChange change = registry.unsubscribe("session-1", "quote-1")
        .orElseThrow();

    assertThat(change.previousRefCount()).isEqualTo(2);
    assertThat(change.currentRefCount()).isEqualTo(1);
    assertThat(change.deactivated()).isFalse();
  }

  @Test
  void disconnectRemovesAllSubscriptionsForSession() {
    registry.subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT");
    registry.subscribe("session-1", "trades-1", "/topic/market/trades/ETHUSDT");

    List<RealtimeSubscriptionRegistry.SymbolDemandChange> changes = registry.disconnect("session-1");

    assertThat(changes).hasSize(2);
    assertThat(changes).allSatisfy(change -> assertThat(change.deactivated()).isTrue());
    assertThat(changes).extracting(RealtimeSubscriptionRegistry.SymbolDemandChange::symbol)
        .containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
  }

  @Test
  void invalidDestinationIsIgnored() {
    assertThat(registry.subscribe("session-1", "bad-1", "/topic/market/bad/BTCUSDT")).isEmpty();
  }

  @Test
  void replacingSubscriptionIdReportsOldAndNewSymbolChanges() {
    registry.subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT");

    List<RealtimeSubscriptionRegistry.SymbolDemandChange> changes = registry.subscribeMany(
        "session-1",
        "quote-1",
        "/topic/market/quotes/ETHUSDT");

    assertThat(changes).hasSize(2);
    assertThat(changes.get(0).symbol()).isEqualTo("BTCUSDT");
    assertThat(changes.get(0).deactivated()).isTrue();
    assertThat(changes.get(1).symbol()).isEqualTo("ETHUSDT");
    assertThat(changes.get(1).activated()).isTrue();
  }
}
