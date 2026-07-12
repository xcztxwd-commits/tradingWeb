package com.fxplatform.trading.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Account-scoped idempotent user batch action. */
public record BatchActionRequest(
    @NotNull UUID accountId,
    @NotNull UUID requestId
) {
}
