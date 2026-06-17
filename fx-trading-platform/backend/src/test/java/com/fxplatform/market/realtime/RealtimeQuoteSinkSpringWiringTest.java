package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fxplatform.market.repository.RealtimeCandleRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.websocket.MarketWsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RealtimeQuoteSinkSpringWiringTest {

  @Test
  void wiresProductionConstructorWhenTestConstructorExists() {
    new ApplicationContextRunner()
        .withBean(QuoteService.class, () -> mock(QuoteService.class))
        .withBean(RealtimeMarketSnapshotCache.class, RealtimeMarketSnapshotCache::new)
        .withBean(RealtimeCandleRepository.class, () -> mock(RealtimeCandleRepository.class))
        .withBean(MarketWsPublisher.class, () -> mock(MarketWsPublisher.class))
        .withBean(MarketTestControlService.class, () -> mock(MarketTestControlService.class))
        .withBean(RealtimeQuoteSink.class)
        .run(context -> assertThat(context).hasSingleBean(RealtimeQuoteSink.class));
  }
}
