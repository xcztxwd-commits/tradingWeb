package com.fxplatform.market.enums;

public enum ProviderHealthStatus {
  UNKNOWN,
  UP,
  DOWN;

  public String code() {
    return name();
  }

  public static ProviderHealthStatus fromConfigured(boolean configured) {
    return configured ? UP : DOWN;
  }

  public static ProviderHealthStatus fromCode(String value) {
    return valueOf(value.trim().toUpperCase());
  }
}
