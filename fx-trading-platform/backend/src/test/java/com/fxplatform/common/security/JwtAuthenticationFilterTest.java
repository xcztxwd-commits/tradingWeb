package com.fxplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.service.AdminAuthorityService;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.auth.service.AuthSessionService;
import jakarta.servlet.DispatcherType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthenticationFilterTest {

  @Test
  void actuatorHealthNeverTouchesJwtBackendsEvenWhenBearerInputIsPresent() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
        jwtService,
        userRepository,
        tokenRevocationService,
        authSessionService,
        adminAuthorityService);
    for (String method : java.util.List.of("GET", "HEAD")) {
      MockHttpServletRequest request = new MockHttpServletRequest(method, "/actuator/health");
      request.addHeader("Authorization", "Bearer must-not-be-parsed");
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(request, new MockHttpServletResponse(), chain);

      assertThat(chain.getRequest()).isSameAs(request);
    }
    verifyNoInteractions(
        jwtService,
        userRepository,
        tokenRevocationService,
        authSessionService,
        adminAuthorityService);
  }

  @Test
  void marksInvalidBearerTokenAndStillAllowsPermitAllSessionEndpointToContinue() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, tokenRevocationService, authSessionService, adminAuthorityService);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    request.addHeader("Authorization", "Bearer bad-token");
    when(jwtService.parseAccessToken("bad-token")).thenThrow(new IllegalArgumentException("bad token"));

    filter.doFilterInternal(request, response, chain);

    assertThat(request.getAttribute("com.fxplatform.common.security.INVALID_BEARER_TOKEN")).isEqualTo(true);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void marksRevokedBearerTokenJtiAsInvalid() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, tokenRevocationService, authSessionService, adminAuthorityService);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    request.addHeader("Authorization", "Bearer revoked-token");
    JwtTokenClaims claims = claims(UUID.randomUUID(), UUID.randomUUID(), "revoked-jti");
    when(jwtService.parseAccessToken("revoked-token")).thenReturn(claims);
    when(tokenRevocationService.isAccessTokenRevoked(claims)).thenReturn(true);

    filter.doFilterInternal(request, response, chain);

    assertThat(request.getAttribute("com.fxplatform.common.security.INVALID_BEARER_TOKEN")).isEqualTo(true);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void rejectsBearerTokenWhenSessionIsNoLongerActive() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, tokenRevocationService, authSessionService, adminAuthorityService);
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000111");
    JwtTokenClaims claims = claims(userId, UUID.randomUUID(), "access-jti");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    request.addHeader("Authorization", "Bearer inactive-session-token");
    when(jwtService.parseAccessToken("inactive-session-token")).thenReturn(claims);
    when(tokenRevocationService.isAccessTokenRevoked(claims)).thenReturn(false);
    when(authSessionService.isAccessSessionActive(claims)).thenReturn(false);

    filter.doFilterInternal(request, response, chain);

    assertThat(request.getAttribute("com.fxplatform.common.security.INVALID_BEARER_TOKEN")).isEqualTo(true);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void rejectsBearerTokenWhenUserIsNotActive() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, tokenRevocationService, authSessionService, adminAuthorityService);
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000222");
    JwtTokenClaims claims = claims(userId, UUID.randomUUID(), "access-jti");
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("frozen@example.com");
    user.setRole(UserRole.USER);
    user.setStatus(UserStatus.FROZEN);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    request.addHeader("Authorization", "Bearer frozen-user-token");
    when(jwtService.parseAccessToken("frozen-user-token")).thenReturn(claims);
    when(tokenRevocationService.isAccessTokenRevoked(claims)).thenReturn(false);
    when(authSessionService.isAccessSessionActive(claims)).thenReturn(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    filter.doFilterInternal(request, response, chain);

    assertThat(request.getAttribute("com.fxplatform.common.security.INVALID_BEARER_TOKEN")).isEqualTo(true);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void authenticatesBearerTokenWithResolvedAdminAuthorities() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, tokenRevocationService, authSessionService, adminAuthorityService);
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000333");
    JwtTokenClaims claims = claims(userId, UUID.randomUUID(), "access-jti");
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("admin@example.com");
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/finance/fund-orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    request.addHeader("Authorization", "Bearer admin-token");
    when(jwtService.parseAccessToken("admin-token")).thenReturn(claims);
    when(tokenRevocationService.isAccessTokenRevoked(claims)).thenReturn(false);
    when(authSessionService.isAccessSessionActive(claims)).thenReturn(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(adminAuthorityService.authoritiesFor(user)).thenReturn(java.util.List.of(
        "ROLE_ADMIN",
        "finance:fund-order:read",
        "finance:fund-order:approve"));

    filter.doFilterInternal(request, response, chain);

    var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .contains("ROLE_ADMIN", "finance:fund-order:approve");
    assertThat(chain.getRequest()).isSameAs(request);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatesBearerTokenOnAsyncRedispatch() throws Exception {
    assertRedispatchAuthenticatesBearerToken(DispatcherType.ASYNC);
  }

  @Test
  void authenticatesBearerTokenOnErrorRedispatch() throws Exception {
    assertRedispatchAuthenticatesBearerToken(DispatcherType.ERROR);
  }

  private void assertRedispatchAuthenticatesBearerToken(
      DispatcherType dispatcherType
  ) throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    TokenRevocationService tokenRevocationService = Mockito.mock(TokenRevocationService.class);
    AuthSessionService authSessionService = Mockito.mock(AuthSessionService.class);
    AdminAuthorityService adminAuthorityService = Mockito.mock(AdminAuthorityService.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
        jwtService,
        userRepository,
        tokenRevocationService,
        authSessionService,
        adminAuthorityService);
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000444");
    JwtTokenClaims claims = claims(userId, UUID.randomUUID(), "redispatch-jti");
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("redispatch-admin@example.com");
    user.setRole(UserRole.ADMIN);
    user.setStatus(UserStatus.ACTIVE);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/admin/trading-lab/reports/report-id/download");
    request.setDispatcherType(dispatcherType);
    if (dispatcherType == DispatcherType.ERROR) {
      request.setAttribute(
          org.springframework.web.util.WebUtils.ERROR_REQUEST_URI_ATTRIBUTE,
          request.getRequestURI());
    }
    request.addHeader("Authorization", "Bearer redispatch-admin-token");
    MockFilterChain chain = new MockFilterChain();
    when(jwtService.parseAccessToken("redispatch-admin-token")).thenReturn(claims);
    when(tokenRevocationService.isAccessTokenRevoked(claims)).thenReturn(false);
    when(authSessionService.isAccessSessionActive(claims)).thenReturn(true);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(adminAuthorityService.authoritiesFor(user))
        .thenReturn(java.util.List.of("ROLE_ADMIN", "trading-lab:view"));

    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    try {
      filter.doFilter(request, new MockHttpServletResponse(), chain);

      var authentication = org.springframework.security.core.context.SecurityContextHolder
          .getContext()
          .getAuthentication();
      assertThat(authentication)
          .as(dispatcherType + " redispatch authentication")
          .isNotNull();
      assertThat(authentication.getAuthorities())
          .extracting("authority")
          .contains("ROLE_ADMIN", "trading-lab:view");
      assertThat(chain.getRequest()).isSameAs(request);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  private JwtTokenClaims claims(UUID userId, UUID sessionId, String jti) {
    return new JwtTokenClaims(userId, sessionId, jti, "access", Instant.parse("2026-06-17T00:15:00Z"));
  }
}
