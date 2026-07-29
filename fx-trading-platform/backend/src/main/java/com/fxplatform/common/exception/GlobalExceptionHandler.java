package com.fxplatform.common.exception;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderType;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * GlobalExceptionHandler 是通用基础设施模块的异常处理组件。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 处理 handleAuthorization 异常相关逻辑。
   */
  @ExceptionHandler(AuthorizationException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ApiResponse<Void> handleAuthorization(AuthorizationException ex) {
    return ApiResponse.fail(ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ApiResponse<Void> handleAccessDenied(AccessDeniedException ex) {
    return ApiResponse.fail(ErrorCode.FORBIDDEN, "Forbidden");
  }

  /**
   * 处理 handleBusiness 异常相关逻辑。
   */
  @ExceptionHandler(BusinessException.class)
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "400",
          description = "Business rule violation"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "503",
          description = "Execution service unavailable")
  })
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
    HttpStatus status = switch (ex.getCode()) {
      case ErrorCode.EXECUTION_UNAVAILABLE,
          "CONTENT_ASSET_STORAGE_UNAVAILABLE",
          "CONTENT_ASSET_INTEGRITY_INVALID" -> HttpStatus.SERVICE_UNAVAILABLE;
      case "CONTENT_ASSET_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      default -> HttpStatus.BAD_REQUEST;
    };
    if (status.is5xxServerError()) {
      log.error("Service unavailable: {}", ex.getCode(), ex);
    }
    return ResponseEntity.status(status)
        .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(
      MaxUploadSizeExceededException exception
  ) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiResponse.fail("CONTENT_ASSET_TOO_LARGE", "Content asset is too large"));
  }

  /**
   * 处理 handleValidation 异常相关逻辑。
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getAllErrors().stream()
        .map(error -> error instanceof FieldError fieldError
            ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
            : error.getDefaultMessage())
        .collect(Collectors.joining("; "));
    boolean stopLimitPostOnlyError = ex.getBindingResult().getTarget()
        instanceof CreateOrderRequest request
        && request.orderType() == OrderType.STOP_LIMIT
        && ex.getBindingResult().getAllErrors().stream()
            .anyMatch(error -> "postOnly requires LIMIT and GTC"
                .equals(error.getDefaultMessage()));
    String code = ex.getBindingResult().getAllErrors().stream()
        .anyMatch(error -> "STOP_LIMIT requires triggerPrice and price"
            .equals(error.getDefaultMessage()))
        || stopLimitPostOnlyError
        ? ErrorCode.STOP_LIMIT_CONTRACT_INVALID
        : "VALIDATION_ERROR";
    return ApiResponse.fail(code, message);
  }

  /**
   * 处理 handleUnexpected 异常相关逻辑。
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResponse<Void> handleUnexpected(Exception ex) {
    log.error("Unexpected server exception", ex);
    return ApiResponse.fail("INTERNAL_ERROR", "Internal server error");
  }
}
