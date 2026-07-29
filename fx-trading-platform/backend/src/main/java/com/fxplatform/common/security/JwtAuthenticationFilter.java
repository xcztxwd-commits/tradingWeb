package com.fxplatform.common.security;

import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.auth.service.AuthSessionService;
import com.fxplatform.admin.service.AdminAuthorityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JwtAuthenticationFilter 是通用基础设施模块的安全认证组件。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  public static final String INVALID_BEARER_TOKEN_ATTRIBUTE =
      "com.fxplatform.common.security.INVALID_BEARER_TOKEN";

  private final JwtService jwtService;
  private final UserRepository userRepository;
  private final TokenRevocationService tokenRevocationService;
  private final AuthSessionService authSessionService;
  private final AdminAuthorityService adminAuthorityService;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    return "/actuator/health".equals(request.getRequestURI())
        && ("GET".equals(method) || "HEAD".equals(method));
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  /**
//   * 处理 doFilterInternal 安全认证逻辑。
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      if (!authenticate(request, authorization.substring(7))) {
        request.setAttribute(INVALID_BEARER_TOKEN_ATTRIBUTE, true);
      }
    }
    filterChain.doFilter(request, response);
  }

  /**
   * 处理 authenticate 安全认证逻辑。
   */
  private boolean authenticate(HttpServletRequest request, String token) {
    if (SecurityContextHolder.getContext().getAuthentication() != null) {
      return true;
    }
    JwtTokenClaims claims;
    try {
      claims = jwtService.parseAccessToken(token);
    } catch (RuntimeException ex) {
      return false;
    }
    if (tokenRevocationService.isAccessTokenRevoked(claims) || !authSessionService.isAccessSessionActive(claims)) {
      return false;
    }
    return userRepository.findById(claims.userId()).map(user -> {
      if (user.getStatus() != UserStatus.ACTIVE) {
        return false;
      }
      UserPrincipal principal = UserPrincipal.from(user, adminAuthorityService.authoritiesFor(user));
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
      authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authentication);
      return true;
    }).orElse(false);
  }
}
