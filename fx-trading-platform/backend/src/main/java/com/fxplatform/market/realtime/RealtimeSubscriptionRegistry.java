package com.fxplatform.market.realtime;

import com.fxplatform.common.market.SymbolNormalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RealtimeSubscriptionRegistry {

  private static final String QUOTE_PREFIX = "/topic/market/quotes/";
  private static final String ORDER_BOOK_PREFIX = "/topic/market/order-book/";
  private static final String TRADES_PREFIX = "/topic/market/trades/";

  private final Map<SubscriptionKey, SessionSubscription> subscriptions = new HashMap<>();
  private final Map<String, Set<SubscriptionKey>> subscriptionsBySession = new HashMap<>();
  private final Map<SessionSymbol, Integer> demandBySessionSymbol = new HashMap<>();
  private final Map<String, Integer> symbolRefCounts = new HashMap<>();

  public synchronized Optional<SymbolDemandChange> subscribe(String sessionId, String subscriptionId, String destination) {
    return subscribeMany(sessionId, subscriptionId, destination).stream()
        .reduce((ignored, latest) -> latest);
  }

  public synchronized List<SymbolDemandChange> subscribeMany(String sessionId, String subscriptionId, String destination) {
    List<SymbolDemandChange> changes = new ArrayList<>();
    Optional<DestinationDemand> demand = parseDestination(destination);
    if (demand.isEmpty() || isBlank(sessionId) || isBlank(subscriptionId)) {
      return changes;
    }

    SubscriptionKey key = new SubscriptionKey(sessionId, subscriptionId);
    removeSubscription(key).ifPresent(changes::add);

    String symbol = demand.get().symbol();
    int previousRefCount = symbolRefCounts.getOrDefault(symbol, 0);
    subscriptions.put(key, new SessionSubscription(sessionId, subscriptionId, destination, symbol, demand.get().kinds()));
    subscriptionsBySession.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(key);
    addSessionSymbolDemand(sessionId, symbol);
    int currentRefCount = symbolRefCounts.getOrDefault(symbol, 0);
    changes.add(new SymbolDemandChange(symbol, previousRefCount, currentRefCount));
    return changes;
  }

  public synchronized Optional<SymbolDemandChange> unsubscribe(String sessionId, String subscriptionId) {
    if (isBlank(sessionId) || isBlank(subscriptionId)) {
      return Optional.empty();
    }
    return removeSubscription(new SubscriptionKey(sessionId, subscriptionId));
  }

  public synchronized List<SymbolDemandChange> disconnect(String sessionId) {
    Set<SubscriptionKey> keys = subscriptionsBySession.getOrDefault(sessionId, Set.of());
    List<SubscriptionKey> keysToRemove = new ArrayList<>(keys);
    List<SymbolDemandChange> changes = new ArrayList<>();
    for (SubscriptionKey key : keysToRemove) {
      removeSubscription(key).ifPresent(changes::add);
    }
    subscriptionsBySession.remove(sessionId);
    return changes;
  }

  private Optional<SymbolDemandChange> removeSubscription(SubscriptionKey key) {
    SessionSubscription subscription = subscriptions.remove(key);
    if (subscription == null) {
      return Optional.empty();
    }

    Set<SubscriptionKey> sessionSubscriptions = subscriptionsBySession.get(subscription.sessionId());
    if (sessionSubscriptions != null) {
      sessionSubscriptions.remove(key);
      if (sessionSubscriptions.isEmpty()) {
        subscriptionsBySession.remove(subscription.sessionId());
      }
    }

    String symbol = subscription.symbol();
    int previousRefCount = symbolRefCounts.getOrDefault(symbol, 0);
    removeSessionSymbolDemand(subscription.sessionId(), symbol);
    int currentRefCount = symbolRefCounts.getOrDefault(symbol, 0);
    return Optional.of(new SymbolDemandChange(symbol, previousRefCount, currentRefCount));
  }

  private void addSessionSymbolDemand(String sessionId, String symbol) {
    SessionSymbol key = new SessionSymbol(sessionId, symbol);
    int previousDemand = demandBySessionSymbol.getOrDefault(key, 0);
    demandBySessionSymbol.put(key, previousDemand + 1);
    if (previousDemand == 0) {
      symbolRefCounts.merge(symbol, 1, Integer::sum);
    }
  }

  private void removeSessionSymbolDemand(String sessionId, String symbol) {
    SessionSymbol key = new SessionSymbol(sessionId, symbol);
    int previousDemand = demandBySessionSymbol.getOrDefault(key, 0);
    if (previousDemand <= 1) {
      demandBySessionSymbol.remove(key);
      symbolRefCounts.computeIfPresent(symbol, (ignored, count) -> count <= 1 ? null : count - 1);
      return;
    }
    demandBySessionSymbol.put(key, previousDemand - 1);
  }

  private Optional<DestinationDemand> parseDestination(String destination) {
    if (isBlank(destination)) {
      return Optional.empty();
    }
    if (destination.startsWith(QUOTE_PREFIX)) {
      return demand(destination, QUOTE_PREFIX, EnumSet.of(
          RealtimeStreamKind.QUOTE,
          RealtimeStreamKind.TICKER_STATS,
          RealtimeStreamKind.KLINE));
    }
    if (destination.startsWith(ORDER_BOOK_PREFIX)) {
      return demand(destination, ORDER_BOOK_PREFIX, EnumSet.of(RealtimeStreamKind.ORDER_BOOK));
    }
    if (destination.startsWith(TRADES_PREFIX)) {
      return demand(destination, TRADES_PREFIX, EnumSet.of(RealtimeStreamKind.TRADE));
    }
    return Optional.empty();
  }

  private Optional<DestinationDemand> demand(String destination, String prefix, EnumSet<RealtimeStreamKind> kinds) {
    String rawSymbol = destination.substring(prefix.length());
    if (isBlank(rawSymbol)) {
      return Optional.empty();
    }
    return Optional.of(new DestinationDemand(SymbolNormalizer.normalize(rawSymbol), kinds));
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record SessionSubscription(
      String sessionId,
      String subscriptionId,
      String destination,
      String symbol,
      EnumSet<RealtimeStreamKind> kinds
  ) {
  }

  public record SymbolDemandChange(String symbol, int previousRefCount, int currentRefCount) {

    public boolean activated() {
      return previousRefCount == 0 && currentRefCount > 0;
    }

    public boolean deactivated() {
      return previousRefCount > 0 && currentRefCount == 0;
    }
  }

  private record DestinationDemand(String symbol, EnumSet<RealtimeStreamKind> kinds) {
  }

  private record SessionSymbol(String sessionId, String symbol) {
  }

  private record SubscriptionKey(String sessionId, String subscriptionId) {
  }
}
