package com.fxplatform.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.auth.entity.KycApplicationEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.List;
import java.util.UUID;

/**
 * KYC 申请 Mapper，承载后台审核列表和会员详情查询。
 */
public interface KycApplicationRepository extends FxBaseMapper<KycApplicationEntity> {

  /** 查询会员最近的 KYC 申请，按创建时间倒序返回。 */
  default List<KycApplicationEntity> findRecentByUserId(UUID userId, int size) {
    return selectPage(new Page<>(1, size), new LambdaQueryWrapper<KycApplicationEntity>()
        .eq(KycApplicationEntity::getUserId, userId)
        .orderByDesc(KycApplicationEntity::getCreatedAt)).getRecords();
  }

  /** 查询后台最近 KYC 申请列表，状态为空时返回全部。 */
  default List<KycApplicationEntity> findRecent(String status, int size) {
    LambdaQueryWrapper<KycApplicationEntity> query = new LambdaQueryWrapper<KycApplicationEntity>()
        .orderByDesc(KycApplicationEntity::getCreatedAt);
    if (cn.hutool.core.util.StrUtil.isNotBlank(status)) {
      query.eq(KycApplicationEntity::getStatus, status);
    }
    return selectPage(new Page<>(1, size), query).getRecords();
  }
}
