package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.ProviderRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminDataProviderResponse(
    UUID id,
    String code,
    String name,
    String providerType,
    List<String> assetClasses,
    String restBaseUrl,
    String wsUrl,
    Boolean enabled,
    Integer priority,
    Integer timeoutMs,
    Integer rateLimitPerMinute,
    String healthStatus,
    Instant lastHealthCheckAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Long failureCount,
    Long avgLatencyMs,
    Instant lastQuoteSuccessAt,
    Long quoteStalenessMs,
    Instant lastInstrumentSyncAt,
    Integer lastInstrumentSyncCount,
    String configJson,
    List<String> capabilities,
    List<CapabilityStatus> capabilityStatuses,
    Instant createdAt,
    Instant updatedAt
) {
  public static AdminDataProviderResponse from(DataProviderEntity entity, List<String> capabilities) {
    return new AdminDataProviderResponse(
        entity.getId(),
        entity.getCode(),
        entity.getName(),
        entity.getProviderType(),
        entity.getAssetClasses(),
        entity.getRestBaseUrl(),
        entity.getWsUrl(),
        entity.getEnabled(),
        entity.getPriority(),
        entity.getTimeoutMs(),
        entity.getRateLimitPerMinute(),
        entity.getHealthStatus() == null ? null : entity.getHealthStatus().code(),
        entity.getLastHealthCheckAt(),
        entity.getLastSuccessAt(),
        entity.getLastFailureAt(),
        entity.getFailureCount(),
        entity.getAvgLatencyMs(),
        entity.getLastQuoteSuccessAt(),
        entity.getQuoteStalenessMs(),
        entity.getLastInstrumentSyncAt(),
        entity.getLastInstrumentSyncCount(),
        entity.getConfigJson(),
        capabilities,
        capabilities.stream()
            .map(capability -> new CapabilityStatus(
                capability,
                true,
                null,
                entity.getHealthStatus() == null ? "UNKNOWN" : entity.getHealthStatus().code()))
            .toList(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  public static AdminDataProviderResponse from(
      DataProviderEntity entity,
      List<String> capabilities,
      ProviderRegistry providerRegistry
  ) {
    return new AdminDataProviderResponse(
        entity.getId(),
        entity.getCode(),
        entity.getName(),
        entity.getProviderType(),
        entity.getAssetClasses(),
        entity.getRestBaseUrl(),
        entity.getWsUrl(),
        entity.getEnabled(),
        entity.getPriority(),
        entity.getTimeoutMs(),
        entity.getRateLimitPerMinute(),
        entity.getHealthStatus() == null ? null : entity.getHealthStatus().code(),
        entity.getLastHealthCheckAt(),
        entity.getLastSuccessAt(),
        entity.getLastFailureAt(),
        entity.getFailureCount(),
        entity.getAvgLatencyMs(),
        entity.getLastQuoteSuccessAt(),
        entity.getQuoteStalenessMs(),
        entity.getLastInstrumentSyncAt(),
        entity.getLastInstrumentSyncCount(),
        entity.getConfigJson(),
        capabilities,
        capabilities.stream()
            .map(capability -> capabilityStatus(entity, capability, providerRegistry))
            .toList(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static CapabilityStatus capabilityStatus(
      DataProviderEntity entity,
      String capability,
      ProviderRegistry providerRegistry
  ) {
    boolean supported = providerRegistry.find(entity.getCode())
        .map(adapter -> {
          try {
            return adapter.supports(MarketDataCapability.valueOf(capability));
          } catch (IllegalArgumentException ex) {
            return false;
          }
        })
        .orElse(false);
    return new CapabilityStatus(capability, true, supported, supported ? "UP" : "DOWN");
  }

  public record CapabilityStatus(String capability, Boolean enabled, Boolean supported, String status) {
  }
}
