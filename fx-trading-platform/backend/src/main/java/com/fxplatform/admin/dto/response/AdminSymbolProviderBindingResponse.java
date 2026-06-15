package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import java.time.Instant;
import java.util.UUID;

public record AdminSymbolProviderBindingResponse(
    UUID id,
    UUID symbolId,
    UUID providerId,
    UUID providerInstrumentId,
    String providerSymbol,
    Integer priority,
    Boolean enabled,
    String configJson,
    Instant createdAt,
    Instant updatedAt
) {
  public static AdminSymbolProviderBindingResponse from(SymbolProviderBindingEntity entity) {
    return new AdminSymbolProviderBindingResponse(
        entity.getId(),
        entity.getSymbolId(),
        entity.getProviderId(),
        entity.getProviderInstrumentId(),
        entity.getProviderSymbol(),
        entity.getPriority(),
        entity.getEnabled(),
        entity.getConfigJson(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
