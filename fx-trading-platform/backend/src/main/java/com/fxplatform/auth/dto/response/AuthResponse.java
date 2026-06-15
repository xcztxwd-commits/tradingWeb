package com.fxplatform.auth.dto.response;

import java.util.UUID;

/**
 * AuthResponse 承载认证授权模块的数据结构。
 */
public record AuthResponse(
    UUID userId,
    String email,
    String role,
    String accessToken
) {
}
