package com.fxplatform.common.security;

import com.fxplatform.auth.entity.RevokedTokenEntity;
import com.fxplatform.auth.repository.RevokedTokenRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenRevocationService {

  private final RevokedTokenRepository revokedTokenRepository;
  private final JwtService jwtService;
  private final TokenHashService tokenHashService;

  public boolean isRevoked(String token) {
    if (!hasText(token)) {
      return false;
    }
    try {
      JwtTokenClaims claims = jwtService.parseAccessToken(token);
      return isAccessTokenRevoked(claims);
    } catch (RuntimeException ignored) {
      try {
        JwtTokenClaims claims = jwtService.parseRefreshToken(token);
        return isTokenIdRevoked("REFRESH", claims.jwtId());
      } catch (RuntimeException ex) {
        return false;
      }
    }
  }

  public boolean isAccessTokenRevoked(JwtTokenClaims claims) {
    return claims != null && isTokenIdRevoked("ACCESS", claims.jwtId());
  }

  public void revokeAccessToken(String token) {
    if (!hasText(token)) {
      return;
    }
    try {
      JwtTokenClaims claims = jwtService.parseAccessToken(token);
      revokeTokenId("ACCESS", claims.jwtId(), claims.expiresAt());
    } catch (RuntimeException ignored) {
      // Logout should be best-effort even if the submitted bearer token is malformed.
    }
  }

  public void revokeRefreshToken(String token) {
    if (!hasText(token)) {
      return;
    }
    try {
      JwtTokenClaims claims = jwtService.parseRefreshToken(token);
      revokeTokenId("REFRESH", claims.jwtId(), claims.expiresAt());
    } catch (RuntimeException ignored) {
      // Refresh session state remains the source of truth for malformed tokens.
    }
  }

  private boolean isTokenIdRevoked(String tokenType, String jwtId) {
    return hasText(jwtId)
        && revokedTokenRepository.findByTokenHash(tokenHashService.hashTokenId(tokenType, jwtId)).isPresent();
  }

  private void revokeTokenId(String tokenType, String jwtId, Instant expiresAt) {
    if (!hasText(jwtId) || isTokenIdRevoked(tokenType, jwtId)) {
      return;
    }
    RevokedTokenEntity revokedToken = new RevokedTokenEntity();
    revokedToken.setTokenHash(tokenHashService.hashTokenId(tokenType, jwtId));
    revokedToken.setTokenType(tokenType);
    revokedToken.setExpiresAt(expiresAt);
    revokedToken.setRevokedAt(Instant.now());
    revokedTokenRepository.save(revokedToken);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
