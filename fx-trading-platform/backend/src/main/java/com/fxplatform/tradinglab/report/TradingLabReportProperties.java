package com.fxplatform.tradinglab.report;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "trading-lab.report")
public final class TradingLabReportProperties {

  public static final int MIN_CHUNK_BYTES = 4 * 1024;
  public static final int MAX_CHUNK_BYTES = 1024 * 1024;
  public static final int MAX_LOGICAL_VALUE_BYTES = 16 * 1024 * 1024;
  public static final int MAX_CLEANUP_BATCH_SIZE = 1_000;

  private static final int DEFAULT_CHUNK_BYTES = 256 * 1024;
  private static final int DEFAULT_MAX_LOGICAL_VALUE_BYTES = 1024 * 1024;
  private static final int DEFAULT_MAX_ACTIVE_WRITERS = 1;

  @Min(MIN_CHUNK_BYTES)
  @Max(MAX_CHUNK_BYTES)
  private int chunkBytes = DEFAULT_CHUNK_BYTES;

  @Min(MIN_CHUNK_BYTES)
  @Max(MAX_LOGICAL_VALUE_BYTES)
  private int maxLogicalValueBytes = DEFAULT_MAX_LOGICAL_VALUE_BYTES;

  @Min(1)
  private int maxActiveWriters = DEFAULT_MAX_ACTIVE_WRITERS;

  @NotNull
  private Duration retention = Duration.ofDays(30);

  @Valid
  @NotNull
  private Cleanup cleanup = new Cleanup();

  public int chunkBytes() {
    return chunkBytes;
  }

  public void setChunkBytes(int chunkBytes) {
    this.chunkBytes = chunkBytes;
  }

  public int maxLogicalValueBytes() {
    return maxLogicalValueBytes;
  }

  public void setMaxLogicalValueBytes(int maxLogicalValueBytes) {
    this.maxLogicalValueBytes = maxLogicalValueBytes;
  }

  public int maxActiveWriters() {
    return maxActiveWriters;
  }

  public void setMaxActiveWriters(int maxActiveWriters) {
    this.maxActiveWriters = maxActiveWriters;
  }

  public Duration retention() {
    return retention;
  }

  public void setRetention(Duration retention) {
    this.retention = retention;
  }

  public Cleanup cleanup() {
    return cleanup;
  }

  public void setCleanup(Cleanup cleanup) {
    this.cleanup = cleanup;
  }

  @AssertTrue(message = "max-logical-value-bytes must be at least chunk-bytes")
  public boolean isLogicalValueLimitAtLeastChunkSize() {
    return maxLogicalValueBytes >= chunkBytes;
  }

  @AssertTrue(message = "retention must be positive")
  public boolean isRetentionPositive() {
    return retention != null && !retention.isZero() && !retention.isNegative();
  }

  public static final class Cleanup {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private boolean enabled;

    @NotNull
    private Duration fixedDelay = Duration.ofHours(1);

    @Min(1)
    @Max(MAX_CLEANUP_BATCH_SIZE)
    private int batchSize = DEFAULT_BATCH_SIZE;

    public boolean enabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration fixedDelay() {
      return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
      this.fixedDelay = fixedDelay;
    }

    public int batchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    @AssertTrue(message = "cleanup fixed-delay must be positive")
    public boolean isFixedDelayPositive() {
      return fixedDelay != null && !fixedDelay.isZero() && !fixedDelay.isNegative();
    }
  }
}
