package com.fxplatform.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fxplatform.audit.service.RequestLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterOrderTest {

  @Test
  void correlationAndAuditFiltersWrapSpringSecurity() {
    Order requestIdOrder = RequestIdFilter.class.getAnnotation(Order.class);
    Order requestLogOrder = RequestLogFilter.class.getAnnotation(Order.class);

    assertThat(requestIdOrder).isNotNull();
    assertThat(requestLogOrder).isNotNull();
    assertThat(requestIdOrder.value()).isLessThan(requestLogOrder.value());
    assertThat(requestLogOrder.value()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
  }

  @ParameterizedTest
  @ValueSource(ints = {
      HttpServletResponse.SC_UNAUTHORIZED,
      HttpServletResponse.SC_FORBIDDEN
  })
  void echoesAndRecordsRequestIdentityWhenSecurityRejectsTheRequest(int status) throws Exception {
    String requestId = UUID.randomUUID().toString();
    RequestLogService requestLogService = mock(RequestLogService.class);
    RequestIdFilter requestIdFilter = new RequestIdFilter();
    RequestLogFilter requestLogFilter = new RequestLogFilter(requestLogService);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET",
        "/api/admin/trading-lab/environment");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain securityRejection = (servletRequest, servletResponse) ->
        ((HttpServletResponse) servletResponse).setStatus(status);

    request.addHeader(RequestIdFilter.HEADER, requestId);
    requestIdFilter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) -> requestLogFilter.doFilter(
            servletRequest,
            servletResponse,
            securityRejection));

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(requestId);
    verify(requestLogService).record(
        eq(requestId),
        eq("GET"),
        eq("/api/admin/trading-lab/environment"),
        eq("127.0.0.1"),
        isNull(),
        eq(status),
        anyLong(),
        isNull());
  }
}
