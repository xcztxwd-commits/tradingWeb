package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

class LiquidationScanSchedulerTest {

  @Test
  void schedulerIsExplicitlyOptInAndDisabledWhenPropertyIsMissing() {
    ConditionalOnProperty condition = LiquidationScanScheduler.class
        .getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("trading.liquidation");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
  }

  @Test
  void schedulerDoesNotHoldOneTransactionAcrossAccountMarketCalls() throws Exception {
    assertThat(LiquidationScanScheduler.class.getMethod("scanAccounts")
        .isAnnotationPresent(Transactional.class)).isFalse();
  }
}
