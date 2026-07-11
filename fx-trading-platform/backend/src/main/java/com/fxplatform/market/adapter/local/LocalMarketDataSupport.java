package com.fxplatform.market.adapter.local;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.provider.CandleRequestPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class LocalMarketDataSupport {

  private LocalMarketDataSupport() {
  }

  static List<CandleResponse> candles(CandleRequest request, BigDecimal reference) {
    if (!CandleRequestPolicy.isValid(request)) {
      return List.of();
    }
    long intervalSeconds = intervalSeconds(request.timeframe());
    Instant from = request.from();
    Instant to = request.to();
    long first = (from.getEpochSecond() / intervalSeconds) * intervalSeconds;
    long lastExclusive = to.getEpochSecond();
    long available = Math.max(1L, (lastExclusive - first + intervalSeconds - 1L) / intervalSeconds);
    long count = Math.min(available, 100L);
    first = Math.max(first, lastExclusive - count * intervalSeconds);
    List<CandleResponse> candles = new ArrayList<>((int) count);
    for (long openSecond = first; openSecond < lastExclusive; openSecond += intervalSeconds) {
      double phase = Math.sin(openSecond / (double) intervalSeconds / 11.0) * 0.0008;
      BigDecimal open = reference.multiply(BigDecimal.valueOf(1 + phase)).setScale(10, RoundingMode.HALF_UP);
      BigDecimal close = reference.multiply(BigDecimal.valueOf(1 - phase / 2)).setScale(10, RoundingMode.HALF_UP);
      BigDecimal range = reference.multiply(new BigDecimal("0.001")).abs();
      candles.add(new CandleResponse(
          Instant.ofEpochSecond(openSecond).toEpochMilli(),
          open,
          open.max(close).add(range),
          open.min(close).subtract(range).max(BigDecimal.ZERO),
          close,
          BigDecimal.valueOf(10 + candles.size())));
    }
    return candles;
  }

  private static long intervalSeconds(String timeframe) {
    return switch (timeframe) {
      case "1s" -> 1L;
      case "1m" -> 60L;
      case "5m" -> 300L;
      case "15m" -> 900L;
      case "1h" -> 3_600L;
      case "4h" -> 14_400L;
      case "1d" -> 86_400L;
      default -> throw new IllegalArgumentException("Unsupported candle timeframe");
    };
  }
}
