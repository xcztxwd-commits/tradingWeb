package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TradingLabReportStreamerLargeTest {

  private static final UUID REPORT_ID =
      UUID.fromString("e2076fd3-a26e-4faf-b340-63379c934d9b");
  private static final int LARGE_CHUNK_BYTES = 256 * 1024;
  private static final int LARGE_CHUNK_COUNT = 400;
  private static final long ONE_HUNDRED_MEBIBYTES = 100L * 1024 * 1024;
  private static final int SINGLE_TOKEN_CHUNK_COUNT = 64;
  private static final long SIXTEEN_MEBIBYTES = 16L * 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void streamsOneHundredMebibytesFromLazyCursorsWithChunkBoundedState() throws Exception {
    byte[] record = fullChunkJsonRecord(LARGE_CHUNK_BYTES);
    TradingLabEncodedChunk template = new TradingLabReportChunkCodec(LARGE_CHUNK_BYTES)
        .encode(record);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    LazyChunkSource source = new LazyChunkSource(
        terminalReport(LARGE_CHUNK_BYTES, LARGE_CHUNK_COUNT, template),
        template,
        LARGE_CHUNK_COUNT,
        transactions::isActive);
    TradingLabReportStreamer streamer = new TradingLabReportStreamer(
        source,
        new TradingLabReportChunkCodec(LARGE_CHUNK_BYTES),
        transactions,
        JSON);
    CountingNullOutputStream output = new CountingNullOutputStream();

    streamer.stream(REPORT_ID, output);

    assertThat(output.bytesWritten())
        .isEqualTo(source.report().getUncompressedBytes())
        .isGreaterThanOrEqualTo(ONE_HUNDRED_MEBIBYTES);
    assertThat(source.generatedChunks()).isEqualTo(2 * LARGE_CHUNK_COUNT);
    assertThat(source.maxSimultaneouslyOpenCursors()).isOne();
    assertThat(source.maxOutstandingChunks()).isOne();
    assertThat(source.peakProtocolBytes())
        .isEqualTo(LARGE_CHUNK_BYTES + template.payload().length)
        .isLessThanOrEqualTo(2L * LARGE_CHUNK_BYTES + 1024L);
    assertThat(output.largestWrite()).isLessThanOrEqualTo(LARGE_CHUNK_BYTES);
    assertThat(source.allCursorsClosed()).isTrue();
    assertThat(source.everyAccessWasInTransaction()).isTrue();
    assertThat(transactions.begins()).isOne();
    assertThat(transactions.commits()).isOne();
    assertThat(transactions.rollbacks()).isZero();
    assertThat(transactions.isActive()).isFalse();
  }

  @Test
  void streamsNearSixteenMebibyteStringTokenFromLazyChunksWithBoundedState()
      throws Exception {
    TradingLabReportChunkCodec codec = new TradingLabReportChunkCodec(LARGE_CHUNK_BYTES);
    TradingLabEncodedChunk first = singleTokenChunk(codec, true, false);
    TradingLabEncodedChunk middle = singleTokenChunk(codec, false, false);
    TradingLabEncodedChunk last = singleTokenChunk(codec, false, true);
    long compressedBytes = Math.addExact(
        Math.addExact(first.compressedBytes(), last.compressedBytes()),
        Math.multiplyExact(
            (long) middle.compressedBytes(),
            SINGLE_TOKEN_CHUNK_COUNT - 2L));
    TradingLabReportEntity report = terminalReport(
        LARGE_CHUNK_BYTES,
        SINGLE_TOKEN_CHUNK_COUNT,
        middle);
    report.setCompressedBytes(compressedBytes);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    LazyChunkSource source = new LazyChunkSource(
        report,
        TradingLabReportSection.ERRORS,
        SINGLE_TOKEN_CHUNK_COUNT,
        sequence -> {
          if (sequence == 0L) {
            return first;
          }
          return sequence == SINGLE_TOKEN_CHUNK_COUNT - 1L ? last : middle;
        },
        transactions::isActive);
    TradingLabReportStreamer streamer = new TradingLabReportStreamer(
        source,
        codec,
        transactions,
        JSON);
    CountingNullOutputStream output = new CountingNullOutputStream();

    streamer.stream(REPORT_ID, output);

    assertThat(output.bytesWritten()).isEqualTo(report.getUncompressedBytes());
    assertThat(Math.multiplyExact(
        (long) LARGE_CHUNK_BYTES,
        SINGLE_TOKEN_CHUNK_COUNT)).isEqualTo(SIXTEEN_MEBIBYTES);
    assertThat(source.generatedChunks()).isEqualTo(2 * SINGLE_TOKEN_CHUNK_COUNT);
    assertThat(source.maxSimultaneouslyOpenCursors()).isOne();
    assertThat(source.maxOutstandingChunks()).isOne();
    assertThat(source.peakProtocolBytes())
        .isLessThanOrEqualTo(2L * LARGE_CHUNK_BYTES + 1024L);
    assertThat(output.largestWrite()).isLessThanOrEqualTo(LARGE_CHUNK_BYTES);
    assertThat(source.allCursorsClosed()).isTrue();
  }

  @Test
  void clientDisconnectClosesCurrentCursorAndRollsBackStreamingTransaction() {
    int chunkBytes = 4096;
    int chunkCount = 2;
    byte[] record = fullChunkJsonRecord(chunkBytes);
    TradingLabEncodedChunk template = new TradingLabReportChunkCodec(chunkBytes).encode(record);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    LazyChunkSource source = new LazyChunkSource(
        terminalReport(chunkBytes, chunkCount, template),
        template,
        chunkCount,
        transactions::isActive);
    TradingLabReportStreamer streamer = new TradingLabReportStreamer(
        source,
        new TradingLabReportChunkCodec(chunkBytes),
        transactions,
        JSON);
    IOException disconnect = new IOException("simulated client disconnect");
    OutputStream output = new DisconnectingOutputStream(source, disconnect);

    IOException thrown = catchThrowableOfType(
        () -> streamer.stream(REPORT_ID, output),
        IOException.class);

    assertThat(thrown).isSameAs(disconnect);
    assertThat(source.allCursorsClosed()).isTrue();
    assertThat(source.openCursorCount()).isZero();
    assertThat(source.maxSimultaneouslyOpenCursors()).isOne();
    assertThat(source.everyAccessWasInTransaction()).isTrue();
    assertThat(transactions.begins()).isOne();
    assertThat(transactions.commits()).isZero();
    assertThat(transactions.rollbacks()).isOne();
    assertThat(transactions.isActive()).isFalse();
    assertThat(transactions.isolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(transactions.readOnly()).isFalse();
  }

  private static TradingLabReportEntity terminalReport(
      int chunkBytes,
      int chunkCount,
      TradingLabEncodedChunk template
  ) {
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(REPORT_ID);
    report.setStatus(TradingLabReportStatus.COMPLETED.name());
    report.setModelVersion("bounded-stream-v1");
    report.setMetadataJson("{}");
    report.setCompletedAt(Instant.parse("2026-07-19T00:00:00Z"));
    report.setVersion(11L);
    report.setCompressedBytes(Math.multiplyExact(
        (long) template.compressedBytes(),
        chunkCount));
    report.setChunkCount(chunkCount);
    report.setUncompressedBytes(exactReportBytes(chunkBytes, chunkCount));
    return report;
  }

  private static long exactReportBytes(int chunkBytes, int chunkCount) {
    String emptyReport = "{"
        + "\"metadata\":{},"
        + "\"actor\":{},"
        + "\"environment\":{},"
        + "\"scenario\":{},"
        + "\"modelVersion\":\"bounded-stream-v1\","
        + "\"configSnapshot\":{},"
        + "\"localCalculation\":{},"
        + "\"lifecycle\":[],"
        + "\"apiTrace\":[],"
        + "\"marketTicks\":[],"
        + "\"checkpoints\":[],"
        + "\"actualState\":{},"
        + "\"errors\":[],"
        + "\"cleanup\":{}"
        + "}";
    long rawNdjsonBytes = Math.multiplyExact((long) chunkBytes, chunkCount);
    long arrayBytes = Math.addExact(rawNdjsonBytes, 1L);
    return Math.addExact(
        emptyReport.getBytes(StandardCharsets.UTF_8).length - 2L,
        arrayBytes);
  }

  private static byte[] fullChunkJsonRecord(int bytes) {
    byte[] prefix = "{\"payload\":\"".getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\"}\n".getBytes(StandardCharsets.UTF_8);
    if (bytes <= prefix.length + suffix.length) {
      throw new IllegalArgumentException("Test chunk is too small");
    }
    byte[] alphabet =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
            .getBytes(StandardCharsets.US_ASCII);
    byte[] record = new byte[bytes];
    long state = 0x6a09e667f3bcc909L;
    for (int index = prefix.length; index < record.length - suffix.length; index++) {
      state ^= state << 13;
      state ^= state >>> 7;
      state ^= state << 17;
      record[index] = alphabet[(int) (state & (alphabet.length - 1))];
    }
    System.arraycopy(prefix, 0, record, 0, prefix.length);
    System.arraycopy(suffix, 0, record, record.length - suffix.length, suffix.length);
    return record;
  }

  private static TradingLabEncodedChunk singleTokenChunk(
      TradingLabReportChunkCodec codec,
      boolean first,
      boolean last
  ) {
    byte[] plain = new byte[LARGE_CHUNK_BYTES];
    Arrays.fill(plain, (byte) 'a');
    if (first) {
      byte[] prefix = "{\"payload\":\"".getBytes(StandardCharsets.UTF_8);
      System.arraycopy(prefix, 0, plain, 0, prefix.length);
    }
    if (last) {
      byte[] suffix = "\"}\n".getBytes(StandardCharsets.UTF_8);
      System.arraycopy(suffix, 0, plain, plain.length - suffix.length, suffix.length);
    }
    return codec.encode(plain);
  }

  @FunctionalInterface
  private interface ChunkTemplateSource {
    TradingLabEncodedChunk forSequence(long sequence);
  }

  private static final class LazyChunkSource implements TradingLabReportChunkSource {
    private final TradingLabReportEntity report;
    private final TradingLabReportSection streamedSection;
    private final ChunkTemplateSource templates;
    private final int chunkCount;
    private final BooleanSupplier transactionActive;
    private final List<LazyCursor> opened = new ArrayList<>();
    private int generatedChunks;
    private int openCursors;
    private int maxOpenCursors;
    private int outstandingChunks;
    private int maxOutstandingChunks;
    private long peakProtocolBytes;
    private boolean everyAccessInTransaction = true;
    private TradingLabReportSection currentSection;

    private LazyChunkSource(
        TradingLabReportEntity report,
        TradingLabEncodedChunk template,
        int chunkCount,
        BooleanSupplier transactionActive
    ) {
      this(
          report,
          TradingLabReportSection.MARKET_TICKS,
          chunkCount,
          sequence -> template,
          transactionActive);
    }

    private LazyChunkSource(
        TradingLabReportEntity report,
        TradingLabReportSection streamedSection,
        int chunkCount,
        ChunkTemplateSource templates,
        BooleanSupplier transactionActive
    ) {
      this.report = report;
      this.streamedSection = streamedSection;
      this.templates = templates;
      this.chunkCount = chunkCount;
      this.transactionActive = transactionActive;
    }

    @Override
    public TradingLabReportEntity lockReportForStream(UUID reportId) {
      assertThat(reportId).isEqualTo(REPORT_ID);
      recordTransactionState();
      return report;
    }

    @Override
    public Cursor<TradingLabReportChunkEntity> openChunks(
        UUID reportId,
        TradingLabReportSection section
    ) {
      assertThat(reportId).isEqualTo(REPORT_ID);
      recordTransactionState();
      openCursors++;
      maxOpenCursors = Math.max(maxOpenCursors, openCursors);
      currentSection = section;
      LazyCursor cursor = new LazyCursor(
          section,
          section == streamedSection ? chunkCount : 0);
      opened.add(cursor);
      return cursor;
    }

    private void recordTransactionState() {
      if (!transactionActive.getAsBoolean()) {
        everyAccessInTransaction = false;
      }
    }

    private TradingLabReportChunkEntity generate(
        TradingLabReportSection section,
        long sequence,
        LazyCursor cursor
    ) {
      TradingLabEncodedChunk template = templates.forSequence(sequence);
      generatedChunks++;
      outstandingChunks++;
      maxOutstandingChunks = Math.max(maxOutstandingChunks, outstandingChunks);
      peakProtocolBytes = Math.max(
          peakProtocolBytes,
          (long) template.uncompressedBytes() + template.payload().length);

      TrackingChunkEntity chunk = new TrackingChunkEntity(cursor);
      chunk.setId(REPORT_ID);
      chunk.setReportId(REPORT_ID);
      chunk.setSection(section.name());
      chunk.setSequence(sequence);
      chunk.setEncoding(template.encoding());
      chunk.setUncompressedBytes((long) template.uncompressedBytes());
      chunk.setCompressedBytes((long) template.compressedBytes());
      chunk.setPayload(Arrays.copyOf(template.payload(), template.payload().length));
      chunk.setChecksum(template.checksum());
      return chunk;
    }

    private void releaseOutstandingChunks(int count) {
      if (count < 0 || count > outstandingChunks) {
        throw new IllegalStateException("Invalid outstanding chunk count");
      }
      outstandingChunks -= count;
    }

    private void cursorClosed(LazyCursor cursor) {
      releaseOutstandingChunks(cursor.releaseUnconsumedChunks());
      openCursors--;
      if (currentSection == cursor.section) {
        currentSection = null;
      }
    }

    TradingLabReportEntity report() {
      return report;
    }

    int generatedChunks() {
      return generatedChunks;
    }

    int openCursorCount() {
      return openCursors;
    }

    int maxSimultaneouslyOpenCursors() {
      return maxOpenCursors;
    }

    int maxOutstandingChunks() {
      return maxOutstandingChunks;
    }

    long peakProtocolBytes() {
      return peakProtocolBytes;
    }

    boolean allCursorsClosed() {
      return openCursors == 0
          && outstandingChunks == 0
          && opened.stream().allMatch(cursor -> !cursor.isOpen());
    }

    boolean everyAccessWasInTransaction() {
      return everyAccessInTransaction;
    }

    boolean marketTicksCursorIsOpen() {
      return currentSection == TradingLabReportSection.MARKET_TICKS && openCursors == 1;
    }

    private final class LazyCursor implements Cursor<TradingLabReportChunkEntity> {
      private final TradingLabReportSection section;
      private final int size;
      private boolean open = true;
      private boolean consumed;
      private boolean iteratorCreated;
      private int unconsumedChunks;
      private int currentIndex = -1;

      private LazyCursor(TradingLabReportSection section, int size) {
        this.section = section;
        this.size = size;
      }

      @Override
      public boolean isOpen() {
        return open;
      }

      @Override
      public boolean isConsumed() {
        return consumed;
      }

      @Override
      public int getCurrentIndex() {
        return currentIndex;
      }

      @Override
      public Iterator<TradingLabReportChunkEntity> iterator() {
        if (iteratorCreated) {
          throw new IllegalStateException("Cursor iterator may only be requested once");
        }
        iteratorCreated = true;
        return new Iterator<>() {
          @Override
          public boolean hasNext() {
            boolean hasNext = currentIndex + 1 < size;
            if (!hasNext) {
              consumed = true;
            }
            return hasNext;
          }

          @Override
          public TradingLabReportChunkEntity next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            currentIndex++;
            unconsumedChunks++;
            return generate(section, currentIndex, LazyCursor.this);
          }
        };
      }

      @Override
      public void close() {
        if (open) {
          open = false;
          cursorClosed(this);
        }
      }

      private void chunkConsumed() {
        if (unconsumedChunks < 1) {
          throw new IllegalStateException("Chunk was consumed more than once");
        }
        unconsumedChunks--;
        releaseOutstandingChunks(1);
      }

      private int releaseUnconsumedChunks() {
        int released = unconsumedChunks;
        unconsumedChunks = 0;
        return released;
      }
    }

    private final class TrackingChunkEntity extends TradingLabReportChunkEntity {
      private final LazyCursor cursor;
      private boolean consumed;

      private TrackingChunkEntity(LazyCursor cursor) {
        this.cursor = cursor;
      }

      @Override
      public byte[] getPayload() {
        byte[] payload = super.getPayload();
        if (!consumed) {
          consumed = true;
          cursor.chunkConsumed();
        }
        return payload;
      }
    }
  }

  private static final class CountingNullOutputStream extends OutputStream {
    private long bytesWritten;
    private int largestWrite;

    @Override
    public void write(int value) {
      bytesWritten++;
      largestWrite = Math.max(largestWrite, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      bytesWritten = Math.addExact(bytesWritten, length);
      largestWrite = Math.max(largestWrite, length);
    }

    long bytesWritten() {
      return bytesWritten;
    }

    int largestWrite() {
      return largestWrite;
    }
  }

  private static final class DisconnectingOutputStream extends OutputStream {
    private final LazyChunkSource source;
    private final IOException disconnect;

    private DisconnectingOutputStream(LazyChunkSource source, IOException disconnect) {
      this.source = source;
      this.disconnect = disconnect;
    }

    @Override
    public void write(int value) throws IOException {
      failWhileMarketTicksCursorIsOpen();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      failWhileMarketTicksCursorIsOpen();
    }

    private void failWhileMarketTicksCursorIsOpen() throws IOException {
      if (source.marketTicksCursorIsOpen()) {
        throw disconnect;
      }
    }
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private int begins;
    private int commits;
    private int rollbacks;
    private boolean active;
    private int isolationLevel;
    private boolean readOnly;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(active).isFalse();
      active = true;
      begins++;
      isolationLevel = definition.getIsolationLevel();
      readOnly = definition.isReadOnly();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      assertThat(active).isTrue();
      active = false;
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      assertThat(active).isTrue();
      active = false;
      rollbacks++;
    }

    int begins() {
      return begins;
    }

    int commits() {
      return commits;
    }

    int rollbacks() {
      return rollbacks;
    }

    boolean isActive() {
      return active;
    }

    int isolationLevel() {
      return isolationLevel;
    }

    boolean readOnly() {
      return readOnly;
    }
  }
}
