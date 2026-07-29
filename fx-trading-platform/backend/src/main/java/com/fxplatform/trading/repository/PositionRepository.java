package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * PositionRepository 通过 MyBatis-Plus 访问持仓。
 */
public interface PositionRepository extends FxBaseMapper<PositionEntity> {

  default long countByAccountId(UUID accountId) {
    return selectCount(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getAccountId, accountId));
  }

  @Select("""
      SELECT id, source, sort_time AS "sortTime"
      FROM (
        SELECT id, 'PERP' AS source, closed_at AS sort_time
        FROM trading.positions
        WHERE account_id = #{accountId}
          AND status = 'CLOSED'
          AND (COALESCE(#{symbol}, '') = '' OR symbol = #{symbol})
        UNION ALL
        SELECT id, 'SPOT' AS source, updated_at AS sort_time
        FROM trading.spot_positions
        WHERE account_id = #{accountId}
          AND wallet_type = 'SPOT'
          AND quantity <= 0
          AND realized_pnl <> 0
          AND (COALESCE(#{symbol}, '') = '' OR UPPER(CONCAT(asset, cost_asset)) = #{symbol})
      ) closed_items
      ORDER BY sort_time DESC NULLS LAST, id DESC
      LIMIT #{size} OFFSET #{offset}
      """)
  List<ClosedPositionPageKey> findClosedPageKeys(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("""
      SELECT COUNT(*)
      FROM (
        SELECT id
        FROM trading.positions
        WHERE account_id = #{accountId}
          AND status = 'CLOSED'
          AND (COALESCE(#{symbol}, '') = '' OR symbol = #{symbol})
        UNION ALL
        SELECT id
        FROM trading.spot_positions
        WHERE account_id = #{accountId}
          AND wallet_type = 'SPOT'
          AND quantity <= 0
          AND realized_pnl <> 0
          AND (COALESCE(#{symbol}, '') = '' OR UPPER(CONCAT(asset, cost_asset)) = #{symbol})
      ) closed_items
      """)
  long countClosedPageItems(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol);

  default List<PositionEntity> findAllByIds(Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .in(PositionEntity::getId, ids));
  }

  class ClosedPositionPageKey {
    private UUID id;
    private String source;
    private Instant sortTime;

    public ClosedPositionPageKey() {
    }

    public ClosedPositionPageKey(UUID id, String source, Instant sortTime) {
      this.id = id;
      this.source = source;
      this.sortTime = sortTime;
    }

    public UUID getId() {
      return id;
    }

    public void setId(UUID id) {
      this.id = id;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public Instant getSortTime() {
      return sortTime;
    }

    public void setSortTime(Instant sortTime) {
      this.sortTime = sortTime;
    }
  }

  @Select("""
      SELECT MIN(p.opened_at)
      FROM trading.positions p
      JOIN core.trading_accounts a
        ON a.id = p.account_id
       AND a.account_type = 'DEMO'
       AND a.status = 'ACTIVE'
      WHERE p.symbol = #{symbol}
        AND p.product_type = 'LINEAR_PERP'
        AND p.status = 'OPEN'
      """)
  Optional<Instant> findEarliestOpenLinearPerpTimeBySymbol(@Param("symbol") String symbol);

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

  default List<PositionEntity> findOpenLinearPerpByAccountId(UUID accountId) {
    return selectList(new LambdaQueryWrapper<PositionEntity>()
        .eq(PositionEntity::getAccountId, accountId)
        .eq(PositionEntity::getProductType, com.fxplatform.market.model.ProductType.LINEAR_PERP)
        .eq(PositionEntity::getStatus, PositionStatus.OPEN)
        .orderByAsc(PositionEntity::getSymbol)
        .orderByAsc(PositionEntity::getPositionSide)
        .orderByAsc(PositionEntity::getId));
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

  @Select("""
      SELECT *
      FROM trading.positions
      WHERE account_id = #{accountId}
        AND symbol = #{symbol}
        AND product_type = 'LINEAR_PERP'
        AND position_mode = #{positionMode}
        AND position_side = #{positionSide}
        AND status = 'OPEN'
      FOR UPDATE
      """)
  Optional<PositionEntity> findOpenPerpetualSlotForUpdate(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol,
      @Param("positionMode") PositionMode positionMode,
      @Param("positionSide") PositionSide positionSide);

  @Select("""
      SELECT *
      FROM trading.positions
      WHERE account_id = #{accountId}
        AND product_type = 'LINEAR_PERP'
        AND status = 'OPEN'
      ORDER BY symbol, position_side, id
      FOR UPDATE
      """)
  List<PositionEntity> findOpenLinearPerpByAccountIdForUpdate(@Param("accountId") UUID accountId);

  @Select("""
      SELECT *
      FROM trading.positions
      WHERE account_id = #{accountId}
        AND symbol = #{symbol}
        AND product_type = 'LINEAR_PERP'
        AND status = 'OPEN'
      ORDER BY symbol, position_side, id
      FOR UPDATE
      """)
  List<PositionEntity> findOpenLinearPerpBySymbolForUpdate(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol);

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
