package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.ProviderInstrumentEntity;
import java.time.Instant;
import java.util.UUID;

public record AdminProviderInstrumentResponse(
    UUID id,
    UUID providerId,
    String providerSymbol,
    String assetClass,
    String baseAsset,
    String quoteAsset,
    String displayName,
    Boolean listed,
    String rawJson,
    Instant lastSyncedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public static AdminProviderInstrumentResponse from(ProviderInstrumentEntity entity) {
    return new AdminProviderInstrumentResponse(
        entity.getId(),
        entity.getProviderId(),
        entity.getProviderSymbol(),
        entity.getAssetClass(),
        entity.getBaseAsset(),
        entity.getQuoteAsset(),
        entity.getDisplayName(),
        entity.getListed(),
        entity.getRawJson(),
        entity.getLastSyncedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
