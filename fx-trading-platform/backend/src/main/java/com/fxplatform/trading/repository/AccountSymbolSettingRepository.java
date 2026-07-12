package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AccountSymbolSettingRepository extends FxBaseMapper<AccountSymbolSettingEntity> {

  @Select("""
      SELECT * FROM trading.account_symbol_settings
      WHERE account_id = #{accountId}
      ORDER BY symbol
      FOR UPDATE
      """)
  List<AccountSymbolSettingEntity> findByAccountIdForUpdate(@Param("accountId") UUID accountId);

  @Update("""
      UPDATE trading.account_symbol_settings
      SET leverage = 10,
          margin_mode = 'CROSS',
          quantity_unit = 'BASE',
          version = version + 1
      WHERE account_id = #{accountId}
        AND symbol = #{symbol}
      """)
  int resetToDemoDefaults(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol);

  default Optional<AccountSymbolSettingEntity> findByAccountIdAndSymbol(
      UUID accountId,
      String symbol
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AccountSymbolSettingEntity>()
        .eq(AccountSymbolSettingEntity::getAccountId, accountId)
        .eq(AccountSymbolSettingEntity::getSymbol, symbol)));
  }
}
