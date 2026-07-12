package com.fxplatform.account.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * TradingAccountRepository 通过 MyBatis-Plus 访问交易账户表。
 */
public interface TradingAccountRepository extends FxBaseMapper<TradingAccountEntity> {

  /** 按用户查询账户列表，供前台账户页和后台账户页复用。 */
  default List<TradingAccountEntity> findByUserId(UUID userId) {
    return selectList(new LambdaQueryWrapper<TradingAccountEntity>()
        .eq(TradingAccountEntity::getUserId, userId));
  }

  /** 按账户 ID 与用户 ID 查询，防止用户越权读取或操作他人账户。 */
  default Optional<TradingAccountEntity> findByIdAndUserId(UUID id, UUID userId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradingAccountEntity>()
        .eq(TradingAccountEntity::getId, id)
        .eq(TradingAccountEntity::getUserId, userId)));
  }

  default Optional<TradingAccountEntity> findActiveDemoByUserId(UUID userId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradingAccountEntity>()
        .eq(TradingAccountEntity::getUserId, userId)
        .eq(TradingAccountEntity::getAccountType, com.fxplatform.account.enums.AccountType.DEMO)
        .eq(TradingAccountEntity::getStatus, com.fxplatform.account.enums.AccountStatus.ACTIVE)));
  }

  @Insert("""
      INSERT INTO core.trading_accounts (
        id, user_id, account_type, base_currency, balance, equity, used_margin,
        free_margin, leverage, status, position_mode, demo_generation
      ) VALUES (
        #{id}, #{userId}, 'DEMO', 'USDT', 50000, 50000, 0,
        50000, 10, 'ACTIVE', 'ONE_WAY', 1
      )
      ON CONFLICT (user_id)
        WHERE account_type = 'DEMO' AND status = 'ACTIVE'
      DO NOTHING
      """)
  int insertActiveDemoIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId);

  @Select("""
      SELECT *
      FROM core.trading_accounts
      WHERE id = #{id}
      FOR UPDATE
      """)
  Optional<TradingAccountEntity> findByIdForUpdate(@Param("id") UUID id);

  @Select("""
      SELECT *
      FROM core.trading_accounts
      WHERE id = #{id}
        AND user_id = #{userId}
      FOR UPDATE
      """)
  Optional<TradingAccountEntity> findByIdAndUserIdForUpdate(
      @Param("id") UUID id,
      @Param("userId") UUID userId);

  @Update("""
      UPDATE core.trading_accounts
      SET used_margin = COALESCE(used_margin, 0) + #{amount},
          free_margin = COALESCE(equity, balance, 0) - (COALESCE(used_margin, 0) + #{amount})
      WHERE id = #{accountId}
        AND COALESCE(free_margin, COALESCE(equity, balance, 0) - COALESCE(used_margin, 0)) >= #{amount}
      """)
  int reserveMarginIfAvailable(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);
}
