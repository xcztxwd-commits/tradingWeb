package com.fxplatform.trading.service;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import org.springframework.stereotype.Component;

@Component
public class OrderCommandFactory {

  public OrderCommand from(UserPrincipal principal, CreateOrderRequest request) {
    String clientOrderId = request.clientOrderId();
    return new OrderCommand(
        principal.id(),
        request.accountId(),
        SymbolNormalizer.normalize(request.symbol()),
        request.side(),
        request.orderType(),
        request.quantity(),
        request.price(),
        request.stopLoss(),
        request.takeProfit(),
        clientOrderId,
        hasText(request.idempotencyKey()) ? request.idempotencyKey() : clientOrderId,
        request.leverage());
  }

  private boolean hasText(String value) {
    return StrUtil.isNotBlank(value);
  }
}
