package com.fxplatform.engagement.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EngagementClockConfiguration {

  @Bean
  Clock applicationClock() {
    return Clock.systemUTC();
  }
}
