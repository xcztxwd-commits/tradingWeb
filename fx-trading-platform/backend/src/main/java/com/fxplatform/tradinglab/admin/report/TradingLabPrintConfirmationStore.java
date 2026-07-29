package com.fxplatform.tradinglab.admin.report;

import java.time.Duration;
import java.util.UUID;

public interface TradingLabPrintConfirmationStore {

  void store(
      String tokenDigest,
      UUID actorId,
      UUID reportId,
      Duration ttl);

  boolean consume(
      String tokenDigest,
      UUID actorId,
      UUID reportId);
}
