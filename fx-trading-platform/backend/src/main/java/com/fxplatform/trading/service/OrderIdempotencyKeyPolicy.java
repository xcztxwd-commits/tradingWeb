package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.trading.enums.OrderOrigin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Keeps server-owned order identities outside every user-controlled key domain. */
final class OrderIdempotencyKeyPolicy {

  static final String SYSTEM_NAMESPACE = "__SYSTEM__:";

  private OrderIdempotencyKeyPolicy() {
  }

  static void requireUserControlled(String... keys) {
    for (String key : keys) {
      if (key != null
          && key.trim().toUpperCase(Locale.ROOT).startsWith(SYSTEM_NAMESPACE)) {
        throw new BusinessException(
            ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
            "Order key uses a reserved system namespace");
      }
    }
  }

  static String systemClose(
      UUID accountId,
      UUID positionId,
      OrderOrigin origin,
      String callerRequestId
  ) {
    String material = origin.name()
        + '|' + accountId
        + '|' + positionId
        + '|' + callerRequestId;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8));
      return SYSTEM_NAMESPACE + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for system order identity", exception);
    }
  }
}
