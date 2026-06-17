package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MarketTestControlServiceTest {

  @Test
  void disabledConfigRejectsOverrideRequests() {
    MarketTestControlProperties properties = new MarketTestControlProperties();
    MarketTestControlService service = service(properties, Clock.systemUTC(), Mockito.mock(RealtimeBackfillService.class));

    assertThatThrownBy(() -> service.startOverride(request("BTCUSDT", Duration.ofMinutes(1))))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("MARKET_TEST_CONTROL_DISABLED");
  }

  @Test
  void enabledConfigAcceptsFixedQuoteWithTtl() {
    MarketTestControlProperties properties = enabledProperties();
    MutableClock clock = new MutableClock(Instant.parse("2026-06-17T00:00:00Z"));
    MarketTestControlService service = service(properties, clock, Mockito.mock(RealtimeBackfillService.class));

    QuoteResponse response = service.startOverride(request("btc-usdt", Duration.ofMinutes(2)));

    assertThat(response.symbol()).isEqualTo("BTCUSDT");
    assertThat(response.bid()).isEqualByComparingTo("100.00");
    assertThat(response.ask()).isEqualByComparingTo("102.00");
    assertThat(response.mid()).isEqualByComparingTo("101.0000000000");
    assertThat(response.spread()).isEqualByComparingTo("2.00");
    assertThat(response.source()).isEqualTo("test-control");
    assertThat(response.timestamp()).isEqualTo(clock.millis());
    assertThat(service.overrideQuote("BTCUSDT")).contains(response);
  }

  @Test
  void overrideExpiresAfterTtl() {
    MarketTestControlProperties properties = enabledProperties();
    MutableClock clock = new MutableClock(Instant.parse("2026-06-17T00:00:00Z"));
    MarketTestControlService service = service(properties, clock, Mockito.mock(RealtimeBackfillService.class));

    service.startOverride(request("BTCUSDT", Duration.ofSeconds(1)));
    clock.advance(Duration.ofSeconds(2));

    assertThat(service.overrideQuote("BTCUSDT")).isEmpty();
  }

  @Test
  void endingOverrideTriggersBackfill() {
    MarketTestControlProperties properties = enabledProperties();
    RealtimeBackfillService backfillService = Mockito.mock(RealtimeBackfillService.class);
    MarketTestControlService service = service(properties, Clock.systemUTC(), backfillService);

    service.startOverride(request("BTCUSDT", Duration.ofMinutes(1)));
    service.endOverride("btc-usdt");
    service.endOverride("btc-usdt");

    verify(backfillService).backfill("BTCUSDT");
    verify(backfillService, never()).backfill("btc-usdt");
  }

  private MarketTestControlService service(
      MarketTestControlProperties properties,
      Clock clock,
      RealtimeBackfillService backfillService) {
    return new MarketTestControlService(properties, backfillService, clock);
  }

  private MarketTestControlProperties enabledProperties() {
    MarketTestControlProperties properties = new MarketTestControlProperties();
    properties.setEnabled(true);
    properties.setMaxTtl(Duration.ofMinutes(5));
    return properties;
  }

  private MarketTestControlRequest request(String symbol, Duration ttl) {
    return new MarketTestControlRequest(
        symbol,
        new BigDecimal("100.00"),
        new BigDecimal("102.00"),
        ttl);
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
