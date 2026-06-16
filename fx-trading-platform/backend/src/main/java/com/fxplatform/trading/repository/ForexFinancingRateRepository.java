package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.ForexFinancingRateEntity;
import java.util.Optional;

public interface ForexFinancingRateRepository extends FxBaseMapper<ForexFinancingRateEntity> {

  default Optional<ForexFinancingRateEntity> findLatestBySymbol(String symbol) {
    return selectList(new LambdaQueryWrapper<ForexFinancingRateEntity>()
        .eq(ForexFinancingRateEntity::getSymbol, normalizeSymbol(symbol))
        .orderByDesc(ForexFinancingRateEntity::getEffectiveDate)
        .last("LIMIT 1"))
        .stream()
        .findFirst();
  }

  private static String normalizeSymbol(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase();
  }
}
