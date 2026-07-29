package com.fxplatform.validation.security;

import com.fxplatform.common.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates validation-only loopback requests with a dedicated shared secret. */
public final class ValidationInternalAuthenticationFilter extends OncePerRequestFilter {

  public static final String AUTHORITY = "VALIDATION_INTERNAL";
  public static final String HEADER = "X-Validation-Internal-Token";

  private static final String INTERNAL_ROOT = "/internal/validation";

  private final byte[] configuredDigest;
  private final SecurityErrorResponseWriter errorResponseWriter;

  public ValidationInternalAuthenticationFilter(
      String configuredSecret,
      SecurityErrorResponseWriter errorResponseWriter
  ) {
    if (configuredSecret == null || configuredSecret.isBlank()) {
      throw new IllegalArgumentException("Validation internal secret is required");
    }
    this.configuredDigest = sha256(configuredSecret);
    this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(INTERNAL_ROOT.equals(path) || path.startsWith(INTERNAL_ROOT + "/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    if (!isLoopback(request.getRemoteAddr()) || !hasValidToken(request)) {
      SecurityContextHolder.clearContext();
      errorResponseWriter.writeValidationInternalUnauthorized(response);
      return;
    }

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            AUTHORITY,
            null,
            List.of(new SimpleGrantedAuthority(AUTHORITY)));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    filterChain.doFilter(request, response);
  }

  private boolean hasValidToken(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HEADER);
    if (values == null || !values.hasMoreElements()) {
      return false;
    }
    String supplied = values.nextElement();
    if (values.hasMoreElements() || supplied == null) {
      return false;
    }
    return MessageDigest.isEqual(configuredDigest, sha256(supplied));
  }

  private static boolean isLoopback(String remoteAddress) {
    if (remoteAddress == null || remoteAddress.isBlank()) {
      return false;
    }
    try {
      return InetAddress.getByName(remoteAddress).isLoopbackAddress();
    } catch (UnknownHostException exception) {
      return false;
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
