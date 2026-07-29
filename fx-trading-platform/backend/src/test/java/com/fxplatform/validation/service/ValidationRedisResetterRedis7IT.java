package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class ValidationRedisResetterRedis7IT {

  @Test
  void redis7EpochProtocolIsAtomicReplaySafeAndLimitedToDatabaseZero() {
    try (GenericContainer<?> redis = startRedisOrAbort()) {
      LettuceConnectionFactory databaseZero = factory(redis, 0);
      LettuceConnectionFactory databaseOne = factory(redis, 1);
      try {
        put(databaseZero, "old-data", "delete-me");
        put(databaseOne, "db-one-sentinel", "preserve-me");
        ValidationRedisResetter resetter = new ValidationRedisResetter(databaseZero, 0);

        assertThat(resetter.resetToGeneration(7L, 11L)).isEqualTo(7L);
        assertThat(get(databaseZero, "old-data")).isNull();
        assertThat(get(databaseZero, "validation:reset:generation")).isEqualTo("7");
        assertThat(get(databaseZero, "validation:reset:epoch")).isEqualTo("11");
        assertThat(get(databaseOne, "db-one-sentinel")).isEqualTo("preserve-me");

        put(databaseZero, "late-data", "must-survive-exact-replay");
        assertThat(resetter.resetToGeneration(7L, 11L)).isEqualTo(7L);
        assertThat(get(databaseZero, "late-data")).isEqualTo("must-survive-exact-replay");

        assertThatThrownBy(() -> resetter.resetToGeneration(7L, 10L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stale");
        assertThat(get(databaseZero, "late-data")).isEqualTo("must-survive-exact-replay");

        assertThat(resetter.resetToGeneration(7L, 12L)).isEqualTo(7L);
        assertThat(get(databaseZero, "late-data")).isNull();
        assertThat(get(databaseZero, "validation:reset:epoch")).isEqualTo("12");
        assertThat(get(databaseOne, "db-one-sentinel")).isEqualTo("preserve-me");
      } finally {
        databaseZero.destroy();
        databaseOne.destroy();
      }
    }
  }

  private static GenericContainer<?> startRedisOrAbort() {
    try {
      DockerClientFactory.instance().client().versionCmd().exec();
      GenericContainer<?> redis = new GenericContainer<>(
          DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379);
      redis.start();
      return redis;
    } catch (RuntimeException failure) {
      Assumptions.assumeTrue(false, "BLOCKED: Docker is required for Redis 7 validation tests");
      throw failure;
    }
  }

  private static LettuceConnectionFactory factory(GenericContainer<?> redis, int database) {
    RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
        redis.getHost(),
        redis.getMappedPort(6379));
    configuration.setDatabase(database);
    LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  private static void put(LettuceConnectionFactory factory, String key, String value) {
    try (RedisConnection connection = factory.getConnection()) {
      connection.stringCommands().set(bytes(key), bytes(value));
    }
  }

  private static String get(LettuceConnectionFactory factory, String key) {
    try (RedisConnection connection = factory.getConnection()) {
      byte[] value = connection.stringCommands().get(bytes(key));
      return value == null ? null : new String(value, StandardCharsets.US_ASCII);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
