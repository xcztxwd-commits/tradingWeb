package com.fxplatform.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Deterministic identity functions for demo DEPTH fills. */
public final class DemoFillIdentity {

  private static final String FILL_VERSION = "demo-depth-fill-v1";
  private static final String TRADE_VERSION = "demo-depth-trade-v1";
  private static final int FILL_IDENTITY_MAX_LENGTH = 64;

  private DemoFillIdentity() {
  }

  public static String forTick(UUID orderId, String tickIdentity, int fillOrdinal) {
    UUID requiredOrderId = requireOrderId(orderId);
    String requiredTickIdentity = requireText(tickIdentity, "tick identity");
    if (fillOrdinal < 0) {
      throw new IllegalArgumentException("fill ordinal must not be negative");
    }
    return sha256(FILL_VERSION + "|" + requiredOrderId + "|" + requiredTickIdentity + "|"
        + fillOrdinal);
  }

  public static String forSnapshot(
      UUID orderId,
      ExecutableMarketSnapshot snapshot,
      int fillOrdinal
  ) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    String tickIdentity = String.join("|",
        encodeComponent(normalize(snapshot.platformSymbol(), "platform symbol")),
        encodeComponent(normalize(enumName(snapshot.productType(), "product type"), "product type")),
        encodeComponent(normalize(enumName(snapshot.sourceMode(), "source mode"), "source mode")),
        encodeComponent(normalize(snapshot.providerCode(), "provider code")),
        encodeComponent(normalize(snapshot.providerSymbol(), "provider symbol")),
        encodeComponent(requireAsOf(snapshot.asOf()).toString()));
    return forTick(orderId, tickIdentity, fillOrdinal);
  }

  public static UUID tradeId(UUID orderId, String fillIdentity) {
    UUID requiredOrderId = requireOrderId(orderId);
    String requiredFillIdentity = requireNonBlank(fillIdentity, "fill identity");
    if (requiredFillIdentity.length() > FILL_IDENTITY_MAX_LENGTH) {
      throw new IllegalArgumentException("fill identity must not exceed 64 characters");
    }
    return UUID.nameUUIDFromBytes((TRADE_VERSION + "|" + requiredOrderId + "|"
        + requiredFillIdentity).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID requireOrderId(UUID orderId) {
    if (orderId == null) {
      throw new IllegalArgumentException("order id must not be null");
    }
    return orderId;
  }

  private static String requireText(String value, String name) {
    return requireNonBlank(value, name).trim();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String normalize(String value, String name) {
    return requireText(value, name).toUpperCase(Locale.ROOT);
  }

  private static String encodeComponent(String value) {
    return value.replace("\\", "\\\\").replace("|", "\\|");
  }

  private static String enumName(Enum<?> value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value.name();
  }

  private static Instant requireAsOf(Instant asOf) {
    if (asOf == null) {
      throw new IllegalArgumentException("as of must not be null");
    }
    return asOf;
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
      for (byte valueByte : digest) {
        hexadecimal.append(String.format(Locale.ROOT, "%02x", valueByte));
      }
      return hexadecimal.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
