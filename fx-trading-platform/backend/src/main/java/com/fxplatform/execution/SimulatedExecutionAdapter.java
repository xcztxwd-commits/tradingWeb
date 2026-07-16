package com.fxplatform.execution;

import com.fxplatform.trading.dto.request.CreateOrderRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Demo adapter acknowledges only an explicit all-or-nothing quantity intent. */
@Primary
@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "demo")
public class SimulatedExecutionAdapter implements ExecutionAdapter {

  @Override
  public ExecutionResult execute(CreateOrderRequest request) {
    return new ExecutionResult(
        null,
        Instant.now(),
        request.quantity(),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        BigDecimal.ZERO,
        null,
        null);
  }
}
