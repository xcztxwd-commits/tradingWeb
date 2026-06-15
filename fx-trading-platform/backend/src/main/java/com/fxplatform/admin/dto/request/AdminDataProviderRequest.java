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
    String configJson
) {
}
