package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.enums.AdminFeatureRecordStatus;
import com.fxplatform.admin.entity.AdminFeatureRecordEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.List;
import java.util.Optional;

/**
 * 管理截图后台通用页面记录。
 */
public interface AdminFeatureRecordRepository extends FxBaseMapper<AdminFeatureRecordEntity> {

  default Optional<AdminFeatureRecordEntity> findByPageKeyAndRecordKey(String pageKey, String recordKey) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminFeatureRecordEntity>()
        .eq(AdminFeatureRecordEntity::getPageKey, pageKey)
        .eq(AdminFeatureRecordEntity::getRecordKey, recordKey)));
  }

  default List<AdminFeatureRecordEntity> findActiveByPageKey(String pageKey) {
    return selectList(new LambdaQueryWrapper<AdminFeatureRecordEntity>()
        .eq(AdminFeatureRecordEntity::getPageKey, pageKey)
        .ne(AdminFeatureRecordEntity::getStatus, AdminFeatureRecordStatus.DELETED)
        .orderByDesc(AdminFeatureRecordEntity::getCreatedAt));
  }

  default Optional<AdminFeatureRecordEntity> findByPageKeyAndActionAndIdempotencyKey(
      String pageKey,
      String action,
      String idempotencyKey
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminFeatureRecordEntity>()
        .eq(AdminFeatureRecordEntity::getPageKey, pageKey)
        .apply("data ->> '_lastAction' = {0}", action)
        .apply("data ->> 'idempotencyKey' = {0}", idempotencyKey)
        .last("limit 1")));
  }
}
