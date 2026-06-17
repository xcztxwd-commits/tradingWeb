package com.fxplatform.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.dto.request.LogoutRequest;
import com.fxplatform.auth.dto.request.RefreshTokenRequest;
import com.fxplatform.auth.dto.response.AuthResponse;
import com.fxplatform.auth.service.AuthService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthControllerTokenLifecycleTest {

  @Test
  void refreshReturnsRotatedTokenPair() {
    AuthService authService = Mockito.mock(AuthService.class);
    AuthController controller = new AuthController(authService);
    AuthResponse rotated = new AuthResponse(
        UUID.fromString("00000000-0000-0000-0000-000000000123"),
        "trader@example.com",
        "USER",
        "new-access-token",
        "new-refresh-token");
    when(authService.refresh("old-refresh-token")).thenReturn(rotated);

    var response = controller.refresh(new RefreshTokenRequest("old-refresh-token"));

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo(rotated);
  }

  @Test
  void logoutRevokesBearerAccessTokenAndSubmittedRefreshToken() {
    AuthService authService = Mockito.mock(AuthService.class);
    AuthController controller = new AuthController(authService);
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.addHeader("Authorization", "Bearer access-token");

    var response = controller.logout(new LogoutRequest("refresh-token"), servletRequest);

    assertThat(response.success()).isTrue();
    verify(authService).logout("access-token", "refresh-token");
  }
}
