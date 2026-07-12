package com.fxplatform.ledger.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

public interface LedgerEntryRepository extends FxBaseMapper<LedgerEntryEntity> {

  default LedgerEntryEntity findByBusinessOperation(
      UUID accountId,
      String referenceType,
      UUID referenceId,
      String operationType
  ) {
    if (!StringUtils.hasText(referenceType) || referenceId == null || !StringUtils.hasText(operationType)) {
      return null;
    }
    return selectOne(new LambdaQueryWrapper<LedgerEntryEntity>()
        .eq(LedgerEntryEntity::getAccountId, accountId)
        .eq(LedgerEntryEntity::getReferenceType, referenceType)
        .eq(LedgerEntryEntity::getReferenceId, referenceId)
        .eq(LedgerEntryEntity::getOperationType, operationType));
  }

  default List<LedgerEntryEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<LedgerEntryEntity>()
        .eq(LedgerEntryEntity::getAccountId, accountId)
        .orderByDesc(LedgerEntryEntity::getCreatedAt));
  }

  default List<LedgerEntryEntity> findByReference(
      UUID accountId,
      String referenceType,
      UUID referenceId
  ) {
    return selectList(new LambdaQueryWrapper<LedgerEntryEntity>()
        .eq(LedgerEntryEntity::getAccountId, accountId)
        .eq(LedgerEntryEntity::getReferenceType, referenceType)
        .eq(LedgerEntryEntity::getReferenceId, referenceId)
        .orderByAsc(LedgerEntryEntity::getCreatedAt)
        .orderByAsc(LedgerEntryEntity::getId));
  }
}
