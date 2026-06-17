package com.fxplatform.common.security;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.auth.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JwtService 是通用基础设施模块的安全认证组件。
 */
@Service
public class JwtService {

  private final String secret;
  private final long accessTokenExpireMinutes;
  private final long refreshTokenExpireDays;

  /**
   * 创建 JwtService 实例。
   */
  public JwtService(
      @Value("${security.jwt.secret}") String secret,
      @Value("${security.jwt.access-token-expire-minutes}") long accessTokenExpireMinutes,
      @Value("${security.jwt.refresh-token-expire-days}") long refreshTokenExpireDays
  ) {
    this.secret = secret;
    this.accessTokenExpireMinutes = accessTokenExpireMinutes;
    this.refreshTokenExpireDays = refreshTokenExpireDays;
  }

  /**
   * 处理 generateAccessToken 安全认证逻辑。
   */
  public String generateAccessToken(UserEntity user) {
    return generateAccessToken(user, UUID.randomUUID());
  }

  public String generateAccessToken(UserEntity user, UUID sessionId) {
    return generateToken(user, sessionId, "access", Duration.ofMinutes(accessTokenExpireMinutes));
  }

  public String generateRefreshToken(UserEntity user) {
    return generateRefreshToken(user, UUID.randomUUID());
  }

  public String generateRefreshToken(UserEntity user, UUID sessionId) {
    return generateToken(user, sessionId, "refresh", Duration.ofDays(refreshTokenExpireDays));
  }

  private String generateToken(UserEntity user, UUID sessionId, String tokenType, Duration ttl) {
    Instant now = DateUtil.date().toInstant();
    var builder = Jwts.builder()
        .id(UUID.randomUUID().toString())
        .subject(user.getId().toString())
        .claim("email", user.getEmail())
        .claim("role", user.getRole().name())
        .claim("type", tokenType)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(signingKey());
    if (sessionId != null) {
      builder.claim("sid", sessionId.toString());
    }
    return builder.compact();
  }

  /**
   * 处理 parseUserId 安全认证逻辑。
   */
  public UUID parseUserId(String token) {
    return parseAccessToken(token).userId();
  }

  public UUID parseRefreshUserId(String token) {
    return parseRefreshToken(token).userId();
  }

  public JwtTokenClaims parseAccessToken(String token) {
    return parseToken(token, "access");
  }

  public JwtTokenClaims parseRefreshToken(String token) {
    return parseToken(token, "refresh");
  }

  public Optional<Instant> expiresAt(String token) {
    try {
      Date expiration = claims(token).getExpiration();
      return expiration == null ? Optional.empty() : Optional.of(expiration.toInstant());
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private JwtTokenClaims parseToken(String token, String expectedType) {
    Claims claims = claims(token);
    if (!expectedType.equals(claims.get("type", String.class))) {
      throw new IllegalArgumentException("Unexpected token type");
    }
    String jwtId = claims.getId();
    String sessionId = claims.get("sid", String.class);
    if (!hasText(jwtId) || !hasText(sessionId)) {
      throw new IllegalArgumentException("Token is missing session claims");
    }
    Date expiration = claims.getExpiration();
    if (expiration == null) {
      throw new IllegalArgumentException("Token is missing expiration");
    }
    return new JwtTokenClaims(
        UUID.fromString(claims.getSubject()),
        UUID.fromString(sessionId),
        jwtId,
        expectedType,
        expiration.toInstant());
  }

  private Claims claims(String token) {
    return Jwts.parser()
        .verifyWith(signingKey())
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  /**
   * 处理 signingKey 安全认证逻辑。
   */
  private SecretKey signingKey() {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
