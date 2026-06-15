package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fxplatform.market.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class ExecutionAdapterApplicationContextTest {

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withPropertyValues("massive.quote-stale-ms=60000")
        .withUserConfiguration(AdapterTestConfiguration.class);
  }

  @Test
  void missingExecutionModeDoesNotFallBackToDemoAdapter() {
    contextRunner().run(context ->
        assertThat(context).doesNotHaveBean(ExecutionAdapter.class));
  }

  @Test
  void demoModeOnlyCreatesSimulatedAdapter() {
    contextRunner()
        .withPropertyValues("execution.mode=demo")
        .run(context -> {
          assertThat(context).hasSingleBean(ExecutionAdapter.class);
          assertThat(context).hasSingleBean(SimulatedExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(BrokerExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(FixExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(LpExecutionAdapter.class);
        });
  }

  @Test
  void brokerModeOnlyCreatesBrokerAdapter() {
    contextRunner()
        .withPropertyValues("execution.mode=broker")
        .run(context -> {
          assertThat(context).hasSingleBean(ExecutionAdapter.class);
          assertThat(context).hasSingleBean(BrokerExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(SimulatedExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(FixExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(LpExecutionAdapter.class);
        });
  }

  @Test
  void fixModeOnlyCreatesFixAdapter() {
    contextRunner()
        .withPropertyValues("execution.mode=fix")
        .run(context -> {
          assertThat(context).hasSingleBean(ExecutionAdapter.class);
          assertThat(context).hasSingleBean(FixExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(SimulatedExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(BrokerExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(LpExecutionAdapter.class);
        });
  }

  @Test
  void lpModeOnlyCreatesLpAdapter() {
    contextRunner()
        .withPropertyValues("execution.mode=lp")
        .run(context -> {
          assertThat(context).hasSingleBean(ExecutionAdapter.class);
          assertThat(context).hasSingleBean(LpExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(SimulatedExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(BrokerExecutionAdapter.class);
          assertThat(context).doesNotHaveBean(FixExecutionAdapter.class);
        });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Import({
      SimulatedExecutionAdapter.class,
      BrokerExecutionAdapter.class,
      FixExecutionAdapter.class,
      LpExecutionAdapter.class
  })
  static class AdapterTestConfiguration {

    @Bean
    QuoteService quoteService() {
      return mock(QuoteService.class);
    }
  }
}
