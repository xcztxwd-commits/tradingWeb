package com.fxplatform.auth.dto.response;

import com.fxplatform.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * SessionStatusResponse 承载前端交易终端的登录状态探针结果。
 */
public record SessionStatusResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"guest", "invalid_token", "valid_token"})
    String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    boolean authenticated,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    UUID userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    String email,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    String role,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
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
