package com.fxplatform.chart.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.chart.entity.CandleEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.time.Instant;
import java.util.List;

/**
 * CandleRepository 通过 MyBatis-Plus 查询 K 线数据。
 */
public interface CandleRepository extends FxBaseMapper<CandleEntity> {

  /** 按品种、周期和时间范围顺序查询 K 线。 */
  default List<CandleEntity> findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(
      String symbol,
      String timeframe,
      Instant from,
      Instant to
  ) {
    return selectList(new LambdaQueryWrapper<CandleEntity>()
        .eq(CandleEntity::getSymbol, symbol)
        .eq(CandleEntity::getTimeframe, timeframe)
        .between(CandleEntity::getOpenTime, from, to)
        .orderByAsc(CandleEntity::getOpenTime));
  }
}
