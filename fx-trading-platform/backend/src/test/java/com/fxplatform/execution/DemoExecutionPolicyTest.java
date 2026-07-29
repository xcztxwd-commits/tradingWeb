package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DemoExecutionPolicyTest {

  @Test
  void propertyProviderExposesImmutableSimpleDefaults() {
    DemoExecutionPolicy policy =
        new PropertyDemoExecutionPolicyProvider(new ExecutionProperties()).current();

    assertThat(policy.matchingMode()).isEqualTo(DemoMatchingMode.SIMPLE);
    assertThat(policy.makerFeeRate()).isEqualByComparingTo("0.0002");
    assertThat(policy.takerFeeRate()).isEqualByComparingTo("0.0005");
    assertThat(policy.liquidationFeeRate()).isEqualByComparingTo("0.001");
    assertThat(policy.slippageRate()).isEqualByComparingTo("0.0001");
    assertThat(policy.bids()).isEmpty();
    assertThat(policy.asks()).isEmpty();
    assertThat(policy.maxFillQuantityPerTick()).isNull();
  }

  @Test
  void propertyProviderReadsOneValidatedSnapshotFromExecutionDemoProperties() {
    ExecutionProperties properties = new ExecutionProperties();
    properties.getDemo().setMatchingMode(DemoMatchingMode.DEPTH);
    properties.getDemo().setMakerFeeRate(new BigDecimal("0.001"));
    properties.getDemo().setTakerFeeRate(new BigDecimal("0.002"));
    properties.getDemo().setLiquidationFeeRate(new BigDecimal("0.003"));
    properties.getDemo().setSlippageRate(new BigDecimal("0.004"));
    properties.getDemo().setBids(List.of(new DemoBookLevel(
        new BigDecimal("99"), new BigDecimal("2"))));
    properties.getDemo().setAsks(List.of(new DemoBookLevel(
        new BigDecimal("101"), new BigDecimal("3"))));
    properties.getDemo().setMaxFillQuantityPerTick(new BigDecimal("0.5"));

    DemoExecutionPolicy policy =
        new PropertyDemoExecutionPolicyProvider(properties).current();

    assertThat(policy.matchingMode()).isEqualTo(DemoMatchingMode.DEPTH);
    assertThat(policy.makerFeeRate()).isEqualByComparingTo("0.001");
    assertThat(policy.takerFeeRate()).isEqualByComparingTo("0.002");
    assertThat(policy.liquidationFeeRate()).isEqualByComparingTo("0.003");
    assertThat(policy.slippageRate()).isEqualByComparingTo("0.004");
    assertThat(policy.bids()).containsExactly(
        new DemoBookLevel(new BigDecimal("99"), new BigDecimal("2")));
    assertThat(policy.asks()).containsExactly(
        new DemoBookLevel(new BigDecimal("101"), new BigDecimal("3")));
    assertThat(policy.maxFillQuantityPerTick()).isEqualByComparingTo("0.5");
  }

  @Test
  void policyDefensivelyCopiesConfiguredDepth() {
    List<DemoBookLevel> bids = new ArrayList<>();
    bids.add(new DemoBookLevel(new BigDecimal("99"), BigDecimal.ONE));
    DemoExecutionPolicy policy = policy(bids, List.of(), null);

    bids.clear();

    assertThat(policy.bids()).hasSize(1);
    assertThatThrownBy(() -> policy.bids().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.0001", "1", "1.1"})
  void rejectsRatesOutsideZeroInclusiveOneExclusive(String rate) {
    BigDecimal invalid = new BigDecimal(rate);

    assertThatThrownBy(() -> new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        invalid,
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate");
    assertThatThrownBy(() -> new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal("0.0002"),
        invalid,
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate");
    assertThatThrownBy(() -> new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        invalid,
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate");
    assertThatThrownBy(() -> new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        invalid,
        List.of(),
        List.of(),
        null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate");
  }

  @Test
  void rejectsNonPositiveBookLevelsAndFillLimit() {
    assertThatThrownBy(() -> policy(
        List.of(new DemoBookLevel(BigDecimal.ZERO, BigDecimal.ONE)),
        List.of(),
        null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy(
        List.of(),
        List.of(new DemoBookLevel(BigDecimal.ONE, BigDecimal.ZERO)),
        null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy(List.of(), List.of(), BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxFillQuantityPerTick");
  }

  @Test
  void explicitProviderOverridesThePropertyBackedDefault() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DemoExecutionPolicyAutoConfiguration.class))
        .withUserConfiguration(OverrideConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(DemoExecutionPolicyProvider.class);
          assertThat(context.getBean(DemoExecutionPolicyProvider.class))
              .isNotInstanceOf(PropertyDemoExecutionPolicyProvider.class);
          assertThat(context).doesNotHaveBean(PropertyDemoExecutionPolicyProvider.class);
        });
  }

  @Test
  void springBindsEveryExecutionDemoPropertyIntoTheDefaultProvider() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DemoExecutionPolicyAutoConfiguration.class))
        .withPropertyValues(
            "execution.demo.matching-mode=DEPTH",
            "execution.demo.maker-fee-rate=0.0011",
            "execution.demo.taker-fee-rate=0.0022",
            "execution.demo.liquidation-fee-rate=0.0033",
            "execution.demo.slippage-rate=0.0044",
            "execution.demo.max-fill-quantity-per-tick=2.5")
        .run(context -> {
          assertThat(context).hasNotFailed();
          DemoExecutionPolicy policy =
              context.getBean(DemoExecutionPolicyProvider.class).current();
          assertThat(policy.matchingMode()).isEqualTo(DemoMatchingMode.DEPTH);
          assertThat(policy.makerFeeRate()).isEqualByComparingTo("0.0011");
          assertThat(policy.takerFeeRate()).isEqualByComparingTo("0.0022");
          assertThat(policy.liquidationFeeRate()).isEqualByComparingTo("0.0033");
          assertThat(policy.slippageRate()).isEqualByComparingTo("0.0044");
          assertThat(policy.maxFillQuantityPerTick()).isEqualByComparingTo("2.5");
        });
  }

  @Test
  void invalidExecutionDemoRateFailsDuringContextStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DemoExecutionPolicyAutoConfiguration.class))
        .withPropertyValues("execution.demo.taker-fee-rate=1")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("takerFeeRate");
        });
  }

  @Test
  void propertyProviderFreezesItsPolicyAtConstruction() {
    ExecutionProperties properties = new ExecutionProperties();
    PropertyDemoExecutionPolicyProvider provider =
        new PropertyDemoExecutionPolicyProvider(properties);

    properties.getDemo().setTakerFeeRate(new BigDecimal("0.25"));

    assertThat(provider.current().takerFeeRate()).isEqualByComparingTo("0.0005");
  }

  @Test
  void autoConfigurationIsRegisteredForTheMainApplicationContext() throws Exception {
    String resource =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    try (var stream = DemoExecutionPolicyTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).isNotNull();
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .contains(DemoExecutionPolicyAutoConfiguration.class.getName());
    }
  }

  private static DemoExecutionPolicy policy(
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks,
      BigDecimal maxFillQuantityPerTick
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        bids,
        asks,
        maxFillQuantityPerTick);
  }

  @Configuration(proxyBeanMethods = false)
  static class OverrideConfiguration {

    @Bean
    DemoExecutionPolicyProvider explicitDemoExecutionPolicyProvider() {
      DemoExecutionPolicy override = policy(List.of(), List.of(), null);
      return () -> override;
    }
  }
}
