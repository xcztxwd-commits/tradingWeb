package com.fxplatform.auth.service;

import com.fxplatform.auth.entity.UserSessionEntity;
import com.fxplatform.auth.enums.UserSessionStatus;
import com.fxplatform.auth.repository.UserSessionRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.JwtTokenClaims;
import com.fxplatform.common.security.TokenHashService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthSessionService {

  private static final String INVALID_REFRESH_TOKEN_CODE = "INVALID_REFRESH_TOKEN";
  private static final String INVALID_REFRESH_TOKEN_MESSAGE = "Refresh token is invalid";

  private final UserSessionRepository userSessionRepository;
  private final TokenHashService tokenHashService;

  @Transactional
  public void createSession(
      UUID sessionId,
      UUID userId,
      String refreshToken,
      JwtTokenClaims accessClaims,
      JwtTokenClaims refreshClaims,
      AuthSessionContext context
  ) {
    UserSessionEntity session = new UserSessionEntity();
    session.setId(sessionId);
    session.setUserId(userId);
    session.setRefreshTokenHash(tokenHashService.hash(refreshToken));
    session.setAccessTokenJti(accessClaims.jwtId());
    session.setDeviceId(context.deviceId());
    session.setIpAddress(context.ipAddress());
    session.setUserAgent(context.userAgent());
    session.setStatus(UserSessionStatus.ACTIVE);
    session.setExpiresAt(refreshClaims.expiresAt());
    userSessionRepository.save(session);
  }

  @Transactional
  public void rotateRefreshToken(
      UUID sessionId,
      UUID userId,
      String oldRefreshToken,
      String newRefreshToken,
      JwtTokenClaims newAccessClaims,
      JwtTokenClaims newRefreshClaims
  ) {
    UserSessionEntity session = userSessionRepository
        .findActiveByRefreshTokenHash(sessionId, userId, tokenHashService.hash(oldRefreshToken), Instant.now())
        .orElseThrow(() -> new BusinessException(INVALID_REFRESH_TOKEN_CODE, INVALID_REFRESH_TOKEN_MESSAGE));
    session.setRefreshTokenHash(tokenHashService.hash(newRefreshToken));
    session.setAccessTokenJti(newAccessClaims.jwtId());
    session.setExpiresAt(newRefreshClaims.expiresAt());
    userSessionRepository.save(session);
  }

  public boolean isAccessSessionActive(JwtTokenClaims accessClaims) {
    return accessClaims.sessionId() != null
        && userSessionRepository.findActiveById(accessClaims.sessionId(), Instant.now()).isPresent();
  }

  @Transactional
  public void revokeSession(JwtTokenClaims claims, String reason) {
    if (claims == null || claims.sessionId() == null) {
      return;
    }
    userSessionRepository.findById(claims.sessionId())
        .ifPresent(session -> revoke(session, reason));
  }

  @Transactional
  public void revokeAllUserSessions(UUID userId, String reason) {
    userSessionRepository.findActiveByUserId(userId)
        .forEach(session -> revoke(session, reason));
  }

  private void revoke(UserSessionEntity session, String reason) {
    if (session.getStatus() == UserSessionStatus.REVOKED) {
      return;
    }
    session.setStatus(UserSessionStatus.REVOKED);
    session.setRevokedAt(Instant.now());
    session.setRevokeReason(reason);
    userSessionRepository.save(session);
  }
}
