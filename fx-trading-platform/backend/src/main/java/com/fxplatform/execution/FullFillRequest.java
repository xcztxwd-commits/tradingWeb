package com.fxplatform.execution;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;

/** Canonical base-quantity execution request used after public request normalization. */
public record FullFillRequest(
    CreateOrderRequest executionIntent,
    String platformSymbol,
    ProductType productType,
    OrderSide side,
    FullFillExecutionPath executionPath,
    BigDecimal requestedBaseQuantity,
    BigDecimal limitPrice
) {

  CreateOrderRequest toExecutionIntent() {
    return executionIntent;
  }
}
