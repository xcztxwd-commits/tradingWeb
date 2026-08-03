package com.fxplatform.trading.service;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * OrderEntityFactory 是交易模块的业务服务。
 */
@Component
public class OrderEntityFactory {

  public OrderEntity createReceived(OrderCommand command) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(command.userId());
    order.setAccountId(command.accountId());
    order.setSymbol(command.symbol());
    order.setSide(command.side());
    order.setOrderType(command.orderType());
    order.setStatus(OrderStatus.RECEIVED);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setLots(command.baseQuantity());
    order.setRequestedPrice(command.price());
    order.setClientOrderId(command.clientOrderId());
    order.setQuantity(command.originalQuantity());
    order.setOriginalQuantity(command.originalQuantity());
    order.setBaseQuantity(command.baseQuantity());
    order.setQuantityUnit(command.quantityUnit());
    order.setMarginMode(command.marginMode());
    order.setPositionSide(command.positionSide());
    order.setTimeInForce(command.timeInForce());
    order.setPostOnly(command.postOnly());
    order.setActivationPrice(command.activationPrice());
    order.setTrailingDelta(command.trailingDelta());
    order.setTrailingRate(command.trailingRate());
    order.setReduceOnly(command.reduceOnly());
    order.setTriggerPrice(command.triggerPrice());
    order.setTriggerPriceType(command.triggerPriceType());
    order.setPrice(command.price());
    order.setStopLoss(command.stopLoss());
    order.setTakeProfit(command.takeProfit());
    order.setLeverage(command.leverage());
    // 新旧客户端都以 clientOrderId 作为业务幂等键，idempotencyKey 继续兼容历史唯一约束。
    order.setIdempotencyKey(command.idempotencyKey());
    order.setCreatedAt(DateUtil.date().toInstant());
    return order;
  }
}
