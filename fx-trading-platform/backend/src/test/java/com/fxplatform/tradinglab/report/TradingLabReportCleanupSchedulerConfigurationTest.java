package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.FxPlatformApplication;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

class TradingLabReportCleanupSchedulerConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withPropertyValues(
          "trading-lab.report.cleanup.fixed-delay=PT1H",
          "trading-lab.report.cleanup.batch-size=17")
      .withUserConfiguration(CleanupTestConfiguration.class);

  @Test
  void missingPropertyCreatesNoCleanupBeanAndSchedulesNothing() {
    contextRunner.run(context -> {
      assertThat(context).doesNotHaveBean(TradingLabReportCleanupScheduler.class);
      assertThat(scheduledTasks(context)).isEmpty();
    });
  }

  @Test
  void falsePropertyCreatesNoCleanupBeanAndSchedulesNothing() {
    contextRunner
        .withPropertyValues("trading-lab.report.cleanup.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(TradingLabReportCleanupScheduler.class);
          assertThat(scheduledTasks(context)).isEmpty();
        });
  }

  @Test
  void truePropertyCreatesExactlyOneCleanupBeanAndOneScheduledTask() {
    contextRunner
        .withPropertyValues("trading-lab.report.cleanup.enabled=true")
        .run(context -> {
          assertThat(context).hasSingleBean(TradingLabReportCleanupScheduler.class);
          assertThat(scheduledTasks(context)).hasSize(1);

          context.getBean(TradingLabReportCleanupScheduler.class).cleanup();

          verify(context.getBean(TradingLabReportRetentionService.class))
              .deleteExpiredTerminalReports(17);
        });
  }

  @Test
  void registrationIsExplicitOptInAndApplicationDefaultsAreFrozen() throws Exception {
    assertThat(Modifier.isPublic(TradingLabReportCleanupScheduler.class.getModifiers())).isTrue();
    assertThat(Modifier.isFinal(TradingLabReportCleanupScheduler.class.getModifiers())).isTrue();

    ConditionalOnProperty condition = TradingLabReportCleanupScheduler.class
        .getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("trading-lab.report.cleanup");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();

    Method[] scheduledMethods = Arrays.stream(
            TradingLabReportCleanupScheduler.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(Scheduled.class))
        .toArray(Method[]::new);
    assertThat(scheduledMethods).hasSize(1);
    assertThat(scheduledMethods[0].getName()).isEqualTo("cleanup");

    EnableConfigurationProperties enabledProperties = FxPlatformApplication.class
        .getAnnotation(EnableConfigurationProperties.class);
    assertThat(enabledProperties).isNotNull();
    assertThat(enabledProperties.value()).contains(TradingLabReportProperties.class);

    List<PropertySource<?>> applicationProperties = new YamlPropertySourceLoader().load(
        "application",
        new FileSystemResource(Path.of("src/main/resources/application.yml")));
    assertThat(applicationProperties).anySatisfy(properties -> {
      assertThat(properties.getProperty("trading-lab.report.retention"))
          .isEqualTo("${TRADING_LAB_REPORT_RETENTION:P30D}");
      assertThat(properties.getProperty("trading-lab.report.cleanup.enabled"))
          .isEqualTo("${TRADING_LAB_REPORT_CLEANUP_ENABLED:false}");
      assertThat(properties.getProperty("trading-lab.report.cleanup.fixed-delay"))
          .isEqualTo("${TRADING_LAB_REPORT_CLEANUP_FIXED_DELAY:PT1H}");
    });
  }

  @Test
  void databaseTestAndValidationProfilesKeepCleanupDisabledByDefault() throws Exception {
    List<PropertySource<?>> databaseIt = new YamlPropertySourceLoader().load(
        "database-it",
        new FileSystemResource(
            Path.of("src/test/resources/application-database-it.yml")));
    assertThat(databaseIt).anySatisfy(properties -> {
      assertThat(properties.getProperty("spring.task.scheduling.enabled"))
          .isEqualTo(false);
      assertThat(properties.getProperty("trading-lab.report.cleanup.enabled"))
          .isEqualTo(false);
    });

    List<PropertySource<?>> validation = new YamlPropertySourceLoader().load(
        "validation",
        new FileSystemResource(
            Path.of("src/main/resources/application-validation.yml")));
    assertThat(validation).anySatisfy(properties ->
        assertThat(properties.getProperty("trading-lab.report.cleanup.enabled"))
            .isEqualTo(false));
  }

  private static java.util.Set<ScheduledTask> scheduledTasks(
      AssertableApplicationContext context) {
    return context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableScheduling
  @EnableConfigurationProperties(TradingLabReportProperties.class)
  @Import(TradingLabReportCleanupScheduler.class)
  static class CleanupTestConfiguration {

    @Bean
    TradingLabReportRetentionService tradingLabReportRetentionService() {
      return mock(TradingLabReportRetentionService.class);
    }

    @Bean
    TaskScheduler taskScheduler() {
      TaskScheduler scheduler = mock(TaskScheduler.class);
      when(scheduler.getClock()).thenReturn(Clock.systemUTC());
      return scheduler;
    }
  }
}
