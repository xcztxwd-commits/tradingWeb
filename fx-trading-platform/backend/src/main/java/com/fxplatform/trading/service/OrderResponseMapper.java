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
    BigDecimal quantity = order.getOriginalQuantity() != null
        ? order.getOriginalQuantity()
        : order.getQuantity() != null ? order.getQuantity() : order.getLots();
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
        order.getCanceledAt(),
        order.getProductType(),
        order.getPositionMode(),
        order.getPositionSide(),
        order.getMarginMode(),
        order.getQuantityUnit(),
        order.getOriginalQuantity(),
        order.getBaseQuantity(),
        order.getTimeInForce(),
        order.getReduceOnly(),
        order.getOrderOrigin(),
        OrderSystemReasonPolicy.external(order),
        order.getFeeAsset(),
        order.getLiquidityRole(),
        order.getTriggerPrice(),
        order.getTriggerPriceType(),
        order.getTriggerExecutionType(),
        order.getProtectionType(),
        order.getParentOrderId(),
        order.getParentPositionId(),
        order.getContingencyGroupId(),
        order.getHoldOwnerOrderId(),
        order.getVersion());
  }
}
