package com.fxplatform.execution;

import com.fxplatform.trading.dto.request.CreateOrderRequest;

/**
 * ExecutionAdapter 定义订单执行模块的契约。
 */
public interface ExecutionAdapter {

  /**
   * 执行 execute 适配器逻辑。
   */
  ExecutionResult execute(CreateOrderRequest request);
}
