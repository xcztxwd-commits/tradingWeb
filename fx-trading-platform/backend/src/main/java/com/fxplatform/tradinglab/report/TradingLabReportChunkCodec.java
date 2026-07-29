package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

public final class TradingLabReportChunkCodec {

  private static final String ENCODING = "GZIP";
  private static final int COMPRESSION_OVERHEAD_BYTES = 1024;
  private static final int INFLATE_BUFFER_BYTES = 8192;
  private static final int UTF8_VALIDATION_BUFFER_CHARS = 1024;
  private static final Pattern CHECKSUM_PATTERN =
      Pattern.compile("sha256:[0-9a-f]{64}");

  private static final int GZIP_FLAG_HEADER_CRC = 0x02;
  private static final int GZIP_FLAG_EXTRA = 0x04;
  private static final int GZIP_FLAG_NAME = 0x08;
  private static final int GZIP_FLAG_COMMENT = 0x10;
  private static final int GZIP_FLAG_RESERVED = 0xe0;

  private final int chunkBytes;

  public TradingLabReportChunkCodec(int chunkBytes) {
    if (chunkBytes <= 0) {
      throw new IllegalArgumentException("Chunk byte limit must be positive");
    }
    this.chunkBytes = chunkBytes;
  }

  public int chunkBytes() {
    return chunkBytes;
  }

  public TradingLabEncodedChunk encode(byte[] plain) {
    Objects.requireNonNull(plain, "plain");
    return encode(plain, 0, plain.length);
  }

  TradingLabEncodedChunk encode(byte[] plain, int offset, int length) {
    Objects.requireNonNull(plain, "plain");
    Objects.checkFromIndexSize(offset, length, plain.length);
    if (length == 0 || length > chunkBytes) {
      throw new IllegalArgumentException("Plaintext must fit in one non-empty chunk");
    }
    requireStrictUtf8ForEncode(plain, offset, length);

    byte[] payload = gzipExact(plain, offset, length);
    return new TradingLabEncodedChunk(
        ENCODING,
        length,
        payload.length,
        payload,
        checksum(plain, offset, length));
  }

  public byte[] decodeAndVerify(TradingLabReportChunkEntity chunk) {
    if (chunk == null || !ENCODING.equals(chunk.getEncoding())) {
      throw corrupt();
    }

    byte[] payload = chunk.getPayload();
    Long declaredRaw = chunk.getUncompressedBytes();
    Long declaredCompressed = chunk.getCompressedBytes();
    String declaredChecksum = chunk.getChecksum();
    if (payload == null
        || declaredRaw == null
        || declaredCompressed == null
        || declaredRaw < 1
        || declaredRaw > chunkBytes
        || declaredCompressed < 1
        || declaredCompressed != payload.length
        || declaredCompressed > declaredRaw + COMPRESSION_OVERHEAD_BYTES
        || declaredChecksum == null
        || !CHECKSUM_PATTERN.matcher(declaredChecksum).matches()) {
      throw corrupt();
    }

    byte[] plain = inflateSingleMember(payload, declaredRaw.intValue());
    if (!declaredChecksum.equals(checksum(plain))) {
      throw corrupt();
    }
    requireStrictUtf8ForDecode(plain);
    return plain;
  }

