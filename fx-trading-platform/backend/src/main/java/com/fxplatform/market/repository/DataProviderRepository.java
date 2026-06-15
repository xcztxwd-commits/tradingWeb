package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.DataProviderEntity;
import java.util.Optional;

public interface DataProviderRepository extends FxBaseMapper<DataProviderEntity> {

  default Optional<DataProviderEntity> findByCode(String code) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<DataProviderEntity>()
        .eq(DataProviderEntity::getCode, code)));
  }
}
