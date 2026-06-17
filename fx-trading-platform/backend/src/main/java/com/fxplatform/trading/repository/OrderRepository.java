package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * OrderRepository 通过 MyBatis-Plus 访问订单。
 */
public interface OrderRepository extends FxBaseMapper<OrderEntity> {

  /** 按用户和幂等键查询订单，避免重复提交。 */
  default Optional<OrderEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getUserId, userId)
        .eq(OrderEntity::getIdempotencyKey, idempotencyKey)));
  }

  default Optional<OrderEntity> findByUserIdAndId(UUID userId, UUID orderId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getUserId, userId)
        .eq(OrderEntity::getId, orderId)));
  }

  /** 按用户、账户和客户端订单 ID 查询订单，支撑 OMS 幂等语义。 */
  default Optional<OrderEntity> findByUserIdAndAccountIdAndClientOrderId(
      UUID userId,
      UUID accountId,
      String clientOrderId
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getUserId, userId)
        .eq(OrderEntity::getAccountId, accountId)
        .eq(OrderEntity::getClientOrderId, clientOrderId)));
  }

  /** 按用户倒序查询订单。 */
  default List<OrderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId) {
    return selectList(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getUserId, userId)
        .orderByDesc(OrderEntity::getCreatedAt));
  }

  /** 按状态查询订单，供挂单执行和后台统计使用。 */
  default List<OrderEntity> findByStatus(OrderStatus status) {
    return selectList(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getStatus, status));
  }

  default List<OrderEntity> findByAccountIdAndStatusIn(UUID accountId, List<OrderStatus> statuses) {
    return selectList(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getAccountId, accountId)
        .in(OrderEntity::getStatus, statuses));
  }

  default long countCreatedAtSince(Instant createdAt) {
    return selectCount(new LambdaQueryWrapper<OrderEntity>()
        .ge(OrderEntity::getCreatedAt, createdAt));
  }

  /** 触价执行前先从 PENDING 抢占到 WORKING，避免多实例重复成交同一挂单。 */
  default int claimPending(UUID orderId) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, orderId)
        .eq(OrderEntity::getStatus, OrderStatus.PENDING)
        .set(OrderEntity::getStatus, OrderStatus.WORKING));
  }

  default int cancelPending(OrderEntity order) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, order.getId())
        .eq(OrderEntity::getUserId, order.getUserId())
        .eq(OrderEntity::getStatus, OrderStatus.PENDING)
        .set(OrderEntity::getStatus, OrderStatus.CANCELED)
        .set(OrderEntity::getCanceledAt, order.getCanceledAt())
        .set(OrderEntity::getRemainingQuantity, java.math.BigDecimal.ZERO)
        .set(OrderEntity::getHoldAmount, java.math.BigDecimal.ZERO));
  }
}
