package com.fxplatform.trading.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Runs one already-prepared trading mutation in an isolated local transaction. */
@Component
public class TradingTransactionExecutor {

  @Transactional
  public <T> T execute(Supplier<T> mutation) {
    return mutation.get();
  }
}
