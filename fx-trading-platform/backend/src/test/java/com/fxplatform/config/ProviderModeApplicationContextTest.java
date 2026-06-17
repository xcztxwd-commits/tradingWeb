package com.fxplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderModeApplicationContextTest {

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer());
  }

  @Test
  void defaultProfileDoesNotEnableDemoProviders() {
    contextRunner().run(context -> {
      assertThat(context.getEnvironment().getProperty("market.demo-quotes.enabled", Boolean.class)).isFalse();
      assertThat(context.getEnvironment().getProperty("market.test-data.enabled", Boolean.class)).isFalse();
      assertThat(context.getEnvironment().getProperty("execution.mode")).isEqualTo("disabled");
    });
  }

  @Test
  void devProfileDoesNotEnableDemoQuotesUnlessExplicitlyConfigured() {
    contextRunner()
        .withPropertyValues("spring.profiles.active=dev")
        .run(context -> {
          assertThat(context.getEnvironment().getProperty("market.demo-quotes.enabled", Boolean.class)).isFalse();
          assertThat(context.getEnvironment().getProperty("market.test-data.enabled", Boolean.class)).isTrue();
          assertThat(context.getEnvironment().getProperty("execution.mode")).isEqualTo("demo");
        });
  }

  @Test
  void envOverrideCanEnableDevDemoQuotes() {
    contextRunner()
        .withPropertyValues(
            "spring.profiles.active=dev",
            "MARKET_DEMO_QUOTES_ENABLED=true")
        .run(context ->
            assertThat(context.getEnvironment().getProperty("market.demo-quotes.enabled", Boolean.class)).isTrue());
  }

  @Test
  void prodProfileDoesNotEnableDemoProviders() {
    contextRunner()
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context.getEnvironment().getProperty("market.demo-quotes.enabled", Boolean.class)).isFalse();
          assertThat(context.getEnvironment().getProperty("market.test-data.enabled", Boolean.class)).isFalse();
          assertThat(context.getEnvironment().getProperty("execution.mode")).isEqualTo("disabled");
        });
  }

  @Test
  void envOverrideCanDisableDevDemoQuotes() {
    contextRunner()
        .withPropertyValues(
            "spring.profiles.active=dev",
            "MARKET_DEMO_QUOTES_ENABLED=false")
        .run(context ->
            assertThat(context.getEnvironment().getProperty("market.demo-quotes.enabled", Boolean.class)).isFalse());
  }
}
