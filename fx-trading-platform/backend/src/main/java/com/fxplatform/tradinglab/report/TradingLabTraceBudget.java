package com.fxplatform.tradinglab.report;

import com.fxplatform.common.exception.BusinessException;

/** One shared byte/node/depth budget for a raw HTTP trace snapshot. */
final class TradingLabTraceBudget {

  static final int MAX_DEPTH = 64;
  private static final long MAX_NODES = 131_072L;

  private long remainingBytes;
  private long remainingNodes;

  TradingLabTraceBudget(int maxBytes) {
    if (maxBytes < 1) {
      throw new IllegalArgumentException("Trading Lab trace budget must be positive");
    }
    remainingBytes = maxBytes;
    remainingNodes = Math.min(MAX_NODES, maxBytes);
  }

  void consumeNode(Domain domain) {
    if (--remainingNodes < 0L) {
      throw tooLarge(domain);
    }
  }

  void requireContainerHint(int size, Domain domain) {
    if (size < 0 || size > remainingNodes) {
      throw tooLarge(domain);
    }
  }

  void checkDepth(int depth, Domain domain) {
    if (depth > MAX_DEPTH) {
      throw tooLarge(domain);
    }
  }

  void consumeText(String value, Domain domain) {
    if (value == null) {
      return;
    }
    long bytes = utf8Length(value, remainingBytes);
    if (bytes > remainingBytes) {
      throw tooLarge(domain);
    }
    remainingBytes -= bytes;
  }

  void consumeBytes(long bytes, Domain domain) {
    if (bytes < 0L || bytes > remainingBytes) {
      throw tooLarge(domain);
    }
    remainingBytes -= bytes;
  }

  static long utf8Length(String value, long limit) {
    long bytes = 0L;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current <= 0x7f) {
        bytes++;
      } else if (current <= 0x7ff) {
        bytes += 2L;
      } else if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return limit + 1L;
        }
        bytes += 4L;
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return limit + 1L;
      } else {
        bytes += 3L;
      }
      if (bytes > limit) {
        return bytes;
      }
    }
    return bytes;
  }

  private static BusinessException tooLarge(Domain domain) {
    if (domain == Domain.BODY) {
      return new BusinessException(
          "TRADING_LAB_REPORT_UNSAFE_BODY",
          "Trading Lab HTTP body could not be safely recorded");
    }
    return new BusinessException(
        "TRADING_LAB_REPORT_UNSAFE_TRACE",
        "Trading Lab HTTP trace could not be safely recorded");
  }

  enum Domain {
    TRACE,
    BODY
  }
}
