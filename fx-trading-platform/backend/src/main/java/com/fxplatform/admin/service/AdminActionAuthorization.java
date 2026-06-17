package com.fxplatform.admin.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

final class AdminActionAuthorization {

  private AdminActionAuthorization() {
  }

  static void requireAuthority(String authority) {
    if (authority == null || authority.isBlank()) {
      return;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return;
    }
    boolean allowed = authentication.getAuthorities().stream()
        .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    if (!allowed) {
      throw new AccessDeniedException("Missing admin authority: " + authority);
    }
  }
}
