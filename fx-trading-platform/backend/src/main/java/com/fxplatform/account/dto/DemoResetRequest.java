package com.fxplatform.account.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DemoResetRequest(@NotNull UUID requestId) {
}
