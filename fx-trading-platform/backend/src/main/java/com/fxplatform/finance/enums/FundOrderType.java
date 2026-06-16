package com.fxplatform.finance.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum FundOrderType {
  RECHARGE,
  WITHDRAWAL;

  public String code() {
    return name();
  }

  public static FundOrderType fromCode(String value) {
    String normalized = normalize(value);
    return switch (normalized) {
      case "DEPOSIT", "RECHARGE" -> RECHARGE;
      case "WITHDRAW", "WITHDRAWAL" -> WITHDRAWAL;
      default -> throw new BusinessException("INVALID_FUND_ORDER_TYPE", "Invalid fund order type");
    };
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
