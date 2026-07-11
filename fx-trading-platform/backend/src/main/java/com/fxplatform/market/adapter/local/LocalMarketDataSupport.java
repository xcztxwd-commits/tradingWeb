package com.fxplatform.market.adapter.local;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.model.CandleRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class LocalMarketDataSupport {

  private LocalMarketDataSupport() {
  }

  static List<CandleResponse> candles(CandleRequest request, BigDecimal reference, Instant now) {
    String timeframe = request == null || request.timeframe() == null ? "1m" : request.timeframe();
    long intervalSeconds = intervalSeconds(timeframe);
    Instant to = request == null || request.to() == null ? now : request.to();
    Instant from = request == null || request.from() == null ? to.minusSeconds(intervalSeconds * 60L) : request.from();
    if (!from.isBefore(to)) {
      return List.of();
    }
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
    if (timeframe == null || timeframe.length() < 2) {
      return 60L;
    }
    try {
      long value = Long.parseLong(timeframe.substring(0, timeframe.length() - 1));
      return switch (Character.toLowerCase(timeframe.charAt(timeframe.length() - 1))) {
        case 's' -> value;
        case 'm' -> value * 60L;
        case 'h' -> value * 3600L;
        case 'd' -> value * 86400L;
        default -> 60L;
      };
    } catch (NumberFormatException ignored) {
      return 60L;
    }
  }
}
