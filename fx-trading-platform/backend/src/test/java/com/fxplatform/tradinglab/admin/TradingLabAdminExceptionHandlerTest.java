package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.response.ApiResponse;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class TradingLabAdminExceptionHandlerTest {

  private final TradingLabAdminExceptionHandler handler =
      new TradingLabAdminExceptionHandler();

  @ParameterizedTest
  @MethodSource("businessMappings")
  void mapsOnlyTheFrozenTradingLabAdminBusinessContract(
      String code,
      HttpStatus expectedStatus
  ) {
    ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
        new BusinessException(code, "safe message"));

    assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(code);
  }

  @Test
  void malformedInputIsBadRequest() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleMalformed(new IllegalArgumentException("bad input"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("TRADING_LAB_REQUEST_INVALID");
  }

  @Test
  void adviceIsScopedOnlyToTheMainTradingLabAdminController() {
    RestControllerAdvice advice =
        TradingLabAdminExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

    assertThat(advice).isNotNull();
    assertThat(Arrays.asList(advice.assignableTypes()))
        .containsExactly(TradingLabAdminController.class);
  }

  private static Stream<Arguments> businessMappings() {
    return Stream.of(
        Arguments.of("TRADING_LAB_SCENARIO_NOT_FOUND", HttpStatus.NOT_FOUND),
        Arguments.of("TRADING_LAB_RUN_NOT_FOUND", HttpStatus.NOT_FOUND),
        Arguments.of("TRADING_LAB_SCENARIO_FROZEN", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_CONFIG_HASH_CONFLICT", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_SCENARIO_VERSION_CONFLICT", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_SCENARIO_WRITE_LOST", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_CONTROL_INVALID_STATE", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_CONTROL_LOST", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_STALE_STATE", HttpStatus.CONFLICT),
        Arguments.of("TRADING_LAB_SCENARIO_INVALID", HttpStatus.BAD_REQUEST));
  }
}
