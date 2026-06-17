package com.fxplatform.market.realtime;

import java.time.Clock;

public class BinanceStreamCommandLimiter {

  private final int maxCommandsPerSecond;
  private final Clock clock;
  private long windowSecond;
  private int commandCount;

  public BinanceStreamCommandLimiter(int maxCommandsPerSecond, Clock clock) {
    this.maxCommandsPerSecond = Math.max(1, maxCommandsPerSecond);
    this.clock = clock;
    this.windowSecond = currentSecond();
  }

  public synchronized boolean tryAcquire() {
    long currentSecond = currentSecond();
    if (currentSecond != windowSecond) {
      windowSecond = currentSecond;
      commandCount = 0;
    }
    if (commandCount >= maxCommandsPerSecond) {
      return false;
    }
    commandCount++;
    return true;
  }

  private long currentSecond() {
    return clock.instant().toEpochMilli() / 1000L;
  }
}
