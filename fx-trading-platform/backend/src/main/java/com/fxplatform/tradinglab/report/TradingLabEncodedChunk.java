package com.fxplatform.tradinglab.report;

import java.util.Objects;

public record TradingLabEncodedChunk(
    String encoding,
    long uncompressedBytes,
    long compressedBytes,
    byte[] payload,
    String checksum
) {

  public TradingLabEncodedChunk {
    Objects.requireNonNull(encoding, "encoding");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(checksum, "checksum");
    if (uncompressedBytes <= 0) {
      throw new IllegalArgumentException("Uncompressed byte count must be positive");
    }
    if (compressedBytes != payload.length) {
      throw new IllegalArgumentException("Compressed byte count must match payload length");
    }
  }
}
