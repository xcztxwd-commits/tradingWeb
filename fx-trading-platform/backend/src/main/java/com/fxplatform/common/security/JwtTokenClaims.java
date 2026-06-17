package com.fxplatform.common.security;

import java.time.Instant;
import java.util.UUID;

public record JwtTokenClaims(
    UUID userId,
    UUID sessionId,
    String jwtId,
    String tokenType,
    Instant expiresAt
) {
}
