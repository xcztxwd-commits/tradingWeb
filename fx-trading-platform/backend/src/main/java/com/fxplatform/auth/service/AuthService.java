package com.fxplatform.auth.service;

import com.fxplatform.account.service.AccountService;
import com.fxplatform.admin.service.AdminAuthorityService;
import com.fxplatform.auth.dto.request.LoginRequest;
import com.fxplatform.auth.dto.request.RegisterRequest;
import com.fxplatform.auth.dto.response.AuthResponse;
import com.fxplatform.auth.dto.response.MeResponse;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.JwtTokenClaims;
import com.fxplatform.common.security.TokenRevocationService;
import com.fxplatform.common.security.UserPrincipal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthService 是认证授权模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AccountService accountService;
  private final TokenRevocationService tokenRevocationService;
  private final AuthSessionService authSessionService;
  private final AdminAuthorityService adminAuthorityService;

  @Transactional
  public AuthResponse register(RegisterRequest request) {
    return register(request, AuthSessionContext.empty());
  }

  @Transactional
  public AuthResponse register(RegisterRequest request, AuthSessionContext context) {
    String email = request.email();
    String phone = request.phone();
    String password = relaxed(request.password());
    if (email != null && userRepository.existsByEmail(email)) {
      throw new BusinessException("EMAIL_EXISTS", "Email already exists");
    }
    if (phone != null && userRepository.existsByPhone(phone)) {
      throw new BusinessException("PHONE_EXISTS", "Phone already exists");
    }
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPhone(phone);
    user.setPasswordHash(passwordEncoder.encode(password));
    userRepository.save(user);

    // 注册即创建 DEMO 账户，并通过 LedgerService 写入初始模拟入金流水。
    accountService.createDemoAccount(user.getId());

    return newSessionTokenResponse(user, context);
  }

  public AuthResponse login(LoginRequest request) {
    return login(request, AuthSessionContext.empty());
  }

  public AuthResponse login(LoginRequest request, AuthSessionContext context) {
    String identifier = relaxed(request.email());
    String password = relaxed(request.password());
    UserEntity user = userRepository.findByEmailOrPhone(identifier)
        .orElseThrow(() -> new BusinessException("BAD_CREDENTIALS", "Invalid email or password"));
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new BusinessException("BAD_CREDENTIALS", "Invalid email or password");
    }
    requireActive(user);
    return newSessionTokenResponse(user, context);
  }

  public AuthResponse refresh(String refreshToken) {
    if (!hasText(refreshToken)) {
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "Refresh token is invalid");
    }
    JwtTokenClaims oldRefreshClaims;
    try {
      oldRefreshClaims = jwtService.parseRefreshToken(refreshToken);
    } catch (RuntimeException ex) {
      throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "Refresh token is invalid");
    }
    UserEntity user = userRepository.findById(oldRefreshClaims.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "Refresh token is invalid"));
    if (user.getStatus() != UserStatus.ACTIVE) {
      authSessionService.revokeAllUserSessions(user.getId(), ErrorCode.USER_DISABLED);
      throw new BusinessException(ErrorCode.USER_DISABLED, "User is not active");
    }
    return rotatedSessionTokenResponse(user, oldRefreshClaims, refreshToken);
  }

  public void logout(String accessToken, String refreshToken) {
    if (hasText(accessToken)) {
      tokenRevocationService.revokeAccessToken(accessToken);
      parseAccessClaims(accessToken).ifPresent(claims ->
          authSessionService.revokeSession(claims, "LOGOUT"));
    }
    if (hasText(refreshToken)) {
      tokenRevocationService.revokeRefreshToken(refreshToken);
      parseRefreshClaims(refreshToken).ifPresent(claims ->
          authSessionService.revokeSession(claims, "LOGOUT"));
    }
  }

  public MeResponse me(UserPrincipal principal) {
    UserEntity user = userRepository.findById(principal.id())
        .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
    return new MeResponse(user.getId(), user.getEmail(), user.getRole().name(), user.getStatus().name());
  }

  private AuthResponse newSessionTokenResponse(UserEntity user, AuthSessionContext context) {
    UUID sessionId = UUID.randomUUID();
    String accessToken = jwtService.generateAccessToken(user, sessionId);
    String refreshToken = jwtService.generateRefreshToken(user, sessionId);
    JwtTokenClaims accessClaims = jwtService.parseAccessToken(accessToken);
    JwtTokenClaims refreshClaims = jwtService.parseRefreshToken(refreshToken);
    authSessionService.createSession(
        sessionId,
        user.getId(),
        refreshToken,
        accessClaims,
        refreshClaims,
        context == null ? AuthSessionContext.empty() : context);
    return authResponse(user, accessToken, refreshToken);
  }

  private AuthResponse rotatedSessionTokenResponse(UserEntity user, JwtTokenClaims oldRefreshClaims, String oldRefreshToken) {
    String accessToken = jwtService.generateAccessToken(user, oldRefreshClaims.sessionId());
    String refreshToken = jwtService.generateRefreshToken(user, oldRefreshClaims.sessionId());
    JwtTokenClaims accessClaims = jwtService.parseAccessToken(accessToken);
    JwtTokenClaims refreshClaims = jwtService.parseRefreshToken(refreshToken);
    authSessionService.rotateRefreshToken(
        oldRefreshClaims.sessionId(),
        user.getId(),
        oldRefreshToken,
        refreshToken,
        accessClaims,
        refreshClaims);
    return authResponse(user, accessToken, refreshToken);
  }

  private AuthResponse authResponse(UserEntity user, String accessToken, String refreshToken) {
    return new AuthResponse(
        user.getId(),
        displayIdentifier(user),
        user.getRole().name(),
        accessToken,
        refreshToken,
        adminAuthorityService.authoritiesFor(user));
  }

  private String displayIdentifier(UserEntity user) {
    return user.getEmail() != null ? user.getEmail() : user.getPhone();
  }

  private String relaxed(String value) {
    return value == null ? "" : value;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private void requireActive(UserEntity user) {
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.USER_DISABLED, "User is not active");
    }
  }

  private Optional<JwtTokenClaims> parseAccessClaims(String token) {
    try {
      return Optional.of(jwtService.parseAccessToken(token));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private Optional<JwtTokenClaims> parseRefreshClaims(String token) {
    try {
      return Optional.of(jwtService.parseRefreshToken(token));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }
}
