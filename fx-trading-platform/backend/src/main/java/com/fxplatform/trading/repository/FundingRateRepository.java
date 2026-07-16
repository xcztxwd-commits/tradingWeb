package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.FundingRateEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FundingRateRepository extends FxBaseMapper<FundingRateEntity> {

  default boolean insertIfAbsent(FundingRateEntity fundingRate) {
    return insertOnConflictDoNothing(fundingRate) == 1;
  }

  @Insert("""
      INSERT INTO trading.funding_rates (
        id,
        symbol,
        funding_rate,
        funding_time,
        next_funding_time,
        mark_price,
        provider_code,
        source_mode,
        as_of,
        interval_minutes,
        raw_payload_hash
      ) VALUES (
        #{id},
        #{symbol},
        #{fundingRate},
        #{fundingTime},
        #{nextFundingTime},
        #{markPrice},
        #{providerCode},
        #{sourceMode},
        #{asOf},
        #{intervalMinutes},
        #{rawPayloadHash}
      )
      ON CONFLICT (symbol, funding_time) DO NOTHING
      """)
  int insertOnConflictDoNothing(FundingRateEntity fundingRate);

  default Optional<FundingRateEntity> findLatestBySymbol(String symbol) {
    return selectList(new LambdaQueryWrapper<FundingRateEntity>()
        .eq(FundingRateEntity::getSymbol, normalize(symbol))
        .orderByDesc(FundingRateEntity::getFundingTime)
        .last("LIMIT 1"))
        .stream()
        .findFirst();
  }

  @Select("""
      SELECT
        fr.id,
        fr.symbol,
        fr.funding_rate,
        fr.funding_time,
        fr.next_funding_time,
        fr.mark_price,
        fr.provider_code,
        fr.source_mode,
        fr.as_of,
        fr.interval_minutes,
        fr.raw_payload_hash,
        fr.created_at
      FROM trading.funding_rates fr
      WHERE fr.funding_time <= #{atOrBefore}
        AND (
          CAST(#{afterExclusive} AS timestamptz) IS NULL
          OR fr.funding_time > CAST(#{afterExclusive} AS timestamptz)
        )
        AND EXISTS (
          SELECT 1
          FROM trading.positions p
          JOIN core.trading_accounts a
            ON a.id = p.account_id
           AND a.account_type = 'DEMO'
           AND a.status = 'ACTIVE'
          WHERE p.symbol = fr.symbol
            AND p.status = 'OPEN'
            AND p.product_type = 'LINEAR_PERP'
            AND p.opened_at <= fr.funding_time
            AND NOT EXISTS (
              SELECT 1
              FROM trading.funding_settlements fs
              WHERE fs.position_id = p.id
                AND fs.funding_time = fr.funding_time
            )
        )
      ORDER BY fr.funding_time ASC, fr.symbol ASC
      """)
  List<FundingRateEntity> findDueRates(
      @Param("afterExclusive") Instant afterExclusive,
      @Param("atOrBefore") Instant atOrBefore);

  private static String normalize(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase();
  }
}
