package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BinanceStreamCommandLimiterTest {

  private final MutableClock clock = new MutableClock();

  @Test
  void releasesAtMostFourJsonCommandsPerSecond() {
    BinanceStreamCommandLimiter limiter = new BinanceStreamCommandLimiter(4, clock);

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isFalse();

    clock.advanceMillis(1000);

    assertThat(limiter.tryAcquire()).isTrue();
  }

  @Test
  void keepsFifthIncomingSlotAvailableForPongPressure() {
    BinanceStreamCommandLimiter limiter = new BinanceStreamCommandLimiter(4, clock);

    int released = 0;
    for (int index = 0; index < 5; index++) {
      if (limiter.tryAcquire()) {
        released++;
      }
    }

    assertThat(released).isEqualTo(4);
  }

  static class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-06-17T00:00:00Z");

    void advanceMillis(long millis) {
      now = now.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
