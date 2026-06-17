package com.fxplatform.execution;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BrokerExecutionAdapter 是订单执行模块的后端组件。
 */
@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "broker")
public class BrokerExecutionAdapter implements ExecutionAdapter, ExecutionAdapterReadiness {

  private static final String NOT_READY_MESSAGE = "Broker execution is reserved for future LIVE trading";

  /**
   * 执行 execute 适配器逻辑。
   */
  @Override
  public ExecutionResult execute(CreateOrderRequest request) {
    throw new BusinessException("BROKER_NOT_ENABLED", NOT_READY_MESSAGE);
  }

  @Override
  public ExecutionMode mode() {
    return ExecutionMode.BROKER;
  }

  @Override
  public boolean readyForLiveTrading() {
    return false;
  }

  @Override
  public String notReadyMessage() {
    return NOT_READY_MESSAGE;
  }
}
