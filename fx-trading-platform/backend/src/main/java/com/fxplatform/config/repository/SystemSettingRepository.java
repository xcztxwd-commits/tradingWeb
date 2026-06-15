package com.fxplatform.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.config.entity.SystemSettingEntity;
import java.util.Optional;

/**
 * SystemSettingRepository 通过 MyBatis-Plus 访问系统设置。
 */
public interface SystemSettingRepository extends FxBaseMapper<SystemSettingEntity> {

  /** 按设置键查询唯一配置项。 */
  default Optional<SystemSettingEntity> findBySettingKey(String settingKey) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
        .eq(SystemSettingEntity::getSettingKey, settingKey)));
  }
}
