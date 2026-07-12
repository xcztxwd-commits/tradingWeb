package com.fxplatform.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ErrorCodeContractTest {

  @Test
  void exposesStandardApiErrorCodesForBackendAndFrontendContracts() {
    assertThat(ErrorCode.standardCodes()).containsExactlyInAnyOrderElementsOf(Set.of(
        "AUTH_TOKEN_EXPIRED",
        "AUTH_REFRESH_TOKEN_INVALID",
        "USER_DISABLED",
        "ACCOUNT_NOT_FOUND",
        "ACCOUNT_NOT_ACTIVE",
        "SYMBOL_NOT_TRADABLE",
        "QUOTE_STALE",
        "INSUFFICIENT_BALANCE",
        "INSUFFICIENT_MARGIN",
        "ORDER_NOT_CANCELABLE",
        "ORDER_ALREADY_FILLED",
        "DUPLICATE_CLIENT_ORDER_ID",
        "EXECUTION_UNAVAILABLE",
        "EXECUTION_DISABLED",
        "DEMO_ACCOUNT_REQUIRED",
        "PRODUCT_NOT_ALLOWED",
        "SYMBOL_NOT_ALLOWED",
        "MARKET_DATA_UNAVAILABLE",
        "MARKET_DATA_STALE",
        "MARKET_BUNDLE_INCOMPLETE",
        "INVALID_CANDLE_REQUEST",
        "PARTIAL_FILL_NOT_SUPPORTED",
        "INVALID_QUANTITY_UNIT",
        "QUANTITY_CONVERTS_TO_ZERO",
        "INVALID_SPOT_ORDER_TYPE",
        "INVALID_SPOT_ORDER_FIELDS",
        "ORDER_PRICE_REQUIRED",
        "ORDER_TRIGGER_PRICE_REQUIRED",
        "OCO_PRICE_RELATION_INVALID",
        "OCO_GROUP_INCOMPLETE",
        "OCO_ORDER_NOT_MODIFIABLE",
        "ORDER_HOLD_INVALID"));
  }
}
