package com.fxplatform.engagement.config;

import com.fxplatform.engagement.application.content.ContentAssetStorage;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class ContentAssetStorageConfiguration {

  @Bean
  @Profile("!prod")
  @ConditionalOnProperty(
      name = "app.engagement.assets.storage",
      havingValue = "local")
  ContentAssetStorage localContentAssetStorage(
      @Value("${app.engagement.assets.local-root:./var/engagement-assets}") String root
  ) {
    return new LocalContentAssetStorage(Path.of(root));
  }

  @Bean
  @Profile("!prod")
  @ConditionalOnProperty(
      name = "app.engagement.assets.storage",
      havingValue = "disabled",
      matchIfMissing = true)
  ContentAssetStorage unavailableContentAssetStorage() {
    return new UnavailableContentAssetStorage();
  }

  @Bean
  @Profile("prod")
  ContentAssetStorage productionContentAssetStorage() {
    return new UnavailableContentAssetStorage();
  }
}
