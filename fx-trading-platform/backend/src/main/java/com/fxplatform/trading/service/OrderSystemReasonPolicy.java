package com.fxplatform.trading.service;

import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderOrigin;

/** Keeps internal idempotency markers out of public order/trade reason fields. */
final class OrderSystemReasonPolicy {

  static final String PROTECTION_IDEMPOTENCY_PREFIX = "IDEMP:";
  static final String USER_WHOLE_CLOSE = "INTERNAL:USER_WHOLE_CLOSE";

  private OrderSystemReasonPolicy() {
  }

  static String external(OrderEntity order) {
    if (order == null) {
      return null;
    }
    String reason = order.getSystemReason();
    if (reason == null) {
      return null;
    }
    if (order.getProtectionType() != null
        && reason.startsWith(PROTECTION_IDEMPOTENCY_PREFIX)) {
      return null;
    }
    if (order.getOrderOrigin() == OrderOrigin.USER
        && USER_WHOLE_CLOSE.equals(reason)) {
      return null;
    }
    return reason;
  }
}
