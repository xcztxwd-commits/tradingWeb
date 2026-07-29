package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed schema policy for scenario-owned public action payloads.
 *
 * <p>Routing, authentication, account identity, and idempotency identity are owned by the
 * validation backend. Scenario payloads can provide only the business fields explicitly listed
 * here and can never smuggle network-control or credential-shaped keys through nested values.</p>
 */
public final class ValidationPublicActionPayloadPolicy {

  private static final Set<String> PLACE_ORDER_FIELDS = Set.of(
      "symbol",
      "side",
      "orderType",
      "lots",
      "requestedPrice",
      "stopLoss",
      "takeProfit",
      "quantity",
      "price",
      "leverage",
      "positionSide",
      "quantityUnit",
      "marginMode",
      "triggerPrice",
      "triggerPriceType",
      "reduceOnly",
      "attachedProtections",
      "timeInForce",
      "postOnly",
      "activationPrice",
      "trailingDelta",
      "trailingRate");
  private static final Set<String> CANCEL_ORDER_FIELDS = Set.of("clientOrderId");
  private static final Set<String> SET_POSITION_MODE_FIELDS = Set.of("positionMode");
  private static final Set<String> SET_MARGIN_MODE_FIELDS = Set.of("symbol", "marginMode");
  private static final Set<String> SET_LEVERAGE_FIELDS = Set.of("symbol", "leverage");
  private static final Set<String> ATTACHED_PROTECTION_FIELDS = Set.of(
      "protectionType",
      "triggerPrice",
      "triggerPriceType",
      "triggerExecutionType",
      "price",
      "quantity",
      "quantityUnit");
  private static final Set<String> PLACE_ORDER_TEXT_FIELDS = Set.of(
      "symbol", "side", "orderType", "positionSide", "quantityUnit", "marginMode",
      "timeInForce");
  private static final Set<String> PLACE_ORDER_NUMBER_FIELDS = Set.of(
      "lots", "requestedPrice", "stopLoss", "takeProfit", "quantity", "price", "leverage",
      "triggerPrice", "activationPrice", "trailingDelta", "trailingRate");
  private static final Set<String> PLACE_ORDER_BOOLEAN_FIELDS = Set.of("reduceOnly", "postOnly");
  private static final int MAX_ATTACHED_PROTECTIONS = 10;
  private static final int MAX_NESTING_DEPTH = 8;
  private static final int MAX_PAYLOAD_NODES = 1024;

  private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of(
      "url",
      "uri",
      "path",
      "method",
      "header",
      "command",
      "password",
      "secret",
      "token",
      "credential",
      "apikey",
      "cookie",
      "authorization");

  private ValidationPublicActionPayloadPolicy() {
  }

  public static void validate(PublicActionType type, Map<String, ?> requestedPayload) {
    validateAndFreeze(type, requestedPayload);
  }

  public static Map<String, Object> validateAndFreeze(
      PublicActionType type,
      Map<String, ?> requestedPayload
  ) {
    Objects.requireNonNull(type, "type");
    Map<String, ?> payload = requestedPayload == null ? Map.of() : requestedPayload;
    rejectForbiddenKeys(payload, new IdentityHashMap<>());

    switch (type) {
      case PLACE_ORDER -> {
        requireOnly(payload, PLACE_ORDER_FIELDS);
        requireText(payload, "symbol");
        requireText(payload, "side");
        requireText(payload, "orderType");
        if (!hasValue(payload, "quantity") && !hasValue(payload, "lots")) {
          throw invalid();
        }
        PLACE_ORDER_TEXT_FIELDS.forEach(field -> requireOptionalText(payload, field));
        PLACE_ORDER_NUMBER_FIELDS.forEach(field -> requireOptionalDecimal(payload, field));
        PLACE_ORDER_BOOLEAN_FIELDS.forEach(field -> requireOptionalBoolean(payload, field));
        validateAttachedProtections(payload);
      }
      case CANCEL_ORDER -> {
        requireOnly(payload, CANCEL_ORDER_FIELDS);
        requireText(payload, "clientOrderId");
      }
      case CANCEL_ALL -> requireOnly(payload, Set.of());
      case SET_POSITION_MODE -> {
        requireOnly(payload, SET_POSITION_MODE_FIELDS);
        requireText(payload, "positionMode");
      }
      case SET_MARGIN_MODE -> {
        requireOnly(payload, SET_MARGIN_MODE_FIELDS);
        requireText(payload, "symbol");
        requireText(payload, "marginMode");
      }
      case SET_LEVERAGE -> {
        requireOnly(payload, SET_LEVERAGE_FIELDS);
        requireText(payload, "symbol");
        if (!hasValue(payload, "leverage")) {
          throw invalid();
        }
        requireOptionalDecimal(payload, "leverage");
      }
    }
    return freezeMap(payload, new IdentityHashMap<>(), 0, new int[] {0});
  }

