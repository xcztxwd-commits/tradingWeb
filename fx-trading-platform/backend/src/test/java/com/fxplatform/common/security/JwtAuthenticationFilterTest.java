package com.fxplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthenticationFilterTest {

  @Test
  void marksInvalidBearerTokenAndStillAllowsPermitAllSessionEndpointToContinue() throws Exception {
    JwtService jwtService = Mockito.mock(JwtService.class);
    UserRepository userRepository = Mockito.mock(UserRepository.class);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    request.addHeader("Authorization", "Bearer bad-token");
    when(jwtService.parseUserId("bad-token")).thenThrow(new IllegalArgumentException("bad token"));

    filter.doFilterInternal(request, response, chain);

    assertThat(request.getAttribute("com.fxplatform.common.security.INVALID_BEARER_TOKEN")).isEqualTo(true);
    assertThat(chain.getRequest()).isSameAs(request);
  }
}
