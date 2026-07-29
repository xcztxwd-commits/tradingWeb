package com.fxplatform.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

  @Test
  void mapsExecutionUnavailableToServiceUnavailableWithoutLeakingTheCause() {
    IllegalStateException cause =
        new IllegalStateException("jdbc:postgresql://secret-host/private");
    BusinessException exception = new BusinessException(
        ErrorCode.EXECUTION_UNAVAILABLE,
        "Order persistence is temporarily unavailable",
        cause);

    ResponseEntity<ApiResponse<Void>> response =
        new GlobalExceptionHandler().handleBusiness(exception);

    assertThat(exception.getCause()).isSameAs(cause);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.EXECUTION_UNAVAILABLE);
    assertThat(response.getBody().message())
        .isEqualTo("Order persistence is temporarily unavailable")
        .doesNotContain("secret-host");
  }

  @Test
  void keepsOrdinaryBusinessFailuresAsBadRequests() {
    ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
        .handleBusiness(new BusinessException("PRICE_TICK_MISMATCH", "Bad price tick"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("PRICE_TICK_MISMATCH");
  }

  @Test
  void mapsMissingContentAssetToNotFoundWithoutLeakingStorageDetails() {
    ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
        .handleBusiness(new BusinessException(
            "CONTENT_ASSET_NOT_FOUND", "Content asset not found"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .isEqualTo("Content asset not found")
        .doesNotContain("storage", "path", "\\", "/var/");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "CONTENT_ASSET_STORAGE_UNAVAILABLE",
      "CONTENT_ASSET_INTEGRITY_INVALID"
  })
  void mapsContentAssetServerFailuresToUnavailableWithoutLeakingPaths(String code) {
    ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
        .handleBusiness(new BusinessException(code, "Content asset is unavailable"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .isEqualTo("Content asset is unavailable")
        .doesNotContain("storageKey", "path", "\\", "/var/");
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsMultipartLimitToStablePayloadTooLargeResponseWithoutLeakingDetails()
      throws Exception {
    Method handler = GlobalExceptionHandler.class.getMethod(
        "handleMaxUploadSize", MaxUploadSizeExceededException.class);
    MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(5_242_880L);

    ResponseEntity<ApiResponse<Void>> response =
        (ResponseEntity<ApiResponse<Void>>) handler.invoke(
            new GlobalExceptionHandler(), exception);

    assertThat(handler.getAnnotation(ExceptionHandler.class).value())
        .containsExactly(MaxUploadSizeExceededException.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("CONTENT_ASSET_TOO_LARGE");
    assertThat(response.getBody().message())
        .isEqualTo("Content asset is too large")
        .doesNotContain("5242880", "exception", "multipart", "path");
  }

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

  @Test
  void mapsStopLimitObjectValidationToItsStableContractCode() throws Exception {
    BeanPropertyBindingResult errors = bindingResult();
    errors.addError(new ObjectError(
        "createOrderRequest",
        "STOP_LIMIT requires triggerPrice and price"));

    ApiResponse<Void> response = new GlobalExceptionHandler()
        .handleValidation(validationException(errors));

    assertThat(response.code()).isEqualTo(ErrorCode.STOP_LIMIT_CONTRACT_INVALID);
    assertThat(response.message()).isEqualTo("STOP_LIMIT requires triggerPrice and price");
  }

  @Test
  void mapsStopLimitPostOnlyObjectValidationToItsStableContractCode() throws Exception {
    BeanPropertyBindingResult errors = bindingResult(validationTarget(OrderType.STOP_LIMIT));
    errors.addError(new ObjectError(
        "createOrderRequest",
        "postOnly requires LIMIT and GTC"));

    ApiResponse<Void> response = new GlobalExceptionHandler()
        .handleValidation(validationException(errors));

    assertThat(response.code()).isEqualTo(ErrorCode.STOP_LIMIT_CONTRACT_INVALID);
    assertThat(response.message()).isEqualTo("postOnly requires LIMIT and GTC");
  }

  @Test
  void keepsNonStopLimitPostOnlyObjectValidationGeneric() throws Exception {
    BeanPropertyBindingResult errors = bindingResult(validationTarget(OrderType.MARKET));
    errors.addError(new ObjectError(
        "createOrderRequest",
        "postOnly requires LIMIT and GTC"));

    ApiResponse<Void> response = new GlobalExceptionHandler()
        .handleValidation(validationException(errors));

    assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(response.message()).isEqualTo("postOnly requires LIMIT and GTC");
  }

  @Test
  void keepsGenericFieldAndObjectValidationMessagesComplete() throws Exception {
    BeanPropertyBindingResult errors = bindingResult();
    errors.addError(new FieldError(
        "createOrderRequest", "quantity", "must be greater than 0"));
    errors.addError(new ObjectError(
        "createOrderRequest", "request contract invalid"));

    ApiResponse<Void> response = new GlobalExceptionHandler()
        .handleValidation(validationException(errors));

    assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(response.message())
        .isEqualTo("quantity: must be greater than 0; request contract invalid");
  }

  private static BeanPropertyBindingResult bindingResult() {
    return bindingResult(new Object());
  }

  private static BeanPropertyBindingResult bindingResult(Object target) {
    return new BeanPropertyBindingResult(target, "createOrderRequest");
  }

  private static CreateOrderRequest validationTarget(OrderType orderType) {
    boolean stopLimit = orderType == OrderType.STOP_LIMIT;
    return new CreateOrderRequest(
        UUID.randomUUID(),
        "BTCUSDT",
        OrderSide.BUY,
        orderType,
        null,
        null,
        null,
        null,
        "validation-target",
        "validation-target",
        BigDecimal.ONE,
        stopLimit ? BigDecimal.TEN : null,
        1,
        PositionSide.BOTH,
        stopLimit ? QuantityUnit.BASE : QuantityUnit.QUOTE,
        MarginMode.CASH,
        stopLimit ? BigDecimal.ONE : null,
        stopLimit ? TriggerPriceType.LAST_PRICE : null,
        false,
        List.of(),
        TimeInForce.GTC,
        true,
        null,
        null,
        null);
  }

  private static MethodArgumentNotValidException validationException(
      BeanPropertyBindingResult errors
  ) throws NoSuchMethodException {
    Method method = GlobalExceptionHandlerTest.class
        .getDeclaredMethod("controller", Object.class);
    return new MethodArgumentNotValidException(new MethodParameter(method, 0), errors);
  }

  @SuppressWarnings("unused")
  private static void controller(Object request) {
  }
}
