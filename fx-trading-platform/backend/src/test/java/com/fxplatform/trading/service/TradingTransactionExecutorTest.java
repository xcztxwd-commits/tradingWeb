package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

class TradingTransactionExecutorTest {

  @Test
  void executesEachPreparedMutationInsideItsOwnTransactionBoundary() throws Exception {
    Transactional transactional = TradingTransactionExecutor.class
        .getMethod("execute", java.util.function.Supplier.class)
        .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    assertThat(new TradingTransactionExecutor().execute(() -> "committed"))
        .isEqualTo("committed");
  }
}
