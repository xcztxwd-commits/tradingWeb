package com.fxplatform.trading.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.CrossLiquidationChargeEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CrossLiquidationChargeRepository
    extends FxBaseMapper<CrossLiquidationChargeEntity> {

  default boolean insertIfAbsent(CrossLiquidationChargeEntity charge) {
    return insertOnConflictDoNothing(charge) == 1;
  }

  @Insert("""
      INSERT INTO trading.cross_liquidation_charges (
        order_id, account_id, position_id, fee_due, fee_charged, status
      ) VALUES (
        #{orderId}, #{accountId}, #{positionId}, #{feeDue}, #{feeCharged}, #{status}
      )
      ON CONFLICT (order_id) DO NOTHING
      """)
  int insertOnConflictDoNothing(CrossLiquidationChargeEntity charge);

  @Select("""
      SELECT c.*
      FROM trading.cross_liquidation_charges c
      JOIN trading.positions p ON p.id = c.position_id
      WHERE c.account_id = #{accountId}
        AND c.status = 'PENDING'
      ORDER BY p.symbol, p.position_side, c.position_id, c.order_id
      FOR UPDATE OF c
      """)
  List<CrossLiquidationChargeEntity> findPendingByAccountIdForUpdate(
      @Param("accountId") UUID accountId);
}
