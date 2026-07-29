package com.fxplatform.common.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.fxplatform.audit.service.RequestLogService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLogFilterTest {

  @Test
  void recordsRequestIdForApiRequests() throws Exception {
    RequestLogService requestLogService = org.mockito.Mockito.mock(RequestLogService.class);
    RequestLogFilter filter = new RequestLogFilter(requestLogService);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/trading/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (servletRequest, servletResponse) -> response.setStatus(400);

    request.addHeader(RequestIdFilter.HEADER, "req-abc");

    filter.doFilter(request, response, chain);

    verify(requestLogService).record(
        eq("req-abc"),
        eq("POST"),
        eq("/api/trading/orders"),
        eq("127.0.0.1"),
        isNull(),
        eq(400),
        org.mockito.ArgumentMatchers.anyLong(),
        isNull());
  }

  @ParameterizedTest
  @ValueSource(strings = {"shown", "close", "opt-out", "click"})
  void redactsPopupDeliveryTokenFromRecordedPath(String action) throws Exception {
    RequestLogService requestLogService = org.mockito.Mockito.mock(RequestLogService.class);
    RequestLogFilter filter = new RequestLogFilter(requestLogService);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/api/me/engagement/popup-deliveries/secret-delivery-token/" + action);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (servletRequest, servletResponse) -> response.setStatus(200);

    filter.doFilter(request, response, chain);

    verify(requestLogService).record(
        isNull(),
        eq("POST"),
        eq("/api/me/engagement/popup-deliveries/<redacted>/" + action),
        eq("127.0.0.1"),
        isNull(),
        eq(200),
        org.mockito.ArgumentMatchers.anyLong(),
        isNull());
  }
}
