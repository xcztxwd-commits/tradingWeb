package com.fxplatform.engagement.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EngagementClockConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(EngagementClockConfiguration.class);

  @Test
  void providesTheSingleUtcApplicationClock() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(Clock.class);
      assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
    });
  }
}
