package com.fxplatform.chart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.repository.CandleRepository;
import com.fxplatform.market.provider.MarketDataRouter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChartServiceTest {

  @Mock
  private CandleRepository candleRepository;

  @Mock
  private MarketDataRouter marketDataRouter;

  @Test
  void candlesResolveThroughMarketDataRouter() {
    Instant from = Instant.parse("2026-06-12T00:00:00Z");
    Instant to = Instant.parse("2026-06-13T00:00:00Z");
    CandleResponse providerCandle = new CandleResponse(
        1781222400000L,
        new BigDecimal("1.15710"),
        new BigDecimal("1.15740"),
        new BigDecimal("1.15690"),
        new BigDecimal("1.15730"),
        BigDecimal.ZERO);

    when(marketDataRouter.candles("EURUSD", "5m", from, to)).thenReturn(List.of(providerCandle));

    ChartService service = new ChartService(candleRepository, marketDataRouter);

    List<CandleResponse> candles = service.candles("eur-usd", "5m", from, to);

    assertThat(candles).containsExactly(providerCandle);
  }
}
