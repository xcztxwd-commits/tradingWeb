package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JdkValidationLoopbackHttpClientWiringTest {

  private static final String INTERNAL_SECRET =
      "validation-internal-secret-at-least-32-characters";

  @Test
  void productionConstructorRemainsInjectableWhenATestSeamConstructorExists() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "spring.profiles.active=validation",
            "validation.internal.secret=" + INTERNAL_SECRET)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(JdkValidationLoopbackHttpClient.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(JdkValidationLoopbackHttpClient.class);
        });
  }
}
