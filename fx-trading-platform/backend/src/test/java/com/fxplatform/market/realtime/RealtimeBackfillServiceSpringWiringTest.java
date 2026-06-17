package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fxplatform.market.adapter.binance.BinanceSpotMarketDataProvider;
import com.fxplatform.market.repository.RealtimeCandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RealtimeBackfillServiceSpringWiringTest {

  @Test
  void wiresWithConstructorInjectedDependencies() {
    new ApplicationContextRunner()
        .withBean(MarketRealtimeProperties.class, MarketRealtimeProperties::new)
        .withBean(BinanceSpotMarketDataProvider.class, () -> mock(BinanceSpotMarketDataProvider.class))
        .withBean(RealtimeCandleRepository.class, () -> mock(RealtimeCandleRepository.class))
        .withBean(RealtimeBackfillService.class)
        .run(context -> assertThat(context).hasSingleBean(RealtimeBackfillService.class));
  }
}
