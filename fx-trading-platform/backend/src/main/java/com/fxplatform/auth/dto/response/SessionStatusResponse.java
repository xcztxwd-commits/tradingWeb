package com.fxplatform.auth.dto.response;

import com.fxplatform.common.security.UserPrincipal;
import java.util.UUID;

/**
 * SessionStatusResponse 承载前端交易终端的登录状态探针结果。
 */
public record SessionStatusResponse(
    String status,
    boolean authenticated,
    UUID userId,
    String email,
    String role,
    String loginPath
) {

  public static SessionStatusResponse guest(String loginPath) {
    return new SessionStatusResponse("guest", false, null, null, null, loginPath);
  }

  public static SessionStatusResponse invalidToken(String loginPath) {
    return new SessionStatusResponse("invalid_token", false, null, null, null, loginPath);
  }

  public static SessionStatusResponse authenticated(UserPrincipal principal, String loginPath) {
    return new SessionStatusResponse("valid_token", true, principal.id(), principal.email(), principal.role(), loginPath);
  }
}
