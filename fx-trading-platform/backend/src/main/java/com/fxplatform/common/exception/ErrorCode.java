package com.fxplatform.common.exception;

import java.util.Set;

public final class ErrorCode {

  public static final String AUTH_TOKEN_EXPIRED = "AUTH_TOKEN_EXPIRED";
  public static final String AUTH_REFRESH_TOKEN_INVALID = "AUTH_REFRESH_TOKEN_INVALID";
  public static final String USER_DISABLED = "USER_DISABLED";
  public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
  public static final String ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE";
  public static final String SYMBOL_NOT_TRADABLE = "SYMBOL_NOT_TRADABLE";
  public static final String QUOTE_STALE = "QUOTE_STALE";
  public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
  public static final String INSUFFICIENT_MARGIN = "INSUFFICIENT_MARGIN";
  public static final String ORDER_NOT_CANCELABLE = "ORDER_NOT_CANCELABLE";
  public static final String ORDER_ALREADY_FILLED = "ORDER_ALREADY_FILLED";
  public static final String DUPLICATE_CLIENT_ORDER_ID = "DUPLICATE_CLIENT_ORDER_ID";
  public static final String EXECUTION_UNAVAILABLE = "EXECUTION_UNAVAILABLE";
  public static final String EXECUTION_DISABLED = "EXECUTION_DISABLED";
  public static final String DEMO_ACCOUNT_REQUIRED = "DEMO_ACCOUNT_REQUIRED";
  public static final String PRODUCT_NOT_ALLOWED = "PRODUCT_NOT_ALLOWED";
  public static final String SYMBOL_NOT_ALLOWED = "SYMBOL_NOT_ALLOWED";
  public static final String MARKET_DATA_UNAVAILABLE = "MARKET_DATA_UNAVAILABLE";
  public static final String MARKET_DATA_STALE = "MARKET_DATA_STALE";
  public static final String MARKET_BUNDLE_INCOMPLETE = "MARKET_BUNDLE_INCOMPLETE";
  public static final String INVALID_CANDLE_REQUEST = "INVALID_CANDLE_REQUEST";
  public static final String PARTIAL_FILL_NOT_SUPPORTED = "PARTIAL_FILL_NOT_SUPPORTED";

  private static final Set<String> STANDARD_CODES = Set.of(
      AUTH_TOKEN_EXPIRED,
      AUTH_REFRESH_TOKEN_INVALID,
      USER_DISABLED,
      ACCOUNT_NOT_FOUND,
      ACCOUNT_NOT_ACTIVE,
      SYMBOL_NOT_TRADABLE,
      QUOTE_STALE,
      INSUFFICIENT_BALANCE,
      INSUFFICIENT_MARGIN,
      ORDER_NOT_CANCELABLE,
      ORDER_ALREADY_FILLED,
      DUPLICATE_CLIENT_ORDER_ID,
      EXECUTION_UNAVAILABLE,
      EXECUTION_DISABLED,
      DEMO_ACCOUNT_REQUIRED,
      PRODUCT_NOT_ALLOWED,
      SYMBOL_NOT_ALLOWED,
      MARKET_DATA_UNAVAILABLE,
      MARKET_DATA_STALE,
      MARKET_BUNDLE_INCOMPLETE,
      INVALID_CANDLE_REQUEST,
      PARTIAL_FILL_NOT_SUPPORTED);

  private ErrorCode() {
  }

  public static Set<String> standardCodes() {
    return STANDARD_CODES;
  }
}
