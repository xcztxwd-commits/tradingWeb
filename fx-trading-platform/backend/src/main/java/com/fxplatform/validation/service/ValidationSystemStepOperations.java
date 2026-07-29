package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import com.fxplatform.validation.service.ValidationSystemStepService.SubStepResult;

public interface ValidationSystemStepOperations {

  SubStepResult publishTick(Request request);

  /**
   * Restores only process-local market state for an already completed durable system step.
   *
   * <p>The default deliberately does nothing so non-production adapters cannot accidentally
   * repeat the business sub-steps represented by the stored receipt.
   */
  default void rehydratePublishedTick(Request request) {
  }

  SubStepResult updateTrailingExtrema(Request request);

  SubStepResult matchRestingOrders(Request request);

  SubStepResult triggerProtectionOrders(Request request);

  SubStepResult settlePersistedFunding(Request request);

  SubStepResult scanLiquidations(Request request);

  SubStepResult captureCheckpoint(Request request);
}
