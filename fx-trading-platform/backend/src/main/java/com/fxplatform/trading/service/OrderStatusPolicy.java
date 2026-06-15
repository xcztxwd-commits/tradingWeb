package com.fxplatform.trading.service;

import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import org.springframework.stereotype.Component;

/**
 * OrderStatusPolicy 是交易模块的业务服务。
 */
@Component
public class OrderStatusPolicy {

  public OrderStatus acceptedStatus(OrderType orderType) {
    // 非市价单进入 PENDING，保证挂单扫描器能按统一状态发现待触发订单。
    return orderType == OrderType.MARKET ? OrderStatus.ACCEPTED : OrderStatus.PENDING;
  }
}
