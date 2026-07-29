package com.fxplatform.common.web;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.audit.service.RequestLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)
public class RequestLogFilter extends OncePerRequestFilter {

  private static final Pattern POPUP_DELIVERY_OUTCOME_PATH = Pattern.compile(
      "^(/api/me/engagement/popup-deliveries/)[^/]+(/(?:shown|close|opt-out|click))$");

  private final RequestLogService requestLogService;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    long started = System.nanoTime();
    String errorMessage = null;
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException ex) {
      errorMessage = ex.getMessage();
      throw ex;
    } finally {
      long durationMs = (System.nanoTime() - started) / 1_000_000;
      try {
        requestLogService.record(
            requestId(request, response),
            request.getMethod(),
            requestPath(request),
            clientIp(request),
            request.getHeader("User-Agent"),
            response.getStatus(),
            durationMs,
            errorMessage);
      } catch (RuntimeException ignored) {
        // Audit logging must not block the main request.
      }
    }
  }

  private String requestId(HttpServletRequest request, HttpServletResponse response) {
    String responseRequestId = response.getHeader(RequestIdFilter.HEADER);
    if (StrUtil.isNotBlank(responseRequestId)) {
      return responseRequestId;
    }
    return request.getHeader(RequestIdFilter.HEADER);
  }

  private String requestPath(HttpServletRequest request) {
    String query = request.getQueryString();
    String path = POPUP_DELIVERY_OUTCOME_PATH.matcher(request.getRequestURI())
        .replaceFirst("$1<redacted>$2");
    return StrUtil.isBlank(query) ? path : path + "?" + query;
  }

  private String clientIp(HttpServletRequest request) {
    return StrUtil.blankToDefault(request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
  }
}
