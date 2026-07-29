package com.fxplatform.wallet.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

public interface AssetLedgerEntryRepository extends FxBaseMapper<AssetLedgerEntryEntity> {

  default List<AssetLedgerEntryEntity> findByReferences(
      UUID accountId,
      String referenceType,
      Collection<UUID> referenceIds
  ) {
    if (referenceIds == null || referenceIds.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId)
        .eq(AssetLedgerEntryEntity::getReferenceType, referenceType)
        .in(AssetLedgerEntryEntity::getReferenceId, referenceIds)
        .orderByAsc(AssetLedgerEntryEntity::getSequenceNo)
        .orderByAsc(AssetLedgerEntryEntity::getId));
  }

  default AssetLedgerEntryEntity findByBusinessOperation(
      UUID accountId,
      String walletType,
      String asset,
      String referenceType,
      UUID referenceId,
      String operationType
  ) {
    if (!StringUtils.hasText(referenceType) || referenceId == null || !StringUtils.hasText(operationType)) {
      return null;
    }
    return selectOne(new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId)
        .eq(AssetLedgerEntryEntity::getWalletType, walletType)
        .eq(AssetLedgerEntryEntity::getAsset, asset)
        .eq(AssetLedgerEntryEntity::getReferenceType, referenceType)
        .eq(AssetLedgerEntryEntity::getReferenceId, referenceId)
        .eq(AssetLedgerEntryEntity::getOperationType, operationType));
  }

  default List<AssetLedgerEntryEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId)
        .orderByDesc(AssetLedgerEntryEntity::getSequenceNo)
        .orderByDesc(AssetLedgerEntryEntity::getId));
  }

  default List<AssetLedgerEntryEntity> findByReference(
      UUID accountId,
      String referenceType,
      UUID referenceId
  ) {
    return selectList(new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId)
        .eq(AssetLedgerEntryEntity::getReferenceType, referenceType)
        .eq(AssetLedgerEntryEntity::getReferenceId, referenceId)
        .orderByAsc(AssetLedgerEntryEntity::getSequenceNo)
        .orderByAsc(AssetLedgerEntryEntity::getId));
  }

  default List<AssetLedgerEntryEntity> findByFilters(
      UUID accountId,
      String walletType,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    LambdaQueryWrapper<AssetLedgerEntryEntity> query = new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId);
    if (StringUtils.hasText(walletType)) {
      query.eq(AssetLedgerEntryEntity::getWalletType, walletType);
    }
    if (StringUtils.hasText(asset)) {
      query.eq(AssetLedgerEntryEntity::getAsset, asset);
    }
    if (StringUtils.hasText(entryType)) {
      query.eq(AssetLedgerEntryEntity::getEntryType, entryType);
    }
    if (referenceId != null) {
      query.eq(AssetLedgerEntryEntity::getReferenceId, referenceId);
    }
    if (from != null) {
      query.ge(AssetLedgerEntryEntity::getCreatedAt, from);
    }
    if (to != null) {
      query.le(AssetLedgerEntryEntity::getCreatedAt, to);
    }
    return selectList(query
        .orderByDesc(AssetLedgerEntryEntity::getSequenceNo)
        .orderByDesc(AssetLedgerEntryEntity::getId));
  }
}
