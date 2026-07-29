package com.fxplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class ProductionConfigurationSafetyTest {

  @Test
  void applicationYamlDoesNotFallbackToDefaultSecretsOrPasswords() throws Exception {
    String application = Files.readString(Path.of("src/main/resources/application.yml"));

    assertThat(application).contains("password: ${DATABASE_PASSWORD}");
    assertThat(application).contains("secret: ${JWT_SECRET}");
    assertThat(application).contains("encryption-key: ${CONFIG_ENCRYPTION_KEY}");
    assertThat(application).contains("mode: ${EXECUTION_MODE:disabled}");
    assertThat(application).doesNotContain("DATABASE_PASSWORD:password");
    assertThat(application).doesNotContain("change_me_to_long_random_secret_at_least_32_chars");
  }

  @Test
  void validatorRejectsDangerousRuntimeSecretValues() {
    ProductionSecuritySettingsValidator validator = new ProductionSecuritySettingsValidator(
        "change_me_to_long_random_secret_at_least_32_chars",
        "password",
        "unit-test-config-encryption-key-32chars");

    assertThatThrownBy(() -> validator.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET")
        .hasMessageContaining("DATABASE_PASSWORD");
  }

  @Test
  void validatorAcceptsExplicitStrongRuntimeSecretValues() throws Exception {
    ProductionSecuritySettingsValidator validator = new ProductionSecuritySettingsValidator(
        "unit-test-jwt-secret-value-that-is-long-enough",
        "postgres-password-from-env",
        "unit-test-config-encryption-key-32chars");

    validator.run(null);
  }

  @Test
  void scenarioIntegrationProfileDoesNotRunTheProductionSecretValidator() {
    Profile profile = ProductionSecuritySettingsValidator.class.getAnnotation(Profile.class);

    assertThat(profile).isNotNull();
    assertThat(profile.value()).contains("!dev & !test & !scenario-it");
  }
}
