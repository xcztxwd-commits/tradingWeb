package com.fxplatform.common.security;
import cn.hutool.core.date.DateUtil;

import com.fxplatform.auth.entity.UserEntity;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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

  /**
   * 创建 JwtService 实例。
   */
  public JwtService(
      @Value("${security.jwt.secret}") String secret,
      @Value("${security.jwt.access-token-expire-minutes}") long accessTokenExpireMinutes
  ) {
    this.secret = secret;
    this.accessTokenExpireMinutes = accessTokenExpireMinutes;
  }

  /**
   * 处理 generateAccessToken 安全认证逻辑。
   */
  public String generateAccessToken(UserEntity user) {
    Instant now = DateUtil.date().toInstant();
    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("email", user.getEmail())
        .claim("role", user.getRole().name())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(Duration.ofMinutes(accessTokenExpireMinutes))))
        .signWith(signingKey())
        .compact();
  }

  /**
   * 处理 parseUserId 安全认证逻辑。
   */
  public UUID parseUserId(String token) {
    String subject = Jwts.parser()
        .verifyWith(signingKey())
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .getSubject();
    return UUID.fromString(subject);
  }

  /**
   * 处理 signingKey 安全认证逻辑。
   */
  private SecretKey signingKey() {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }
}
