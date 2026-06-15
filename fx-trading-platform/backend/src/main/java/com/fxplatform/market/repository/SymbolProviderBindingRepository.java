package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SymbolProviderBindingRepository extends FxBaseMapper<SymbolProviderBindingEntity> {

  default List<SymbolProviderBindingEntity> findEnabledBySymbolIdOrderByPriority(UUID symbolId) {
    return selectList(new LambdaQueryWrapper<SymbolProviderBindingEntity>()
        .eq(SymbolProviderBindingEntity::getSymbolId, symbolId)
        .eq(SymbolProviderBindingEntity::getEnabled, true)
        .orderByAsc(SymbolProviderBindingEntity::getPriority)
        .orderByAsc(SymbolProviderBindingEntity::getProviderSymbol));
  }

  default List<SymbolProviderBindingEntity> findBySymbolId(UUID symbolId) {
    return selectList(new LambdaQueryWrapper<SymbolProviderBindingEntity>()
        .eq(SymbolProviderBindingEntity::getSymbolId, symbolId)
        .orderByAsc(SymbolProviderBindingEntity::getPriority)
        .orderByAsc(SymbolProviderBindingEntity::getProviderSymbol));
  }

  default Optional<SymbolProviderBindingEntity> findByIdAndSymbolId(UUID id, UUID symbolId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SymbolProviderBindingEntity>()
        .eq(SymbolProviderBindingEntity::getId, id)
        .eq(SymbolProviderBindingEntity::getSymbolId, symbolId)));
  }
}