  boolean decodeAndVerifyMatches(
      TradingLabReportChunkEntity chunk,
      byte[] expected,
      int expectedOffset,
      int expectedLength
  ) {
    Objects.requireNonNull(expected, "expected");
    Objects.checkFromIndexSize(expectedOffset, expectedLength, expected.length);
    if (chunk == null || !ENCODING.equals(chunk.getEncoding())) {
      throw corrupt();
    }

    byte[] payload = chunk.getPayload();
    Long declaredRaw = chunk.getUncompressedBytes();
    Long declaredCompressed = chunk.getCompressedBytes();
    String declaredChecksum = chunk.getChecksum();
    if (payload == null
        || declaredRaw == null
        || declaredCompressed == null
        || declaredRaw < 1
        || declaredRaw > chunkBytes
        || declaredCompressed < 1
        || declaredCompressed != payload.length
        || declaredCompressed > declaredRaw + COMPRESSION_OVERHEAD_BYTES
        || declaredChecksum == null
        || !CHECKSUM_PATTERN.matcher(declaredChecksum).matches()) {
      throw corrupt();
    }

    int expectedBytes = declaredRaw.intValue();
    int deflateOffset = parseGzipHeader(payload);
    Inflater inflater = new Inflater(true);
    byte[] buffer = new byte[Math.min(INFLATE_BUFFER_BYTES, expectedBytes)];
    byte[] overflowProbe = new byte[1];
    CRC32 crc = new CRC32();
    MessageDigest digest = sha256Digest();
    StrictUtf8Validator utf8 = new StrictUtf8Validator();
    int total = 0;
    boolean matches = expectedLength == expectedBytes;

    try {
      inflater.setInput(payload, deflateOffset, payload.length - deflateOffset);
      while (!inflater.finished()) {
        int remaining = expectedBytes - total;
        int read = remaining > 0
            ? inflater.inflate(buffer, 0, Math.min(buffer.length, remaining))
            : inflater.inflate(overflowProbe);
        if (read > 0) {
          if (read > remaining) {
            throw corrupt();
          }
          crc.update(buffer, 0, read);
          digest.update(buffer, 0, read);
          utf8.accept(buffer, 0, read);
          for (int index = 0; index < read; index++) {
            int expectedIndex = total + index;
            if (expectedIndex >= expectedLength
                || buffer[index] != expected[expectedOffset + expectedIndex]) {
              matches = false;
            }
          }
          total += read;
        } else if (inflater.finished()) {
          break;
        } else if (inflater.needsInput() || inflater.needsDictionary()) {
          throw corrupt();
        } else {
          throw corrupt();
        }
      }

      int trailerBytes = inflater.getRemaining();
      if (trailerBytes != 8 || total != expectedBytes) {
        throw corrupt();
      }
      int trailerOffset = payload.length - trailerBytes;
      long expectedCrc = readLittleEndianUnsignedInt(payload, trailerOffset);
      long expectedSize = readLittleEndianUnsignedInt(payload, trailerOffset + 4);
      if (expectedCrc != crc.getValue()
          || expectedSize != Integer.toUnsignedLong(total)
          || !declaredChecksum.equals(
              "sha256:" + HexFormat.of().formatHex(digest.digest()))) {
        throw corrupt();
      }
      utf8.finish();
      return matches;
    } catch (DataFormatException exception) {
      throw corrupt();
    } finally {
      inflater.end();
    }
  }

  private static byte[] gzipExact(byte[] plain, int offset, int length) {
    CountingOutputStream counter = new CountingOutputStream();
    writeGzip(plain, offset, length, counter);
    long compressedBytes = counter.count();
    if (compressedBytes < 1L
        || compressedBytes > (long) length + COMPRESSION_OVERHEAD_BYTES
        || compressedBytes > Integer.MAX_VALUE) {
      throw new IllegalStateException("GZIP output exceeds the chunk protocol bound");
    }

    byte[] payload = new byte[(int) compressedBytes];
    ExactByteArrayOutputStream output = new ExactByteArrayOutputStream(payload);
    writeGzip(plain, offset, length, output);
    if (output.count() != payload.length) {
      throw new IllegalStateException("GZIP output length changed between passes");
    }
    return payload;
  }

