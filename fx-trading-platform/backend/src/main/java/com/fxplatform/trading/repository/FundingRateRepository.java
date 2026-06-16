package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.FundingRateEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FundingRateRepository extends FxBaseMapper<FundingRateEntity> {

  default Optional<FundingRateEntity> findLatestBySymbol(String symbol) {
    return selectList(new LambdaQueryWrapper<FundingRateEntity>()
        .eq(FundingRateEntity::getSymbol, normalize(symbol))
        .orderByDesc(FundingRateEntity::getFundingTime)
        .last("LIMIT 1"))
        .stream()
        .findFirst();
  }

  default List<FundingRateEntity> findDueRates(Instant afterExclusive, Instant atOrBefore) {
    LambdaQueryWrapper<FundingRateEntity> query = new LambdaQueryWrapper<FundingRateEntity>()
        .le(FundingRateEntity::getFundingTime, atOrBefore)
        .orderByAsc(FundingRateEntity::getFundingTime);
    if (afterExclusive != null) {
      query.gt(FundingRateEntity::getFundingTime, afterExclusive);
    }
    return selectList(query);
  }

  private static String normalize(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase();
  }
}
