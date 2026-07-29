package com.fxplatform.tradinglab.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TradingLabCredentialMetadata {

  private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

  private final String credentialType;
  private final UUID actorId;
  private final List<String> scopes;
  private final Instant expiresAt;
  private final String fingerprint;

  private TradingLabCredentialMetadata(
      String credentialType,
      UUID actorId,
      List<String> scopes,
      Instant expiresAt,
      String fingerprint
  ) {
    this.credentialType = credentialType;
    this.actorId = actorId;
    this.scopes = scopes;
    this.expiresAt = expiresAt;
    this.fingerprint = fingerprint;
  }

  public static TradingLabCredentialMetadata bearer(
      UUID actorId,
      List<String> scopes,
      Instant expiresAt,
      String bearer
  ) {
    return highEntropyCredential("BEARER", actorId, scopes, expiresAt, bearer);
  }

  public static TradingLabCredentialMetadata session(
      UUID actorId,
      List<String> scopes,
      Instant expiresAt,
      String session
  ) {
    return highEntropyCredential("SESSION", actorId, scopes, expiresAt, session);
  }

  public String fingerprint() {
    return fingerprint;
  }

  public Map<String, Object> toSafeMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("credentialType", credentialType);
    value.put("actorId", actorId.toString());
    value.put("scopes", scopes);
    value.put("expiresAt", expiresAt.toString());
    value.put("fingerprint", fingerprint);
    return value;
  }

  private static TradingLabCredentialMetadata highEntropyCredential(
      String credentialType,
      UUID actorId,
      List<String> scopes,
      Instant expiresAt,
      String credential
  ) {
    if (actorId == null || expiresAt == null || credential == null) {
      throw unsafeCredential();
    }
    byte[] credentialBytes = credential.getBytes(StandardCharsets.UTF_8);
    long distinctCodePoints = credential.codePoints().distinct().limit(8).count();
    if (credentialBytes.length < 32
        || credential.isBlank()
        || credential.codePoints().anyMatch(Character::isWhitespace)
        || distinctCodePoints < 8) {
      throw unsafeCredential();
    }

    List<String> safeScopes;
    try {
      safeScopes = scopes == null ? List.of() : List.copyOf(scopes);
    } catch (RuntimeException exception) {
      throw unsafeCredential();
    }
    if (safeScopes.stream().anyMatch(scope -> scope == null || !SAFE_SCOPE.matcher(scope).matches())) {
      throw unsafeCredential();
    }

    String fingerprint = "sha256:" + sha256(credentialBytes);
    return new TradingLabCredentialMetadata(
        credentialType,
        actorId,
        safeScopes,
        expiresAt,
        fingerprint);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private static IllegalArgumentException unsafeCredential() {
    return new IllegalArgumentException("Unsafe Trading Lab credential metadata");
  }
}
