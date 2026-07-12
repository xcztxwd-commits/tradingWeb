package com.fxplatform.trading.service;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import org.springframework.stereotype.Component;

@Component
public class OrderCommandFactory {

  public OrderCommand from(UserPrincipal principal, CreateOrderRequest request) {
    return from(principal, request, request.quantity());
  }

  public OrderCommand from(
      UserPrincipal principal,
      CreateOrderRequest request,
      java.math.BigDecimal canonicalBaseQuantity
  ) {
    String clientOrderId = request.clientOrderId();
    return new OrderCommand(
        principal.id(),
        request.accountId(),
        SymbolNormalizer.normalize(request.symbol()),
        request.side(),
        request.orderType(),
        canonicalBaseQuantity,
        request.price(),
        request.stopLoss(),
        request.takeProfit(),
        clientOrderId,
        hasText(request.idempotencyKey()) ? request.idempotencyKey() : clientOrderId,
        request.leverage(),
        request.quantity(),
        canonicalBaseQuantity,
        request.quantityUnit(),
        request.marginMode(),
        request.positionSide(),
        request.reduceOnly(),
        request.triggerPrice(),
        request.triggerPriceType(),
        request.attachedProtections());
  }

  private boolean hasText(String value) {
    return StrUtil.isNotBlank(value);
  }
}