  private static void writeGzip(
      byte[] plain,
      int offset,
      int length,
      OutputStream output
  ) {
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(plain, offset, length);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode GZIP chunk", exception);
    }
  }

  private static byte[] inflateSingleMember(byte[] payload, int expectedBytes) {
    int deflateOffset = parseGzipHeader(payload);
    Inflater inflater = new Inflater(true);
    byte[] plain = new byte[expectedBytes];
    byte[] overflowProbe = new byte[1];
    CRC32 crc = new CRC32();
    int total = 0;

    try {
      inflater.setInput(payload, deflateOffset, payload.length - deflateOffset);
      while (!inflater.finished()) {
        int remaining = expectedBytes - total;
        int read = remaining > 0
            ? inflater.inflate(plain, total, remaining)
            : inflater.inflate(overflowProbe);
        if (read > 0) {
          if (read > remaining) {
            throw corrupt();
          }
          crc.update(plain, total, read);
          total += read;
        } else if (inflater.finished()) {
          break;
        } else if (inflater.needsInput() || inflater.needsDictionary()) {
          throw corrupt();
        } else {
          throw corrupt();
        }
      }

      int trailerBytes = inflater.getRemaining();
      if (trailerBytes != 8 || total != expectedBytes) {
        throw corrupt();
      }
      int trailerOffset = payload.length - trailerBytes;
      long expectedCrc = readLittleEndianUnsignedInt(payload, trailerOffset);
      long expectedSize = readLittleEndianUnsignedInt(payload, trailerOffset + 4);
      if (expectedCrc != crc.getValue() || expectedSize != Integer.toUnsignedLong(total)) {
        throw corrupt();
      }
      return plain;
    } catch (DataFormatException exception) {
      throw corrupt();
    } finally {
      inflater.end();
    }
  }

  private static int parseGzipHeader(byte[] payload) {
    requireAvailable(payload, 0, 10);
    if ((payload[0] & 0xff) != 0x1f
        || (payload[1] & 0xff) != 0x8b
        || (payload[2] & 0xff) != 8) {
      throw corrupt();
    }

    int flags = payload[3] & 0xff;
    if ((flags & GZIP_FLAG_RESERVED) != 0) {
      throw corrupt();
    }

    int offset = 10;
    if ((flags & GZIP_FLAG_EXTRA) != 0) {
      requireAvailable(payload, offset, 2);
      int extraLength = (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
      offset += 2;
      requireAvailable(payload, offset, extraLength);
      offset += extraLength;
    }
    if ((flags & GZIP_FLAG_NAME) != 0) {
      offset = skipZeroTerminatedField(payload, offset);
    }
    if ((flags & GZIP_FLAG_COMMENT) != 0) {
      offset = skipZeroTerminatedField(payload, offset);
    }
    if ((flags & GZIP_FLAG_HEADER_CRC) != 0) {
      requireAvailable(payload, offset, 2);
      CRC32 headerCrc = new CRC32();
      headerCrc.update(payload, 0, offset);
      int expectedHeaderCrc = (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
      if ((headerCrc.getValue() & 0xffff) != expectedHeaderCrc) {
        throw corrupt();
      }
      offset += 2;
    }

    requireAvailable(payload, offset, 1);
    return offset;
  }

  private static int skipZeroTerminatedField(byte[] payload, int offset) {
    while (offset < payload.length && payload[offset] != 0) {
      offset++;
    }
    if (offset >= payload.length) {
      throw corrupt();
    }
    return offset + 1;
  }

  private static void requireAvailable(byte[] payload, int offset, int length) {
    if (offset < 0 || length < 0 || offset > payload.length - length) {
      throw corrupt();
    }
  }

  private static long readLittleEndianUnsignedInt(byte[] bytes, int offset) {
    requireAvailable(bytes, offset, 4);
    return (bytes[offset] & 0xffL)
        | ((bytes[offset + 1] & 0xffL) << 8)
        | ((bytes[offset + 2] & 0xffL) << 16)
        | ((bytes[offset + 3] & 0xffL) << 24);
  }

  private static void requireStrictUtf8ForEncode(
      byte[] bytes,
      int offset,
      int length
  ) {
    try {
      decodeStrictUtf8(bytes, offset, length);
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Plaintext must be strict UTF-8");
    }
  }

  private static void requireStrictUtf8ForDecode(byte[] bytes) {
    try {
      decodeStrictUtf8(bytes, 0, bytes.length);
    } catch (CharacterCodingException exception) {
      throw corrupt();
    }
  }

  private static void decodeStrictUtf8(
      byte[] bytes,
      int offset,
      int length
  ) throws CharacterCodingException {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer input = ByteBuffer.wrap(bytes, offset, length);
    CharBuffer output = CharBuffer.allocate(UTF8_VALIDATION_BUFFER_CHARS);

    while (true) {
      CoderResult result = decoder.decode(input, output, true);
      if (result.isError()) {
        result.throwException();
      }
      if (result.isUnderflow()) {
        break;
      }
      output.clear();
    }

    output.clear();
    while (true) {
      CoderResult result = decoder.flush(output);
      if (result.isError()) {
        result.throwException();
      }
      if (result.isUnderflow()) {
        return;
      }
      output.clear();
    }
  }

  private static String checksum(byte[] plain) {
    return checksum(plain, 0, plain.length);
  }

  private static String checksum(byte[] plain, int offset, int length) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(plain, offset, length);
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static final class StrictUtf8Validator {

    private int continuationBytes;
    private int nextMinimum = 0x80;
    private int nextMaximum = 0xbf;

    private void accept(byte[] bytes, int offset, int length) {
      for (int index = offset; index < offset + length; index++) {
        accept(bytes[index] & 0xff);
      }
    }

    private void accept(int value) {
      if (continuationBytes > 0) {
        if (value < nextMinimum || value > nextMaximum) {
          throw corrupt();
        }
        continuationBytes--;
        nextMinimum = 0x80;
        nextMaximum = 0xbf;
        return;
      }
      if (value <= 0x7f) {
        return;
      }
      if (value >= 0xc2 && value <= 0xdf) {
        continuationBytes = 1;
        return;
      }
      if (value == 0xe0) {
        continuationBytes = 2;
        nextMinimum = 0xa0;
        return;
      }
      if ((value >= 0xe1 && value <= 0xec)
          || (value >= 0xee && value <= 0xef)) {
        continuationBytes = 2;
        return;
      }
      if (value == 0xed) {
        continuationBytes = 2;
        nextMaximum = 0x9f;
        return;
      }
      if (value == 0xf0) {
        continuationBytes = 3;
        nextMinimum = 0x90;
        return;
      }
      if (value >= 0xf1 && value <= 0xf3) {
        continuationBytes = 3;
        return;
      }
      if (value == 0xf4) {
        continuationBytes = 3;
        nextMaximum = 0x8f;
        return;
      }
      throw corrupt();
    }

    private void finish() {
      if (continuationBytes != 0) {
        throw corrupt();
      }
    }
  }

  private static final class CountingOutputStream extends OutputStream {

    private long count;

    @Override
    public void write(int value) {
      count = Math.addExact(count, 1L);
    }

    @Override
    public void write(byte[] value, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, value.length);
      count = Math.addExact(count, length);
    }

    private long count() {
      return count;
    }
  }

  private static final class ExactByteArrayOutputStream extends OutputStream {

    private final byte[] output;
    private int count;

    private ExactByteArrayOutputStream(byte[] output) {
      this.output = output;
    }

    @Override
    public void write(int value) throws IOException {
      if (count >= output.length) {
        throw new IOException("GZIP output exceeded its counted length");
      }
      output[count++] = (byte) value;
    }

    @Override
    public void write(byte[] value, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, value.length);
      if (length > output.length - count) {
        throw new IOException("GZIP output exceeded its counted length");
      }
      System.arraycopy(value, offset, output, count, length);
      count += length;
    }

    private int count() {
      return count;
    }
  }

  private static TradingLabReportCorruptionException corrupt() {
    return new TradingLabReportCorruptionException();
  }
}
