package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * OrderRepository 通过 MyBatis-Plus 访问订单。
 */
public interface OrderRepository extends FxBaseMapper<OrderEntity> {

  default long countByAccountId(UUID accountId) {
    return selectCount(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getAccountId, accountId));
  }

  default Page<OrderEntity> findPageByAccountId(
      UUID accountId,
      OrderStatus status,
      String symbol,
      Page<OrderEntity> page
  ) {
    LambdaQueryWrapper<OrderEntity> query = new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getAccountId, accountId);
    if (status != null) {
      query.eq(OrderEntity::getStatus, status);
    }
    if (symbol != null && !symbol.isBlank()) {
      query.eq(OrderEntity::getSymbol, symbol);
    }
    return selectPage(page, query
        .orderByDesc(OrderEntity::getCreatedAt)
        .orderByDesc(OrderEntity::getId));
  }

  /** 按用户和幂等键查询订单，避免重复提交。 */
  @Select("""
      SELECT *
      FROM trading.orders
      WHERE user_id = #{userId}
        AND idempotency_key = #{idempotencyKey}
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  Optional<OrderEntity> findByUserIdAndIdempotencyKey(
      @Param("userId") UUID userId,
      @Param("idempotencyKey") String idempotencyKey);

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
        .eq(OrderEntity::getClientOrderId, clientOrderId)
        .in(OrderEntity::getStatus, List.of(
            OrderStatus.RECEIVED,
            OrderStatus.VALIDATING,
            OrderStatus.ACCEPTED,
            OrderStatus.PENDING_ACTIVATION,
            OrderStatus.PENDING,
            OrderStatus.WORKING,
            OrderStatus.PARTIALLY_FILLED,
            OrderStatus.CANCEL_PENDING))));
  }

  /** Recovery lookup includes terminal rows so a completed command is never replayed. */
  default Optional<OrderEntity> findAnyByUserIdAndAccountIdAndClientOrderId(
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

  /** Reads only bound protection carriers; a Task 9 internal close parent is not a protection. */
  @Select("""
      SELECT *
      FROM trading.orders
      WHERE status = #{status}
        AND parent_position_id IS NOT NULL
        AND protection_type IS NOT NULL
        AND product_type = 'LINEAR_PERP'
        AND order_origin = 'PROTECTIVE'
        AND order_type <> 'TRAILING_STOP_MARKET'
      ORDER BY created_at, id
      """)
  List<OrderEntity> findBoundProtectionsByStatus(@Param("status") OrderStatus status);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE status = 'PENDING_ACTIVATION'
        AND order_type = 'TRAILING_STOP_MARKET'
        AND product_type = 'LINEAR_PERP'
        AND order_origin = 'PROTECTIVE'
        AND protection_type = 'STOP_LOSS'
        AND reduce_only = TRUE
        AND parent_position_id IS NOT NULL
      ORDER BY created_at, id
      """)
  List<OrderEntity> findTrailingStopsAwaitingActivation();

  @Update("""
      UPDATE trading.orders
      SET trailing_extreme = #{nextExtreme},
          trigger_price = #{triggerPrice},
          version = version + 1,
          updated_at = CURRENT_TIMESTAMP
      WHERE id = #{id}
        AND status = 'PENDING_ACTIVATION'
        AND order_type = 'TRAILING_STOP_MARKET'
        AND version = #{expectedVersion}
      """)
  int updateTrailingState(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("nextExtreme") BigDecimal nextExtreme,
      @Param("triggerPrice") BigDecimal triggerPrice);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE status = 'PENDING_ACTIVATION'
        AND order_type = 'STOP_LIMIT'
        AND order_origin = 'USER'
        AND protection_type IS NULL
        AND contingency_group_id IS NULL
        AND parent_order_id IS NULL
      ORDER BY created_at, id
      """)
  List<OrderEntity> findUserStopLimitsAwaitingActivation();

  default List<OrderEntity> findByAccountIdAndStatusIn(UUID accountId, List<OrderStatus> statuses) {
    return selectList(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getAccountId, accountId)
        .in(OrderEntity::getStatus, statuses));
  }

  default long countCreatedAtSince(Instant createdAt) {
    return selectCount(new LambdaQueryWrapper<OrderEntity>()
        .ge(OrderEntity::getCreatedAt, createdAt));
  }

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE id = #{id}
      FOR UPDATE
      """)
  Optional<OrderEntity> findByIdForUpdate(@Param("id") UUID id);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE account_id = #{accountId}
        AND status IN ('PENDING', 'WORKING')
      ORDER BY id
      FOR UPDATE
      """)
  List<OrderEntity> findPendingByAccountIdForUpdate(@Param("accountId") UUID accountId);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE account_id = #{accountId}
        AND status IN (
          'RECEIVED', 'VALIDATING', 'ACCEPTED', 'PENDING_ACTIVATION', 'PENDING',
          'WORKING', 'PARTIALLY_FILLED', 'CANCEL_PENDING'
        )
      ORDER BY id
      FOR UPDATE
      """)
  List<OrderEntity> findActiveByAccountIdForUpdate(@Param("accountId") UUID accountId);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE account_id = #{accountId}
        AND product_type = 'LINEAR_PERP'
        AND status IN (
          'RECEIVED', 'VALIDATING', 'ACCEPTED', 'PENDING_ACTIVATION', 'PENDING',
          'WORKING', 'PARTIALLY_FILLED', 'CANCEL_PENDING'
        )
      ORDER BY id
      FOR UPDATE
      """)
  List<OrderEntity> findActiveLinearPerpByAccountIdForUpdate(@Param("accountId") UUID accountId);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE account_id = #{accountId}
        AND symbol = #{symbol}
        AND product_type = 'LINEAR_PERP'
        AND status IN (
          'RECEIVED', 'VALIDATING', 'ACCEPTED', 'PENDING_ACTIVATION', 'PENDING',
          'WORKING', 'PARTIALLY_FILLED', 'CANCEL_PENDING'
        )
      ORDER BY id
      FOR UPDATE
      """)
  List<OrderEntity> findActiveLinearPerpBySymbolForUpdate(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol);

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE parent_order_id = #{parentOrderId}
        AND protection_type IS NOT NULL
        AND product_type = 'LINEAR_PERP'
        AND order_origin = 'PROTECTIVE'
        AND status IN ('PENDING_ACTIVATION', 'PENDING', 'WORKING', 'CANCEL_PENDING')
      ORDER BY created_at, id
      FOR UPDATE
      """)
  List<OrderEntity> findProtectionsByParentOrderIdForUpdate(
      @Param("parentOrderId") UUID parentOrderId);

  default List<OrderEntity> findByContingencyGroupId(UUID contingencyGroupId) {
    return selectList(new LambdaQueryWrapper<OrderEntity>()
        .eq(OrderEntity::getContingencyGroupId, contingencyGroupId)
        .orderByAsc(OrderEntity::getId));
  }

  @Select("""
      SELECT *
      FROM trading.orders
      WHERE contingency_group_id = #{contingencyGroupId}
      ORDER BY id
      FOR UPDATE
      """)
  List<OrderEntity> findByContingencyGroupIdForUpdate(
      @Param("contingencyGroupId") UUID contingencyGroupId);

  /** 触价执行前先从 PENDING 抢占到 WORKING，避免多实例重复成交同一挂单。 */
  default int claimPending(UUID orderId) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, orderId)
        .eq(OrderEntity::getStatus, OrderStatus.PENDING)
        .set(OrderEntity::getStatus, OrderStatus.WORKING));
  }

  default int activateStopLimitPending(UUID orderId) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, orderId)
        .eq(OrderEntity::getStatus, OrderStatus.PENDING_ACTIVATION)
        .eq(OrderEntity::getOrderType, OrderType.STOP_LIMIT)
        .eq(OrderEntity::getOrderOrigin, OrderOrigin.USER)
        .isNull(OrderEntity::getProtectionType)
        .isNull(OrderEntity::getContingencyGroupId)
        .isNull(OrderEntity::getParentOrderId)
        .set(OrderEntity::getStatus, OrderStatus.PENDING));
  }

  default int activateStopLimitWorking(UUID orderId) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, orderId)
        .eq(OrderEntity::getStatus, OrderStatus.PENDING_ACTIVATION)
        .eq(OrderEntity::getOrderType, OrderType.STOP_LIMIT)
        .eq(OrderEntity::getOrderOrigin, OrderOrigin.USER)
        .isNull(OrderEntity::getProtectionType)
        .isNull(OrderEntity::getContingencyGroupId)
        .isNull(OrderEntity::getParentOrderId)
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

  default int cancelPartiallyFilled(OrderEntity order) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, order.getId())
        .eq(OrderEntity::getUserId, order.getUserId())
        .eq(OrderEntity::getStatus, OrderStatus.PARTIALLY_FILLED)
        .set(OrderEntity::getStatus, OrderStatus.CANCELED)
        .set(OrderEntity::getCanceledAt, order.getCanceledAt())
        .set(OrderEntity::getRemainingQuantity, java.math.BigDecimal.ZERO)
        .set(OrderEntity::getHoldAmount, java.math.BigDecimal.ZERO));
  }

  default int cancelPendingActivation(OrderEntity order) {
    return update(null, new LambdaUpdateWrapper<OrderEntity>()
        .eq(OrderEntity::getId, order.getId())
        .eq(OrderEntity::getUserId, order.getUserId())
        .eq(OrderEntity::getStatus, OrderStatus.PENDING_ACTIVATION)
        .eq(OrderEntity::getOrderType, OrderType.STOP_LIMIT)
        .eq(OrderEntity::getOrderOrigin, OrderOrigin.USER)
        .isNull(OrderEntity::getProtectionType)
        .isNull(OrderEntity::getContingencyGroupId)
        .isNull(OrderEntity::getParentOrderId)
        .set(OrderEntity::getStatus, OrderStatus.CANCELED)
        .set(OrderEntity::getCanceledAt, order.getCanceledAt())
        .set(OrderEntity::getRemainingQuantity, java.math.BigDecimal.ZERO)
        .set(OrderEntity::getHoldAmount, java.math.BigDecimal.ZERO));
  }
}
