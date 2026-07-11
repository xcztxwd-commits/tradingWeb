package com.fxplatform.market.provider;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.market.model.CandleRequest;
import java.time.Duration;
import java.util.Set;

public final class CandleRequestPolicy {

  private static final Set<String> ALLOWED_TIMEFRAMES = Set.of(
      "1s", "1m", "5m", "15m", "1h", "4h", "1d");
  private static final Duration MAX_RANGE = Duration.ofDays(366);

  private CandleRequestPolicy() {
  }

  public static void requireValid(CandleRequest request) {
    if (!isValid(request)) {
      throw new BusinessException(
          ErrorCode.INVALID_CANDLE_REQUEST,
          "Candle request requires an allowed timeframe and a positive range of at most 366 days");
    }
  }

  public static boolean isValid(CandleRequest request) {
    if (request == null
        || request.timeframe() == null
        || !ALLOWED_TIMEFRAMES.contains(request.timeframe())
        || request.from() == null
        || request.to() == null
        || !request.from().isBefore(request.to())) {
      return false;
    }
    try {
      return Duration.between(request.from(), request.to()).compareTo(MAX_RANGE) <= 0;
    } catch (ArithmeticException ignored) {
      return false;
    }
  }
}
