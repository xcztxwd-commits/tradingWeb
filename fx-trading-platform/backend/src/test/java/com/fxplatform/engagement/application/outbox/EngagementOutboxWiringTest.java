package com.fxplatform.engagement.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class EngagementOutboxWiringTest {

  @Test
  void workerIsOffInBaseAndDevButExplicitlyOnByDefaultInProduction() throws IOException {
    assertThat(enabled("application.yml")).isFalse();
    assertThat(enabled("application-dev.yml")).isFalse();
    assertThat(enabled("application-prod.yml")).isTrue();
  }

  private static boolean enabled(String resource) throws IOException {
    List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(
        resource,
        new ClassPathResource(resource));
    MutablePropertySources sources = new MutablePropertySources();
    loaded.forEach(sources::addLast);
    return Boolean.TRUE.equals(new PropertySourcesPropertyResolver(sources)
        .getProperty("app.engagement.outbox.enabled", Boolean.class));
  }
}
