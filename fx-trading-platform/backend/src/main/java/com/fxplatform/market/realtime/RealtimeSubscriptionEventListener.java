package com.fxplatform.market.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@Component
public class RealtimeSubscriptionEventListener {

  private final RealtimeSubscriptionRegistry registry;
  private final BinanceSubscriptionManager subscriptionManager;

  public RealtimeSubscriptionEventListener(
      RealtimeSubscriptionRegistry registry,
      BinanceSubscriptionManager subscriptionManager
  ) {
    this.registry = registry;
    this.subscriptionManager = subscriptionManager;
  }

  @EventListener
  public void onSubscribe(SessionSubscribeEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    registry.subscribeMany(accessor.getSessionId(), accessor.getSubscriptionId(), accessor.getDestination())
        .forEach(this::applyDemandChange);
  }

  @EventListener
  public void onUnsubscribe(SessionUnsubscribeEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    registry.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId())
        .filter(RealtimeSubscriptionRegistry.SymbolDemandChange::deactivated)
        .ifPresent(change -> subscriptionManager.deactivateSymbol(change.symbol()));
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    String sessionId = accessor.getSessionId() == null ? event.getSessionId() : accessor.getSessionId();
    registry.disconnect(sessionId).stream()
        .forEach(this::applyDemandChange);
  }

  private void applyDemandChange(RealtimeSubscriptionRegistry.SymbolDemandChange change) {
    if (change.activated()) {
      subscriptionManager.activateSymbol(change.symbol());
    } else if (change.deactivated()) {
      subscriptionManager.deactivateSymbol(change.symbol());
    }
  }
}
