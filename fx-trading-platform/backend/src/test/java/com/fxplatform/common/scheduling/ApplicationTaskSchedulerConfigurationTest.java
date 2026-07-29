package com.fxplatform.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ApplicationTaskSchedulerConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
      .withBean(
          "messageBrokerTaskScheduler",
          TaskScheduler.class,
          () -> mock(TaskScheduler.class))
      .withUserConfiguration(SchedulingScan.class);

  @Test
  void standardApplicationSchedulerStillExistsAlongsideTheMessageBrokerScheduler() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.containsBean("taskScheduler")).isTrue();
      assertThat(context.getBean("taskScheduler"))
          .isInstanceOf(ThreadPoolTaskScheduler.class);
      assertThat(context.getBeansOfType(TaskScheduler.class))
          .containsOnlyKeys("messageBrokerTaskScheduler", "taskScheduler");
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ComponentScan(basePackages = "com.fxplatform.common.scheduling")
  static class SchedulingScan {
  }
}
