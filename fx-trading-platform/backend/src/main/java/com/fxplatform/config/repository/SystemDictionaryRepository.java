package com.fxplatform.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.config.entity.SystemDictionaryEntity;
import java.util.Optional;

/**
 * SystemDictionaryRepository 通过 MyBatis-Plus 访问系统字典。
 */
public interface SystemDictionaryRepository extends FxBaseMapper<SystemDictionaryEntity> {

  /** 按分组键和字典项键查询唯一字典项。 */
  default Optional<SystemDictionaryEntity> findByGroupKeyAndItemKey(String groupKey, String itemKey) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SystemDictionaryEntity>()
        .eq(SystemDictionaryEntity::getGroupKey, groupKey)
        .eq(SystemDictionaryEntity::getItemKey, itemKey)));
  }
}
