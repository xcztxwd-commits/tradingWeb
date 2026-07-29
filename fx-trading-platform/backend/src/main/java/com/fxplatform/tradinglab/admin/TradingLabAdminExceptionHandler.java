package com.fxplatform.tradinglab.admin;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TradingLabAdminController.class)
public class TradingLabAdminExceptionHandler {

  private static final Set<String> CONFLICT_CODES = Set.of(
      "TRADING_LAB_SCENARIO_FROZEN",
      "TRADING_LAB_CONTROL_INVALID_STATE",
      "TRADING_LAB_INVALID_TRANSITION",
      "TRADING_LAB_STALE_STATE");

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
    HttpStatus status = statusFor(exception.getCode());
    return ResponseEntity.status(status)
        .body(ApiResponse.fail(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler({
      BindException.class,
      ConstraintViolationException.class,
      HttpMessageNotReadableException.class,
      IllegalArgumentException.class,
      MethodArgumentNotValidException.class,
      MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception exception) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(
        "TRADING_LAB_REQUEST_INVALID",
        "Trading Lab request is invalid"));
  }

  private static HttpStatus statusFor(String code) {
    if (code == null || !code.startsWith("TRADING_LAB_")) {
      return HttpStatus.BAD_REQUEST;
    }
    if (code.endsWith("_NOT_FOUND")) {
      return HttpStatus.NOT_FOUND;
    }
    if (CONFLICT_CODES.contains(code)
        || code.endsWith("_CONFLICT")
        || code.endsWith("_LOST")
        || code.endsWith("_STALE")) {
      return HttpStatus.CONFLICT;
    }
    return HttpStatus.BAD_REQUEST;
  }
}
