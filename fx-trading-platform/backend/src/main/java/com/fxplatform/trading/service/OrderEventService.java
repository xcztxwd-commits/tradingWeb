package com.fxplatform.trading.service;

import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.entity.OrderEventEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.trading.repository.OrderEventRepository;
import com.fxplatform.trading.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OrderEventService 是交易模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class OrderEventService {

  private final OrderEventRepository orderEventRepository;
  private final OrderRepository orderRepository;
  private final ApplicationEventPublisher eventPublisher;

  public OrderEventEntity record(
      UUID orderId,
      String eventType,
      OrderStatus fromStatus,
      OrderStatus toStatus,
      String reasonCode,
      String message
  ) {
    OrderEventEntity event = new OrderEventEntity();
    event.setOrderId(orderId);
    event.setEventType(eventType);
    event.setFromStatus(fromStatus);
    event.setToStatus(toStatus);
    event.setReasonCode(reasonCode);
    event.setMessage(message);
    OrderEventEntity saved = orderEventRepository.save(event);
    publishTradingSessionEvent(saved);
    return saved;
  }

  /** Persists a retryable worker failure after the candidate mutation transaction has rolled back. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordWorkerFailure(
      UUID orderId,
      String eventType,
      OrderStatus pendingStatus,
      String errorCode,
      String message
  ) {
    OrderEntity persisted = orderRepository.findByIdForUpdate(orderId).orElse(null);
    if (persisted == null || persisted.getStatus() != pendingStatus) {
      return;
    }
    record(
        orderId,
        eventType,
        pendingStatus,
        pendingStatus,
        errorCode,
        message);
  }

  public List<OrderEventResponse> events(UUID orderId) {
    return orderEventRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  private OrderEventResponse toResponse(OrderEventEntity event) {
    return new OrderEventResponse(
        event.getId(),
        event.getOrderId(),
        event.getEventType(),
        event.getFromStatus() == null ? null : event.getFromStatus().name(),
        event.getToStatus().name(),
        event.getReasonCode(),
        event.getMessage(),
        event.getCreatedAt());
  }

  private void publishTradingSessionEvent(OrderEventEntity event) {
    OrderEntity order = orderRepository.selectById(event.getOrderId());
    if (order == null) {
      return;
    }
    if (event.getFromStatus() == OrderStatus.ACCEPTED
        && event.getToStatus() != OrderStatus.ACCEPTED
        && !"ORDER_ACCEPTED".equals(event.getEventType())) {
      publishOrderEvent(order, event, "ORDER_ACCEPTED");
    }
    publishOrderEvent(order, event, event.getEventType());
    if (event.getToStatus() == OrderStatus.EXPIRED
        && !"ORDER_EXPIRED".equals(event.getEventType())) {
      publishOrderEvent(order, event, "ORDER_EXPIRED");
    }
    if ("ORDER_FILLED".equals(event.getEventType())
        && order.getOrderOrigin() == OrderOrigin.LIQUIDATION
        && order.getParentPositionId() != null) {
      publishLiquidationEvents(order, event);
    }
  }

  private void publishOrderEvent(
      OrderEntity order,
      OrderEventEntity event,
      String eventType
  ) {
    eventPublisher.publishEvent(new TradingAccountMutationEvent(
        order.getUserId(),
        order.getAccountId(),
        eventType,
        "ORDER",
        order.getId(),
        event.getId(),
        order.getVersion(),
        event.getCreatedAt()));
  }

  private void publishLiquidationEvents(OrderEntity order, OrderEventEntity event) {
    eventPublisher.publishEvent(new TradingAccountMutationEvent(
        order.getUserId(),
        order.getAccountId(),
        "LIQUIDATION",
        "POSITION",
        order.getParentPositionId(),
        order.getId(),
        order.getVersion(),
        event.getCreatedAt()));
  }
}
