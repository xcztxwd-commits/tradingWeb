package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class TradingLabReportStreamerTest {

  private static final UUID REPORT_ID =
      UUID.fromString("9c7b61bc-5d91-4aaa-9ac8-f28a88a5ef24");
  private static final int CHUNK_BYTES = 4096;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void prepareValidatesTheWholeTerminalReportAndReturnsAnImmutableExactTicket() {
    Fixture fixture = validFailedFixture();

    TradingLabReportReadTicket ticket = fixture.streamer().prepare(REPORT_ID);

    assertThat(ticket).isEqualTo(new TradingLabReportReadTicket(
        REPORT_ID,
        7L,
        fixture.source().report().getUncompressedBytes(),
        fixture.source().report().getCompressedBytes(),
        fixture.source().report().getChunkCount()));
    assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
  }

  @Test
  void preparedStreamRechecksVersionAndTotalsBeforeWritingAnyResponseByte() {
    Fixture fixture = validFailedFixture();
    TradingLabReportReadTicket ticket = fixture.streamer().prepare(REPORT_ID);
    fixture.source().report().setVersion(8L);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    assertThatThrownBy(() -> fixture.streamer().stream(ticket, output))
        .isInstanceOf(TradingLabReportCorruptionException.class)
        .hasMessageContaining("changed after read preparation");
    assertThat(output.size()).isZero();
  }

  @Test
  void streamsAClosedFailedPartialReportInTheExactFourteenKeyOrder() throws Exception {
    Fixture fixture = validFailedFixture();
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    fixture.streamer().stream(REPORT_ID, output);

    String json = output.toString(StandardCharsets.UTF_8);
    assertThat(json).isEqualTo(fixture.expectedJson());
    JsonNode parsed = JSON.readTree(json);
    assertThat(iterable(parsed.fieldNames())).containsExactly(
        "metadata",
        "actor",
        "environment",
        "scenario",
        "modelVersion",
        "configSnapshot",
        "localCalculation",
        "lifecycle",
        "apiTrace",
        "marketTicks",
        "checkpoints",
        "actualState",
        "errors",
        "cleanup");
    assertThat(parsed.path("lifecycle")).hasSize(1);
    assertThat(parsed.path("marketTicks")).hasSize(2);
    assertThat(parsed.path("apiTrace")).isEmpty();
    assertThat(fixture.source().maxSimultaneouslyOpenCursors()).isOne();
    assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
  }

  @Test
  void assemblesNdjsonRecordsAndJsonTokensAcrossIndependentChunks() throws Exception {
    Fixture fixture = validFailedFixture();
    List<TradingLabReportChunkEntity> ticks = fixture.source().chunks()
        .get(TradingLabReportSection.MARKET_TICKS);

    assertThat(ticks).hasSizeGreaterThan(1);
    assertThat(codec().decodeAndVerify(ticks.getFirst()))
        .asString(StandardCharsets.UTF_8)
        .doesNotEndWith("\n");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    fixture.streamer().stream(REPORT_ID, output);

    assertThat(JSON.readTree(output.toByteArray()).path("marketTicks"))
        .isEqualTo(JSON.readTree("""
            [
              {"price":"1","symbol":"BTCUSDT"},
              {"price":"2","symbol":"ETHUSDT"}
            ]
            """));
  }

  @Test
  void guardedMeasurementRejectsCanaryReassembledByNdjsonCommaFraming() {
    Fixture fixture = validFailedFixture();
    String canary = "abc\"},{\"right\":\"def";
    fixture.source().chunks().put(
        TradingLabReportSection.ERRORS,
        List.of(chunk(
            TradingLabReportSection.ERRORS,
            0L,
            ("{\"left\":\"abc\"}\n{\"right\":\"def\"}\n")
                .getBytes(StandardCharsets.UTF_8))));
    fixture.refreshTerminalCounters();
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.register(canary);

    assertThatThrownBy(() -> fixture.streamer().measureForClose(REPORT_ID, secrets))
        .isInstanceOf(TradingLabCanonicalCanaryScanner.CanaryDetectedException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
  }

  @Test
  void guardedMeasurementRejectsCanaryAcrossSectionKeyFraming() {
    Fixture fixture = validFailedFixture();
    String canary = "abc\"},\"actor\":{\"right\":\"def";
    fixture.source().chunks().put(
        TradingLabReportSection.METADATA,
        List.of(chunk(
            TradingLabReportSection.METADATA,
            0L,
            "{\"left\":\"abc\"}".getBytes(StandardCharsets.UTF_8))));
    fixture.source().chunks().put(
        TradingLabReportSection.ACTOR,
        List.of(chunk(
            TradingLabReportSection.ACTOR,
            0L,
            "{\"right\":\"def\"}".getBytes(StandardCharsets.UTF_8))));
    fixture.refreshTerminalCounters();
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.register(canary);

    assertThatThrownBy(() -> fixture.streamer().measureForClose(REPORT_ID, secrets))
        .isInstanceOf(TradingLabCanonicalCanaryScanner.CanaryDetectedException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
  }

  @Test
  void guardedMeasurementRejectsEscapedModelVersionFallbackCanary() {
    Fixture fixture = validFailedFixture();
    String canary = "model-\"line\n" + UUID.randomUUID();
    fixture.source().report().setModelVersion(canary);
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.register(canary);

    assertThatThrownBy(() -> fixture.streamer().measureForClose(REPORT_ID, secrets))
        .isInstanceOf(TradingLabCanonicalCanaryScanner.CanaryDetectedException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
  }

  @Test
  void legacyGenericTerminalsWithUnsafeModelFailBeforeResponseBytes() {
    for (boolean cancelled : List.of(false, true)) {
      Fixture fixture = validFailedFixture();
      String canary = "legacy-terminal-model-secret-" + UUID.randomUUID();
      fixture.source().report().setModelVersion(canary);
      fixture.source().report().setStatus(cancelled ? "CANCELLED" : "FAILED");
      fixture.source().report().setFailureCode(
          cancelled ? "CANCELLED" : "TRADING_LAB_REPORT_UNSAFE_TRACE");
      fixture.source().report().setFailureMessage(
          "Trading Lab report contains unsafe trace evidence");
      fixture.source().report().setUncompressedBytes((long) fixture.expectedJson()
          .replace("model-v3", canary)
          .getBytes(StandardCharsets.UTF_8).length);
      ByteArrayOutputStream output = new ByteArrayOutputStream();

      assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
          .isInstanceOf(TradingLabReportCorruptionException.class);
      assertThat(output.size()).isZero();
    }
  }

  @Test
  void preflightPreventsAnyResponseBytesWhenAChunkChecksumIsCorrupt() {
    Fixture fixture = validFailedFixture();
    TradingLabReportChunkEntity corrupt = fixture.source().chunks()
        .get(TradingLabReportSection.ERRORS)
        .getFirst();
    corrupt.setChecksum("sha256:" + "0".repeat(64));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
        .isInstanceOf(TradingLabReportCorruptionException.class);
    assertThat(output.size()).isZero();
    assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
  }

  @Test
  void protocolIsIsolatedFromHostileGlobalMapperModulesMixinsAndConstraints() throws Exception {
    ObjectMapper hostileModuleMapper = new ObjectMapper();
    hostileModuleMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(1)
        .maxStringLength(1)
        .build());
    SimpleModule hostileModule = new SimpleModule();
    hostileModule.addSerializer(String.class, new HostileStringSerializer("hostile-module"));
    hostileModuleMapper.registerModule(hostileModule);
    assertThat(hostileModuleMapper.writeValueAsString("model-v3"))
        .isEqualTo("\"hostile-module\"");

    ObjectMapper hostileMixinMapper = new ObjectMapper();
    hostileMixinMapper.addMixIn(String.class, HostileStringMixin.class);
    assertThat(hostileMixinMapper.findMixInClassFor(String.class))
        .isEqualTo(HostileStringMixin.class);

    for (ObjectMapper hostile : List.of(hostileModuleMapper, hostileMixinMapper)) {
      Fixture fixture = validFailedFixture(hostile);
      ByteArrayOutputStream output = new ByteArrayOutputStream();

      fixture.streamer().stream(REPORT_ID, output);

      assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(fixture.expectedJson());
      assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
    }
  }

  @TestFactory
  Stream<DynamicTest> rejectsMalformedJsonGrammarWithValidChecksumsBeforeEmission() {
    return Stream.of(
        Map.entry("invalid escape", "{\"value\":\"bad\\x\"}\n"),
        Map.entry("leading zero", "{\"value\":01}\n"),
        Map.entry("missing fraction digit", "{\"value\":1.}\n"),
        Map.entry("missing exponent digit", "{\"value\":1e+}\n"),
        Map.entry("incomplete literal", "{\"value\":tru}\n"),
        Map.entry("array trailing comma", "{\"value\":[1,]}\n"),
        Map.entry("object trailing comma", "{\"value\":{\"nested\":1,}}\n"),
        Map.entry("raw string control", "{\"value\":\"raw\tcontrol\"}\n"),
        Map.entry("raw LF in string", "{\"value\":\"raw\ncontrol\"}\n"))
        .map(testCase -> DynamicTest.dynamicTest(testCase.getKey(), () -> {
          Fixture fixture = validFailedFixture();
          fixture.source().chunks().put(
              TradingLabReportSection.ERRORS,
              List.of(chunk(
                  TradingLabReportSection.ERRORS,
                  0L,
                  testCase.getValue().getBytes(StandardCharsets.UTF_8))));
          fixture.refreshTerminalCounters();
          ByteArrayOutputStream output = new ByteArrayOutputStream();

          assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
              .isInstanceOf(TradingLabReportCorruptionException.class);
          assertThat(output.size()).isZero();
          assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
        }));
  }

  @TestFactory
  Stream<DynamicTest> rejectsWhitespaceOnlyOrNonCanonicalNdjsonRecordsBeforeEmission() {
    return Stream.of(
        Map.entry("space-only record", " \n"),
        Map.entry("control-whitespace-only record", "\t\r\n"),
        Map.entry("presentation whitespace", "{\"value\": 1}\n"))
        .map(testCase -> DynamicTest.dynamicTest(testCase.getKey(), () -> {
          Fixture fixture = validFailedFixture();
          String record = testCase.getValue();
          fixture.source().chunks().put(
              TradingLabReportSection.ERRORS,
              List.of(chunk(
                  TradingLabReportSection.ERRORS,
                  0L,
                  record.getBytes(StandardCharsets.UTF_8))));
          fixture.refreshTerminalCounters();
          String previouslyAccepted = fixture.expectedJson().replace(
              "{\"code\":\"BOOM\"}",
              record.substring(0, record.length() - 1));
          fixture.source().report().setUncompressedBytes(
              (long) previouslyAccepted.getBytes(StandardCharsets.UTF_8).length);
          ByteArrayOutputStream output = new ByteArrayOutputStream();

          assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
              .isInstanceOf(TradingLabReportCorruptionException.class);
          assertThat(output.size()).isZero();
          assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
        }));
  }

  @Test
  void acceptsCanonicalDepthBoundaryInsideArrayReportFraming() throws Exception {
    Fixture fixture = validFailedFixture();
    String boundaryRecord = "{}";
    for (int depth = 0; depth < 64; depth++) {
      boundaryRecord = "{\"nested\":" + boundaryRecord + "}";
    }
    fixture.source().chunks().put(
        TradingLabReportSection.ERRORS,
        List.of(chunk(
            TradingLabReportSection.ERRORS,
            0L,
            (boundaryRecord + "\n").getBytes(StandardCharsets.UTF_8))));
    fixture.refreshTerminalCounters();
    String expected = fixture.expectedJson().replace("{\"code\":\"BOOM\"}", boundaryRecord);
    fixture.source().report().setUncompressedBytes(
        (long) expected.getBytes(StandardCharsets.UTF_8).length);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    fixture.streamer().stream(REPORT_ID, output);

    assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(expected);
    assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
  }

  @TestFactory
  Stream<DynamicTest> streamPathRejectsEveryCorruptEnvelopeAndTerminalTotalBeforeEmission() {
    List<CorruptionCase> cases = List.of(
        new CorruptionCase("bad GZIP", (fixture, canary) -> {
          byte[] invalidPayload = ("not-gzip-" + canary).getBytes(StandardCharsets.UTF_8);
          TradingLabReportChunkEntity target = firstChunk(
              fixture, TradingLabReportSection.LIFECYCLE);
          target.setPayload(invalidPayload);
          target.setCompressedBytes((long) invalidPayload.length);
          target.setUncompressedBytes((long) invalidPayload.length);
          target.setChecksum(checksum(invalidPayload));
        }),
        new CorruptionCase("declared compressed size", (fixture, canary) -> {
          TradingLabReportChunkEntity target = firstChunk(
              fixture, TradingLabReportSection.LIFECYCLE);
          target.setCompressedBytes(target.getCompressedBytes() + 1L);
        }),
        new CorruptionCase("declared raw size", (fixture, canary) -> {
          TradingLabReportChunkEntity target = firstChunk(
              fixture, TradingLabReportSection.LIFECYCLE);
          target.setUncompressedBytes(target.getUncompressedBytes() + 1L);
        }),
        new CorruptionCase("sequence gap", (fixture, canary) ->
            fixture.source().chunks().get(TradingLabReportSection.MARKET_TICKS)
                .get(1).setSequence(2L)),
        new CorruptionCase("sequence regression", (fixture, canary) ->
            fixture.source().chunks().get(TradingLabReportSection.MARKET_TICKS)
                .get(1).setSequence(0L)),
        new CorruptionCase("unsupported encoding", (fixture, canary) ->
            firstChunk(fixture, TradingLabReportSection.LIFECYCLE).setEncoding("PLAIN")),
        new CorruptionCase("invalid UTF-8", (fixture, canary) -> {
          byte[] prefix = ("{\"value\":\"" + canary).getBytes(StandardCharsets.UTF_8);
          byte[] invalidUtf8 = Arrays.copyOf(prefix, prefix.length + 1);
          invalidUtf8[invalidUtf8.length - 1] = (byte) 0xc3;
          fixture.source().chunks().put(
              TradingLabReportSection.LIFECYCLE,
              List.of(uncheckedChunk(TradingLabReportSection.LIFECYCLE, 0L, invalidUtf8)));
        }),
        new CorruptionCase("empty chunk", (fixture, canary) ->
            fixture.source().chunks().put(
                TradingLabReportSection.LIFECYCLE,
                List.of(uncheckedChunk(
                    TradingLabReportSection.LIFECYCLE, 0L, new byte[0])))),
        new CorruptionCase("missing final LF", (fixture, canary) ->
            fixture.source().chunks().put(
                TradingLabReportSection.ERRORS,
                List.of(chunk(
                    TradingLabReportSection.ERRORS,
                    0L,
                    ("{\"code\":\"" + canary + "\"}")
                        .getBytes(StandardCharsets.UTF_8))))),
        new CorruptionCase("terminal compressed byte count", (fixture, canary) ->
            fixture.source().report().setCompressedBytes(
                fixture.source().report().getCompressedBytes() + 1L)),
        new CorruptionCase("terminal chunk count", (fixture, canary) ->
            fixture.source().report().setChunkCount(
                fixture.source().report().getChunkCount() + 1)),
        new CorruptionCase("exact output byte total", (fixture, canary) ->
            fixture.source().report().setUncompressedBytes(
                fixture.source().report().getUncompressedBytes() + 1L)));

    return cases.stream().map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
      Fixture fixture = validFailedFixture();
      String canary = "stream-matrix-secret-" + UUID.randomUUID();
      installCanary(fixture, canary);
      testCase.corrupt().accept(fixture, canary);
      ByteArrayOutputStream output = new ByteArrayOutputStream();

      assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
          .isInstanceOfSatisfying(TradingLabReportCorruptionException.class, exception ->
              assertCauseChainDoesNotContain(exception, canary));
      assertThat(output.size()).isZero();
      assertThat(fixture.source().allOpenedCursorsClosed()).isTrue();
    }));
  }

  @Test
  void rejectsValidChecksumContentThatDoesNotFormTheRequiredJsonShapeBeforeEmission() {
    Fixture fixture = validFailedFixture();
    fixture.source().chunks().put(
        TradingLabReportSection.ERRORS,
        List.of(chunk(TradingLabReportSection.ERRORS, 0L,
            "not-json\n".getBytes(StandardCharsets.UTF_8))));
    fixture.refreshTerminalCounters();
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
        .isInstanceOf(TradingLabReportCorruptionException.class);
    assertThat(output.size()).isZero();
  }

  @Test
  void preflightParseFailureDoesNotRetainUntrustedPayloadInItsMessageOrCauseChain() {
    Fixture fixture = validFailedFixture();
    String canary = "stream-preflight-secret-" + UUID.randomUUID();
    fixture.source().chunks().put(
        TradingLabReportSection.ERRORS,
        List.of(chunk(
            TradingLabReportSection.ERRORS,
            0L,
            (canary + "\n").getBytes(StandardCharsets.UTF_8))));
    fixture.refreshTerminalCounters();
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    assertThatThrownBy(() -> fixture.streamer().stream(REPORT_ID, output))
        .isInstanceOfSatisfying(TradingLabReportCorruptionException.class, exception -> {
          assertThat(exception.getMessage())
              .isEqualTo("Trading Lab report JSON preflight failed")
              .doesNotContain(canary);
          assertThat(exception.getCause()).isNull();
          for (Throwable current = exception; current != null; current = current.getCause()) {
            assertThat(current.getMessage()).doesNotContain(canary);
          }
        });
    assertThat(output.size()).isZero();
  }

  @Test
  void rejectsSequenceGapsAndActiveReports() {
    Fixture gap = validFailedFixture();
    gap.source().chunks().get(TradingLabReportSection.MARKET_TICKS).get(1).setSequence(2L);
    ByteArrayOutputStream gapOutput = new ByteArrayOutputStream();

    assertThatThrownBy(() -> gap.streamer().stream(REPORT_ID, gapOutput))
        .isInstanceOf(TradingLabReportCorruptionException.class);
    assertThat(gapOutput.size()).isZero();

    Fixture active = validFailedFixture();
    active.source().report().setCompletedAt(null);
    ByteArrayOutputStream activeOutput = new ByteArrayOutputStream();

    assertThatThrownBy(() -> active.streamer().stream(REPORT_ID, activeOutput))
        .isInstanceOf(IllegalStateException.class);
    assertThat(activeOutput.size()).isZero();
  }

  private static Fixture validFailedFixture() {
    return validFailedFixture(JSON);
  }

  private static Fixture validFailedFixture(ObjectMapper objectMapper) {
    String lifecycle = "{\"state\":\"FAILED\"}\n";
    String ticks = "{\"price\":\"1\",\"symbol\":\"BTCUSDT\"}\n"
        + "{\"price\":\"2\",\"symbol\":\"ETHUSDT\"}\n";
    String errors = "{\"code\":\"BOOM\"}\n";

    Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks =
        new EnumMap<>(TradingLabReportSection.class);
    chunks.put(TradingLabReportSection.LIFECYCLE,
        List.of(chunk(TradingLabReportSection.LIFECYCLE, 0L,
            lifecycle.getBytes(StandardCharsets.UTF_8))));
    byte[] tickBytes = ticks.getBytes(StandardCharsets.UTF_8);
    int split = 17;
    chunks.put(TradingLabReportSection.MARKET_TICKS, List.of(
        chunk(TradingLabReportSection.MARKET_TICKS, 0L,
            Arrays.copyOfRange(tickBytes, 0, split)),
        chunk(TradingLabReportSection.MARKET_TICKS, 1L,
            Arrays.copyOfRange(tickBytes, split, tickBytes.length))));
    chunks.put(TradingLabReportSection.ERRORS,
        List.of(chunk(TradingLabReportSection.ERRORS, 0L,
            errors.getBytes(StandardCharsets.UTF_8))));

    String expected = "{"
        + "\"metadata\":{},"
        + "\"actor\":{},"
        + "\"environment\":{},"
        + "\"scenario\":{},"
        + "\"modelVersion\":\"model-v3\","
        + "\"configSnapshot\":{},"
        + "\"localCalculation\":{},"
        + "\"lifecycle\":[{\"state\":\"FAILED\"}],"
        + "\"apiTrace\":[],"
        + "\"marketTicks\":[{\"price\":\"1\",\"symbol\":\"BTCUSDT\"},"
        + "{\"price\":\"2\",\"symbol\":\"ETHUSDT\"}],"
        + "\"checkpoints\":[],"
        + "\"actualState\":{},"
        + "\"errors\":[{\"code\":\"BOOM\"}],"
        + "\"cleanup\":{}"
        + "}";

    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(REPORT_ID);
    report.setStatus("FAILED");
    report.setModelVersion("model-v3");
    report.setMetadataJson("{}");
    report.setCompletedAt(Instant.parse("2026-07-19T00:00:00Z"));
    report.setVersion(7L);

    FakeChunkSource source = new FakeChunkSource(report, chunks);
    TransactionTemplate transactionTemplate = immediateTransactions();
    TradingLabReportStreamer streamer = new TradingLabReportStreamer(
        source,
        codec(),
        transactionTemplate,
        objectMapper);
    Fixture fixture = new Fixture(streamer, source, expected);
    fixture.refreshTerminalCounters();
    return fixture;
  }

  private static TradingLabReportChunkEntity chunk(
      TradingLabReportSection section,
      long sequence,
      byte[] plain
  ) {
    TradingLabEncodedChunk encoded = codec().encode(plain);
    TradingLabReportChunkEntity chunk = new TradingLabReportChunkEntity();
    chunk.setId(UUID.randomUUID());
    chunk.setReportId(REPORT_ID);
    chunk.setSection(section.name());
    chunk.setSequence(sequence);
    chunk.setEncoding(encoded.encoding());
    chunk.setUncompressedBytes(encoded.uncompressedBytes());
    chunk.setCompressedBytes(encoded.compressedBytes());
    chunk.setPayload(Arrays.copyOf(encoded.payload(), encoded.payload().length));
    chunk.setChecksum(encoded.checksum());
    return chunk;
  }

  private static TradingLabReportChunkEntity uncheckedChunk(
      TradingLabReportSection section,
      long sequence,
      byte[] plain
  ) {
    byte[] payload = gzip(plain);
    TradingLabReportChunkEntity chunk = new TradingLabReportChunkEntity();
    chunk.setId(UUID.randomUUID());
    chunk.setReportId(REPORT_ID);
    chunk.setSection(section.name());
    chunk.setSequence(sequence);
    chunk.setEncoding("GZIP");
    chunk.setUncompressedBytes((long) plain.length);
    chunk.setCompressedBytes((long) payload.length);
    chunk.setPayload(payload);
    chunk.setChecksum(checksum(plain));
    return chunk;
  }

  private static byte[] gzip(byte[] plain) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(plain);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to create corrupt-stream fixture", exception);
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

  private static TradingLabReportChunkEntity firstChunk(
      Fixture fixture,
      TradingLabReportSection section
  ) {
    return fixture.source().chunks().get(section).getFirst();
  }

  private static void installCanary(Fixture fixture, String canary) {
    fixture.source().chunks().put(
        TradingLabReportSection.ERRORS,
        List.of(chunk(
            TradingLabReportSection.ERRORS,
            0L,
            ("{\"code\":\"" + canary + "\"}\n").getBytes(StandardCharsets.UTF_8))));
    fixture.refreshTerminalCounters();
    fixture.source().report().setUncompressedBytes((long) fixture.expectedJson()
        .replace("BOOM", canary)
        .getBytes(StandardCharsets.UTF_8).length);
  }

  private static void assertCauseChainDoesNotContain(Throwable exception, String canary) {
    for (Throwable current = exception; current != null; current = current.getCause()) {
      assertThat(current.getMessage() == null || !current.getMessage().contains(canary))
          .as("exception messages must not retain an untrusted payload")
          .isTrue();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static TransactionTemplate immediateTransactions() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
        ((TransactionCallback) invocation.getArgument(0)).doInTransaction(null));
    return template;
  }

  private static TradingLabReportChunkCodec codec() {
    return new TradingLabReportChunkCodec(CHUNK_BYTES);
  }

  private static <T> Iterable<T> iterable(Iterator<T> iterator) {
    return () -> iterator;
  }

  private record Fixture(
      TradingLabReportStreamer streamer,
      FakeChunkSource source,
      String expectedJson
  ) {
    void refreshTerminalCounters() {
      long compressed = source.chunks().values().stream()
          .flatMap(List::stream)
          .mapToLong(TradingLabReportChunkEntity::getCompressedBytes)
          .sum();
      int count = source.chunks().values().stream().mapToInt(List::size).sum();
      source.report().setCompressedBytes(compressed);
      source.report().setChunkCount(count);
      source.report().setUncompressedBytes((long) expectedJson.getBytes(StandardCharsets.UTF_8).length);
    }
  }

  private record CorruptionCase(
      String name,
      BiConsumer<Fixture, String> corrupt
  ) {
  }

  @JsonSerialize(using = HostileMixinStringSerializer.class)
  private abstract static class HostileStringMixin {
  }

  private static final class HostileStringSerializer extends JsonSerializer<String> {
    private final String replacement;

    private HostileStringSerializer(String replacement) {
      this.replacement = replacement;
    }

    @Override
    public void serialize(
        String value,
        JsonGenerator generator,
        SerializerProvider serializers
    ) throws IOException {
      generator.writeString(replacement);
    }
  }

  private static final class HostileMixinStringSerializer extends JsonSerializer<String> {
    @Override
    public void serialize(
        String value,
        JsonGenerator generator,
        SerializerProvider serializers
    ) throws IOException {
      generator.writeString("hostile-mixin");
    }
  }

  private static final class FakeChunkSource implements TradingLabReportChunkSource {
    private final TradingLabReportEntity report;
    private final Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks;
    private final List<ListCursor<TradingLabReportChunkEntity>> opened = new ArrayList<>();
    private int openNow;
    private int maxOpen;

    private FakeChunkSource(
        TradingLabReportEntity report,
        Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks
    ) {
      this.report = report;
      this.chunks = chunks;
    }

    @Override
    public TradingLabReportEntity lockReportForStream(UUID reportId) {
      assertThat(reportId).isEqualTo(REPORT_ID);
      return report;
    }

    @Override
    public Cursor<TradingLabReportChunkEntity> openChunks(
        UUID reportId,
        TradingLabReportSection section
    ) {
      assertThat(reportId).isEqualTo(REPORT_ID);
      openNow++;
      maxOpen = Math.max(maxOpen, openNow);
      ListCursor<TradingLabReportChunkEntity> cursor = new ListCursor<>(
          chunks.getOrDefault(section, List.of()),
          () -> openNow--);
      opened.add(cursor);
      return cursor;
    }

    TradingLabReportEntity report() {
      return report;
    }

    Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks() {
      return chunks;
    }

    int maxSimultaneouslyOpenCursors() {
      return maxOpen;
    }

    boolean allOpenedCursorsClosed() {
      return opened.stream().allMatch(ListCursor::isClosed);
    }
  }

  private static final class ListCursor<T> implements Cursor<T> {
    private final List<T> values;
    private final Runnable onClose;
    private boolean open = true;
    private boolean consumed;
    private int currentIndex = -1;

    private ListCursor(List<T> values, Runnable onClose) {
      this.values = values;
      this.onClose = onClose;
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
    public Iterator<T> iterator() {
      Iterator<T> delegate = values.iterator();
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          boolean hasNext = delegate.hasNext();
          if (!hasNext) {
            consumed = true;
          }
          return hasNext;
        }

        @Override
        public T next() {
          currentIndex++;
          return delegate.next();
        }
      };
    }

    @Override
    public void close() throws IOException {
      if (open) {
        open = false;
        onClose.run();
      }
    }

    boolean isClosed() {
      return !open;
    }
  }
}
