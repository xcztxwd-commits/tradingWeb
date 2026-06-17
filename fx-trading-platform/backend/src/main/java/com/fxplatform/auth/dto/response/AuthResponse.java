package com.fxplatform.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * AuthResponse 承载认证授权模块的数据结构。
 */
public record AuthResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    UUID userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    String email,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    String role,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    String accessToken,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    String refreshToken,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    List<String> authorities
) {
  public AuthResponse(UUID userId, String email, String role, String accessToken, String refreshToken) {
    this(userId, email, role, accessToken, refreshToken, List.of());
  }

  public AuthResponse {
    authorities = authorities == null ? List.of() : List.copyOf(authorities);
  }
}
