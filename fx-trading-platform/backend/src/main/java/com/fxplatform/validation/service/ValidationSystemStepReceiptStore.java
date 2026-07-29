package com.fxplatform.validation.service;

import com.fxplatform.validation.service.ValidationSystemStepService.Receipt;
import java.util.List;
import java.util.UUID;

public interface ValidationSystemStepReceiptStore {

  /**
   * Locks and validates the durable run before returning every receipt for one Tick.
   *
   * <p>The caller must keep the surrounding transaction open through all system work and receipt
   * persistence.
   */
  List<Receipt> lockTick(UUID runId, long generation, long tickSequence);

  Receipt save(Receipt receipt);
}
