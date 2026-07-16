package com.fxplatform.wallet.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WalletBalanceRepository extends FxBaseMapper<WalletBalanceEntity> {

  default Optional<WalletBalanceEntity> findByAccountIdAndAsset(UUID accountId, String asset) {
    return findByAccountIdAndWalletTypeAndAsset(accountId, "SPOT", asset);
  }

  default Optional<WalletBalanceEntity> findByAccountIdAndWalletTypeAndAsset(
      UUID accountId,
      String walletType,
      String asset
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<WalletBalanceEntity>()
        .eq(WalletBalanceEntity::getAccountId, accountId)
        .eq(WalletBalanceEntity::getWalletType, walletType)
        .eq(WalletBalanceEntity::getAsset, asset)));
  }

  default List<WalletBalanceEntity> findByAccountIdOrderByAssetAsc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<WalletBalanceEntity>()
        .eq(WalletBalanceEntity::getAccountId, accountId)
        .orderByAsc(WalletBalanceEntity::getWalletType)
        .orderByAsc(WalletBalanceEntity::getAsset));
  }

  @Select("""
      SELECT *
      FROM core.wallet_balances
      WHERE account_id = #{accountId}
        AND wallet_type = #{walletType}
        AND asset = #{asset}
      FOR UPDATE
      """)
  Optional<WalletBalanceEntity> findByAccountIdAndWalletTypeAndAssetForUpdate(
      @Param("accountId") UUID accountId,
      @Param("walletType") String walletType,
      @Param("asset") String asset);

  @Select("""
      SELECT *
      FROM core.wallet_balances
      WHERE account_id = #{accountId}
      ORDER BY wallet_type, asset
      FOR UPDATE
      """)
  List<WalletBalanceEntity> findByAccountIdForUpdate(@Param("accountId") UUID accountId);
}
