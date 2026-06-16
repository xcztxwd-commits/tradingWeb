package com.fxplatform.finance.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.finance.entity.FundOrderEntity;
import com.fxplatform.finance.enums.FundOrderType;
import java.util.List;
import java.util.UUID;

/**
 * 资金审核订单 Mapper。
 */
public interface FundOrderRepository extends FxBaseMapper<FundOrderEntity> {

  default List<FundOrderEntity> findRecent(String orderType, int size) {
    LambdaQueryWrapper<FundOrderEntity> query = new LambdaQueryWrapper<FundOrderEntity>()
        .orderByDesc(FundOrderEntity::getCreatedAt)
        .last("limit " + Math.max(1, size));
    if (orderType != null && !orderType.isBlank()) {
      query.eq(FundOrderEntity::getOrderType, FundOrderType.fromCode(orderType));
    }
    return selectList(query);
  }

  default List<FundOrderEntity> findByUserIdAndAccountIdOrderByCreatedAtDesc(UUID userId, UUID accountId) {
    return selectList(new LambdaQueryWrapper<FundOrderEntity>()
        .eq(FundOrderEntity::getUserId, userId)
        .eq(FundOrderEntity::getAccountId, accountId)
        .orderByDesc(FundOrderEntity::getCreatedAt));
  }
}
