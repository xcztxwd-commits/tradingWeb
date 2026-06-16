package com.fxplatform.risk.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.risk.entity.RiskConfigEntity;
import java.util.Optional;

/**
 * RiskConfigRepository 通过 MyBatis-Plus 访问风控配置。
 */
public interface RiskConfigRepository extends FxBaseMapper<RiskConfigEntity> {

  default Optional<RiskConfigEntity> findFirstEnabledWithStopOutLevel() {
    return selectList(new LambdaQueryWrapper<RiskConfigEntity>()
        .eq(RiskConfigEntity::getEnabled, true)
        .isNotNull(RiskConfigEntity::getStopOutLevel)
        .last("LIMIT 1"))
        .stream()
        .findFirst();
  }
}
