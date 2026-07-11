package com.fxplatform.market.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.adapter.binance.BinanceSpotMarketDataProvider;
import com.fxplatform.market.adapter.binance.BinanceUsdMMarketDataProvider;
import com.fxplatform.market.adapter.local.LocalPerpMarketDataProvider;
import com.fxplatform.market.adapter.local.LocalSpotMarketDataProvider;
import com.fxplatform.market.adapter.okx.OkxSpotMarketDataProvider;
import com.fxplatform.market.adapter.okx.OkxSwapMarketDataProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

class MarketBundleProviderSpringWiringTest {

  @Test
  void allSixBundleProvidersBindSharedDurationConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(BundleProviderConfiguration.class)
        .withPropertyValues(
            "binance.rest-base-url=http://127.0.0.1:1",
            "binance.futures-base-url=http://127.0.0.1:1",
            "okx.rest-base-url=http://127.0.0.1:1",
            "market.bundle-freshness=7s",
            "market.public-http-timeout=1500ms",
            "market.local-perp.simulated-premium=0.0001",
            "market.local-perp.premium-limit=0.001")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(BinanceSpotMarketDataProvider.class);
          assertThat(context).hasSingleBean(OkxSpotMarketDataProvider.class);
          assertThat(context).hasSingleBean(BinanceUsdMMarketDataProvider.class);
          assertThat(context).hasSingleBean(OkxSwapMarketDataProvider.class);
          assertThat(context).hasSingleBean(LocalSpotMarketDataProvider.class);
          assertThat(context).hasSingleBean(LocalPerpMarketDataProvider.class);
          for (Object provider : java.util.List.of(
              context.getBean(BinanceSpotMarketDataProvider.class),
              context.getBean(OkxSpotMarketDataProvider.class),
              context.getBean(BinanceUsdMMarketDataProvider.class),
              context.getBean(OkxSwapMarketDataProvider.class),
              context.getBean(LocalSpotMarketDataProvider.class),
              context.getBean(LocalPerpMarketDataProvider.class))) {
            assertThat(ReflectionTestUtils.getField(provider, "freshness"))
                .isEqualTo(Duration.ofSeconds(7));
          }
          for (Object provider : java.util.List.of(
              context.getBean(BinanceSpotMarketDataProvider.class),
              context.getBean(OkxSpotMarketDataProvider.class),
              context.getBean(BinanceUsdMMarketDataProvider.class),
              context.getBean(OkxSwapMarketDataProvider.class))) {
            assertThat(ReflectionTestUtils.getField(provider, "requestTimeout"))
                .isEqualTo(Duration.ofMillis(1500));
          }
        });
  }

  @Configuration(proxyBeanMethods = false)
  @Import({
      BinanceSpotMarketDataProvider.class,
      OkxSpotMarketDataProvider.class,
      BinanceUsdMMarketDataProvider.class,
      OkxSwapMarketDataProvider.class,
      LocalSpotMarketDataProvider.class,
      LocalPerpMarketDataProvider.class
  })
  static class BundleProviderConfiguration {
  }
}
