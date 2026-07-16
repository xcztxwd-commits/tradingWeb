package com.fxplatform.trading.dto.response;

import java.util.List;
import java.util.UUID;

/** Account-scoped batch result with deterministic item ordering. */
public record BatchActionResponse(
    UUID accountId,
    String requestId,
    List<Item> items
) {

  public BatchActionResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      UUID positionId,
      UUID orderId,
      String status,
      String errorCode,
      String message
  ) {
  }
}
