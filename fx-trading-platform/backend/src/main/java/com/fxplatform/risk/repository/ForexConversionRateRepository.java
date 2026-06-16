package com.fxplatform.risk.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.risk.entity.ForexConversionRateEntity;
import java.util.Optional;

public interface ForexConversionRateRepository extends FxBaseMapper<ForexConversionRateEntity> {

  default Optional<ForexConversionRateEntity> findLatest(String fromCurrency, String toCurrency) {
    return selectList(new LambdaQueryWrapper<ForexConversionRateEntity>()
        .eq(ForexConversionRateEntity::getFromCurrency, normalizeCurrency(fromCurrency))
        .eq(ForexConversionRateEntity::getToCurrency, normalizeCurrency(toCurrency))
        .orderByDesc(ForexConversionRateEntity::getEffectiveAt)
        .last("LIMIT 1"))
        .stream()
        .findFirst();
  }

  private static String normalizeCurrency(String currency) {
    return currency == null ? "" : currency.trim().toUpperCase();
  }
}
