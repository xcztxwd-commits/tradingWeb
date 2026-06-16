package com.fxplatform.finance.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum PaymentAccountType {
  BANK,
  WALLET;

  public String code() {
    return name();
  }

  public static PaymentAccountType fromCode(String value) {
    if ("银行卡".equals(value)) {
      return BANK;
    }
    if ("钱包".equals(value)) {
      return WALLET;
    }
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "BANK" -> BANK;
      case "WALLET" -> WALLET;
      default -> throw new BusinessException("INVALID_PAYMENT_ACCOUNT_TYPE", "Invalid payment account type");
    };
  }
}
