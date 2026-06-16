package com.fxplatform.auth.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum KycStatus {
  NOT_SUBMITTED,
  PENDING,
  APPROVED,
  REJECTED;

  public String code() {
    return name();
  }

  public static KycStatus fromCode(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "NOT_SUBMITTED" -> NOT_SUBMITTED;
      case "PENDING" -> PENDING;
      case "APPROVED" -> APPROVED;
      case "REJECTED" -> REJECTED;
      default -> throw new BusinessException("INVALID_KYC_STATUS", "Invalid KYC status");
    };
  }

  public static KycStatus fromReviewCode(String value) {
    if ("通过".equals(value)) {
      return APPROVED;
    }
    if ("拒绝".equals(value)) {
      return REJECTED;
    }
    if ("待审核".equals(value)) {
      return PENDING;
    }
    return fromCode(value);
  }
}
