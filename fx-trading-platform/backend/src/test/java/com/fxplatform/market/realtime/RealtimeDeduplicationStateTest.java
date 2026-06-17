package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.realtime.RealtimeMarketEvent.Candle;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Quote;
import com.fxplatform.market.realtime.RealtimeMarketEvent.Trade;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RealtimeDeduplicationStateTest {

  private static final BigDecimal BID = new BigDecimal("100.00");
  private static final BigDecimal ASK = new BigDecimal("101.00");
  private static final BigDecimal PRICE = new BigDecimal("100.50");
  private static final BigDecimal QUANTITY = new BigDecimal("0.25");

  private final RealtimeDeduplicationState state = new RealtimeDeduplicationState();

  @Test
  void dropsDuplicateQuoteUpdateIds() {
    assertThat(state.shouldProcess(new Quote("BTCUSDT", 1000L, 10L, BID, ASK))).isTrue();
    assertThat(state.shouldProcess(new Quote("BTCUSDT", 1001L, 10L, BID, ASK))).isFalse();
  }

  @Test
  void dropsOldAggregateTradeIds() {
    assertThat(state.shouldProcess(new Trade("BTCUSDT", 1000L, 50L, PRICE, QUANTITY, "BUY"))).isTrue();
    assertThat(state.shouldProcess(new Trade("BTCUSDT", 1001L, 49L, PRICE, QUANTITY, "SELL"))).isFalse();
  }

  @Test
  void dropsOldCandleUpdatesForSameOpenTime() {
    Candle first = new Candle("BTCUSDT", 2000L, "1m", 1000L, 59999L, 10L,
        PRICE, PRICE, PRICE, PRICE, QUANTITY, false);
    Candle stale = new Candle("BTCUSDT", 1999L, "1m", 1000L, 59999L, 9L,
        PRICE, PRICE, PRICE, PRICE, QUANTITY, false);

    assertThat(state.shouldProcess(first)).isTrue();
    assertThat(state.shouldProcess(stale)).isFalse();
  }
}
