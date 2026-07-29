package com.fxplatform.tradinglab.admin.report;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisTradingLabPrintConfirmationStore
    implements TradingLabPrintConfirmationStore {

  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final String KEY_PREFIX =
      "trading-lab:print-confirmation:";
  private static final DefaultRedisScript<Long> CONSUME_SCRIPT =
      new DefaultRedisScript<>("""
          local value = redis.call('GET', KEYS[1])
          if not value then
            return 0
          end
          if value ~= ARGV[1] then
            return -1
          end
          redis.call('DEL', KEYS[1])
          return 1
          """, Long.class);

  private final StringRedisTemplate redis;

  public RedisTradingLabPrintConfirmationStore(StringRedisTemplate redis) {
    this.redis = Objects.requireNonNull(redis, "redis");
  }

  @Override
  public void store(
      String tokenDigest,
      UUID actorId,
      UUID reportId,
      Duration ttl
  ) {
    String key = key(tokenDigest);
    String binding = binding(actorId, reportId);
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException(
          "Trading Lab print confirmation TTL must be positive");
    }
    try {
      redis.opsForValue().set(key, binding, ttl);
    } catch (RuntimeException exception) {
      throw TradingLabReportAdminException.confirmationUnavailable(exception);
    }
  }

  @Override
  public boolean consume(
      String tokenDigest,
      UUID actorId,
      UUID reportId
  ) {
    String key = key(tokenDigest);
    String binding = binding(actorId, reportId);
    try {
      Long result = redis.execute(
          CONSUME_SCRIPT,
          List.of(key),
          binding);
      if (result == null) {
        throw TradingLabReportAdminException.confirmationUnavailable(null);
      }
      return result == 1L;
    } catch (TradingLabReportAdminException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw TradingLabReportAdminException.confirmationUnavailable(exception);
    }
  }

  private static String key(String digest) {
    if (digest == null || !SHA_256.matcher(digest).matches()) {
      throw new IllegalArgumentException(
          "Trading Lab print confirmation digest is invalid");
    }
    return KEY_PREFIX + digest;
  }

  private static String binding(UUID actorId, UUID reportId) {
    return Objects.requireNonNull(actorId, "actorId")
        + ":"
        + Objects.requireNonNull(reportId, "reportId");
  }
}
