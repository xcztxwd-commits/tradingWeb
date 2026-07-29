package com.fxplatform.execution;

import com.fxplatform.trading.enums.OrderStatus;
import java.math.BigDecimal;
import java.util.List;

public record DemoMatchingResult(
    List<DemoMatchFill> fills,
    BigDecimal filledQuantity,
    BigDecimal remainingQuantity,
    OrderStatus terminalOrWorkingStatus
) {

  public DemoMatchingResult {
    fills = List.copyOf(fills);
  }
}
