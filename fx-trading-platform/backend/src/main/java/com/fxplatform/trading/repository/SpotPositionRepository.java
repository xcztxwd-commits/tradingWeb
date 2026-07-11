package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SpotPositionRepository extends FxBaseMapper<SpotPositionEntity> {

  default Optional<SpotPositionEntity> findByAccountIdAndWalletTypeAndAssetAndCostAsset(
      UUID accountId,
      String walletType,
      String asset,
      String costAsset
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SpotPositionEntity>()
        .eq(SpotPositionEntity::getAccountId, accountId)
        .eq(SpotPositionEntity::getWalletType, walletType)
        .eq(SpotPositionEntity::getAsset, asset)
        .eq(SpotPositionEntity::getCostAsset, costAsset)));
  }

  default List<SpotPositionEntity> findOpenByAccountId(UUID accountId) {
    return selectList(new LambdaQueryWrapper<SpotPositionEntity>()
        .eq(SpotPositionEntity::getAccountId, accountId)
        .eq(SpotPositionEntity::getWalletType, WalletType.SPOT.code())
        .gt(SpotPositionEntity::getQuantity, BigDecimal.ZERO)
        .orderByDesc(SpotPositionEntity::getUpdatedAt));
  }

  default List<SpotPositionEntity> findClosedWithRealizedPnlByAccountId(UUID accountId) {
    return selectList(new LambdaQueryWrapper<SpotPositionEntity>()
        .eq(SpotPositionEntity::getAccountId, accountId)
        .eq(SpotPositionEntity::getWalletType, WalletType.SPOT.code())
        .le(SpotPositionEntity::getQuantity, BigDecimal.ZERO)
        .ne(SpotPositionEntity::getRealizedPnl, BigDecimal.ZERO)
        .orderByDesc(SpotPositionEntity::getUpdatedAt));
  }

  @Select("""
      SELECT *
      FROM trading.spot_positions
      WHERE account_id = #{accountId}
        AND wallet_type = #{walletType}
        AND asset = #{asset}
        AND cost_asset = #{costAsset}
      FOR UPDATE
      """)
  Optional<SpotPositionEntity> findBySlotForUpdate(
      @Param("accountId") UUID accountId,
      @Param("walletType") String walletType,
      @Param("asset") String asset,
      @Param("costAsset") String costAsset);

  @Select("""
      SELECT *
      FROM trading.spot_positions
      WHERE account_id = #{accountId}
      ORDER BY wallet_type, asset, cost_asset, id
      FOR UPDATE
      """)
  List<SpotPositionEntity> findByAccountIdForUpdate(@Param("accountId") UUID accountId);
}
