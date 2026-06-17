package com.fxplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserSessionEntity;
import com.fxplatform.auth.enums.UserSessionStatus;
import com.fxplatform.auth.repository.UserSessionRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.JwtTokenClaims;
import com.fxplatform.common.security.TokenHashService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {

  @Mock
  private UserSessionRepository userSessionRepository;

  @Mock
  private TokenHashService tokenHashService;

  @Test
  void createSessionStoresHashedRefreshTokenAndAccessJti() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000111");
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000222");
    Instant refreshExpiresAt = Instant.parse("2026-06-24T00:00:00Z");
    JwtTokenClaims accessClaims = claims(userId, sessionId, "access-jti", "access", Instant.parse("2026-06-17T00:15:00Z"));
    JwtTokenClaims refreshClaims = claims(userId, sessionId, "refresh-jti", "refresh", refreshExpiresAt);
    AuthSessionContext context = new AuthSessionContext("device-1", "127.0.0.1", "JUnit");
    when(tokenHashService.hash("refresh-token")).thenReturn("refresh-hash");

    AuthSessionService service = new AuthSessionService(userSessionRepository, tokenHashService);

    service.createSession(sessionId, userId, "refresh-token", accessClaims, refreshClaims, context);

    ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
    verify(userSessionRepository).save(captor.capture());
    UserSessionEntity saved = captor.getValue();
    assertThat(saved.getId()).isEqualTo(sessionId);
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getRefreshTokenHash()).isEqualTo("refresh-hash");
    assertThat(saved.getAccessTokenJti()).isEqualTo("access-jti");
    assertThat(saved.getDeviceId()).isEqualTo("device-1");
    assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
    assertThat(saved.getUserAgent()).isEqualTo("JUnit");
    assertThat(saved.getStatus()).isEqualTo(UserSessionStatus.ACTIVE);
    assertThat(saved.getExpiresAt()).isEqualTo(refreshExpiresAt);
  }

  @Test
  void rotateRefreshTokenRequiresMatchingActiveSessionAndStoresNewTokenState() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000333");
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000444");
    UserSessionEntity session = new UserSessionEntity();
    session.setId(sessionId);
    session.setUserId(userId);
    session.setStatus(UserSessionStatus.ACTIVE);
    when(tokenHashService.hash("old-refresh")).thenReturn("old-hash");
    when(tokenHashService.hash("new-refresh")).thenReturn("new-hash");
    when(userSessionRepository.findActiveByRefreshTokenHash(eq(sessionId), eq(userId), eq("old-hash"), any(Instant.class)))
        .thenReturn(Optional.of(session));
    JwtTokenClaims accessClaims = claims(userId, sessionId, "new-access-jti", "access", Instant.parse("2026-06-17T00:15:00Z"));
    JwtTokenClaims refreshClaims = claims(userId, sessionId, "new-refresh-jti", "refresh", Instant.parse("2026-06-24T00:00:00Z"));

    AuthSessionService service = new AuthSessionService(userSessionRepository, tokenHashService);

    service.rotateRefreshToken(sessionId, userId, "old-refresh", "new-refresh", accessClaims, refreshClaims);

    assertThat(session.getRefreshTokenHash()).isEqualTo("new-hash");
    assertThat(session.getAccessTokenJti()).isEqualTo("new-access-jti");
    assertThat(session.getExpiresAt()).isEqualTo(refreshClaims.expiresAt());
    verify(userSessionRepository).save(session);
  }

  @Test
  void rotateRefreshTokenRejectsMissingOrRevokedSession() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000555");
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000666");
    when(tokenHashService.hash("old-refresh")).thenReturn("old-hash");
    when(userSessionRepository.findActiveByRefreshTokenHash(eq(sessionId), eq(userId), eq("old-hash"), any(Instant.class)))
        .thenReturn(Optional.empty());

    AuthSessionService service = new AuthSessionService(userSessionRepository, tokenHashService);

    assertThatThrownBy(() -> service.rotateRefreshToken(
        sessionId,
        userId,
        "old-refresh",
        "new-refresh",
        claims(userId, sessionId, "new-access-jti", "access", Instant.parse("2026-06-17T00:15:00Z")),
        claims(userId, sessionId, "new-refresh-jti", "refresh", Instant.parse("2026-06-24T00:00:00Z"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Refresh token is invalid");
  }

  @Test
  void revokeAllUserSessionsMarksEveryActiveSessionRevoked() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000777");
    UserSessionEntity first = activeSession(userId);
    UserSessionEntity second = activeSession(userId);
    when(userSessionRepository.findActiveByUserId(userId)).thenReturn(List.of(first, second));

    AuthSessionService service = new AuthSessionService(userSessionRepository, tokenHashService);

    service.revokeAllUserSessions(userId, "ADMIN_FORCE_LOGOUT");

    assertThat(first.getStatus()).isEqualTo(UserSessionStatus.REVOKED);
    assertThat(second.getStatus()).isEqualTo(UserSessionStatus.REVOKED);
    assertThat(first.getRevokeReason()).isEqualTo("ADMIN_FORCE_LOGOUT");
    assertThat(second.getRevokeReason()).isEqualTo("ADMIN_FORCE_LOGOUT");
    assertThat(first.getRevokedAt()).isNotNull();
    assertThat(second.getRevokedAt()).isNotNull();
    verify(userSessionRepository).save(first);
    verify(userSessionRepository).save(second);
  }

  private UserSessionEntity activeSession(UUID userId) {
    UserSessionEntity session = new UserSessionEntity();
    session.setId(UUID.randomUUID());
    session.setUserId(userId);
    session.setStatus(UserSessionStatus.ACTIVE);
    return session;
  }

  private JwtTokenClaims claims(UUID userId, UUID sessionId, String jti, String tokenType, Instant expiresAt) {
    return new JwtTokenClaims(userId, sessionId, jti, tokenType, expiresAt);
  }
}
