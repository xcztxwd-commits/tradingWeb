package com.fxplatform.trading.service;

import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.dto.response.TradingSessionEventResponse;
import com.fxplatform.trading.entity.OrderEventEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderEventRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.websocket.TradingWsPublisher;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * OrderEventService 是交易模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class OrderEventService {

  private final OrderEventRepository orderEventRepository;
  private final OrderRepository orderRepository;
  private final TradingWsPublisher tradingWsPublisher;

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
    tradingWsPublisher.publishAccountEvent(order.getAccountId(), new TradingSessionEventResponse(
        "ORDER_EVENT",
        order.getAccountId(),
        order.getId(),
        event.getId(),
        event.getEventType(),
        event.getToStatus().name(),
        event.getCreatedAt()));
  }
}
