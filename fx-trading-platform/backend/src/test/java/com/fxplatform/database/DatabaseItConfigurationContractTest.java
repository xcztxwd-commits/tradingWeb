package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class DatabaseItConfigurationContractTest {

  @Test
  void databaseIntegrationProfileUsesDemoExecution() throws Exception {
    var propertySources = new YamlPropertySourceLoader().load(
        "database-it",
        new ClassPathResource("application-database-it.yml"));

    assertThat(propertySources)
        .extracting(source -> source.getProperty("execution.mode"))
        .containsExactly("demo");
  }
}
