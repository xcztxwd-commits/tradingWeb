package com.fxplatform.tradinglab.report;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Fixed, bounded source for validation-only configuration secrets. */
public final class TradingLabFixedValidationSecretProvider {

  private static final int MAX_SECRETS = 32;
  private static final int MAX_SECRET_BYTES = 16_384;
  private static final int MAX_TOTAL_BYTES = 65_536;
  private static final List<String> PROPERTY_NAMES = List.of(
      "VALIDATION_INTERNAL_SECRET",
      "validation.internal.secret",
      "VALIDATION_INTERNAL_TOKEN",
      "validation.internal.token",
      "TRADING_LAB_VALIDATION_INTERNAL_TOKEN",
      "trading-lab.validation.internal-token",
      "SUPERVISOR_INTERNAL_TOKEN",
      "supervisor.internal.token",
      "TRADING_LAB_SUPERVISOR_INTERNAL_TOKEN",
      "trading-lab.supervisor.internal-token",
      "ADMIN_BOOTSTRAP_PASSWORD",
      "admin.bootstrap.password",
      "DATABASE_PASSWORD",
      "spring.datasource.password",
      "REDIS_PASSWORD",
      "spring.data.redis.password",
      "JWT_SECRET",
      "security.jwt.secret",
      "CONFIG_ENCRYPTION_KEY",
      "security.config.encryption-key",
      "MASSIVE_API_KEY",
      "massive.api-key",
      "MASSIVE_S3_ACCESS_KEY_ID",
      "massive.flat-files.access-key-id",
      "MASSIVE_S3_SECRET_ACCESS_KEY",
      "massive.flat-files.secret-access-key",
      "EXECUTION_BROKER_API_KEY",
      "execution.broker.api-key",
      "EXECUTION_FIX_API_KEY",
      "execution.fix.api-key",
      "EXECUTION_LP_API_KEY",
      "execution.lp.api-key");

  private final List<String> secrets;

  TradingLabFixedValidationSecretProvider(Environment environment) {
    this(readEnvironment(environment));
  }

  TradingLabFixedValidationSecretProvider(List<String> configuredSecrets) {
    if (configuredSecrets == null || configuredSecrets.size() > MAX_SECRETS) {
      throw unsafeConfiguration();
    }
    Set<String> copy = new LinkedHashSet<>();
    int totalBytes = 0;
    for (String secret : configuredSecrets) {
      if (secret == null) {
        throw unsafeConfiguration();
      }
      if (secret.isBlank()) {
        continue;
      }
      int bytes = utf8Length(secret);
      if (bytes > MAX_SECRET_BYTES || bytes > MAX_TOTAL_BYTES - totalBytes) {
        throw unsafeConfiguration();
      }
      if (copy.add(secret)) {
        totalBytes += bytes;
      }
    }
    this.secrets = List.copyOf(copy);
  }

  public List<String> secrets() {
    return secrets;
  }

  private static List<String> readEnvironment(Environment environment) {
    if (environment == null) {
      throw unsafeConfiguration();
    }
    return PROPERTY_NAMES.stream()
        .map(environment::getProperty)
        .filter(value -> value != null && !value.isBlank())
        .toList();
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static IllegalArgumentException unsafeConfiguration() {
    return new IllegalArgumentException("Unsafe Trading Lab validation secret configuration");
  }
}
