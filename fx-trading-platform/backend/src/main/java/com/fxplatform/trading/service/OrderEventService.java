package com.fxplatform.trading.service;

import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.entity.OrderEventEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderEventRepository;
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
    return orderEventRepository.save(event);
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
}
