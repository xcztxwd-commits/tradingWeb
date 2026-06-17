package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.DataProviderEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataProviderRepository extends FxBaseMapper<DataProviderEntity> {

  default Optional<DataProviderEntity> findByCode(String code) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<DataProviderEntity>()
        .eq(DataProviderEntity::getCode, code)));
  }

  default List<DataProviderEntity> findByIds(Collection<UUID> providerIds) {
    if (providerIds == null || providerIds.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<DataProviderEntity>()
        .in(DataProviderEntity::getId, providerIds));
  }
}
