package com.fxplatform.trading.enums;

/**
 * OrderStatus 定义交易模块的枚举值。
 */
public enum OrderStatus {
  RECEIVED,
  VALIDATING,
  ACCEPTED,
  WORKING,
  PARTIALLY_FILLED,
  PENDING_ACTIVATION,
  PENDING,
  FILLED,
  CANCEL_PENDING,
  CANCELED,
  CANCELLED,
  REJECTED,
  EXPIRED,
  FAILED
}
