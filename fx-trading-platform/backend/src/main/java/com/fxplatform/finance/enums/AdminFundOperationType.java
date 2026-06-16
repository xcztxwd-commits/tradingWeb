package com.fxplatform.finance.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum AdminFundOperationType {
  DEPOSIT,
  WITHDRAWAL,
  ADJUSTMENT;

  public String code() {
    return name();
  }

  public static AdminFundOperationType fromCode(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "DEPOSIT" -> DEPOSIT;
      case "WITHDRAW", "WITHDRAWAL" -> WITHDRAWAL;
      case "ADJUST", "ADJUSTMENT" -> ADJUSTMENT;
      default -> throw new BusinessException("INVALID_FUND_OPERATION_TYPE", "Invalid fund operation type");
    };
  }
}
