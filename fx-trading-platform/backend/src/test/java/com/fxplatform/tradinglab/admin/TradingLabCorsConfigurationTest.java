package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fxplatform.common.security.JwtAuthenticationFilter;
import com.fxplatform.common.security.SecurityConfig;
import com.fxplatform.common.security.SecurityErrorResponseWriter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

class TradingLabCorsConfigurationTest {

  @Test
  void printConfirmationHeaderSurvivesARealCorsPreflight() throws Exception {
    SecurityConfig security = new SecurityConfig(
        mock(JwtAuthenticationFilter.class),
        mock(SecurityErrorResponseWriter.class));
    ReflectionTestUtils.setField(
        security,
        "allowedOrigins",
        "https://admin.example.test");
    ReflectionTestUtils.setField(security, "allowedOriginPatterns", "");
    CorsConfigurationSource source = security.corsConfigurationSource();
    CorsFilter filter = new CorsFilter(source);

    MockHttpServletRequest request = new MockHttpServletRequest(
        "OPTIONS",
        "/api/admin/trading-lab/reports/"
            + "50000000-0000-0000-0000-000000000005/print");
    request.addHeader(HttpHeaders.ORIGIN, "https://admin.example.test");
    request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    request.addHeader(
        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
        "authorization,x-trading-lab-print-confirmation");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNull();
    assertThat(Arrays.stream(response
        .getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)
        .split(","))
        .map(String::trim)
        .map(String::toLowerCase))
        .contains("authorization", "x-trading-lab-print-confirmation");
  }

  @Test
  void lastEventIdHeaderSurvivesARealSseCorsPreflight() throws Exception {
    SecurityConfig security = new SecurityConfig(
        mock(JwtAuthenticationFilter.class),
        mock(SecurityErrorResponseWriter.class));
    ReflectionTestUtils.setField(
        security,
        "allowedOrigins",
        "https://admin.example.test");
    ReflectionTestUtils.setField(security, "allowedOriginPatterns", "");
    CorsFilter filter = new CorsFilter(security.corsConfigurationSource());

    MockHttpServletRequest request = new MockHttpServletRequest(
        "OPTIONS",
        "/api/admin/trading-lab/runs/"
            + "10000000-0000-0000-0000-000000000008/events");
    request.addHeader(HttpHeaders.ORIGIN, "https://admin.example.test");
    request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    request.addHeader(
        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
        "authorization,last-event-id");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNull();
    assertThat(Arrays.stream(response
        .getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)
        .split(","))
        .map(String::trim)
        .map(String::toLowerCase))
        .contains("authorization", "last-event-id");
  }

  @Test
  void requestIdHeaderIsExposedToBrowserFetch() throws Exception {
    SecurityConfig security = new SecurityConfig(
        mock(JwtAuthenticationFilter.class),
        mock(SecurityErrorResponseWriter.class));
    ReflectionTestUtils.setField(
        security,
        "allowedOrigins",
        "https://admin.example.test");
    ReflectionTestUtils.setField(security, "allowedOriginPatterns", "");
    CorsFilter filter = new CorsFilter(security.corsConfigurationSource());

    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET",
        "/api/admin/trading-lab/config");
    request.addHeader(HttpHeaders.ORIGIN, "https://admin.example.test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
        .isNotNull();
    assertThat(Arrays.stream(response
        .getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)
        .split(","))
        .map(String::trim)
        .map(String::toLowerCase))
        .contains("x-request-id");
  }
}