  private static void validateAttachedProtections(Map<String, ?> payload) {
    if (!payload.containsKey("attachedProtections")) {
      return;
    }
    Object requested = payload.get("attachedProtections");
    if (!(requested instanceof List<?> protections)
        || protections.size() > MAX_ATTACHED_PROTECTIONS) {
      throw invalid();
    }
    for (Object item : protections) {
      if (!(item instanceof Map<?, ?> raw)) {
        throw invalid();
      }
      LinkedHashMap<String, Object> protection = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : raw.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalid();
        }
        protection.put(key, entry.getValue());
      }
      requireOnly(protection, ATTACHED_PROTECTION_FIELDS);
      requireText(protection, "protectionType");
      requireDecimal(protection, "triggerPrice");
      requireText(protection, "triggerExecutionType");
      requireOptionalText(protection, "triggerPriceType");
      requireOptionalText(protection, "quantityUnit");
      requireOptionalDecimal(protection, "price");
      requireOptionalDecimal(protection, "quantity");
    }
  }

  private static void requireOnly(Map<String, ?> payload, Set<String> allowed) {
    if (!allowed.containsAll(payload.keySet())) {
      throw invalid();
    }
  }

  private static void requireText(Map<String, ?> payload, String key) {
    Object value = payload.get(key);
    if (!(value instanceof CharSequence text) || text.toString().isBlank()) {
      throw invalid();
    }
  }

  private static boolean hasValue(Map<String, ?> payload, String key) {
    return payload.containsKey(key) && payload.get(key) != null;
  }

  private static void requireOptionalText(Map<String, ?> payload, String key) {
    if (hasValue(payload, key)) {
      requireText(payload, key);
    }
  }

  private static void requireDecimal(Map<String, ?> payload, String key) {
    if (!hasValue(payload, key) || !isExactDecimal(payload.get(key))) {
      throw invalid();
    }
  }

  private static void requireOptionalDecimal(Map<String, ?> payload, String key) {
    if (hasValue(payload, key) && !isExactDecimal(payload.get(key))) {
      throw invalid();
    }
  }

  private static void requireOptionalBoolean(Map<String, ?> payload, String key) {
    if (hasValue(payload, key) && !(payload.get(key) instanceof Boolean)) {
      throw invalid();
    }
  }

  private static boolean isExactDecimal(Object value) {
    if (!(value instanceof Number) && !(value instanceof CharSequence)) {
      return false;
    }
    try {
      if ((value instanceof Double && !Double.isFinite((Double) value))
          || (value instanceof Float && !Float.isFinite((Float) value))) {
        return false;
      }
      String text = value instanceof BigDecimal decimal
          ? decimal.toPlainString()
          : value.toString();
      if (!text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) {
        return false;
      }
      new BigDecimal(text);
      return true;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static Map<String, Object> freezeMap(
      Map<?, ?> requested,
      IdentityHashMap<Object, Boolean> active,
      int depth,
      int[] nodes
  ) {
    enter(requested, active, depth, nodes);
    try {
      LinkedHashMap<String, Object> frozen = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : requested.entrySet()) {
        if (!(entry.getKey() instanceof String key) || key.isBlank()) {
          throw invalid();
        }
        frozen.put(key, freezeValue(entry.getValue(), active, depth + 1, nodes));
      }
      return Collections.unmodifiableMap(frozen);
    } finally {
      active.remove(requested);
    }
  }

  private static Object freezeValue(
      Object value,
      IdentityHashMap<Object, Boolean> active,
      int depth,
      int[] nodes
  ) {
    if (value == null || scalar(value)) {
      count(depth, nodes);
      return value instanceof CharSequence text ? text.toString() : value;
    }
    if (value instanceof Map<?, ?> map) {
      return freezeMap(map, active, depth, nodes);
    }
    if (value instanceof List<?> list) {
      enter(list, active, depth, nodes);
      try {
        ArrayList<Object> frozen = new ArrayList<>(list.size());
        for (Object item : list) {
          frozen.add(freezeValue(item, active, depth + 1, nodes));
        }
        return Collections.unmodifiableList(frozen);
      } finally {
        active.remove(list);
      }
    }
    throw invalid();
  }

  private static void enter(
      Object value,
      IdentityHashMap<Object, Boolean> active,
      int depth,
      int[] nodes
  ) {
    count(depth, nodes);
    if (active.put(value, Boolean.TRUE) != null) {
      throw invalid();
    }
  }

  private static void count(int depth, int[] nodes) {
    if (depth > MAX_NESTING_DEPTH || ++nodes[0] > MAX_PAYLOAD_NODES) {
      throw invalid();
    }
  }

  private static void rejectForbiddenKeys(Object value, IdentityHashMap<Object, Boolean> visited) {
    if (value == null || scalar(value) || visited.put(value, Boolean.TRUE) != null) {
      return;
    }
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key) || key.isBlank() || forbidden(key)) {
          throw invalid();
        }
        rejectForbiddenKeys(entry.getValue(), visited);
      }
      return;
    }
    if (value instanceof Iterable<?> iterable) {
      iterable.forEach(item -> rejectForbiddenKeys(item, visited));
      return;
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      for (int index = 0; index < length; index++) {
        rejectForbiddenKeys(Array.get(value, index), visited);
      }
    }
  }

  private static boolean scalar(Object value) {
    return value instanceof CharSequence
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>
        || value instanceof java.time.temporal.TemporalAccessor
        || value instanceof java.util.UUID;
  }

  private static boolean forbidden(String key) {
    String canonical = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return FORBIDDEN_KEY_PARTS.stream().anyMatch(canonical::contains);
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "VALIDATION_ACTION_INVALID",
        "Validation public action payload is outside the fixed schema");
  }
}
