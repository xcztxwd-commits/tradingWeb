package com.fxplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityErrorResponseWriterTest {

  private final SecurityErrorResponseWriter writer =
      new SecurityErrorResponseWriter(new ObjectMapper().findAndRegisterModules());

  @Test
  void writesUnauthorizedApiResponseAsJson() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.writeUnauthorized(response);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString()).contains("\"success\":false");
    assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    assertThat(response.getContentAsString()).contains("登录已过期或未登录，请重新登录");
  }

  @Test
  void writesForbiddenApiResponseAsJson() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.writeForbidden(response);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString()).contains("\"success\":false");
    assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
    assertThat(response.getContentAsString()).contains("当前账号无管理员权限");
  }
}
