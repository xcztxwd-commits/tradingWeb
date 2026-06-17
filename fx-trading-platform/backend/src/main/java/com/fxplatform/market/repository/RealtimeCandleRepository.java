package com.fxplatform.market.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'demo-realtime-tick')
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

  public Optional<Instant> findLastOpenTime(String symbol, String timeframe) {
    return jdbcTemplate.query("""
        SELECT open_time
        FROM market.candles
        WHERE symbol = ? AND timeframe = ?
        ORDER BY open_time DESC
        LIMIT 1
        """,
        (rs, rowNum) -> rs.getTimestamp("open_time").toInstant(),
        symbol,
        timeframe)
        .stream()
        .findFirst();
  }

  public void upsertCandle(
      String symbol,
      String timeframe,
      Instant openTime,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal volume,
      String source
  ) {
    jdbcTemplate.update("""
        INSERT INTO market.candles (symbol, timeframe, open_time, open, high, low, close, volume, source)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (symbol, timeframe, open_time) DO UPDATE SET
          open = EXCLUDED.open,
          high = EXCLUDED.high,
          low = EXCLUDED.low,
          close = EXCLUDED.close,
          volume = EXCLUDED.volume,
          source = EXCLUDED.source
        """,
        symbol,
        timeframe,
        Timestamp.from(openTime),
        open,
        high,
        low,
        close,
        volume,
        source);
  }
}
