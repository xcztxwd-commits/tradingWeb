package com.fxplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.service.AccountService;
import com.fxplatform.admin.service.AdminAuthorityService;
import com.fxplatform.auth.dto.request.LoginRequest;
import com.fxplatform.auth.dto.request.RegisterRequest;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.JwtTokenClaims;
import com.fxplatform.common.security.TokenRevocationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private JwtService jwtService;

  @Mock
  private AccountService accountService;

  @Mock
  private TokenRevocationService tokenRevocationService;

  @Mock
  private AuthSessionService authSessionService;

  @Mock
  private AdminAuthorityService adminAuthorityService;

  @Test
  void registersPhoneOnlyInputAndPersistsItForLogin() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000321");
    when(userRepository.existsByPhone("+60 raw phone")).thenReturn(false);
    when(passwordEncoder.encode("Password123!")).thenReturn("encoded-password");
    when(jwtService.generateAccessToken(any(UserEntity.class), any(UUID.class))).thenReturn("registered-token");
    when(jwtService.generateRefreshToken(any(UserEntity.class), any(UUID.class))).thenReturn("registered-refresh-token");
    when(jwtService.parseAccessToken("registered-token")).thenReturn(claims(userId, "access-jti", "access"));
    when(jwtService.parseRefreshToken("registered-refresh-token")).thenReturn(claims(userId, "refresh-jti", "refresh"));
    doAnswer(invocation -> {
      UserEntity user = invocation.getArgument(0);
      user.setId(userId);
      return user;
    }).when(userRepository).save(any(UserEntity.class));

    AuthService service = authService();

    var response = service.register(new RegisterRequest(null, "+60 raw phone", "Password123!"));

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository).save(captor.capture());
    UserEntity saved = captor.getValue();

    assertThat(saved.getEmail()).isNull();
    assertThat(saved.getPhone()).isEqualTo("+60 raw phone");
    assertThat(saved.getPasswordHash()).isEqualTo("encoded-password");
    verify(accountService).getOrCreateDemoAccount(userId);
    verify(authSessionService).createSession(any(UUID.class), eq(userId), eq("registered-refresh-token"), any(), any(), any());
    assertThat(response.email()).isEqualTo("+60 raw phone");
    assertThat(response.accessToken()).isEqualTo("registered-token");
    assertThat(response.refreshToken()).isEqualTo("registered-refresh-token");
  }

  @Test
  void logsInWithPhoneIdentifierCreatedByRegistration() {
    UserEntity user = new UserEntity();
    user.setId(UUID.fromString("00000000-0000-0000-0000-000000000456"));
    user.setPhone("+60 raw phone");
    user.setPasswordHash("encoded-password");
    user.setStatus(UserStatus.ACTIVE);
    when(userRepository.findByEmailOrPhone("+60 raw phone")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Password123!", "encoded-password")).thenReturn(true);
    when(jwtService.generateAccessToken(eq(user), any(UUID.class))).thenReturn("login-token");
    when(jwtService.generateRefreshToken(eq(user), any(UUID.class))).thenReturn("login-refresh-token");
    when(jwtService.parseAccessToken("login-token")).thenReturn(claims(user.getId(), "access-jti", "access"));
    when(jwtService.parseRefreshToken("login-refresh-token")).thenReturn(claims(user.getId(), "refresh-jti", "refresh"));

    AuthService service = authService();

    var response = service.login(new LoginRequest("+60 raw phone", "Password123!"));

    verify(authSessionService).createSession(any(UUID.class), eq(user.getId()), eq("login-refresh-token"), any(), any(), any());
    assertThat(response.email()).isEqualTo("+60 raw phone");
    assertThat(response.accessToken()).isEqualTo("login-token");
    assertThat(response.refreshToken()).isEqualTo("login-refresh-token");
  }

  @Test
  void loginIncludesResolvedAdminAuthorities() {
    UserEntity user = new UserEntity();
    user.setId(UUID.fromString("00000000-0000-0000-0000-000000000458"));
    user.setEmail("admin@example.com");
    user.setPasswordHash("encoded-password");
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    when(userRepository.findByEmailOrPhone("admin@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Password123!", "encoded-password")).thenReturn(true);
    when(jwtService.generateAccessToken(eq(user), any(UUID.class))).thenReturn("admin-token");
    when(jwtService.generateRefreshToken(eq(user), any(UUID.class))).thenReturn("admin-refresh-token");
    when(jwtService.parseAccessToken("admin-token")).thenReturn(claims(user.getId(), "access-jti", "access"));
    when(jwtService.parseRefreshToken("admin-refresh-token")).thenReturn(claims(user.getId(), "refresh-jti", "refresh"));
    when(adminAuthorityService.authoritiesFor(user)).thenReturn(List.of("ROLE_ADMIN", "finance:fund-order:approve"));

    var response = authService().login(new LoginRequest("admin@example.com", "Password123!"));

    assertThat(response.authorities()).containsExactly("ROLE_ADMIN", "finance:fund-order:approve");
  }

  @Test
  void loginRejectsNonActiveUsersBeforeIssuingTokens() {
    UserEntity user = new UserEntity();
    user.setId(UUID.fromString("00000000-0000-0000-0000-000000000457"));
    user.setEmail("frozen@example.com");
    user.setPasswordHash("encoded-password");
    user.setStatus(UserStatus.FROZEN);
    when(userRepository.findByEmailOrPhone("frozen@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Password123!", "encoded-password")).thenReturn(true);

    AuthService service = authService();

    assertThatThrownBy(() -> service.login(new LoginRequest("frozen@example.com", "Password123!")))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("USER_DISABLED");
          assertThat(ex.getMessage()).contains("User is not active");
        });
  }

  @Test
  void refreshRotatesRefreshTokenAndRevokesOldRefreshToken() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000789");
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000790");
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("trader@example.com");
    user.setStatus(UserStatus.ACTIVE);
    JwtTokenClaims oldRefreshClaims = claims(userId, sessionId, "old-refresh-jti", "refresh");
    JwtTokenClaims newAccessClaims = claims(userId, sessionId, "new-access-jti", "access");
    JwtTokenClaims newRefreshClaims = claims(userId, sessionId, "new-refresh-jti", "refresh");
    when(jwtService.parseRefreshToken("old-refresh-token")).thenReturn(oldRefreshClaims);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(jwtService.generateAccessToken(user, sessionId)).thenReturn("new-access-token");
    when(jwtService.generateRefreshToken(user, sessionId)).thenReturn("new-refresh-token");
    when(jwtService.parseAccessToken("new-access-token")).thenReturn(newAccessClaims);
    when(jwtService.parseRefreshToken("new-refresh-token")).thenReturn(newRefreshClaims);

    AuthService service = authService();

    var response = service.refresh("old-refresh-token");

    verify(authSessionService).rotateRefreshToken(sessionId, userId, "old-refresh-token", "new-refresh-token", newAccessClaims, newRefreshClaims);
    assertThat(response.userId()).isEqualTo(userId);
    assertThat(response.accessToken()).isEqualTo("new-access-token");
    assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
  }

  @Test
  void refreshRejectsNonActiveUsersAndRevokesTheirSessions() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000791");
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000792");
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("disabled@example.com");
    user.setStatus(UserStatus.DISABLED);
    when(jwtService.parseRefreshToken("refresh-token")).thenReturn(claims(userId, sessionId, "refresh-jti", "refresh"));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    AuthService service = authService();

    assertThatThrownBy(() -> service.refresh("refresh-token"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("USER_DISABLED");
          assertThat(ex.getMessage()).contains("User is not active");
        });
    verify(authSessionService).revokeAllUserSessions(userId, "USER_DISABLED");
  }

  @Test
  void logoutRevokesAccessAndRefreshTokens() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000793");
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000794");
    JwtTokenClaims accessClaims = claims(userId, sessionId, "access-jti", "access");
    JwtTokenClaims refreshClaims = claims(userId, sessionId, "refresh-jti", "refresh");
    when(jwtService.parseAccessToken("access-token")).thenReturn(accessClaims);
    when(jwtService.parseRefreshToken("refresh-token")).thenReturn(refreshClaims);
    AuthService service = new AuthService(
        userRepository, passwordEncoder, jwtService, accountService, tokenRevocationService, authSessionService,
        adminAuthorityService);

    service.logout("access-token", "refresh-token");

    verify(tokenRevocationService).revokeAccessToken("access-token");
    verify(authSessionService).revokeSession(accessClaims, "LOGOUT");
    verify(authSessionService).revokeSession(refreshClaims, "LOGOUT");
  }

  private AuthService authService() {
    return new AuthService(
        userRepository, passwordEncoder, jwtService, accountService, tokenRevocationService, authSessionService,
        adminAuthorityService);
  }

  private JwtTokenClaims claims(UUID userId, String jti, String tokenType) {
    return claims(userId, UUID.fromString("00000000-0000-0000-0000-000000000999"), jti, tokenType);
  }

  private JwtTokenClaims claims(UUID userId, UUID sessionId, String jti, String tokenType) {
    return new JwtTokenClaims(userId, sessionId, jti, tokenType, Instant.parse("2026-06-24T00:00:00Z"));
  }
}
