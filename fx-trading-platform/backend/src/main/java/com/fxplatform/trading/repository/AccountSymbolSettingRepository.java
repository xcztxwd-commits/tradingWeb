package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import java.util.Optional;
import java.util.UUID;

public interface AccountSymbolSettingRepository extends FxBaseMapper<AccountSymbolSettingEntity> {

  default Optional<AccountSymbolSettingEntity> findByAccountIdAndSymbol(
      UUID accountId,
      String symbol
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AccountSymbolSettingEntity>()
        .eq(AccountSymbolSettingEntity::getAccountId, accountId)
        .eq(AccountSymbolSettingEntity::getSymbol, symbol)));
  }
}
