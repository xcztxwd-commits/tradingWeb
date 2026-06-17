package com.fxplatform.auth.service;

import jakarta.servlet.http.HttpServletRequest;

public record AuthSessionContext(
    String deviceId,
    String ipAddress,
    String userAgent
) {

  public static AuthSessionContext empty() {
    return new AuthSessionContext(null, null, null);
  }

  public static AuthSessionContext from(HttpServletRequest request) {
    if (request == null) {
      return empty();
    }
    return new AuthSessionContext(
        blankToNull(request.getHeader("X-Device-Id")),
        blankToNull(request.getRemoteAddr()),
        blankToNull(request.getHeader("User-Agent")));
  }

  private static String blankToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
