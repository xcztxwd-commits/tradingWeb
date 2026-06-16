package com.fxplatform.finance.enums;

public enum AdminFundOperationStatus {
  COMPLETED;

  public String code() {
    return name();
  }

  public static AdminFundOperationStatus fromCode(String value) {
    return valueOf(value.trim().toUpperCase());
  }
}
