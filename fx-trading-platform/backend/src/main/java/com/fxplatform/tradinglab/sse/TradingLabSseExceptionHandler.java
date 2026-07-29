package com.fxplatform.tradinglab.sse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Raw pre-stream failures for the SSE endpoint. The endpoint deliberately does not use the JSON
 * API envelope, including when validation fails before the response is committed.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TradingLabSseController.class)
final class TradingLabSseExceptionHandler {

  @ExceptionHandler(TradingLabSseRequestException.class)
  ResponseEntity<Void> handle(TradingLabSseRequestException exception) {
    return ResponseEntity.status(exception.status()).build();
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Void> handleMalformed(
      MethodArgumentTypeMismatchException exception
  ) {
    return ResponseEntity.badRequest().build();
  }
}
