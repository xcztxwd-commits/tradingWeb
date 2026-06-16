package com.fxplatform.wallet.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletBalanceRepository extends FxBaseMapper<WalletBalanceEntity> {

  default Optional<WalletBalanceEntity> findByAccountIdAndAsset(UUID accountId, String asset) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<WalletBalanceEntity>()
        .eq(WalletBalanceEntity::getAccountId, accountId)
        .eq(WalletBalanceEntity::getAsset, asset)));
  }

  default List<WalletBalanceEntity> findByAccountIdOrderByAssetAsc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<WalletBalanceEntity>()
        .eq(WalletBalanceEntity::getAccountId, accountId)
        .orderByAsc(WalletBalanceEntity::getAsset));
  }
}
