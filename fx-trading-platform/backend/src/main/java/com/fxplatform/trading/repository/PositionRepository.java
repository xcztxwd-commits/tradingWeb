package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.PositionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * PositionRepository 通过 MyBatis-Plus 访问持仓。
 */
public interface PositionRepository extends FxBaseMapper<PositionEntity> {

  /** 按账户和状态倒序查询持仓。 */
  default List<PositionEntity> findByAccountIdAndStatusOrderByOpenedAtDesc(UUID accountId, PositionStatus status) {
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getAccountId, accountId)
        .eq(PositionEntity::getStatus, status)
        .orderByDesc(PositionEntity::getOpenedAt));
  }

  default List<PositionEntity> findByAccountIdOrderByOpenedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getAccountId, accountId)
        .orderByDesc(PositionEntity::getOpenedAt));
  }

  default Optional<PositionEntity> findOpenNetPosition(UUID accountId, String symbol) {
    String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getAccountId, accountId)
        .eq(PositionEntity::getSymbol, normalizedSymbol)
        .eq(PositionEntity::getStatus, PositionStatus.OPEN)
        .orderByDesc(PositionEntity::getOpenedAt)
        .last("LIMIT 1"))
        .stream()
        .findFirst();
  }

  /** 按状态查询持仓，供止盈止损调度和后台统计使用。 */
  default List<PositionEntity> findByStatus(PositionStatus status) {
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getStatus, status));
  }

  default List<PositionEntity> findBySymbolAndStatusOrderByOpenedAtAsc(String symbol, PositionStatus status) {
    String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getSymbol, normalizedSymbol)
        .eq(PositionEntity::getStatus, status)
        .orderByAsc(PositionEntity::getOpenedAt));
  }

  default long countByStatus(PositionStatus status) {
    return selectCount(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getStatus, status));
  }

  @Select("""
      SELECT *
      FROM trading.positions
      WHERE id = #{id}
      FOR UPDATE
      """)
  Optional<PositionEntity> findByIdForUpdate(@Param("id") UUID id);

  @Select("""
      SELECT *
      FROM trading.positions
      WHERE account_id = #{accountId}
        AND status = 'OPEN'
      ORDER BY symbol, position_side, id
      FOR UPDATE
      """)
  List<PositionEntity> findOpenByAccountIdForUpdate(@Param("accountId") UUID accountId);

  /** 按 OPEN 状态条件平仓，保证重复触发不会重复结算保证金和 PnL。 */
  default int closeIfOpen(PositionEntity position) {
    return update(null, new LambdaUpdateWrapper<PositionEntity>()
        .eq(PositionEntity::getId, position.getId())
        .eq(PositionEntity::getAccountId, position.getAccountId())
        .eq(PositionEntity::getStatus, PositionStatus.OPEN)
        .set(PositionEntity::getCurrentPrice, position.getCurrentPrice())
        .set(PositionEntity::getFloatingPnl, position.getFloatingPnl())
        .set(PositionEntity::getRealizedPnl, position.getRealizedPnl())
        .set(PositionEntity::getMarginHeld, position.getMarginHeld())
        .set(PositionEntity::getStatus, PositionStatus.CLOSED)
        .set(PositionEntity::getClosedAt, position.getClosedAt()));
  }
}
