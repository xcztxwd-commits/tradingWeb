package com.fxplatform.tradinglab.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisTradingLabPrintConfirmationStoreTest {

  private static final String DIGEST = "a".repeat(64);
  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final UUID REPORT_ID = UUID.randomUUID();
  private static final String KEY =
      "trading-lab:print-confirmation:" + DIGEST;
  private static final String BINDING = ACTOR_ID + ":" + REPORT_ID;

  @Test
  void storesDigestKeyAndBindingForExactlyFiveMinutes() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    RedisTradingLabPrintConfirmationStore store =
        new RedisTradingLabPrintConfirmationStore(redis);

    store.store(
        DIGEST,
        ACTOR_ID,
        REPORT_ID,
        Duration.ofMinutes(5));

    verify(values).set(KEY, BINDING, Duration.ofMinutes(5));
  }

  @Test
  void matchingConsumeUsesOneAtomicLuaEvaluation() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(
        any(RedisScript.class),
        eq(List.of(KEY)),
        eq(BINDING))).thenReturn(1L);
    RedisTradingLabPrintConfirmationStore store =
        new RedisTradingLabPrintConfirmationStore(redis);

    assertThat(store.consume(DIGEST, ACTOR_ID, REPORT_ID)).isTrue();

    verify(redis).execute(
        any(RedisScript.class),
        eq(List.of(KEY)),
        eq(BINDING));
  }

  @Test
  void redisFailureIsTruthfulAndFailClosed() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(
        any(RedisScript.class),
        eq(List.of(KEY)),
        eq(BINDING)))
        .thenThrow(new RedisConnectionFailureException("offline"));
    RedisTradingLabPrintConfirmationStore store =
        new RedisTradingLabPrintConfirmationStore(redis);

    assertThatThrownBy(() -> store.consume(DIGEST, ACTOR_ID, REPORT_ID))
        .isInstanceOf(TradingLabReportAdminException.class)
        .extracting("httpStatus")
        .isEqualTo(503);
  }
}
