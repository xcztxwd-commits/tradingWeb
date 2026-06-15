package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.ProviderInstrumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderInstrumentRepository extends FxBaseMapper<ProviderInstrumentEntity> {

  default Optional<ProviderInstrumentEntity> findByProviderIdAndProviderSymbol(UUID providerId, String providerSymbol) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<ProviderInstrumentEntity>()
        .eq(ProviderInstrumentEntity::getProviderId, providerId)
        .eq(ProviderInstrumentEntity::getProviderSymbol, providerSymbol)));
  }

  default List<ProviderInstrumentEntity> findByProviderId(UUID providerId) {
    return selectList(new LambdaQueryWrapper<ProviderInstrumentEntity>()
        .eq(ProviderInstrumentEntity::getProviderId, providerId)
        .orderByAsc(ProviderInstrumentEntity::getAssetClass)
        .orderByAsc(ProviderInstrumentEntity::getProviderSymbol));
  }
}
