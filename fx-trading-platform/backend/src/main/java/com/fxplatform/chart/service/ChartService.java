package com.fxplatform.chart.service;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.provider.MarketDataRouter;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves K-line data through configured market-data bindings.
 */
@Service
@RequiredArgsConstructor
public class ChartService {

  @SuppressWarnings("unused")
  private final CandleRepository candleRepository;
  private final MarketDataRouter marketDataRouter;

  public List<CandleResponse> candles(String symbol, String timeframe, Instant from, Instant to) {
    return marketDataRouter.candles(SymbolNormalizer.normalize(symbol), timeframe, from, to);
  }
}
