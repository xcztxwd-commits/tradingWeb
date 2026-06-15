package com.fxplatform.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.common.security.UserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthControllerSessionTest {

  private final AuthController controller = new AuthController(null);

  @Test
  void returnsGuestSessionStatusWhenPrincipalIsMissing() {
    var response = controller.session(new MockHttpServletRequest(), null);

    assertThat(response.success()).isTrue();
    assertThat(response.data().status()).isEqualTo("guest");
    assertThat(response.data().authenticated()).isFalse();
    assertThat(response.data().userId()).isNull();
    assertThat(response.data().email()).isNull();
    assertThat(response.data().role()).isNull();
    assertThat(response.data().loginPath()).isEqualTo("/login");
  }

  @Test
  void returnsAuthenticatedSessionStatusWhenPrincipalExists() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000123");
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "USER");

    var response = controller.session(new MockHttpServletRequest(), principal);

    assertThat(response.success()).isTrue();
    assertThat(response.data().status()).isEqualTo("valid_token");
    assertThat(response.data().authenticated()).isTrue();
    assertThat(response.data().userId()).isEqualTo(userId);
    assertThat(response.data().email()).isEqualTo("trader@example.com");
    assertThat(response.data().role()).isEqualTo("USER");
    assertThat(response.data().loginPath()).isEqualTo("/login");
  }

  @Test
  void returnsInvalidTokenSessionStatusWhenBearerTokenCannotBeAccepted() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("com.fxplatform.common.security.INVALID_BEARER_TOKEN", true);

    var response = controller.session(request, null);

    assertThat(response.success()).isTrue();
    assertThat(response.data().status()).isEqualTo("invalid_token");
    assertThat(response.data().authenticated()).isFalse();
    assertThat(response.data().userId()).isNull();
    assertThat(response.data().email()).isNull();
    assertThat(response.data().role()).isNull();
    assertThat(response.data().loginPath()).isEqualTo("/login");
  }
}
