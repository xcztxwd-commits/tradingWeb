package com.fxplatform.tradinglab.admin.report;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.tradinglab.admin.TradingLabReportController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TradingLabReportController.class)
public class TradingLabReportExceptionHandler {

  @ExceptionHandler(TradingLabReportAdminException.class)
  public ResponseEntity<ApiResponse<Void>> handleAdmin(
      TradingLabReportAdminException exception
  ) {
    return ResponseEntity.status(exception.getHttpStatus())
        .body(ApiResponse.fail(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleReport(
      BusinessException exception
  ) {
    HttpStatus status = switch (exception.getCode()) {
      case "TRADING_LAB_REPORT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "TRADING_LAB_PRINT_CONFIRMATION_INVALID" -> HttpStatus.FORBIDDEN;
      case "TRADING_LAB_PRINT_CONFIRMATION_UNAVAILABLE" ->
          HttpStatus.SERVICE_UNAVAILABLE;
      default -> exception.getCode().startsWith("TRADING_LAB_REPORT_")
          ? HttpStatus.CONFLICT
          : HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status)
        .body(ApiResponse.fail(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(
      MethodArgumentNotValidException exception
  ) {
    String message = exception.getBindingResult().getAllErrors().stream()
        .map(error -> error instanceof FieldError fieldError
            ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
            : error.getDefaultMessage())
        .findFirst()
        .orElse("Trading Lab report request is invalid");
    return ResponseEntity.badRequest()
        .body(ApiResponse.fail("TRADING_LAB_REPORT_REQUEST_INVALID", message));
  }

  @ExceptionHandler({
      HttpMessageNotReadableException.class,
      IllegalArgumentException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception exception) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(
        "TRADING_LAB_REPORT_REQUEST_INVALID",
        "Trading Lab report request is invalid"));
  }
}
