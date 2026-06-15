package com.fxplatform.execution;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FixExecutionAdapter 是订单执行模块的后端组件。
 */
@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "fix")
public class FixExecutionAdapter implements ExecutionAdapter {

  /**
   * 执行 execute 适配器逻辑。
   */
  @Override
  public ExecutionResult execute(CreateOrderRequest request) {
    throw new BusinessException("FIX_NOT_ENABLED", "FIX execution is reserved for future integration");
  }
}
