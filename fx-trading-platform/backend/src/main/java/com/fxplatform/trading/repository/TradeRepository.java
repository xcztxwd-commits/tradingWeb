package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.TradeEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TradeRepository 通过 MyBatis-Plus 访问成交记录。
 */
public interface TradeRepository extends FxBaseMapper<TradeEntity> {

  default long countByAccountId(UUID accountId) {
    return selectCount(new LambdaQueryWrapper<TradeEntity>()
        .eq(TradeEntity::getAccountId, accountId));
  }

  default Optional<TradeEntity> findByOrderIdAndFillIdentity(
      UUID orderId,
      String fillIdentity
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeEntity>()
        .eq(TradeEntity::getOrderId, orderId)
        .eq(TradeEntity::getFillIdentity, fillIdentity)));
  }

  default Page<TradeEntity> findPageByAccountId(
      UUID accountId,
      String symbol,
      Page<TradeEntity> page
  ) {
    LambdaQueryWrapper<TradeEntity> query = new LambdaQueryWrapper<TradeEntity>()
        .eq(TradeEntity::getAccountId, accountId);
    if (symbol != null && !symbol.isBlank()) {
      query.eq(TradeEntity::getSymbol, symbol);
    }
    return selectPage(page, query
        .orderByDesc(TradeEntity::getExecutedAt)
        .orderByDesc(TradeEntity::getId));
  }

  /** 按账户倒序查询成交记录。 */
  default List<TradeEntity> findByAccountIdOrderByExecutedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<TradeEntity>()
        .eq(TradeEntity::getAccountId, accountId)
        .orderByDesc(TradeEntity::getExecutedAt));
  }
}
