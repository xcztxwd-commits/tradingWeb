package com.fxplatform.validation.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/** Clears only the configured validation Redis database and advances its generation marker. */
@Profile("validation")
@Component
public class ValidationRedisResetter {

  private static final byte[] GENERATION_KEY =
      "validation:reset:generation".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EPOCH_KEY =
      "validation:reset:epoch".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_TRANSACTION_RETRIES = 16;

  private final RedisConnectionFactory connectionFactory;

  public ValidationRedisResetter(
      RedisConnectionFactory connectionFactory,
      @Value("${spring.data.redis.database}") int configuredDatabase
  ) {
    if (configuredDatabase != 0) {
      throw new IllegalStateException("Validation Redis must use logical database zero");
    }
    this.connectionFactory = connectionFactory;
  }

  /** Atomically installs one PostgreSQL-fenced reset epoch, or replays it as a strict no-op. */
  public long resetToGeneration(long targetGeneration, long resetEpoch) {
    if (targetGeneration <= 0L || resetEpoch <= 0L) {
      throw new IllegalArgumentException("Validation reset target and epoch must be positive");
    }
    try (RedisConnection connection = connectionFactory.getConnection()) {
      for (int attempt = 0; attempt < MAX_TRANSACTION_RETRIES; attempt++) {
        connection.watch(GENERATION_KEY, EPOCH_KEY);
        long storedGeneration = parseNonNegative(
            connection.stringCommands().get(GENERATION_KEY),
            "generation");
        long storedEpoch = parseNonNegative(
            connection.stringCommands().get(EPOCH_KEY),
            "epoch");
        if (storedEpoch > resetEpoch
            || storedGeneration > targetGeneration
            || (storedEpoch == resetEpoch && storedGeneration != targetGeneration)) {
          connection.unwatch();
          throw new IllegalStateException("Validation Redis reset epoch is stale");
        }
        if (storedEpoch == resetEpoch && storedGeneration == targetGeneration) {
          connection.unwatch();
          return targetGeneration;
        }

        connection.multi();
        connection.serverCommands().flushDb();
        connection.stringCommands().set(GENERATION_KEY, ascii(targetGeneration));
        connection.stringCommands().set(EPOCH_KEY, ascii(resetEpoch));
        List<Object> results = connection.exec();
        if (results != null && results.size() == 3) {
          return targetGeneration;
        }
      }
      throw new IllegalStateException("Validation Redis reset transaction did not converge");
    }
  }

  public long currentGeneration() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      return parseNonNegative(
          connection.stringCommands().get(GENERATION_KEY),
          "generation");
    }
  }

  private static byte[] ascii(long value) {
    return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
  }

  private static long parseNonNegative(byte[] value, String label) {
    if (value == null) {
      return 0L;
    }
    long generation;
    try {
      generation = Long.parseLong(new String(value, StandardCharsets.US_ASCII));
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("Validation Redis " + label + " is invalid");
    }
    if (generation < 0) {
      throw new IllegalStateException("Validation Redis " + label + " is invalid");
    }
    return generation;
  }
}
