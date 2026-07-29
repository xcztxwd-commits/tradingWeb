package com.fxplatform.validation.service;

import java.math.BigDecimal;

public interface ValidationRunPacer {

  void launch(Runnable command);

  void awaitNextTick(BigDecimal speedMultiplier);

  /** Identifies the currently published local runtime generation. */
  default long generation() {
    return Long.MIN_VALUE;
  }
}
