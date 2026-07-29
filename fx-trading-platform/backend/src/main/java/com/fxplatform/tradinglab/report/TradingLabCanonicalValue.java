package com.fxplatform.tradinglab.report;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class TradingLabCanonicalValue {

  private final byte[] bytes;
  private final String checksum;

  TradingLabCanonicalValue(byte[] bytes, String checksum) {
    this.bytes = Objects.requireNonNull(bytes, "bytes");
    this.checksum = Objects.requireNonNull(checksum, "checksum");
  }

  public byte[] bytes() {
    return bytes.clone();
  }

  byte[] internalBytes() {
    return bytes;
  }

  public int byteLength() {
    return bytes.length;
  }

  public String checksum() {
    return checksum;
  }

  public String utf8() {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  boolean sameBytes(TradingLabCanonicalValue other) {
    return other != null && Arrays.equals(bytes, other.bytes);
  }
}
