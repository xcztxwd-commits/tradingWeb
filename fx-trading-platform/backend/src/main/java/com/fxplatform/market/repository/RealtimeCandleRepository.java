package com.fxplatform.market.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 封装演示实时行情的 candle upsert SQL，避免调度服务直接维护数据库细节。
 */
@Repository
@RequiredArgsConstructor
public class RealtimeCandleRepository {

  private final JdbcTemplate jdbcTemplate;

  public void upsert(String symbol, String timeframe, Instant openTime, BigDecimal price, BigDecimal volume) {
    jdbcTemplate.update("""
        INSERT INTO market.candles (symbol, timeframe, open_time, open, high, low, close, volume, source)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'demo-realtime')
        ON CONFLICT (symbol, timeframe, open_time) DO UPDATE SET
          high = GREATEST(market.candles.high, EXCLUDED.high),
          low = LEAST(market.candles.low, EXCLUDED.low),
          close = EXCLUDED.close,
          volume = market.candles.volume + EXCLUDED.volume
        """,
        symbol,
        timeframe,
        Timestamp.from(openTime),
        price,
        price,
        price,
        price,
        volume);
  }
}
