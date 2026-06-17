package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.DataProviderCapabilityEntity;
import com.fxplatform.market.provider.MarketDataCapability;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DataProviderCapabilityRepository extends FxBaseMapper<DataProviderCapabilityEntity> {

  default boolean existsEnabledCapability(UUID providerId, MarketDataCapability capability) {
    return selectCount(new LambdaQueryWrapper<DataProviderCapabilityEntity>()
        .eq(DataProviderCapabilityEntity::getProviderId, providerId)
        .eq(DataProviderCapabilityEntity::getCapability, capability.name())
        .eq(DataProviderCapabilityEntity::getEnabled, true)) > 0;
  }

  default List<DataProviderCapabilityEntity> findByProviderId(UUID providerId) {
    return selectList(new LambdaQueryWrapper<DataProviderCapabilityEntity>()
        .eq(DataProviderCapabilityEntity::getProviderId, providerId)
        .orderByAsc(DataProviderCapabilityEntity::getCapability));
  }

  default List<DataProviderCapabilityEntity> findEnabledByProviderIds(Collection<UUID> providerIds) {
    if (providerIds == null || providerIds.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<DataProviderCapabilityEntity>()
        .in(DataProviderCapabilityEntity::getProviderId, providerIds)
        .eq(DataProviderCapabilityEntity::getEnabled, true)
        .orderByAsc(DataProviderCapabilityEntity::getProviderId)
        .orderByAsc(DataProviderCapabilityEntity::getCapability));
  }
}
