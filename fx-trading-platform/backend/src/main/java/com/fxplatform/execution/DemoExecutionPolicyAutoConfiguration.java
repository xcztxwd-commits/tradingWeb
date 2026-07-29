package com.fxplatform.execution;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ExecutionProperties.class)
public class DemoExecutionPolicyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(DemoExecutionPolicyProvider.class)
  PropertyDemoExecutionPolicyProvider demoExecutionPolicyProvider(ExecutionProperties properties) {
    return new PropertyDemoExecutionPolicyProvider(properties);
  }
}
