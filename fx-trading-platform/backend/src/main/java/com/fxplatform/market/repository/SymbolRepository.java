package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.SymbolEntity;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * SymbolRepository 通过 MyBatis-Plus 访问交易品种。
 */
public interface SymbolRepository extends FxBaseMapper<SymbolEntity> {

  /** 按品种代码查询唯一品种。 */
  default Optional<SymbolEntity> findBySymbol(String symbol) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SymbolEntity>()
        .eq(SymbolEntity::getSymbol, symbol)));
  }

  /** 查询启用中的品种，按品种代码升序返回。 */
  default List<SymbolEntity> findByEnabledTrueOrderBySymbolAsc() {
    return selectList(new LambdaQueryWrapper<SymbolEntity>()
        .eq(SymbolEntity::getEnabled, true)
        .orderByAsc(SymbolEntity::getSymbol));
  }

  default List<SymbolEntity> findVisibleSymbols(String assetClass) {
    LambdaQueryWrapper<SymbolEntity> query = new LambdaQueryWrapper<SymbolEntity>()
        .eq(SymbolEntity::getEnabled, true)
        .eq(SymbolEntity::getDisplayEnabled, true)
        .orderByAsc(SymbolEntity::getDisplayOrder)
        .orderByAsc(SymbolEntity::getSymbol);
    if (assetClass != null && !assetClass.isBlank()) {
      query.eq(SymbolEntity::getAssetClass, assetClass.trim().toUpperCase(Locale.ROOT));
    }
    return selectList(query);
  }

  default List<SymbolEntity> findQuoteBroadcastSymbols() {
    return selectList(new LambdaQueryWrapper<SymbolEntity>()
        .eq(SymbolEntity::getEnabled, true)
        .eq(SymbolEntity::getDisplayEnabled, true)
        .eq(SymbolEntity::getQuoteEnabled, true)
        .orderByAsc(SymbolEntity::getDisplayOrder)
        .orderByAsc(SymbolEntity::getSymbol));
  }
}
