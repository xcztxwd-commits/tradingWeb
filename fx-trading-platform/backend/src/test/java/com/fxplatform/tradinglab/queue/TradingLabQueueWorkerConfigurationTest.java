package com.fxplatform.tradinglab.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;

class TradingLabQueueWorkerConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withPropertyValues(
          "trading-lab.queue.worker-instance-id=configuration-test-worker",
          "trading-lab.queue.lease-duration=PT30S",
          "trading-lab.queue.poll-delay=PT1H",
          "trading-lab.queue.poll-delay-ms=3600000")
      .withUserConfiguration(WorkerTestConfiguration.class);

  @Test
  void missingPropertyCreatesNoWorkerBeanAndSchedulesNothing() {
    contextRunner.run(context -> {
      assertThat(context).doesNotHaveBean(TradingLabQueueWorker.class);
      assertThat(scheduledTasks(context)).isEmpty();
    });
  }

  @Test
  void falsePropertyCreatesNoWorkerBeanAndSchedulesNothing() {
    contextRunner
        .withPropertyValues("trading-lab.queue.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(TradingLabQueueWorker.class);
          assertThat(scheduledTasks(context)).isEmpty();
        });
  }

  @Test
  void truePropertyCreatesExactlyOneWorkerAndOneScheduledPoll() {
    contextRunner
        .withPropertyValues("trading-lab.queue.enabled=true")
        .run(context -> {
          assertThat(context).hasSingleBean(TradingLabQueueWorker.class);
          assertThat(scheduledTasks(context)).hasSize(1);
        });
  }

  @Test
  void registrationIsExplicitOptInAndApplicationDefaultIsOff() throws Exception {
    ConditionalOnProperty condition = TradingLabQueueWorker.class
        .getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("trading-lab.queue");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();

    Method[] scheduledMethods = java.util.Arrays.stream(
            TradingLabQueueWorker.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(Scheduled.class))
        .toArray(Method[]::new);
    assertThat(scheduledMethods).hasSize(1);
    assertThat(scheduledMethods[0].getName()).isEqualTo("poll");

    List<PropertySource<?>> applicationProperties = new YamlPropertySourceLoader().load(
        "application",
        new FileSystemResource(Path.of("src/main/resources/application.yml")));
    assertThat(applicationProperties)
        .anySatisfy(properties -> assertThat(
            properties.getProperty("trading-lab.queue.enabled"))
            .isEqualTo("${TRADING_LAB_QUEUE_ENABLED:false}"));
  }

  private static java.util.Set<ScheduledTask> scheduledTasks(
      AssertableApplicationContext context) {
    return context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableScheduling
  @Import(TradingLabQueueWorker.class)
  static class WorkerTestConfiguration {

    @Bean
    TradingLabLeaseService tradingLabLeaseService() {
      return mock(TradingLabLeaseService.class);
    }

    @Bean
    TradingLabRunCoordinator tradingLabRunCoordinator() {
      return mock(TradingLabRunCoordinator.class);
    }

    @Bean
    TaskScheduler taskScheduler() {
      TaskScheduler scheduler = mock(TaskScheduler.class);
      when(scheduler.getClock()).thenReturn(Clock.systemUTC());
      return scheduler;
    }
  }
}
