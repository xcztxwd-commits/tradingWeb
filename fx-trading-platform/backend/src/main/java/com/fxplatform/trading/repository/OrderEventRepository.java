package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.OrderEventEntity;
import java.util.List;
import java.util.UUID;

/**
 * OrderEventRepository 通过 MyBatis-Plus 访问订单事件。
 */
public interface OrderEventRepository extends FxBaseMapper<OrderEventEntity> {

  default List<OrderEventEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId) {
    return selectList(new LambdaQueryWrapper<OrderEventEntity>()
        .eq(OrderEventEntity::getOrderId, orderId)
        .orderByAsc(OrderEventEntity::getCreatedAt));
  }
}
