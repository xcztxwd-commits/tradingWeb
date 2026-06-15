package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.TradeEntity;
import java.util.List;
import java.util.UUID;

/**
 * TradeRepository 通过 MyBatis-Plus 访问成交记录。
 */
public interface TradeRepository extends FxBaseMapper<TradeEntity> {

  /** 按账户倒序查询成交记录。 */
  default List<TradeEntity> findByAccountIdOrderByExecutedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<TradeEntity>()
        .eq(TradeEntity::getAccountId, accountId)
        .orderByDesc(TradeEntity::getExecutedAt));
  }
}
