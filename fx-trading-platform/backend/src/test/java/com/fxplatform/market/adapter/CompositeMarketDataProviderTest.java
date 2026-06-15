package com.fxplatform.market.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.provider.MarketDataRouter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositeMarketDataProviderTest {

  @Mock
  private MarketDataRouter marketDataRouter;

  @Test
  void legacyQuoteCallsDelegateToMarketDataRouter() {
    QuoteResponse okxQuote = quote("BTCUSDT", "okx-quote");
    when(marketDataRouter.latestQuote("BTCUSDT")).thenReturn(okxQuote);

    CompositeMarketDataProvider provider = new CompositeMarketDataProvider(marketDataRouter);

    assertThat(provider.fetchLatestQuote("btcusdt", "BTC-USDT")).containsSame(okxQuote);
    verify(marketDataRouter).latestQuote("BTCUSDT");
  }

  @Test
  void legacyCandleCallsDelegateToMarketDataRouter() {
    Instant from = Instant.parse("2026-06-15T00:00:00Z");
    Instant to = Instant.parse("2026-06-15T01:00:00Z");
    CandleResponse okxCandle = new CandleResponse(
        1781462400000L,
        new BigDecimal("66000"),
        new BigDecimal("66010"),
        new BigDecimal("65990"),
        new BigDecimal("66005"),
        BigDecimal.ONE);
    when(marketDataRouter.candles("BTCUSDT", "1m", from, to)).thenReturn(List.of(okxCandle));

    CompositeMarketDataProvider provider = new CompositeMarketDataProvider(marketDataRouter);

    assertThat(provider.fetchCandles("btcusdt", "BTC-USDT", "1m", from, to)).containsExactly(okxCandle);
    verify(marketDataRouter).candles("BTCUSDT", "1m", from, to);
  }

  private QuoteResponse quote(String symbol, String source) {
    return new QuoteResponse(
        "quote",
        symbol,
        new BigDecimal("1.00"),
        new BigDecimal("1.02"),
        new BigDecimal("1.01"),
        new BigDecimal("0.02"),
        source,
        1781462400000L);
  }
}
