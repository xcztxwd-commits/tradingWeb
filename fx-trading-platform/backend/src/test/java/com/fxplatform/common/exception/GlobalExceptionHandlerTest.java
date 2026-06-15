package com.fxplatform.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

class GlobalExceptionHandlerTest {

  @Test
  void mapsAuthorizationFailuresToForbidden() throws NoSuchMethodException {
    ResponseStatus responseStatus = GlobalExceptionHandler.class
        .getMethod("handleAuthorization", AuthorizationException.class)
        .getAnnotation(ResponseStatus.class);

    ApiResponse<Void> response = new GlobalExceptionHandler()
        .handleAuthorization(new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));

    assertThat(responseStatus.value()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.success()).isFalse();
    assertThat(response.code()).isEqualTo("ACCOUNT_NOT_FOUND");
    assertThat(response.message()).isEqualTo("Account not found");
  }

  @Test
  void hidesUnexpectedExceptionDetailsFromClients() throws NoSuchMethodException {
    ResponseStatus responseStatus = GlobalExceptionHandler.class
        .getMethod("handleUnexpected", Exception.class)
        .getAnnotation(ResponseStatus.class);

    ApiResponse<Void> response = new GlobalExceptionHandler()
        .handleUnexpected(new IllegalStateException("database password leaked"));

    assertThat(responseStatus.value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.success()).isFalse();
    assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.message()).isEqualTo("Internal server error");
  }
}
