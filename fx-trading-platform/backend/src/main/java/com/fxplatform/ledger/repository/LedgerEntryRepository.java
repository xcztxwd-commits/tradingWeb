package com.fxplatform.ledger.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import java.util.List;
import java.util.UUID;

/**
 * LedgerEntryRepository 通过 MyBatis-Plus 访问资金流水。
 */
public interface LedgerEntryRepository extends FxBaseMapper<LedgerEntryEntity> {

  /** 按账户倒序查询资金流水。 */
  default List<LedgerEntryEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<LedgerEntryEntity>()
        .eq(LedgerEntryEntity::getAccountId, accountId)
        .orderByDesc(LedgerEntryEntity::getCreatedAt));
  }
}
