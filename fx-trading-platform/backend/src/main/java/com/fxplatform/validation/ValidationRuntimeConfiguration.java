package com.fxplatform.validation;

import com.fxplatform.validation.service.ValidationLoopbackHttpClient;
import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationRunEngine;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunRuntimeStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("validation")
@Configuration(proxyBeanMethods = false)
public class ValidationRuntimeConfiguration {

  @Bean
  public ValidationRunEngine validationRunEngine(
      ValidationLoopbackHttpClient loopbackHttpClient,
      ValidationRunRuntimeStore runtimeStore,
      ValidationMarketClock marketClock,
      ValidationRunEventStore eventStore
  ) {
    return new ValidationRunEngine(
        loopbackHttpClient,
        runtimeStore,
        marketClock,
        eventStore);
  }
}
