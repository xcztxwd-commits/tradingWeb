package com.fxplatform.market.service;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.MarketSourceMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class MarketSourceSelectionTracker {

  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;
  private final ConcurrentHashMap<String, SymbolState> symbolStates = new ConcurrentHashMap<>();

  @Autowired
  public MarketSourceSelectionTracker(ApplicationEventPublisher eventPublisher) {
    this(eventPublisher, Clock.systemUTC());
  }

  public MarketSourceSelectionTracker(ApplicationEventPublisher eventPublisher, Clock clock) {
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  public void recordSelection(String platformSymbol, String providerCode, MarketSourceMode sourceMode) {
    Instant observedAt = clock.instant();
    recordSelection(platformSymbol, providerCode, sourceMode, observedAt, observedAt, false);
  }

  public void recordSelection(
      String platformSymbol,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt,
      boolean stale
  ) {
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    Selection next = new Selection(providerCode, sourceMode);
    SymbolState state = symbolStates.computeIfAbsent(symbol, ignored -> new SymbolState());
    SequencedEvent pendingEvent = null;
    state.selectionLock.lock();
    try {
      Selection current = state.selection;
      state.selection = next;
      if (current != null && !current.equals(next)) {
        pendingEvent = new SequencedEvent(
            ++state.lastEventSequence,
            new MarketSourceChangedEvent(
                symbol,
                current.providerCode(),
                current.sourceMode(),
                next.providerCode(),
                next.sourceMode(),
                clock.instant(),
                Objects.requireNonNull(asOf, "asOf"),
                Objects.requireNonNull(expiresAt, "expiresAt"),
                stale));
      }
    } finally {
      state.selectionLock.unlock();
    }

    if (pendingEvent != null && enqueue(state, pendingEvent)) {
      drain(state);
    }
  }

  private boolean enqueue(SymbolState state, SequencedEvent pendingEvent) {
    synchronized (state.queueMonitor) {
      state.pendingEvents.put(pendingEvent.sequence(), pendingEvent.event());
      if (state.draining || !state.pendingEvents.containsKey(state.nextSequenceToDrain)) {
        return false;
      }
      state.draining = true;
      return true;
    }
  }

  private void drain(SymbolState state) {
    while (true) {
      MarketSourceChangedEvent event;
      synchronized (state.queueMonitor) {
        event = state.pendingEvents.remove(state.nextSequenceToDrain);
        if (event == null) {
          state.draining = false;
          return;
        }
        state.nextSequenceToDrain += 1;
      }

      try {
        eventPublisher.publishEvent(event);
      } catch (RuntimeException ignored) {
        // Market selection is authoritative; a failed observer only consumes this event.
      }
    }
  }

  private static final class SymbolState {
    private final ReentrantLock selectionLock = new ReentrantLock();
    private final Object queueMonitor = new Object();
    private final TreeMap<Long, MarketSourceChangedEvent> pendingEvents = new TreeMap<>();
    private Selection selection;
    private long lastEventSequence;
    private long nextSequenceToDrain = 1L;
    private boolean draining;
  }

  private record SequencedEvent(long sequence, MarketSourceChangedEvent event) {
  }

  private record Selection(String providerCode, MarketSourceMode sourceMode) {
    private Selection {
      Objects.requireNonNull(providerCode, "providerCode");
      Objects.requireNonNull(sourceMode, "sourceMode");
    }
  }

  public record MarketSourceChangedEvent(
      String platformSymbol,
      String previousProviderCode,
      MarketSourceMode previousSourceMode,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant changedAt,
      Instant asOf,
      Instant expiresAt,
      boolean stale
  ) {
    public MarketSourceChangedEvent(
        String platformSymbol,
        String previousProviderCode,
        MarketSourceMode previousSourceMode,
        String providerCode,
        MarketSourceMode sourceMode,
        Instant changedAt
    ) {
      this(
          platformSymbol,
          previousProviderCode,
          previousSourceMode,
          providerCode,
          sourceMode,
          changedAt,
          changedAt,
          changedAt,
          false);
    }
  }
}
