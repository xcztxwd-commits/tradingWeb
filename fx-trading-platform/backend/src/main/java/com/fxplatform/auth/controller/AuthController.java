package com.fxplatform.auth.controller;

import com.fxplatform.auth.dto.request.LoginRequest;
import com.fxplatform.auth.dto.request.LogoutRequest;
import com.fxplatform.auth.dto.request.RefreshTokenRequest;
import com.fxplatform.auth.dto.request.RegisterRequest;
import com.fxplatform.auth.dto.response.AuthResponse;
import com.fxplatform.auth.dto.response.MeResponse;
import com.fxplatform.auth.dto.response.SessionStatusResponse;
import com.fxplatform.auth.service.AuthSessionContext;
import com.fxplatform.auth.service.AuthService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.JwtAuthenticationFilter;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuthController 是认证授权模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * 处理 register 提交接口请求。
   */
  @PostMapping("/register")
  public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
    return ApiResponse.success(authService.register(request, AuthSessionContext.from(servletRequest)));
  }

  /**
   * 处理 login 提交接口请求。
   */
  @PostMapping("/login")
  public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
    return ApiResponse.success(authService.login(request, AuthSessionContext.from(servletRequest)));
  }

  /**
   * 处理 refresh 提交接口请求。
   */
  @PostMapping("/refresh")
  public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return ApiResponse.success(authService.refresh(request.refreshToken()));
  }

  /**
   * 处理 session 查询接口请求。
   */
  @GetMapping("/session")
  public ApiResponse<SessionStatusResponse> session(
      HttpServletRequest request,
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    if (Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.INVALID_BEARER_TOKEN_ATTRIBUTE))) {
      return ApiResponse.success(SessionStatusResponse.invalidToken("/login"));
    }
    if (principal == null) {
      return ApiResponse.success(SessionStatusResponse.guest("/login"));
    }
    return ApiResponse.success(SessionStatusResponse.authenticated(principal, "/login"));
  }

  /**
   * 处理 logout 提交接口请求。
   */
  @PostMapping("/logout")
  public ApiResponse<Void> logout(
      @RequestBody(required = false) LogoutRequest logoutRequest,
      HttpServletRequest servletRequest
  ) {
    authService.logout(bearerToken(servletRequest), logoutRequest == null ? null : logoutRequest.refreshToken());
    return ApiResponse.success(null);
  }

  /**
   * 处理 me 查询接口请求。
   */
  @GetMapping("/me")
  public ApiResponse<MeResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(authService.me(principal));
  }

  private String bearerToken(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    return authorization != null && authorization.startsWith("Bearer ")
        ? authorization.substring(7)
        : null;
  }
}
