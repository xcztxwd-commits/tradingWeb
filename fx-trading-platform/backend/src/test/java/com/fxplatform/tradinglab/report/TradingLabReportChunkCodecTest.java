package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class TradingLabReportChunkCodecTest {

  private static final int CHUNK_BYTES = 4096;
  private final TradingLabReportChunkCodec codec =
      new TradingLabReportChunkCodec(CHUNK_BYTES);

  @Test
  void createsAnIndependentGzipMemberWithCanonicalPlaintextChecksum() {
    byte[] plain = "{\"price\":\"60000.00\",\"symbol\":\"BTCUSDT\"}\n"
        .getBytes(StandardCharsets.UTF_8);

    TradingLabEncodedChunk encoded = codec.encode(plain);

    assertThat(encoded.encoding()).isEqualTo("GZIP");
    assertThat(encoded.uncompressedBytes()).isEqualTo(plain.length);
    assertThat(encoded.compressedBytes()).isEqualTo(encoded.payload().length);
    assertThat(encoded.checksum()).matches("sha256:[0-9a-f]{64}");
    assertThat(codec.decodeAndVerify(entity(encoded, 0L))).containsExactly(plain);
  }

  @Test
  void encodesOnlyTheRequestedSliceIntoAnIndependentExactPayload() {
    byte[] plain = "{\"slice\":\"value\"}\n".getBytes(StandardCharsets.UTF_8);
    byte[] container = new byte[plain.length + 8];
    Arrays.fill(container, (byte) 0xff);
    System.arraycopy(plain, 0, container, 4, plain.length);

    TradingLabEncodedChunk encoded = codec.encode(container, 4, plain.length);
    TradingLabEncodedChunk whole = codec.encode(plain);
    Arrays.fill(container, 4, 4 + plain.length, (byte) 'x');

    assertThat(encoded.uncompressedBytes()).isEqualTo(plain.length);
    assertThat(encoded.checksum()).isEqualTo(whole.checksum());
    assertThat(codec.decodeAndVerify(entity(encoded, 0L))).containsExactly(plain);
    assertThat(encoded.payload()).isNotSameAs(container);
  }

  @Test
  void replayIdentityUsesVerifiedCanonicalBytesNotTheGzipHeader() {
    byte[] plain = "{\"value\":\"A\"}\n".getBytes(StandardCharsets.UTF_8);
    TradingLabEncodedChunk encoded = codec.encode(plain);
    TradingLabReportChunkEntity alternateHeader = entity(encoded, 0L);
    byte[] payload = Arrays.copyOf(
        alternateHeader.getPayload(), alternateHeader.getPayload().length);
    payload[4] = 1;
    payload[8] ^= 1;
    alternateHeader.setPayload(payload);

    assertThat(codec.decodeAndVerifyMatches(
        alternateHeader, plain, 0, plain.length)).isTrue();

    byte[] different = "{\"value\":\"B\"}\n".getBytes(StandardCharsets.UTF_8);
    assertThat(different).hasSameSizeAs(plain);
    assertThat(codec.decodeAndVerifyMatches(
        alternateHeader, different, 0, different.length)).isFalse();
  }

  @Test
  void independentlyDecodesAdjacentChunks() {
    TradingLabEncodedChunk first = codec.encode("first\n".getBytes(StandardCharsets.UTF_8));
    TradingLabEncodedChunk second = codec.encode("second\n".getBytes(StandardCharsets.UTF_8));

    assertThat(codec.decodeAndVerify(entity(first, 0L)))
        .asString(StandardCharsets.UTF_8)
        .isEqualTo("first\n");
    assertThat(codec.decodeAndVerify(entity(second, 1L)))
        .asString(StandardCharsets.UTF_8)
        .isEqualTo("second\n");
  }

  @Test
  void rejectsEmptyOrOversizedPlaintextBeforeCompression() {
    assertThatThrownBy(() -> codec.encode(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.encode(new byte[CHUNK_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChecksumLengthEncodingAndPayloadTamperingBeforeReturningBytes() {
    byte[] plain = "{\"safe\":true}\n".getBytes(StandardCharsets.UTF_8);
    TradingLabEncodedChunk encoded = codec.encode(plain);

    TradingLabReportChunkEntity checksum = entity(encoded, 0L);
    checksum.setChecksum("sha256:" + "0".repeat(64));
    assertThatThrownBy(() -> codec.decodeAndVerify(checksum))
        .isInstanceOf(TradingLabReportCorruptionException.class);

    TradingLabReportChunkEntity rawLength = entity(encoded, 0L);
    rawLength.setUncompressedBytes(rawLength.getUncompressedBytes() + 1);
    assertThatThrownBy(() -> codec.decodeAndVerify(rawLength))
        .isInstanceOf(TradingLabReportCorruptionException.class);

    TradingLabReportChunkEntity compressedLength = entity(encoded, 0L);
    compressedLength.setCompressedBytes(compressedLength.getCompressedBytes() + 1);
    assertThatThrownBy(() -> codec.decodeAndVerify(compressedLength))
        .isInstanceOf(TradingLabReportCorruptionException.class);

    TradingLabReportChunkEntity encoding = entity(encoded, 0L);
    encoding.setEncoding("IDENTITY");
    assertThatThrownBy(() -> codec.decodeAndVerify(encoding))
        .isInstanceOf(TradingLabReportCorruptionException.class);

    TradingLabReportChunkEntity payload = entity(encoded, 0L);
    byte[] tampered = Arrays.copyOf(payload.getPayload(), payload.getPayload().length);
    tampered[tampered.length / 2] ^= 1;
    payload.setPayload(tampered);
    assertThatThrownBy(() -> codec.decodeAndVerify(payload))
        .isInstanceOf(TradingLabReportCorruptionException.class);
  }

  @Test
  void rejectsDeclaredGzipBombSizeWithoutAllocatingIt() {
    TradingLabEncodedChunk encoded = codec.encode("small".getBytes(StandardCharsets.UTF_8));
    TradingLabReportChunkEntity bomb = entity(encoded, 0L);
    bomb.setUncompressedBytes((long) CHUNK_BYTES + 1);

    assertThatThrownBy(() -> codec.decodeAndVerify(bomb))
        .isInstanceOf(TradingLabReportCorruptionException.class);
  }

  @Test
  void boundsActualInflationEvenWhenTheDeclaredSizeIsAllowed() throws IOException {
    byte[] expanded = new byte[CHUNK_BYTES + 1];
    Arrays.fill(expanded, (byte) 'a');
    byte[] payload = gzip(expanded);
    TradingLabReportChunkEntity bomb = entity(
        codec.encode("small".getBytes(StandardCharsets.UTF_8)),
        0L);
    bomb.setUncompressedBytes((long) CHUNK_BYTES);
    bomb.setCompressedBytes((long) payload.length);
    bomb.setPayload(payload);
    bomb.setChecksum(checksum(expanded));

    assertThatThrownBy(() -> codec.decodeAndVerify(bomb))
        .isInstanceOf(TradingLabReportCorruptionException.class);
  }

  @Test
  void rejectsInvalidUtf8OnBothEncodeAndDecode() throws IOException {
    byte[] invalidUtf8 = {(byte) 0xc3, 0x28};

    assertThatThrownBy(() -> codec.encode(invalidUtf8))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] payload = gzip(invalidUtf8);
    TradingLabReportChunkEntity persisted = entity(
        codec.encode("ok".getBytes(StandardCharsets.UTF_8)),
        0L);
    persisted.setUncompressedBytes((long) invalidUtf8.length);
    persisted.setCompressedBytes((long) payload.length);
    persisted.setPayload(payload);
    persisted.setChecksum(checksum(invalidUtf8));

    assertThatThrownBy(() -> codec.decodeAndVerify(persisted))
        .isInstanceOf(TradingLabReportCorruptionException.class);
  }

  @Test
  void rejectsCompressedExpansionAndMultipleGzipMembers() throws IOException {
    TradingLabReportChunkEntity excessiveCompressed = entity(
        codec.encode("x".getBytes(StandardCharsets.UTF_8)),
        0L);
    byte[] oversizedPayload = new byte[1026];
    excessiveCompressed.setPayload(oversizedPayload);
    excessiveCompressed.setCompressedBytes((long) oversizedPayload.length);
    assertThatThrownBy(() -> codec.decodeAndVerify(excessiveCompressed))
        .isInstanceOf(TradingLabReportCorruptionException.class);

    byte[] first = gzip("first".getBytes(StandardCharsets.UTF_8));
    byte[] second = gzip("second".getBytes(StandardCharsets.UTF_8));
    byte[] concatenated = new byte[first.length + second.length];
    System.arraycopy(first, 0, concatenated, 0, first.length);
    System.arraycopy(second, 0, concatenated, first.length, second.length);
    byte[] combinedPlaintext = "firstsecond".getBytes(StandardCharsets.UTF_8);
    TradingLabReportChunkEntity multipleMembers = entity(
        codec.encode("placeholder".getBytes(StandardCharsets.UTF_8)),
        0L);
    multipleMembers.setUncompressedBytes((long) combinedPlaintext.length);
    multipleMembers.setCompressedBytes((long) concatenated.length);
    multipleMembers.setPayload(concatenated);
    multipleMembers.setChecksum(checksum(combinedPlaintext));

    assertThatThrownBy(() -> codec.decodeAndVerify(multipleMembers))
        .isInstanceOf(TradingLabReportCorruptionException.class);
  }

  @Test
  void corruptionErrorsNeverEchoThePersistedPayload() {
    String canary = "do-not-echo-this-payload";
    TradingLabReportChunkEntity corrupt = entity(
        codec.encode("safe".getBytes(StandardCharsets.UTF_8)),
        0L);
    byte[] payload = canary.getBytes(StandardCharsets.UTF_8);
    corrupt.setPayload(payload);
    corrupt.setCompressedBytes((long) payload.length);

    assertThatThrownBy(() -> codec.decodeAndVerify(corrupt))
        .isInstanceOf(TradingLabReportCorruptionException.class)
        .hasMessageNotContaining(canary);
  }

  private static TradingLabReportChunkEntity entity(
      TradingLabEncodedChunk encoded,
      long sequence
  ) {
    TradingLabReportChunkEntity entity = new TradingLabReportChunkEntity();
    entity.setId(UUID.randomUUID());
    entity.setReportId(UUID.randomUUID());
    entity.setSection(TradingLabReportSection.MARKET_TICKS.name());
    entity.setSequence(sequence);
    entity.setEncoding(encoded.encoding());
    entity.setUncompressedBytes(encoded.uncompressedBytes());
    entity.setCompressedBytes(encoded.compressedBytes());
    entity.setPayload(Arrays.copyOf(encoded.payload(), encoded.payload().length));
    entity.setChecksum(encoded.checksum());
    return entity;
  }

  private static byte[] gzip(byte[] plain) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(plain);
    }
    return output.toByteArray();
  }

  private static String checksum(byte[] plain) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(plain));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
