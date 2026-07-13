package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.OrderEventEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.trading.repository.OrderEventRepository;
import com.fxplatform.trading.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class OrderEventServiceTest {

  @Test
  void acceptedOrderPublishesAcceptedHintBeforeItsFirstTimelineEvent() {
    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID timelineEventId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-07-13T01:00:00Z");
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setVersion(8L);
    when(orderRepository.selectById(orderId)).thenReturn(order);
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> {
          OrderEventEntity saved = invocation.getArgument(0);
          saved.setId(timelineEventId);
          saved.setCreatedAt(occurredAt);
          return saved;
        });

    service.record(
        orderId,
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "filled");

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(event.capture());
    assertThat(event.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast))
        .containsExactly(
            new TradingAccountMutationEvent(
                userId,
                accountId,
                "ORDER_ACCEPTED",
                "ORDER",
                orderId,
                timelineEventId,
                8L,
                occurredAt),
            new TradingAccountMutationEvent(
                userId,
                accountId,
                "ORDER_FILLED",
                "ORDER",
                orderId,
                timelineEventId,
                8L,
                occurredAt));
  }

  @Test
  void acceptedPendingTimelinePublishesAcceptedBeforePending() {
    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID orderId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setVersion(3L);
    when(orderRepository.selectById(orderId)).thenReturn(order);
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> {
          OrderEventEntity saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          saved.setCreatedAt(Instant.parse("2026-07-13T02:00:00Z"));
          return saved;
        });

    service.record(
        orderId,
        "ORDER_PENDING",
        OrderStatus.ACCEPTED,
        OrderStatus.PENDING,
        null,
        "pending");

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("ORDER_ACCEPTED", "ORDER_PENDING");
  }

  @Test
  void protectionExpiredTimelineAlsoPublishesCanonicalOrderExpiredHint() {
    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID orderId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setVersion(4L);
    when(orderRepository.selectById(orderId)).thenReturn(order);
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> {
          OrderEventEntity saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          saved.setCreatedAt(Instant.parse("2026-07-13T03:00:00Z"));
          return saved;
        });

    service.record(
        orderId,
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        "expired");

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("PROTECTION_EXPIRED", "ORDER_EXPIRED");
  }

  @Test
  void canonicalOrderExpiredTimelineIsNotPublishedTwice() {
    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID orderId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    when(orderRepository.selectById(orderId)).thenReturn(order);
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> {
          OrderEventEntity saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          saved.setCreatedAt(Instant.parse("2026-07-13T04:00:00Z"));
          return saved;
        });

    service.record(
        orderId,
        "ORDER_EXPIRED",
        OrderStatus.PENDING,
        OrderStatus.EXPIRED,
        null,
        "expired");

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(((TradingAccountMutationEvent) event.getValue()).type())
        .isEqualTo("ORDER_EXPIRED");
  }

  @Test
  void liquidationFillAlsoPublishesOnlyOneDedicatedLiquidationHint() {
    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-07-13T01:00:00Z");
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setOrderOrigin(OrderOrigin.LIQUIDATION);
    order.setParentPositionId(positionId);
    order.setVersion(5L);
    when(orderRepository.selectById(orderId)).thenReturn(order);
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> {
          OrderEventEntity saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          saved.setCreatedAt(occurredAt);
          return saved;
        });

    service.record(
        orderId,
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "liquidated");

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, org.mockito.Mockito.times(3)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("ORDER_ACCEPTED", "ORDER_FILLED", "LIQUIDATION");
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .filter(event -> "LIQUIDATION".equals(event.type())))
        .allSatisfy(event -> {
          assertThat(event.resourceId()).isEqualTo(positionId);
          assertThat(event.relatedResourceId()).isEqualTo(orderId);
          assertThat(event.version()).isEqualTo(5L);
        });
  }
}
