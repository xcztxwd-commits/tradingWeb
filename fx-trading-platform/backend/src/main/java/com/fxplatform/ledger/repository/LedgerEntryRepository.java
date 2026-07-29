package com.fxplatform.ledger.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

public interface LedgerEntryRepository extends FxBaseMapper<LedgerEntryEntity> {

  default Page<LedgerEntryEntity> findTransferPage(
      UUID accountId,
      String operationType,
      Page<LedgerEntryEntity> page
  ) {
    LambdaQueryWrapper<LedgerEntryEntity> query = new LambdaQueryWrapper<LedgerEntryEntity>()
        .eq(LedgerEntryEntity::getAccountId, accountId)
        .eq(LedgerEntryEntity::getReferenceType, "TRANSFER")
        .isNotNull(LedgerEntryEntity::getReferenceId);
    if (StringUtils.hasText(operationType)) {
      query.eq(LedgerEntryEntity::getOperationType, operationType);
    }
    return selectPage(page, query
        .orderByDesc(LedgerEntryEntity::getSequenceNo)
        .orderByDesc(LedgerEntryEntity::getId));
  }

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
        .orderByDesc(LedgerEntryEntity::getSequenceNo)
        .orderByDesc(LedgerEntryEntity::getId));
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
        .orderByAsc(LedgerEntryEntity::getSequenceNo)
        .orderByAsc(LedgerEntryEntity::getId));
  }
}
