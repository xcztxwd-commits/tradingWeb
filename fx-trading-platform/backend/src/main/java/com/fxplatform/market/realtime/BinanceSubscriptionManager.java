package com.fxplatform.market.realtime;

import com.fxplatform.common.market.SymbolNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BinanceSubscriptionManager {

  private static final BinanceRealtimeControlClient NOOP_CLIENT = (method, params) -> {
  };

  private final MarketRealtimeProperties properties;
  private final Supplier<BinanceRealtimeControlClient> clientSupplier;
  private final Clock clock;
  private final BinanceStreamCommandLimiter commandLimiter;
  private final AtomicLong commandIds = new AtomicLong();
  private final AtomicLong rejectedSymbolCount = new AtomicLong();
  private final Set<String> activeSymbols = new LinkedHashSet<>();
  private final Set<String> subscribedSymbols = new LinkedHashSet<>();
  private final Map<String, Instant> pendingUnsubscribes = new ConcurrentHashMap<>();
  private final ArrayDeque<ControlCommand> pendingCommands = new ArrayDeque<>();

  @Autowired
  public BinanceSubscriptionManager(
      MarketRealtimeProperties properties,
      ObjectProvider<BinanceRealtimeControlClient> clientProvider
  ) {
    this(properties, () -> clientProvider.getIfAvailable(() -> NOOP_CLIENT), Clock.systemUTC());
  }

  BinanceSubscriptionManager(MarketRealtimeProperties properties, BinanceRealtimeControlClient client, Clock clock) {
    this(properties, () -> client, clock);
  }

  private BinanceSubscriptionManager(
      MarketRealtimeProperties properties,
      Supplier<BinanceRealtimeControlClient> clientSupplier,
      Clock clock
  ) {
    this.properties = properties;
    this.clientSupplier = clientSupplier;
    this.clock = clock;
    this.commandLimiter = new BinanceStreamCommandLimiter(properties.binanceCommandPerSecond(), clock);
  }

  public synchronized void activateSymbol(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    if (pendingUnsubscribes.remove(normalizedSymbol) != null) {
      if (!activeSymbols.contains(normalizedSymbol) && activeSymbols.size() >= properties.maxActiveSymbols()) {
        rejectedSymbolCount.incrementAndGet();
        pendingUnsubscribes.put(normalizedSymbol, clock.instant().plus(properties.unsubscribeGrace()));
        return;
      }
      activeSymbols.add(normalizedSymbol);
      return;
    }
    if (activeSymbols.contains(normalizedSymbol)) {
      return;
    }
    if (activeSymbols.size() >= properties.maxActiveSymbols()) {
      rejectedSymbolCount.incrementAndGet();
      return;
    }
    activeSymbols.add(normalizedSymbol);
    if (subscribedSymbols.add(normalizedSymbol)) {
      sendControlMessage("SUBSCRIBE", streamNames(normalizedSymbol));
    }
  }

  public synchronized void deactivateSymbol(String symbol) {
    String normalizedSymbol = SymbolNormalizer.normalize(symbol);
    if (!activeSymbols.remove(normalizedSymbol) || !subscribedSymbols.contains(normalizedSymbol)) {
      return;
    }
    pendingUnsubscribes.put(normalizedSymbol, clock.instant().plus(properties.unsubscribeGrace()));
  }

  public synchronized void onConnected() {
    pendingCommands.clear();
    pendingUnsubscribes.keySet().forEach(subscribedSymbols::remove);
    pendingUnsubscribes.clear();
    subscribedSymbols.retainAll(activeSymbols);
    Set<String> streams = desiredStreams();
    if (!streams.isEmpty()) {
      sendControlMessage("SUBSCRIBE", new ArrayList<>(streams));
    }
  }

  public void onDisconnected() {
  }

  public synchronized Set<String> activeSymbols() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(activeSymbols));
  }

  public synchronized Set<String> desiredStreams() {
    LinkedHashSet<String> streams = new LinkedHashSet<>();
    for (String symbol : activeSymbols) {
      streams.addAll(streamNames(symbol));
    }
    return Collections.unmodifiableSet(streams);
  }

  public long rejectedSymbolCount() {
    return rejectedSymbolCount.get();
  }

  public synchronized MarketRealtimeStatus snapshotStatus() {
    return new MarketRealtimeStatus(
        properties.enabled(),
        properties.provider(),
        false,
        null,
        null,
        activeSymbols(),
        desiredStreams().size(),
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        rejectedSymbolCount.get());
  }

  @Scheduled(fixedDelayString = "${market.realtime.maintenance-delay:1000}")
  synchronized void runMaintenance() {
    Instant now = clock.instant();
    List<String> expired = pendingUnsubscribes.entrySet().stream()
        .filter(entry -> !entry.getValue().isAfter(now))
        .map(Map.Entry::getKey)
        .toList();
    for (String symbol : expired) {
      pendingUnsubscribes.remove(symbol);
      if (!activeSymbols.contains(symbol) && subscribedSymbols.remove(symbol)) {
        sendControlMessage("UNSUBSCRIBE", streamNames(symbol));
      }
    }
    flushPendingCommands();
  }

  synchronized void flushPendingCommands() {
    while (!pendingCommands.isEmpty() && commandLimiter.tryAcquire()) {
      ControlCommand command = pendingCommands.removeFirst();
      clientSupplier.get().sendControlMessage(command.method(), command.params());
    }
  }

  private void sendControlMessage(String method, List<String> params) {
    commandIds.incrementAndGet();
    ControlCommand command = new ControlCommand(method, List.copyOf(params));
    if (pendingCommands.isEmpty() && commandLimiter.tryAcquire()) {
      clientSupplier.get().sendControlMessage(command.method(), command.params());
      return;
    }
    pendingCommands.addLast(command);
    flushPendingCommands();
  }

  private List<String> streamNames(String symbol) {
    List<String> streams = new ArrayList<>();
    streams.add(BinanceStreamName.bookTicker(symbol));
    streams.add(BinanceStreamName.ticker(symbol));
    streams.add(BinanceStreamName.aggTrade(symbol));
    streams.add(BinanceStreamName.depth(symbol, properties.orderBookLevels(), properties.orderBookFast()));
    for (String interval : properties.klineIntervals()) {
      streams.add(BinanceStreamName.kline(symbol, interval));
    }
    return streams;
  }

  private record ControlCommand(String method, List<String> params) {
  }
}
