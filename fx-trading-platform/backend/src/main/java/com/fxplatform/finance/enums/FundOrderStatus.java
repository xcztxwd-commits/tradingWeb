package com.fxplatform.finance.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum FundOrderStatus {
  PENDING,
  PENDING_REVIEW,
  APPROVED,
  REJECTED;

  public String code() {
    return name();
  }

  public boolean isPendingReview() {
    return this == PENDING || this == PENDING_REVIEW;
  }

  public boolean isApproved() {
    return this == APPROVED;
  }

  public static FundOrderStatus fromCode(String value) {
    String normalized = normalize(value);
    return switch (normalized) {
      case "PENDING" -> PENDING;
      case "PENDING_REVIEW" -> PENDING_REVIEW;
      case "APPROVED" -> APPROVED;
      case "REJECTED" -> REJECTED;
      default -> throw new BusinessException("INVALID_FUND_ORDER_STATUS", "Invalid fund order status");
    };
  }

  public static FundOrderStatus fromReviewCode(String value) {
    if ("通过".equals(value)) {
      return APPROVED;
    }
    if ("拒绝".equals(value)) {
      return REJECTED;
    }
    return fromCode(value);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
