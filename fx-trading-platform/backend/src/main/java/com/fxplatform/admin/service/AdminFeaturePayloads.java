package com.fxplatform.admin.service;

import cn.hutool.core.util.IdUtil;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.request.AdminPriceAdjustmentRequest;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 后台通用动作 payload 解析工具。
 */
final class AdminFeaturePayloads {

  private AdminFeaturePayloads() {
  }

  static String string(Map<String, Object> payload, String key, String defaultValue) {
    Object value = payload.get(key);
    return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
  }

  static Integer integer(Map<String, Object> payload, String key, Integer defaultValue) {
    Object value = payload.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null || String.valueOf(value).isBlank()) {
      return defaultValue;
    }
    return Integer.parseInt(String.valueOf(value));
  }

  static UUID uuid(Map<String, Object> payload, String key) {
    return uuid(payload.get(key));
  }

  static UUID requireUuid(Map<String, ?> payload, String key, String code, String message) {
    UUID value = parseRequiredUuidValue(payload.get(key), key, code);
    if (value == null) {
      throw new BusinessException(code, message);
    }
    return value;
  }

  static UUID requireFirstUuid(String rowId, Map<String, ?> payload, String code, String message, String... keys) {
    UUID rowValue = uuid(rowId);
    UUID payloadValue = null;
    String payloadKey = null;
    for (String key : keys) {
      if (!hasValue(payload.get(key))) {
        continue;
      }
      UUID value = parseRequiredUuidValue(payload.get(key), key, code);
      if (payloadValue == null) {
        payloadValue = value;
        payloadKey = key;
      } else if (!payloadValue.equals(value)) {
        throw new BusinessException(code, payloadKey + " conflicts with " + key);
      }
    }
    if (rowValue != null && payloadValue != null && !rowValue.equals(payloadValue)) {
      throw new BusinessException(code, "rowId conflicts with " + payloadKey);
    }
    if (rowValue != null) {
      return rowValue;
    }
    if (payloadValue != null) {
      return payloadValue;
    }
    if (hasValue(rowId)) {
      throw new BusinessException(code, "rowId must be a UUID");
    }
    throw new BusinessException(code, message);
  }

  static AdminFundOperationRequest fundRequest(Map<String, Object> payload, String defaultReason) {
    return new AdminFundOperationRequest(
        decimal(payload, "amount", BigDecimal.ZERO),
        string(payload, "reason", defaultReason),
        uuid(payload, "paymentMethodId"),
        string(payload, "note", string(payload, "remark", "")),
        string(payload, "idempotencyKey", IdUtil.fastUUID()));
  }

  static AdminPriceAdjustmentRequest priceAdjustmentRequest(Map<String, Object> payload, String defaultReason) {
    return new AdminPriceAdjustmentRequest(
        string(payload, "mode", "PRICE_REPAIR"),
        string(payload, "adjustmentType", "SET_MID_PRICE"),
        decimal(payload, "targetPrice", decimal(payload, "endPrice", BigDecimal.ONE)),
        instant(payload, "startsAt", "startAt"),
        instant(payload, "endsAt", "endAt"),
        string(payload, "reason", defaultReason));
  }

  static BigDecimal decimal(Map<String, Object> payload, String key, BigDecimal defaultValue) {
    return decimalValue(payload.get(key), defaultValue);
  }

  static BigDecimal requirePositiveDecimal(Map<String, ?> payload, String key, String code, String message) {
    BigDecimal value = requiredDecimalValue(payload.get(key), key, code);
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(code, message);
    }
    return value;
  }

  private static BigDecimal decimalValue(Object value, BigDecimal defaultValue) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (value == null || String.valueOf(value).isBlank()) {
      return defaultValue;
    }
    return new BigDecimal(String.valueOf(value));
  }

  private static BigDecimal requiredDecimalValue(Object value, String key, String code) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (!hasValue(value)) {
      return null;
    }
    try {
      return new BigDecimal(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new BusinessException(code, key + " must be a decimal");
    }
  }

  static Boolean enabled(Map<String, Object> payload) {
    Object value = payload.get("enabled");
    if (value instanceof Boolean bool) {
      return bool;
    }
    String status = string(payload, "status", "正常");
    return !"停用".equals(status) && !"DISABLED".equalsIgnoreCase(status) && !"false".equalsIgnoreCase(status);
  }

  static String kycStatus(Map<String, Object> payload) {
    String status = string(payload, "realNameStatus", string(payload, "status", "通过"));
    if ("通过".equals(status)) {
      return "APPROVED";
    }
    if ("拒绝".equals(status)) {
      return "REJECTED";
    }
    if ("待审核".equals(status)) {
      return "PENDING";
    }
    if ("未提交".equals(status)) {
      return "NOT_SUBMITTED";
    }
    return status;
  }

  static UserStatus userStatus(Map<String, Object> payload) {
    String status = string(payload, "status", string(payload, "control", "正常"));
    if ("冻结".equals(status) || "FROZEN".equalsIgnoreCase(status)) {
      return UserStatus.FROZEN;
    }
    if ("禁用".equals(status) || "停用".equals(status) || "DISABLED".equalsIgnoreCase(status)) {
      return UserStatus.DISABLED;
    }
    return UserStatus.ACTIVE;
  }

  static boolean approved(Map<String, Object> payload) {
    String status = string(payload, "status", string(payload, "reviewStatus", "通过"));
    return "通过".equals(status) || "APPROVED".equalsIgnoreCase(status) || "PASS".equalsIgnoreCase(status);
  }

  static String paymentMethodType(String value) {
    if ("银行卡".equals(value)) {
      return "BANK_TRANSFER";
    }
    if ("数字货币".equals(value)) {
      return "CRYPTO";
    }
    return value;
  }

  static String valueType(Object value) {
    if (value instanceof Number) {
      return "NUMBER";
    }
    if (value instanceof Boolean) {
      return "BOOLEAN";
    }
    return "STRING";
  }

  static UUID uuid(Object value) {
    if (!hasValue(value)) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static UUID uuid(String value) {
    return uuid((Object) value);
  }

  private static UUID parseRequiredUuidValue(Object value, String key, String code) {
    if (!hasValue(value)) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(code, key + " must be a UUID");
    }
  }

  private static boolean hasValue(Object value) {
    return value != null && !String.valueOf(value).isBlank();
  }

  private static Instant instant(Map<String, Object> payload, String... keys) {
    for (String key : keys) {
      Object value = payload.get(key);
      if (value instanceof Instant instant) {
        return instant;
      }
      if (value != null && !String.valueOf(value).isBlank()) {
        try {
          return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
          return null;
        }
      }
    }
    return null;
  }
}
