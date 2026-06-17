package com.fxplatform.market.realtime;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@ExtendWith(MockitoExtension.class)
class RealtimeSubscriptionEventListenerTest {

  @Mock
  private BinanceSubscriptionManager subscriptionManager;

  @Test
  void subscribeActivatesOnlyOnFirstSymbolDemand() {
    RealtimeSubscriptionEventListener listener = listener();

    listener.onSubscribe(subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT"));
    listener.onSubscribe(subscribe("session-1", "book-1", "/topic/market/order-book/BTCUSDT"));

    verify(subscriptionManager).activateSymbol("BTCUSDT");
  }

  @Test
  void unsubscribeDeactivatesOnlyWhenLastSessionLeaves() {
    RealtimeSubscriptionEventListener listener = listener();
    listener.onSubscribe(subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT"));
    listener.onSubscribe(subscribe("session-2", "quote-2", "/topic/market/quotes/BTCUSDT"));

    listener.onUnsubscribe(unsubscribe("session-1", "quote-1"));
    verify(subscriptionManager, never()).deactivateSymbol("BTCUSDT");

    listener.onUnsubscribe(unsubscribe("session-2", "quote-2"));
    verify(subscriptionManager).deactivateSymbol("BTCUSDT");
  }

  @Test
  void disconnectDeactivatesSymbolsWhenSessionWasLastRef() {
    RealtimeSubscriptionEventListener listener = listener();
    listener.onSubscribe(subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT"));
    listener.onSubscribe(subscribe("session-1", "trade-1", "/topic/market/trades/ETHUSDT"));

    listener.onDisconnect(disconnect("session-1"));

    verify(subscriptionManager).deactivateSymbol("BTCUSDT");
    verify(subscriptionManager).deactivateSymbol("ETHUSDT");
  }

  @Test
  void invalidDestinationDoesNotActivateUpstream() {
    RealtimeSubscriptionEventListener listener = listener();

    listener.onSubscribe(subscribe("session-1", "bad-1", "/topic/market/bad/BTCUSDT"));

    verify(subscriptionManager, never()).activateSymbol("BTCUSDT");
  }

  @Test
  void replacingSubscriptionIdDeactivatesOldSymbolAndActivatesNewSymbol() {
    RealtimeSubscriptionEventListener listener = listener();
    listener.onSubscribe(subscribe("session-1", "quote-1", "/topic/market/quotes/BTCUSDT"));

    listener.onSubscribe(subscribe("session-1", "quote-1", "/topic/market/quotes/ETHUSDT"));

    verify(subscriptionManager).deactivateSymbol("BTCUSDT");
    verify(subscriptionManager).activateSymbol("ETHUSDT");
  }

  private RealtimeSubscriptionEventListener listener() {
    return new RealtimeSubscriptionEventListener(new RealtimeSubscriptionRegistry(), subscriptionManager);
  }

  private SessionSubscribeEvent subscribe(String sessionId, String subscriptionId, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setSessionId(sessionId);
    accessor.setSubscriptionId(subscriptionId);
    accessor.setDestination(destination);
    return new SessionSubscribeEvent(this, message(accessor));
  }

  private SessionUnsubscribeEvent unsubscribe(String sessionId, String subscriptionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
    accessor.setSessionId(sessionId);
    accessor.setSubscriptionId(subscriptionId);
    return new SessionUnsubscribeEvent(this, message(accessor));
  }

  private SessionDisconnectEvent disconnect(String sessionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
    accessor.setSessionId(sessionId);
    return new SessionDisconnectEvent(this, message(accessor), sessionId, CloseStatus.NORMAL);
  }

  private Message<byte[]> message(StompHeaderAccessor accessor) {
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
