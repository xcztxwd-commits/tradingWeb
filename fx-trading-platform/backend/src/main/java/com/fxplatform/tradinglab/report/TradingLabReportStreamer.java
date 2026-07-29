package com.fxplatform.tradinglab.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.cursor.Cursor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TradingLabReportStreamer {

  private static final byte[] OBJECT_START = "{".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OBJECT_END = "}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ARRAY_START = "[".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ARRAY_END = "]".getBytes(StandardCharsets.UTF_8);
  private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);
  private static final byte[] COLON = ":".getBytes(StandardCharsets.UTF_8);
  // Canonical values allow levels 0..64; report root and array sections add framing.
  private static final int MAX_JSON_DEPTH = 68;
  private static final int MAX_JSON_TOKEN_BYTES =
      TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES;

  private final TradingLabReportChunkSource chunkSource;
  private final TradingLabReportChunkCodec codec;
  private final TransactionTemplate transactions;
  private final JsonMapper objectMapper;

  @Autowired
  public TradingLabReportStreamer(
      TradingLabReportChunkSource chunkSource,
      TradingLabReportChunkCodec codec,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper
  ) {
    this(
        chunkSource,
        codec,
        configuredTransactions(transactionManager),
        objectMapper);
  }

  TradingLabReportStreamer(
      TradingLabReportChunkSource chunkSource,
      TradingLabReportChunkCodec codec,
      TransactionTemplate transactions,
      ObjectMapper objectMapper
  ) {
    this.chunkSource = Objects.requireNonNull(chunkSource, "chunkSource");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    Objects.requireNonNull(objectMapper, "objectMapper");
    this.objectMapper = deterministicJsonMapper();
  }

  private static JsonMapper deterministicJsonMapper() {
    JsonFactory factory = JsonFactory.builder().build();
    return JsonMapper.builder(factory).build();
  }

  public void stream(UUID reportId, OutputStream output) throws IOException {
    Objects.requireNonNull(output, "output");
    try {
      transactions.execute(ignored -> {
        streamInSnapshot(reportId, output);
        return null;
      });
    } catch (UncheckedIOException exception) {
      throw exception.getCause();
    }
  }

  /**
   * Performs the complete bounded validation pass synchronously, before an HTTP 200 can commit.
   */
  public TradingLabReportReadTicket prepare(UUID reportId) {
    return transactions.execute(ignored -> prepareInSnapshot(reportId));
  }

  /**
   * Streams only the exact report version and totals proven by {@link #prepare(UUID)}.
   */
  public void stream(
      TradingLabReportReadTicket ticket,
      OutputStream output
  ) throws IOException {
    Objects.requireNonNull(ticket, "ticket");
    Objects.requireNonNull(output, "output");
    try {
      transactions.execute(ignored -> {
        streamPreparedInSnapshot(ticket, output);
        return null;
      });
    } catch (UncheckedIOException exception) {
      throw exception.getCause();
    }
  }

  public TradingLabReportMeasurement measureForClose(UUID reportId) {
    return measureForClose(reportId, null);
  }

  TradingLabReportMeasurement measureForClose(
      UUID reportId,
      TradingLabReportSecretRegistry secrets
  ) {
    TradingLabCanonicalCanaryScanner.StreamingMatcher matcher = secrets == null
        ? null
        : TradingLabCanonicalCanaryScanner.streamingMatcher(secrets.values());
    return transactions.execute(ignored -> {
      TradingLabReportEntity report = requireBaseReport(reportId);
      PassMetrics metrics = preflight(report, matcher);
      verifyStoredChunkTotals(report, metrics);
      return new TradingLabReportMeasurement(
          requiredVersion(report),
          metrics.outputBytes(),
          metrics.compressedBytes(),
          metrics.chunkCount());
    });
  }

  private void streamInSnapshot(UUID reportId, OutputStream output) {
    TradingLabReportEntity report = requireBaseReport(reportId);
    TradingLabReportStatus status = parseStatus(report.getStatus());
    if (!status.terminal() || report.getCompletedAt() == null) {
      throw new IllegalStateException("Trading Lab report is not closed");
    }

    PassMetrics preflight = preflight(report);
    verifyTerminalTotals(report, preflight);
    PassMetrics emitted = assemble(report, output);
    if (!preflight.equals(emitted)) {
      throw corrupt("Trading Lab report changed between preflight and emission");
    }
  }

  private TradingLabReportReadTicket prepareInSnapshot(UUID reportId) {
    TradingLabReportEntity report = requireBaseReport(reportId);
    requireTerminal(report);
    if (TradingLabReportQuarantinePolicy.isNormalizedGenericTerminal(report)) {
      throw corrupt("Trading Lab report is quarantined");
    }
    PassMetrics metrics = preflight(report);
    verifyTerminalTotals(report, metrics);
    return new TradingLabReportReadTicket(
        report.getId(),
        requiredVersion(report),
        metrics.outputBytes(),
        metrics.compressedBytes(),
        metrics.chunkCount());
  }

  private void streamPreparedInSnapshot(
      TradingLabReportReadTicket ticket,
      OutputStream output
  ) {
    TradingLabReportEntity report = requireBaseReport(ticket.reportId());
    requireTerminal(report);
    verifyPreparedTicket(report, ticket);
    PassMetrics preflight = preflight(report);
    verifyTerminalTotals(report, preflight);
    if (preflight.outputBytes() != ticket.uncompressedBytes()
        || preflight.compressedBytes() != ticket.compressedBytes()
        || preflight.chunkCount() != ticket.chunkCount()) {
      throw corrupt("Trading Lab report changed after read preparation");
    }
    PassMetrics emitted = assemble(report, output);
    if (!preflight.equals(emitted)) {
      throw corrupt("Trading Lab report changed between preflight and emission");
    }
  }

  private void requireTerminal(TradingLabReportEntity report) {
    TradingLabReportStatus status = parseStatus(report.getStatus());
    if (!status.terminal() || report.getCompletedAt() == null) {
      throw new TradingLabReportException(
          "TRADING_LAB_REPORT_STATUS_CONFLICT",
          "Trading Lab report is not closed");
    }
  }

  private void verifyPreparedTicket(
      TradingLabReportEntity report,
      TradingLabReportReadTicket ticket
  ) {
    if (!report.getId().equals(ticket.reportId())
        || requiredVersion(report) != ticket.reportVersion()
        || report.getUncompressedBytes() == null
        || report.getUncompressedBytes() != ticket.uncompressedBytes()
        || report.getCompressedBytes() == null
        || report.getCompressedBytes() != ticket.compressedBytes()
        || report.getChunkCount() == null
        || report.getChunkCount() != ticket.chunkCount()) {
      throw corrupt("Trading Lab report changed after read preparation");
    }
  }

  private TradingLabReportEntity requireBaseReport(UUID reportId) {
    if (reportId == null) {
      throw new IllegalArgumentException("Trading Lab report ID is required");
    }
    TradingLabReportEntity report = chunkSource.lockReportForStream(reportId);
    if (report.getModelVersion() == null || report.getModelVersion().isBlank()) {
      throw corrupt("Trading Lab report model version is missing");
    }
    parseStatus(report.getStatus());
    if (TradingLabReportQuarantinePolicy.hasGenericTerminalOutcome(report)
        && !TradingLabReportQuarantinePolicy.isNormalizedGenericTerminal(report)) {
      throw corrupt("Trading Lab report quarantine terminal is not normalized");
    }
    requiredVersion(report);
    return report;
  }

  private PassMetrics preflight(TradingLabReportEntity report) {
    return preflight(report, null);
  }

  private PassMetrics preflight(
      TradingLabReportEntity report,
      TradingLabCanonicalCanaryScanner.StreamingMatcher matcher
  ) {
    try (IncrementalJsonValidator validator =
        new IncrementalJsonValidator(TradingLabReportSection.values())) {
      PassMetrics metrics = assemble(report, validator, matcher);
      validator.finish();
      return metrics;
    } catch (UncheckedIOException exception) {
      if (exception.getCause() instanceof TradingLabReportCorruptionIOException corruption) {
        throw corruption.corruption();
      }
      throw corrupt("Trading Lab report JSON preflight failed");
    } catch (IOException exception) {
      if (exception instanceof TradingLabReportCorruptionIOException corruption) {
        throw corruption.corruption();
      }
      throw corrupt("Trading Lab report JSON preflight failed");
    }
  }

  private PassMetrics assemble(TradingLabReportEntity report, OutputStream destination) {
    return assemble(report, destination, null);
  }

  private PassMetrics assemble(
      TradingLabReportEntity report,
      OutputStream destination,
      TradingLabCanonicalCanaryScanner.StreamingMatcher matcher
  ) {
    CountingOutputStream output = new CountingOutputStream(destination, matcher);
    long compressedBytes = 0L;
    int chunkCount = 0;
    try {
      output.write(OBJECT_START);
      TradingLabReportSection[] sections = TradingLabReportSection.values();
      for (int index = 0; index < sections.length; index++) {
        TradingLabReportSection section = sections[index];
        if (index > 0) {
          output.write(COMMA);
        }
        output.write(objectMapper.writeValueAsBytes(section.jsonKey()));
        output.write(COLON);
        SectionMetrics sectionMetrics = section.isArray()
            ? writeArraySection(report.getId(), section, output)
            : writeSingletonSection(report, section, output);
        compressedBytes = Math.addExact(compressedBytes, sectionMetrics.compressedBytes());
        chunkCount = Math.addExact(chunkCount, sectionMetrics.chunkCount());
      }
      output.write(OBJECT_END);
      output.flush();
      return new PassMetrics(output.count(), compressedBytes, chunkCount);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (ArithmeticException exception) {
      throw corrupt("Trading Lab report byte totals overflow", exception);
    }
  }

  private SectionMetrics writeSingletonSection(
      TradingLabReportEntity report,
      TradingLabReportSection section,
      OutputStream output
  ) throws IOException {
    long compressedBytes = 0L;
    int chunkCount = 0;
    long expectedSequence = 0L;
    try (Cursor<TradingLabReportChunkEntity> cursor =
        chunkSource.openChunks(report.getId(), section)) {
      for (TradingLabReportChunkEntity chunk : cursor) {
        verifyChunkEnvelope(report.getId(), section, expectedSequence, chunk);
        byte[] plain = codec.decodeAndVerify(chunk);
        output.write(plain);
        compressedBytes = Math.addExact(compressedBytes, chunk.getCompressedBytes());
        chunkCount = Math.addExact(chunkCount, 1);
        expectedSequence++;
      }
    }
    if (chunkCount == 0) {
      if (section.isString()) {
        output.write(objectMapper.writeValueAsBytes(report.getModelVersion()));
      } else {
        output.write(section.emptyJson().getBytes(StandardCharsets.UTF_8));
      }
    }
    return new SectionMetrics(compressedBytes, chunkCount);
  }

  private SectionMetrics writeArraySection(
      UUID reportId,
      TradingLabReportSection section,
      CountingOutputStream output
  ) throws IOException {
    output.write(ARRAY_START);
    long compressedBytes = 0L;
    int chunkCount = 0;
    long expectedSequence = 0L;
    boolean recordHasBytes = false;
    boolean commaBeforeNextRecord = false;
    try (Cursor<TradingLabReportChunkEntity> cursor =
        chunkSource.openChunks(reportId, section)) {
      for (TradingLabReportChunkEntity chunk : cursor) {
        verifyChunkEnvelope(reportId, section, expectedSequence, chunk);
        byte[] plain = codec.decodeAndVerify(chunk);
        int segmentStart = 0;
        for (int index = 0; index < plain.length; index++) {
          if (plain[index] != '\n') {
            continue;
          }
          if (index > segmentStart) {
            if (!recordHasBytes && commaBeforeNextRecord) {
              output.writeNdjsonDelimiter();
              commaBeforeNextRecord = false;
            }
            output.write(plain, segmentStart, index - segmentStart);
            recordHasBytes = true;
          }
          if (!recordHasBytes) {
            throw corrupt("Trading Lab report contains an empty NDJSON record");
          }
          output.observeNdjsonRecordEnd();
          recordHasBytes = false;
          commaBeforeNextRecord = true;
          segmentStart = index + 1;
        }
        if (segmentStart < plain.length) {
          if (!recordHasBytes && commaBeforeNextRecord) {
            output.writeNdjsonDelimiter();
            commaBeforeNextRecord = false;
          }
          output.write(plain, segmentStart, plain.length - segmentStart);
          recordHasBytes = true;
        }
        compressedBytes = Math.addExact(compressedBytes, chunk.getCompressedBytes());
        chunkCount = Math.addExact(chunkCount, 1);
        expectedSequence++;
      }
    }
    if (recordHasBytes) {
      throw corrupt("Trading Lab report NDJSON is missing its terminal LF");
    }
    output.write(ARRAY_END);
    return new SectionMetrics(compressedBytes, chunkCount);
  }

  private void verifyChunkEnvelope(
      UUID reportId,
      TradingLabReportSection section,
      long expectedSequence,
      TradingLabReportChunkEntity chunk
  ) {
    if (chunk == null
        || !reportId.equals(chunk.getReportId())
        || !section.name().equals(chunk.getSection())
        || chunk.getSequence() == null
        || chunk.getSequence() != expectedSequence) {
      throw corrupt("Trading Lab report chunk sequence or section is corrupt");
    }
  }

  private void verifyStoredChunkTotals(TradingLabReportEntity report, PassMetrics metrics) {
    if (report.getCompressedBytes() == null
        || report.getChunkCount() == null
        || report.getCompressedBytes() != metrics.compressedBytes()
        || report.getChunkCount() != metrics.chunkCount()) {
      throw corrupt("Trading Lab report chunk totals are corrupt");
    }
  }

  private void verifyTerminalTotals(TradingLabReportEntity report, PassMetrics metrics) {
    verifyStoredChunkTotals(report, metrics);
    if (report.getUncompressedBytes() == null
        || report.getUncompressedBytes() != metrics.outputBytes()) {
      throw corrupt("Trading Lab report exact byte total is corrupt");
    }
  }

  private TradingLabReportStatus parseStatus(String status) {
    try {
      return TradingLabReportStatus.valueOf(status);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw corrupt("Trading Lab report status is corrupt", exception);
    }
  }

  private long requiredVersion(TradingLabReportEntity report) {
    if (report.getVersion() == null || report.getVersion() < 0L) {
      throw corrupt("Trading Lab report version is corrupt");
    }
    return report.getVersion();
  }

  private static TransactionTemplate configuredTransactions(
      PlatformTransactionManager transactionManager
  ) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    template.setReadOnly(false);
    return template;
  }

  private static TradingLabReportCorruptionException corrupt(String message) {
    return new TradingLabReportCorruptionException(message);
  }

  private static TradingLabReportCorruptionException corrupt(String message, Throwable cause) {
    return new TradingLabReportCorruptionException(message, cause);
  }

  private record SectionMetrics(long compressedBytes, int chunkCount) {
  }

  private record PassMetrics(long outputBytes, long compressedBytes, int chunkCount) {
  }

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final TradingLabCanonicalCanaryScanner.StreamingMatcher matcher;
    private long count;

    private CountingOutputStream(OutputStream delegate) {
      this(delegate, null);
    }

    private CountingOutputStream(
        OutputStream delegate,
        TradingLabCanonicalCanaryScanner.StreamingMatcher matcher
    ) {
      this.delegate = delegate;
      this.matcher = matcher;
    }

    @Override
    public void write(int value) throws IOException {
      if (matcher != null) {
        matcher.accept(value);
      }
      delegate.write(value);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (matcher != null) {
        matcher.accept(bytes, offset, length);
      }
      delegate.write(bytes, offset, length);
      count = Math.addExact(count, length);
    }

    private void writeNdjsonDelimiter() throws IOException {
      if (matcher != null) {
        matcher.accept(',');
      }
      if (delegate instanceof IncrementalJsonValidator validator) {
        validator.acceptNdjsonDelimiter();
      } else {
        delegate.write(COMMA);
      }
      count = Math.addExact(count, 1L);
    }

    private void observeNdjsonRecordEnd() throws IOException {
      if (delegate instanceof IncrementalJsonValidator validator) {
        validator.acceptNdjsonRecordEnd();
      }
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    private long count() {
      return count;
    }
  }

  private static final class IncrementalJsonValidator extends OutputStream {
    private static final byte OBJECT_KEY_OR_END = 1;
    private static final byte OBJECT_KEY_REQUIRED = 2;
    private static final byte OBJECT_COLON = 3;
    private static final byte OBJECT_VALUE = 4;
    private static final byte OBJECT_COMMA_OR_END = 5;
    private static final byte ARRAY_VALUE_OR_END = 6;
    private static final byte ARRAY_VALUE_REQUIRED = 7;
    private static final byte ARRAY_COMMA_OR_END = 8;

    private static final byte[] TRUE_LITERAL = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_LITERAL = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL_LITERAL = {'n', 'u', 'l', 'l'};

    private final TradingLabReportSection[] sections;
    private final byte[] containerStates = new byte[MAX_JSON_DEPTH];
    private TokenState tokenState = TokenState.NONE;
    private byte[] literal;
    private int literalOffset;
    private int unicodeDigitsRemaining;
    private int tokenBytes;
    private int depth;
    private int sectionIndex;
    private int topLevelKeyOffset;
    private boolean stringFieldName;
    private boolean topLevelFieldName;
    private boolean rootStarted;
    private boolean rootEnded;
    private boolean acceptingNdjsonRecordEnd;
    private boolean finished;

    private IncrementalJsonValidator(TradingLabReportSection[] sections) {
      this.sections = sections;
    }

    @Override
    public void write(int value) throws IOException {
      if (finished) {
        throw new IOException("JSON validator is already finished");
      }
      acceptByte(value & 0xff);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (finished) {
        throw new IOException("JSON validator is already finished");
      }
      Objects.checkFromIndexSize(offset, length, bytes.length);
      int end = offset + length;
      for (int index = offset; index < end; index++) {
        acceptByte(bytes[index] & 0xff);
      }
    }

    private void finish() throws IOException {
      if (finished) {
        return;
      }
      if (isCompleteNumberState(tokenState)) {
        tokenState = TokenState.NONE;
        completeValue();
      } else if (tokenState != TokenState.NONE) {
        throw corruptionIo("Trading Lab report JSON schema is incomplete");
      }
      finished = true;
      if (!rootEnded || depth != 0 || sectionIndex != sections.length) {
        throw corruptionIo("Trading Lab report JSON schema is incomplete");
      }
    }

    private void acceptNdjsonRecordEnd() throws IOException {
      acceptingNdjsonRecordEnd = true;
      try {
        acceptByte('\n');
      } finally {
        acceptingNdjsonRecordEnd = false;
      }
      if (tokenState != TokenState.NONE
          || depth != 2
          || containerStates[depth - 1] != ARRAY_COMMA_OR_END) {
        throw corruptionIo("Trading Lab report contains an empty NDJSON record");
      }
    }

    private void acceptNdjsonDelimiter() throws IOException {
      if (tokenState == TokenState.STRING
          || tokenState == TokenState.STRING_ESCAPE
          || tokenState == TokenState.STRING_UNICODE) {
        throw syntaxError();
      }
      acceptByte(',');
    }

    private void acceptByte(int value) throws IOException {
      boolean consumed = false;
      while (!consumed) {
        consumed = switch (tokenState) {
          case NONE -> {
            acceptStructural(value);
            yield true;
          }
          case STRING, STRING_ESCAPE, STRING_UNICODE -> {
            acceptStringByte(value);
            yield true;
          }
          case LITERAL -> {
            acceptLiteralByte(value);
            yield true;
          }
          default -> acceptNumberByte(value);
        };
      }
    }

    private void acceptStructural(int value) throws IOException {
      if (isWhitespace(value)) {
        if (!acceptingNdjsonRecordEnd || value != '\n') {
          throw syntaxError();
        }
        return;
      }
      if (rootEnded) {
        throw syntaxError();
      }
      if (!rootStarted) {
        if (value != '{') {
          throw corruptionIo("Trading Lab report root must be an object");
        }
        rootStarted = true;
        pushContainer(OBJECT_KEY_OR_END);
        return;
      }
      if (depth == 0) {
        throw syntaxError();
      }

      int frame = depth - 1;
      switch (containerStates[frame]) {
        case OBJECT_KEY_OR_END -> {
          if (value == '}') {
            closeObject();
          } else if (value == '"') {
            startString(true);
          } else {
            throw syntaxError();
          }
        }
        case OBJECT_KEY_REQUIRED -> {
          if (value != '"') {
            throw syntaxError();
          }
          startString(true);
        }
        case OBJECT_COLON -> {
          if (value != ':') {
            throw syntaxError();
          }
          containerStates[frame] = OBJECT_VALUE;
        }
        case OBJECT_VALUE -> startValue(value);
        case OBJECT_COMMA_OR_END -> {
          if (value == ',') {
            containerStates[frame] = OBJECT_KEY_REQUIRED;
          } else if (value == '}') {
            closeObject();
          } else {
            throw syntaxError();
          }
        }
        case ARRAY_VALUE_OR_END -> {
          if (value == ']') {
            closeArray();
          } else {
            startValue(value);
          }
        }
        case ARRAY_VALUE_REQUIRED -> {
          if (value == ']') {
            throw syntaxError();
          }
          startValue(value);
        }
        case ARRAY_COMMA_OR_END -> {
          if (value == ',') {
            containerStates[frame] = ARRAY_VALUE_REQUIRED;
          } else if (value == ']') {
            closeArray();
          } else {
            throw syntaxError();
          }
        }
        default -> throw syntaxError();
      }
    }

    private void startValue(int value) throws IOException {
      validateTopLevelShape(value);
      if (value == '{') {
        pushContainer(OBJECT_KEY_OR_END);
      } else if (value == '[') {
        pushContainer(ARRAY_VALUE_OR_END);
      } else if (value == '"') {
        startString(false);
      } else if (value == 't') {
        startLiteral(TRUE_LITERAL);
      } else if (value == 'f') {
        startLiteral(FALSE_LITERAL);
      } else if (value == 'n') {
        startLiteral(NULL_LITERAL);
      } else if (value == '-') {
        startNumber(TokenState.NUMBER_SIGN);
      } else if (value == '0') {
        startNumber(TokenState.NUMBER_ZERO);
      } else if (value >= '1' && value <= '9') {
        startNumber(TokenState.NUMBER_INTEGER);
      } else {
        throw syntaxError();
      }
    }

    private void validateTopLevelShape(int value) throws IOException {
      if (depth != 1) {
        return;
      }
      if (sectionIndex >= sections.length) {
        throw corruptionIo("Trading Lab report has too many top-level sections");
      }
      TradingLabReportSection section = sections[sectionIndex];
      if ((section.isArray() && value != '[')
          || (section.isString() && value != '"')
          || (!section.isArray() && !section.isString() && value != '{')) {
        throw corruptionIo("Trading Lab report section has the wrong JSON shape");
      }
    }

    private void startString(boolean fieldName) throws IOException {
      tokenState = TokenState.STRING;
      tokenBytes = 1;
      stringFieldName = fieldName;
      topLevelFieldName = fieldName && depth == 1;
      topLevelKeyOffset = 0;
      if (topLevelFieldName && sectionIndex >= sections.length) {
        throw corruptionIo("Trading Lab report top-level key order is corrupt");
      }
    }

    private void acceptStringByte(int value) throws IOException {
      countTokenByte();
      if (tokenState == TokenState.STRING_UNICODE) {
        if (!isHexDigit(value)) {
          throw syntaxError();
        }
        unicodeDigitsRemaining--;
        if (unicodeDigitsRemaining == 0) {
          tokenState = TokenState.STRING;
        }
        return;
      }
      if (tokenState == TokenState.STRING_ESCAPE) {
        if (value == 'u') {
          tokenState = TokenState.STRING_UNICODE;
          unicodeDigitsRemaining = 4;
        } else if (value == '"'
            || value == '\\'
            || value == '/'
            || value == 'b'
            || value == 'f'
            || value == 'n'
            || value == 'r'
            || value == 't') {
          tokenState = TokenState.STRING;
        } else {
          throw syntaxError();
        }
        return;
      }
      if (value == '"') {
        finishString();
        return;
      }
      if (value == '\\') {
        if (topLevelFieldName) {
          throw corruptionIo("Trading Lab report top-level key order is corrupt");
        }
        tokenState = TokenState.STRING_ESCAPE;
        return;
      }
      if (value < 0x20) {
        throw syntaxError();
      }
      if (topLevelFieldName) {
        String expected = sections[sectionIndex].jsonKey();
        if (value > 0x7f
            || topLevelKeyOffset >= expected.length()
            || value != expected.charAt(topLevelKeyOffset)) {
          throw corruptionIo("Trading Lab report top-level key order is corrupt");
        }
        topLevelKeyOffset++;
      }
    }

    private void finishString() throws IOException {
      if (topLevelFieldName
          && topLevelKeyOffset != sections[sectionIndex].jsonKey().length()) {
        throw corruptionIo("Trading Lab report top-level key order is corrupt");
      }
      tokenState = TokenState.NONE;
      if (stringFieldName) {
        if (depth == 0
            || (containerStates[depth - 1] != OBJECT_KEY_OR_END
                && containerStates[depth - 1] != OBJECT_KEY_REQUIRED)) {
          throw syntaxError();
        }
        containerStates[depth - 1] = OBJECT_COLON;
      } else {
        completeValue();
      }
    }

    private void startLiteral(byte[] expected) {
      tokenState = TokenState.LITERAL;
      literal = expected;
      literalOffset = 1;
      tokenBytes = 1;
    }

    private void acceptLiteralByte(int value) throws IOException {
      countTokenByte();
      if (literalOffset >= literal.length || value != (literal[literalOffset] & 0xff)) {
        throw syntaxError();
      }
      literalOffset++;
      if (literalOffset == literal.length) {
        tokenState = TokenState.NONE;
        literal = null;
        completeValue();
      }
    }

    private void startNumber(TokenState state) {
      tokenState = state;
      tokenBytes = 1;
    }

    private boolean acceptNumberByte(int value) throws IOException {
      switch (tokenState) {
        case NUMBER_SIGN -> {
          countTokenByte();
          if (value == '0') {
            tokenState = TokenState.NUMBER_ZERO;
          } else if (value >= '1' && value <= '9') {
            tokenState = TokenState.NUMBER_INTEGER;
          } else {
            throw syntaxError();
          }
          return true;
        }
        case NUMBER_ZERO -> {
          if (value == '.') {
            countTokenByte();
            tokenState = TokenState.NUMBER_FRACTION_FIRST;
            return true;
          }
          if (value == 'e' || value == 'E') {
            countTokenByte();
            tokenState = TokenState.NUMBER_EXPONENT_FIRST;
            return true;
          }
          if (value >= '0' && value <= '9') {
            throw syntaxError();
          }
          return finishNumberAndReprocess();
        }
        case NUMBER_INTEGER -> {
          if (value >= '0' && value <= '9') {
            countTokenByte();
            return true;
          }
          if (value == '.') {
            countTokenByte();
            tokenState = TokenState.NUMBER_FRACTION_FIRST;
            return true;
          }
          if (value == 'e' || value == 'E') {
            countTokenByte();
            tokenState = TokenState.NUMBER_EXPONENT_FIRST;
            return true;
          }
          return finishNumberAndReprocess();
        }
        case NUMBER_FRACTION_FIRST -> {
          countTokenByte();
          if (value < '0' || value > '9') {
            throw syntaxError();
          }
          tokenState = TokenState.NUMBER_FRACTION;
          return true;
        }
        case NUMBER_FRACTION -> {
          if (value >= '0' && value <= '9') {
            countTokenByte();
            return true;
          }
          if (value == 'e' || value == 'E') {
            countTokenByte();
            tokenState = TokenState.NUMBER_EXPONENT_FIRST;
            return true;
          }
          return finishNumberAndReprocess();
        }
        case NUMBER_EXPONENT_FIRST -> {
          countTokenByte();
          if (value == '+' || value == '-') {
            tokenState = TokenState.NUMBER_EXPONENT_SIGN;
          } else if (value >= '0' && value <= '9') {
            tokenState = TokenState.NUMBER_EXPONENT;
          } else {
            throw syntaxError();
          }
          return true;
        }
        case NUMBER_EXPONENT_SIGN -> {
          countTokenByte();
          if (value < '0' || value > '9') {
            throw syntaxError();
          }
          tokenState = TokenState.NUMBER_EXPONENT;
          return true;
        }
        case NUMBER_EXPONENT -> {
          if (value >= '0' && value <= '9') {
            countTokenByte();
            return true;
          }
          return finishNumberAndReprocess();
        }
        default -> throw syntaxError();
      }
    }

    private boolean finishNumberAndReprocess() throws IOException {
      tokenState = TokenState.NONE;
      completeValue();
      return false;
    }

    private void pushContainer(byte initialState) throws IOException {
      if (depth >= containerStates.length) {
        throw corruptionIo("Trading Lab report JSON nesting is too deep");
      }
      containerStates[depth++] = initialState;
    }

    private void closeObject() throws IOException {
      byte state = containerStates[depth - 1];
      if (state != OBJECT_KEY_OR_END && state != OBJECT_COMMA_OR_END) {
        throw syntaxError();
      }
      if (depth == 1 && sectionIndex != sections.length) {
        throw corruptionIo("Trading Lab report JSON schema is incomplete");
      }
      depth--;
      if (depth == 0) {
        rootEnded = true;
      } else {
        completeValue();
      }
    }

    private void closeArray() throws IOException {
      byte state = containerStates[depth - 1];
      if (state != ARRAY_VALUE_OR_END && state != ARRAY_COMMA_OR_END) {
        throw syntaxError();
      }
      depth--;
      completeValue();
    }

    private void completeValue() throws IOException {
      if (depth == 0) {
        throw syntaxError();
      }
      int frame = depth - 1;
      byte state = containerStates[frame];
      if (state == OBJECT_VALUE) {
        containerStates[frame] = OBJECT_COMMA_OR_END;
        if (depth == 1) {
          sectionIndex++;
        }
      } else if (state == ARRAY_VALUE_OR_END || state == ARRAY_VALUE_REQUIRED) {
        containerStates[frame] = ARRAY_COMMA_OR_END;
      } else {
        throw syntaxError();
      }
    }

    private void countTokenByte() throws IOException {
      tokenBytes++;
      if (tokenBytes > MAX_JSON_TOKEN_BYTES) {
        throw corruptionIo("Trading Lab report JSON token exceeds the protocol limit");
      }
    }

    private static boolean isCompleteNumberState(TokenState state) {
      return state == TokenState.NUMBER_ZERO
          || state == TokenState.NUMBER_INTEGER
          || state == TokenState.NUMBER_FRACTION
          || state == TokenState.NUMBER_EXPONENT;
    }

    private static boolean isWhitespace(int value) {
      return value == 0x20 || value == '\t' || value == '\r' || value == '\n';
    }

    private static boolean isHexDigit(int value) {
      return (value >= '0' && value <= '9')
          || (value >= 'a' && value <= 'f')
          || (value >= 'A' && value <= 'F');
    }

    private IOException syntaxError() {
      return new IOException("Trading Lab report JSON preflight failed");
    }

    @Override
    public void close() throws IOException {
      finish();
    }

    private TradingLabReportCorruptionIOException corruptionIo(String message) {
      return new TradingLabReportCorruptionIOException(corrupt(message));
    }

    private enum TokenState {
      NONE,
      STRING,
      STRING_ESCAPE,
      STRING_UNICODE,
      LITERAL,
      NUMBER_SIGN,
      NUMBER_ZERO,
      NUMBER_INTEGER,
      NUMBER_FRACTION_FIRST,
      NUMBER_FRACTION,
      NUMBER_EXPONENT_FIRST,
      NUMBER_EXPONENT_SIGN,
      NUMBER_EXPONENT
    }
  }

  private static final class TradingLabReportCorruptionIOException extends IOException {
    private final TradingLabReportCorruptionException corruption;

    private TradingLabReportCorruptionIOException(
        TradingLabReportCorruptionException corruption
    ) {
      super(corruption.getMessage(), corruption);
      this.corruption = corruption;
    }

    private TradingLabReportCorruptionException corruption() {
      return corruption;
    }
  }
}
