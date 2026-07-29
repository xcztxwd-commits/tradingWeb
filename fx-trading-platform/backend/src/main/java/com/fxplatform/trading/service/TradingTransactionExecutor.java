package com.fxplatform.trading.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Runs one already-prepared trading mutation in an isolated local transaction. */
@Component
public class TradingTransactionExecutor {

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T execute(Supplier<T> mutation) {
    return mutation.get();
  }

  /**
   * Executes a validation-owned mutation inside the already-active system-step transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T executeJoined(Supplier<T> mutation) {
    return mutation.get();
  }
}
