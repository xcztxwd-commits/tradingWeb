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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class OrderEventServiceTest {

  @Test
  void workerFailureEventUsesAnIndependentTransactionAndKeepsPendingState() throws Exception {
    Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
        OrderEventService.class.getMethod(
            "recordWorkerFailure",
            UUID.class,
            String.class,
            OrderStatus.class,
            String.class,
            String.class),
        Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID orderId = UUID.randomUUID();
    OrderEntity pending = new OrderEntity();
    pending.setId(orderId);
    pending.setStatus(OrderStatus.PENDING);
    when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(pending));
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.recordWorkerFailure(
        orderId,
        "ORDER_EXECUTION_FAILED",
        OrderStatus.PENDING,
        "MARKET_DATA_STALE",
        "expired after row locks");

    ArgumentCaptor<OrderEventEntity> event = ArgumentCaptor.forClass(OrderEventEntity.class);
    verify(eventRepository).save(event.capture());
    assertThat(event.getValue().getOrderId()).isEqualTo(orderId);
    assertThat(event.getValue().getEventType()).isEqualTo("ORDER_EXECUTION_FAILED");
    assertThat(event.getValue().getFromStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(event.getValue().getToStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(event.getValue().getReasonCode()).isEqualTo("MARKET_DATA_STALE");
    assertThat(event.getValue().getMessage()).isEqualTo("expired after row locks");
  }

  @Test
  void workerFailureEventIsSkippedWhenThePersistedOrderIsAlreadyTerminal() {
    OrderEventRepository eventRepository = org.mockito.Mockito.mock(OrderEventRepository.class);
    OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    OrderEventService service = new OrderEventService(
        eventRepository, orderRepository, eventPublisher);
    UUID orderId = UUID.randomUUID();
    OrderEntity filled = new OrderEntity();
    filled.setId(orderId);
    filled.setStatus(OrderStatus.FILLED);
    when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(filled));
    when(eventRepository.save(org.mockito.ArgumentMatchers.any(OrderEventEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.recordWorkerFailure(
        orderId,
        "ORDER_EXECUTION_FAILED",
        OrderStatus.PENDING,
        "MARKET_DATA_STALE",
        "Pending order execution deferred");

    verify(eventRepository, org.mockito.Mockito.never()).save(
        org.mockito.ArgumentMatchers.any(OrderEventEntity.class));
    verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(
        org.mockito.ArgumentMatchers.any());
  }

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
