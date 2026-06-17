package com.fxplatform.common.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev & !test")
public class ProductionSecuritySettingsValidator implements ApplicationRunner {

  private static final String DEFAULT_JWT_SECRET = "change_me_to_long_random_secret_at_least_32_chars";

  private final String jwtSecret;
  private final String databasePassword;
  private final String configEncryptionKey;

  public ProductionSecuritySettingsValidator(
      @Value("${security.jwt.secret}") String jwtSecret,
      @Value("${spring.datasource.password}") String databasePassword,
      @Value("${security.config.encryption-key}") String configEncryptionKey
  ) {
    this.jwtSecret = jwtSecret;
    this.databasePassword = databasePassword;
    this.configEncryptionKey = configEncryptionKey;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> failures = new ArrayList<>();
    if (!hasMinimumLength(jwtSecret, 32) || DEFAULT_JWT_SECRET.equals(jwtSecret)) {
      failures.add("JWT_SECRET must be explicit and at least 32 characters");
    }
    if (!hasText(databasePassword) || "password".equalsIgnoreCase(databasePassword.trim())) {
      failures.add("DATABASE_PASSWORD must be explicit and cannot use the default value");
    }
    if (!hasMinimumLength(configEncryptionKey, 32)) {
      failures.add("CONFIG_ENCRYPTION_KEY must be explicit and at least 32 characters");
    }
    if (!failures.isEmpty()) {
      throw new IllegalStateException(String.join("; ", failures));
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private boolean hasMinimumLength(String value, int minimumLength) {
    return hasText(value) && value.length() >= minimumLength;
  }
}
