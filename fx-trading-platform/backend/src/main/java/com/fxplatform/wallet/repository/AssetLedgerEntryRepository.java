package com.fxplatform.wallet.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

public interface AssetLedgerEntryRepository extends FxBaseMapper<AssetLedgerEntryEntity> {

  default List<AssetLedgerEntryEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId)
        .orderByDesc(AssetLedgerEntryEntity::getCreatedAt));
  }

  default List<AssetLedgerEntryEntity> findByFilters(
      UUID accountId,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    LambdaQueryWrapper<AssetLedgerEntryEntity> query = new LambdaQueryWrapper<AssetLedgerEntryEntity>()
        .eq(AssetLedgerEntryEntity::getAccountId, accountId);
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
    return selectList(query.orderByDesc(AssetLedgerEntryEntity::getCreatedAt));
  }
}
