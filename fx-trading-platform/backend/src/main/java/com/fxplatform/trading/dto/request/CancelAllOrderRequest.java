package com.fxplatform.trading.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Account-scoped idempotent cancel-all request with a client-observed order scope. */
public record CancelAllOrderRequest(
    @NotNull UUID accountId,
    @NotNull UUID requestId,
    @NotNull @Valid List<@NotNull UUID> expectedOrderIds
) {
}
