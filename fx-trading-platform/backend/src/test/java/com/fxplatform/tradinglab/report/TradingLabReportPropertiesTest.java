package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TradingLabReportPropertiesTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void defaultsToA256KiBChunkAndThirtyDayRetention() {
    contextRunner.run(context -> {
      TradingLabReportProperties properties = context.getBean(TradingLabReportProperties.class);

      assertThat(properties.chunkBytes()).isEqualTo(262_144);
      assertThat(properties.maxLogicalValueBytes()).isEqualTo(1_048_576);
      assertThat(properties.maxActiveWriters()).isEqualTo(1);
      assertThat(properties.retention()).isEqualTo(Duration.ofDays(30));
      assertThat(properties.cleanup().enabled()).isFalse();
      assertThat(properties.cleanup().fixedDelay()).isEqualTo(Duration.ofHours(1));
      assertThat(properties.cleanup().batchSize()).isPositive();
    });
  }

  @Test
  void bindsExplicitBoundedValues() {
    contextRunner
        .withPropertyValues(
            "trading-lab.report.chunk-bytes=4096",
            "trading-lab.report.max-logical-value-bytes=8192",
            "trading-lab.report.max-active-writers=2",
            "trading-lab.report.retention=P7D",
            "trading-lab.report.cleanup.enabled=true",
            "trading-lab.report.cleanup.fixed-delay=PT5M",
            "trading-lab.report.cleanup.batch-size=17")
        .run(context -> {
          TradingLabReportProperties properties =
              context.getBean(TradingLabReportProperties.class);

          assertThat(properties.chunkBytes()).isEqualTo(4096);
          assertThat(properties.maxLogicalValueBytes()).isEqualTo(8192);
          assertThat(properties.maxActiveWriters()).isEqualTo(2);
          assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
          assertThat(properties.cleanup().enabled()).isTrue();
          assertThat(properties.cleanup().fixedDelay()).isEqualTo(Duration.ofMinutes(5));
          assertThat(properties.cleanup().batchSize()).isEqualTo(17);
        });
  }

  @Test
  void rejectsProductionChunkSizesOutsideTheFrozenBounds() {
    contextRunner
        .withPropertyValues("trading-lab.report.chunk-bytes=4095")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues("trading-lab.report.chunk-bytes=1048577")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsLogicalValueLimitsOutsideTheFrozenBoundsOrBelowTheChunkSize() {
    contextRunner
        .withPropertyValues(
            "trading-lab.report.chunk-bytes=8192",
            "trading-lab.report.max-logical-value-bytes=8191")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues("trading-lab.report.max-logical-value-bytes=16777217")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsNonPositiveAdmissionRetentionAndCleanupSettings() {
    contextRunner
        .withPropertyValues("trading-lab.report.max-active-writers=0")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues("trading-lab.report.retention=PT0S")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues("trading-lab.report.cleanup.fixed-delay=PT0S")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues("trading-lab.report.cleanup.batch-size=0")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues("trading-lab.report.cleanup.batch-size=1001")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(TradingLabReportProperties.class)
  static class PropertiesConfiguration {
  }
}
