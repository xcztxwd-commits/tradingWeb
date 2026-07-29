package com.fxplatform.tradinglab.admin.report;

import java.util.Objects;
import java.util.UUID;

public record TradingLabAdminRequestContext(
    UUID actorId,
    String clientIp,
    UUID requestId
) {

  public TradingLabAdminRequestContext {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(clientIp, "clientIp");
    Objects.requireNonNull(requestId, "requestId");
  }
}
