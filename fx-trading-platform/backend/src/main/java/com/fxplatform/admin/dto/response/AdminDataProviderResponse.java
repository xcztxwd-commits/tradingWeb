package com.fxplatform.admin.dto.response;

import com.fxplatform.market.entity.DataProviderEntity;
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
    String configJson,
    List<String> capabilities,
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
        entity.getHealthStatus(),
        entity.getLastHealthCheckAt(),
        entity.getConfigJson(),
        capabilities,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
