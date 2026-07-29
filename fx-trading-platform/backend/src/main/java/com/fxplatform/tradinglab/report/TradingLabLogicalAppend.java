package com.fxplatform.tradinglab.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class TradingLabLogicalAppend {

  private final Long sourceSequence;
  private final byte[] canonicalBytes;
  private final String canonicalChecksum;

  public TradingLabLogicalAppend(
      Long sourceSequence,
      byte[] canonicalBytes,
      String canonicalChecksum
  ) {
    requireSourceSequence(sourceSequence);
    this.sourceSequence = sourceSequence;
    this.canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
    requireNotEmpty(this.canonicalBytes);
    String suppliedChecksum = Objects.requireNonNull(
        canonicalChecksum, "canonicalChecksum");
    if (!checksum(this.canonicalBytes).equals(suppliedChecksum)) {
      throw new IllegalArgumentException("Trading Lab logical append checksum is invalid");
    }
    this.canonicalChecksum = suppliedChecksum;
  }

  TradingLabLogicalAppend(
      Long sourceSequence,
      TradingLabCanonicalValue canonical
  ) {
    requireSourceSequence(sourceSequence);
    TradingLabCanonicalValue trusted = Objects.requireNonNull(canonical, "canonical");
    this.sourceSequence = sourceSequence;
    this.canonicalBytes = trusted.internalBytes();
    requireNotEmpty(this.canonicalBytes);
    this.canonicalChecksum = trusted.checksum();
  }

  public Long sourceSequence() {
    return sourceSequence;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  byte[] internalBytes() {
    return canonicalBytes;
  }

  public long canonicalByteCount() {
    return canonicalBytes.length;
  }

  public String canonicalChecksum() {
    return canonicalChecksum;
  }

  public String utf8() {
    return new String(canonicalBytes, StandardCharsets.UTF_8);
  }

  boolean sameCanonical(TradingLabLogicalAppend other) {
    return other != null && Arrays.equals(canonicalBytes, other.canonicalBytes);
  }

  private static void requireSourceSequence(Long sourceSequence) {
    if (sourceSequence != null && sourceSequence < -1L) {
      throw new IllegalArgumentException("Trading Lab source sequence is invalid");
    }
  }

  private static void requireNotEmpty(byte[] value) {
    if (value.length == 0) {
      throw new IllegalArgumentException("Trading Lab logical append is empty");
    }
  }

  private static String checksum(byte[] value) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
