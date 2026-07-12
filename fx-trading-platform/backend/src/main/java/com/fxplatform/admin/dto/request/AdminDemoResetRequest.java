package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request for an Admin-initiated Demo account reset. */
public record AdminDemoResetRequest(
    @NotBlank String reason,
    @NotNull UUID requestId,
    @NotBlank String confirmationText
) {
}
