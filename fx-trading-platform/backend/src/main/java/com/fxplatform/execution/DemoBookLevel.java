package com.fxplatform.execution;

import java.math.BigDecimal;

public record DemoBookLevel(BigDecimal price, BigDecimal quantity) {

  public DemoBookLevel {
    requirePositive(price, "price");
    requirePositive(quantity, "quantity");
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException("Demo book level " + name + " must be positive");
    }
  }
}
