package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminTableColumnPreferenceEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;
import java.util.UUID;

/**
 * 后台表格列偏好 Mapper。
 */
public interface AdminTableColumnPreferenceRepository extends FxBaseMapper<AdminTableColumnPreferenceEntity> {

  /** 按用户和页面查询唯一偏好。 */
  default Optional<AdminTableColumnPreferenceEntity> findByUserIdAndPageKey(UUID userId, String pageKey) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminTableColumnPreferenceEntity>()
        .eq(AdminTableColumnPreferenceEntity::getUserId, userId)
        .eq(AdminTableColumnPreferenceEntity::getPageKey, pageKey)));
  }
}
