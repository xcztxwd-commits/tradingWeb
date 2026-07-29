package com.fxplatform.common.web;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RequestIdFilter 是通用基础设施模块的 Web 请求组件。
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 2)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";

  /**
   * 执行 doFilterInternal 方法逻辑。
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String requestId = request.getHeader(HEADER);
    if (StrUtil.isBlank(requestId)) {
      requestId = IdUtil.fastUUID();
    }
    response.setHeader(HEADER, requestId);
    MDC.put("requestId", requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }
}
