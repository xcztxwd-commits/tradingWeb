package com.fxplatform.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * SecurityErrorResponseWriter 负责将 Spring Security 拦截阶段的错误写成统一 API 响应。
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

  private final ObjectMapper objectMapper;

  /**
   * 写出未登录或 token 失效时的 JSON 响应。
   */
  public void writeUnauthorized(HttpServletResponse response) throws IOException {
    write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.AUTH_TOKEN_EXPIRED, "登录已过期或未登录，请重新登录");
  }

  /**
   * 写出已登录但权限不足时的 JSON 响应。
   */
  public void writeForbidden(HttpServletResponse response) throws IOException {
    write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "当前账号无管理员权限");
  }

  /**
   * Spring Security 在 Controller 之前返回错误，这里补齐和业务接口一致的响应格式。
   */
  private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), ApiResponse.fail(code, message));
  }
}
