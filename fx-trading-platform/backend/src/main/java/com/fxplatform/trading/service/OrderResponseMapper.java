package com.fxplatform.trading.service;

import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * OrderResponseMapper 是交易模块的业务服务。
 */
@Component
public class OrderResponseMapper {

  public OrderResponse toResponse(OrderEntity order) {
    BigDecimal quantity = order.getQuantity() != null ? order.getQuantity() : order.getLots();
    BigDecimal price = order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
    BigDecimal avgFillPrice = order.getAvgFillPrice() != null ? order.getAvgFillPrice() : order.getExecutionPrice();
    return new OrderResponse(
        order.getId(),
        order.getAccountId(),
        order.getSymbol(),
        order.getSide().name(),
        order.getOrderType().name(),
        order.getLeverage(),
        order.getStatus().name(),
        order.getLots(),
        quantity,
        price,
        order.getExecutionPrice(),
        order.getFilledQuantity(),
        order.getRemainingQuantity(),
        avgFillPrice,
        order.getFee(),
        order.getSlippage(),
        order.getHoldAmount(),
        order.getHoldCurrency(),
        order.getRejectCode(),
        order.getRejectMessage(),
        order.getCreatedAt(),
        order.getUpdatedAt(),
        order.getFilledAt(),
        order.getCanceledAt());
  }
}
