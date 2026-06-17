package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AdminDataProviderRequest(
    @NotBlank String code,
    @NotBlank String name,
    @NotBlank String providerType,
    List<String> assetClasses,
    String restBaseUrl,
    String wsUrl,
    Boolean enabled,
    Integer priority,
    Integer timeoutMs,
    Integer rateLimitPerMinute,
    String configJson,
    String confirmationText
) {
  public AdminDataProviderRequest(
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
      String configJson
  ) {
    this(code, name, providerType, assetClasses, restBaseUrl, wsUrl, enabled, priority, timeoutMs, rateLimitPerMinute, configJson, null);
  }
}
