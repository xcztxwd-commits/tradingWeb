package com.fxplatform.common.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Restores the application scheduler when another infrastructure scheduler makes Boot back off.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformTaskSchedulingConfiguration {

  @Bean(name = "taskScheduler")
  @ConditionalOnMissingBean(name = "taskScheduler")
  ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.build();
  }
}
