package com.fxplatform.market.service;

import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.MarketSourceMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class MarketSourceSelectionTracker {

  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;
  private final ConcurrentHashMap<String, Selection> selections = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ReentrantLock> selectionLocks = new ConcurrentHashMap<>();

  @Autowired
  public MarketSourceSelectionTracker(ApplicationEventPublisher eventPublisher) {
    this(eventPublisher, Clock.systemUTC());
  }

  public MarketSourceSelectionTracker(ApplicationEventPublisher eventPublisher, Clock clock) {
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  public void recordSelection(String platformSymbol, String providerCode, MarketSourceMode sourceMode) {
    String symbol = SymbolNormalizer.normalize(platformSymbol);
    Selection next = new Selection(providerCode, sourceMode);
    ReentrantLock lock = selectionLocks.computeIfAbsent(symbol, ignored -> new ReentrantLock());
    lock.lock();
    try {
      Selection current = selections.put(symbol, next);
      if (current != null && !current.equals(next)) {
        MarketSourceChangedEvent event = new MarketSourceChangedEvent(
            symbol,
            current.providerCode(),
            current.sourceMode(),
            next.providerCode(),
            next.sourceMode(),
            clock.instant());
        try {
          eventPublisher.publishEvent(event);
        } catch (RuntimeException ignored) {
          // Market selection is authoritative; an internal observer cannot invalidate it.
        }
      }
    } finally {
      lock.unlock();
    }
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
      Instant changedAt
  ) {
  }
}
