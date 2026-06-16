package com.fxplatform.market.enums;

public enum PriceAdjustmentStatus {
  SCHEDULED,
  CANCELED;

  public String code() {
    return name();
  }

  public boolean isCanceled() {
    return this == CANCELED;
  }

  public static PriceAdjustmentStatus fromCode(String value) {
    return valueOf(value.trim().toUpperCase());
  }
}
