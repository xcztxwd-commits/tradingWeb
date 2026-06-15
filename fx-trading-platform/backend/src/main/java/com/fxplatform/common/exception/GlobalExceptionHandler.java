package com.fxplatform.common.exception;

import com.fxplatform.common.response.ApiResponse;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

  /**
   * 处理 handleBusiness 异常相关逻辑。
   */
  @ExceptionHandler(BusinessException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleBusiness(BusinessException ex) {
    return ApiResponse.fail(ex.getCode(), ex.getMessage());
  }

  /**
   * 处理 handleValidation 异常相关逻辑。
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .collect(Collectors.joining("; "));
    return ApiResponse.fail("VALIDATION_ERROR", message);
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
