package com.fxplatform.execution;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "disabled")
public class DisabledExecutionAdapter implements ExecutionAdapter {

  @Override
  public ExecutionResult execute(CreateOrderRequest request) {
    throw new BusinessException(
        "EXECUTION_DISABLED",
        "Order execution is disabled; configure execution.mode=demo for simulation or a ready live adapter for trading");
  }
}
