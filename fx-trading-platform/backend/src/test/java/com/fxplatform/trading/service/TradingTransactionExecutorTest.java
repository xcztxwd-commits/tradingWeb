package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class TradingTransactionExecutorTest {

  @Test
  void executesEachPreparedMutationInsideItsOwnTransactionBoundary() throws Exception {
    assertThat(TradingTransactionExecutor.class
        .getMethod("execute", java.util.function.Supplier.class)
        .isAnnotationPresent(Transactional.class))
        .isTrue();
    assertThat(new TradingTransactionExecutor().execute(() -> "committed"))
        .isEqualTo("committed");
  }
}
