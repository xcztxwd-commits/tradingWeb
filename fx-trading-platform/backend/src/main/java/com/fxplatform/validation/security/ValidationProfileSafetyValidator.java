package com.fxplatform.validation.security;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Rejects an unsafe validation profile before the application context can create infrastructure beans. */
public final class ValidationProfileSafetyValidator implements EnvironmentPostProcessor, Ordered {

  private static final String VALIDATION_PROFILE = "validation";
  private static final String FAIL_SINK = "http://127.0.0.1:9";
  private static final String DATABASE_HOST = "validation-postgres";
  private static final String DATABASE_PREFIX = "fx_validation_";
  private static final String DATABASE_USERNAME = "fx_validation_app";
  private static final String DATABASE_DRIVER = "org.postgresql.Driver";
  private static final String REDIS_HOST = "validation-redis";

  private static final List<String> ABSENT_PROPERTIES = List.of(
      "spring.datasource.jndi-name",
      "spring.datasource.type",
      "spring.flyway.url",
      "spring.flyway.user",
      "spring.flyway.password",
      "spring.flyway.driver-class-name",
      "spring.flyway.schemas",
      "spring.flyway.default-schema",
      "spring.flyway.table",
      "spring.data.redis.url",
      "spring.data.redis.username",
      "server.servlet.context-path");

  private static final List<String> ABSENT_PROPERTY_PREFIXES = List.of(
      "spring.datasource.hikari",
      "spring.datasource.xa",
      "spring.data.redis.sentinel",
      "spring.data.redis.cluster");

  private static final List<String> FALSE_PROPERTIES = List.of(
      "spring.task.scheduling.enabled",
      "admin.bootstrap.enabled",
      "app.engagement.scheduler.enabled",
      "app.engagement.retention.enabled",
      "app.engagement.outbox.enabled",
      "massive.write-quotes-to-db",
      "market.demo-quotes.enabled",
      "market.realtime.enabled",
      "market.realtime.backfill-enabled",
      "market.realtime.dynamic-symbols-enabled",
      "market.provider-instrument-sync.enabled",
      "market.quote-broadcast-enabled",
      "market.test-data.enabled",
      "market.test-control.enabled",
      "trading.pending-order-execution-enabled",
      "trading.protective-order-execution-enabled",
      "trading.funding.enabled",
      "trading.fx-financing.enabled",
      "trading.liquidation.enabled",
      "trading-lab.queue.enabled",
      "trading-lab.report.cleanup.enabled");

  private static final List<String> FAIL_SINK_PROPERTIES = List.of(
      "massive.rest-base-url",
      "massive.websocket-forex-url",
      "massive.flat-files.endpoint",
      "binance.rest-base-url",
      "binance.web-base-url",
      "binance.futures-base-url",
      "binance.websocket-base-url",
      "okx.rest-base-url",
      "market.fear-greed-url",
      "market.realtime.websocket-base-url",
      "execution.broker.endpoint",
      "execution.fix.endpoint",
      "execution.lp.endpoint");

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment,
      SpringApplication application
  ) {
    if (!Arrays.asList(environment.getActiveProfiles()).contains(VALIDATION_PROFILE)) {
      return;
    }

    Set<String> failures = new LinkedHashSet<>();
    if (environment.getActiveProfiles().length != 1) {
      failures.add("spring.profiles.active");
    }

    requireExact(environment, failures, "server.port", "8080");
    requireExact(environment, failures, "validation.safety.enabled", "true");
    requireExact(environment, failures, "execution.mode", "demo");
    requireDatabaseBinding(environment, failures);
    requireExact(environment, failures, "spring.datasource.username", DATABASE_USERNAME);
    requireText(environment, failures, "spring.datasource.password", 1);
    requireExact(environment, failures, "spring.datasource.driver-class-name", DATABASE_DRIVER);
    requireExact(environment, failures, "spring.data.redis.host", REDIS_HOST);
    requireExact(environment, failures, "spring.data.redis.port", "6379");
    requireExact(environment, failures, "spring.data.redis.database", "0");
    requireText(environment, failures, "spring.data.redis.password", 1);
    requireText(environment, failures, "security.jwt.secret", 32);
    requireText(environment, failures, "security.config.encryption-key", 32);
    requireText(environment, failures, "validation.internal.secret", 32);

    ABSENT_PROPERTIES.forEach(key -> requireAbsent(environment, failures, key));
    ABSENT_PROPERTY_PREFIXES.forEach(prefix ->
        requireAbsentPrefix(environment, failures, prefix));

    FALSE_PROPERTIES.forEach(key -> requireExact(environment, failures, key, "false"));
    FAIL_SINK_PROPERTIES.forEach(key -> requireExact(environment, failures, key, FAIL_SINK));

    if (!failures.isEmpty()) {
      throw new IllegalStateException(
          "Unsafe validation configuration keys: " + String.join(", ", failures));
    }
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  private static void requireDatabaseBinding(
      ConfigurableEnvironment environment,
      Set<String> failures
  ) {
    String key = "spring.datasource.url";
    String value = read(environment, failures, key);
    if (!hasText(value)) {
      failures.add(key);
      return;
    }

    try {
      if (!value.startsWith("jdbc:")) {
        failures.add(key);
        return;
      }
      URI uri = URI.create(value.substring("jdbc:".length()));
      String path = uri.getPath();
      String database = path == null || path.length() < 2 ? "" : path.substring(1);
      if (!"postgresql".equalsIgnoreCase(uri.getScheme())
          || !DATABASE_HOST.equalsIgnoreCase(uri.getHost())
          || uri.getPort() != 5432
          || !database.startsWith(DATABASE_PREFIX)
          || database.length() == DATABASE_PREFIX.length()
          || database.indexOf('/') >= 0
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        failures.add(key);
      }
    } catch (RuntimeException exception) {
      failures.add(key);
    }
  }

  private static void requireExact(
      ConfigurableEnvironment environment,
      Set<String> failures,
      String key,
      String expected
  ) {
    String value = read(environment, failures, key);
    if (value == null || !expected.equalsIgnoreCase(value.trim())) {
      failures.add(key);
    }
  }

  private static void requireAbsent(
      ConfigurableEnvironment environment,
      Set<String> failures,
      String key
  ) {
    if (read(environment, failures, key) != null) {
      failures.add(key);
    }
  }

  private static void requireAbsentPrefix(
      ConfigurableEnvironment environment,
      Set<String> failures,
      String prefix
  ) {
    try {
      ConfigurationPropertyName prefixName = ConfigurationPropertyName.of(prefix);
      for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
        if (source.getConfigurationProperty(prefixName) != null) {
          failures.add(prefix);
          return;
        }
        if (source instanceof IterableConfigurationPropertySource iterable
            && iterable.stream().anyMatch(prefixName::isAncestorOf)) {
          failures.add(prefix);
          return;
        }
      }
    } catch (RuntimeException exception) {
      failures.add(prefix);
    }
  }

  private static void requireText(
      ConfigurableEnvironment environment,
      Set<String> failures,
      String key,
      int minimumLength
  ) {
    String value = read(environment, failures, key);
    if (!hasText(value) || value.length() < minimumLength) {
      failures.add(key);
    }
  }

  private static String read(
      ConfigurableEnvironment environment,
      Set<String> failures,
      String key
  ) {
    try {
      return environment.getProperty(key);
    } catch (RuntimeException exception) {
      failures.add(key);
      return null;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
