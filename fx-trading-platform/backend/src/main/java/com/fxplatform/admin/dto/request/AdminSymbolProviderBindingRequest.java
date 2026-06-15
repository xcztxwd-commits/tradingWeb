package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AdminSymbolProviderBindingRequest(
    @NotNull UUID providerId,
    UUID providerInstrumentId,
    String providerSymbol,
    Integer priority,
    Boolean enabled,
    String configJson
) {
}
