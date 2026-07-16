package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.QuantityUnit;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AccountSymbolSettingRepository extends FxBaseMapper<AccountSymbolSettingEntity> {

  default List<AccountSymbolSettingEntity> findByAccountId(UUID accountId) {
    return selectList(new LambdaQueryWrapper<AccountSymbolSettingEntity>()
        .eq(AccountSymbolSettingEntity::getAccountId, accountId)
        .orderByAsc(AccountSymbolSettingEntity::getSymbol));
  }

  @Select("""
      SELECT * FROM trading.account_symbol_settings
      WHERE account_id = #{accountId}
      ORDER BY symbol
      FOR UPDATE
      """)
  List<AccountSymbolSettingEntity> findByAccountIdForUpdate(@Param("accountId") UUID accountId);

  @Select("""
      SELECT * FROM trading.account_symbol_settings
      WHERE account_id = #{accountId}
        AND symbol = #{symbol}
      FOR UPDATE
      """)
  Optional<AccountSymbolSettingEntity> findByAccountIdAndSymbolForUpdate(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol);

  @Update("""
      UPDATE trading.account_symbol_settings
      SET leverage = #{leverage},
          margin_mode = #{marginMode},
          quantity_unit = #{quantityUnit},
          version = version + 1
      WHERE account_id = #{accountId}
        AND symbol = #{symbol}
        AND version = #{expectedVersion}
      """)
  int updateIfVersion(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol,
      @Param("expectedVersion") long expectedVersion,
      @Param("leverage") int leverage,
      @Param("marginMode") MarginMode marginMode,
      @Param("quantityUnit") QuantityUnit quantityUnit);

  @Insert("""
      INSERT INTO trading.account_symbol_settings (
        account_id, symbol, leverage, margin_mode, quantity_unit, version
      ) VALUES (
        #{accountId}, #{symbol}, #{leverage}, #{marginMode}, #{quantityUnit}, 1
      )
      ON CONFLICT (account_id, symbol) DO NOTHING
      """)
  int insertIfAbsent(
      @Param("accountId") UUID accountId,
      @Param("symbol") String symbol,
      @Param("leverage") int leverage,
      @Param("marginMode") MarginMode marginMode,
      @Param("quantityUnit") QuantityUnit quantityUnit);

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
