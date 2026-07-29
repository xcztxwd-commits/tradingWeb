package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class TradingLabChunkedReportWriterTest {

  @Test
  void persistsTheBufferedPrefixAndWholeAppendWhenTheThresholdIsReached() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.LIFECYCLE, Map.of("step", "one"));
    TradingLabCanonicalValue second = canonicalizer.canonicalize(
        TradingLabReportSection.LIFECYCLE, Map.of("step", "two"));
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(
        canonicalizer, store, first.byteLength() + second.byteLength(), 1);
    UUID reportId = UUID.randomUUID();

    writer.append(reportId, TradingLabReportSection.LIFECYCLE, Map.of("step", "one"));
    assertThat(store.batches).isEmpty();

    writer.append(reportId, TradingLabReportSection.LIFECYCLE, Map.of("step", "two"));

    assertThat(store.batches).hasSize(1);
    TradingLabReportSectionWrite write = store.batches.getFirst().sections().getFirst();
    assertThat(write.section()).isEqualTo(TradingLabReportSection.LIFECYCLE);
    assertThat(write.firstChunkSequence()).isZero();
    assertThat(write.appends()).extracting(TradingLabLogicalAppend::utf8)
        .containsExactly(first.utf8(), second.utf8());
    writer.flush(reportId);
    assertThat(store.batches).hasSize(1);
  }

  @Test
  void tinyAppendsFlushAtTheMetadataCapBeforeTheByteThresholdInOrder() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    int byteThreshold = 1024 * 1024;
    TradingLabChunkedReportWriter writer = writer(
        canonicalizer, store, byteThreshold, 1);
    UUID reportId = UUID.randomUUID();
    List<String> expected = new ArrayList<>();

    for (int sequence = 0; sequence < 1024; sequence++) {
      Map<String, Integer> value = Map.of("sequence", sequence);
      expected.add(canonicalizer.canonicalize(
          TradingLabReportSection.LIFECYCLE, value).utf8());
      writer.append(reportId, TradingLabReportSection.LIFECYCLE, value);
    }

    assertThat(expected.stream()
        .mapToInt(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .sum()).isLessThan(byteThreshold);
    assertThat(store.batches).hasSize(1);
    TradingLabReportSectionWrite capped =
        store.batches.getFirst().sections().getFirst();
    assertThat(capped.appends())
        .extracting(TradingLabLogicalAppend::utf8)
        .containsExactlyElementsOf(expected);

    Map<String, Integer> tail = Map.of("sequence", 1024);
    String expectedTail = canonicalizer.canonicalize(
        TradingLabReportSection.LIFECYCLE, tail).utf8();
    writer.append(reportId, TradingLabReportSection.LIFECYCLE, tail);
    assertThat(store.batches).hasSize(1);

    writer.flush(reportId);

    assertThat(store.batches).hasSize(2);
    assertThat(store.batches.get(1).sections().getFirst().appends())
        .extracting(TradingLabLogicalAppend::utf8)
        .containsExactly(expectedTail);
  }

  @Test
  void thresholdMinusOneStaysLazyAndFlushesAllTailsInEnumOrder() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    TradingLabCanonicalValue metadata = canonicalizer.canonicalize(
        TradingLabReportSection.METADATA, Map.of("a", 1));
    TradingLabCanonicalValue error = canonicalizer.canonicalize(
        TradingLabReportSection.ERRORS, Map.of("code", "SAFE"));
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(
        canonicalizer, store, metadata.byteLength() + error.byteLength() + 1, 1);
    UUID reportId = UUID.randomUUID();

    writer.append(reportId, TradingLabReportSection.ERRORS, Map.of("code", "SAFE"));
    writer.append(reportId, TradingLabReportSection.METADATA, Map.of("a", 1));
    assertThat(store.batches).isEmpty();

    writer.flush(reportId);

    assertThat(store.batches).hasSize(1);
    assertThat(store.batches.getFirst().sections())
        .extracting(TradingLabReportSectionWrite::section)
        .containsExactly(
            TradingLabReportSection.METADATA,
            TradingLabReportSection.ERRORS);
  }

  @Test
  void singletonAndFencedSourceSequenceReplayExactlyButConflictOnDifferentMeaning() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "writer:" + UUID.randomUUID());

    writer.append(reportId, TradingLabReportSection.METADATA, Map.of("model", "v1"));
    writer.append(reportId, TradingLabReportSection.METADATA, Map.of("model", "v1"));
    assertCode(
        () -> writer.append(
            reportId, TradingLabReportSection.METADATA, Map.of("model", "v2")),
        "TRADING_LAB_REPORT_SINGLETON_CONFLICT");

    writer.flush(reportId);
    writer.appendEvent(
        fence, TradingLabReportSection.CHECKPOINTS, 7L, Map.of("step", 1));
    writer.appendEvent(
        fence, TradingLabReportSection.CHECKPOINTS, 7L, Map.of("step", 1));
    assertCode(
        () -> writer.appendEvent(
            fence, TradingLabReportSection.CHECKPOINTS, 7L, Map.of("step", 2)),
        "TRADING_LAB_REPORT_EVENT_CONFLICT");

    writer.flush(fence);
    assertThat(store.batches).hasSize(2);
    TradingLabReportWriteBatch setup = store.batches.getFirst();
    TradingLabReportWriteBatch claimed = store.batches.get(1);
    assertThat(setup.fence()).isNull();
    assertThat(setup.sections()).extracting(TradingLabReportSectionWrite::section)
        .containsExactly(TradingLabReportSection.METADATA);
    assertThat(claimed.fence()).isEqualTo(fence);
    assertThat(claimed.sections()).extracting(TradingLabReportSectionWrite::section)
        .containsExactly(TradingLabReportSection.CHECKPOINTS);
    assertThat(claimed.sections().getFirst().appends().getFirst().sourceSequence())
        .isEqualTo(7L);
  }

  @Test
  void fencedSingletonUsesTheDurableMinusOneReplayIdentity() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "singleton-owner");

    writer.appendSingleton(
        fence, TradingLabReportSection.ACTUAL_STATE, Map.of("status", "flat"));
    writer.appendSingleton(
        fence, TradingLabReportSection.ACTUAL_STATE, Map.of("status", "flat"));
    assertCode(
        () -> writer.appendSingleton(
            fence, TradingLabReportSection.ACTUAL_STATE, Map.of("status", "open")),
        "TRADING_LAB_REPORT_SINGLETON_CONFLICT");
    writer.flush(fence);

    assertThat(store.batches).hasSize(1);
    assertThat(store.batches.getFirst().fence()).isEqualTo(fence);
    assertThat(store.batches.getFirst().sections().getFirst().appends().getFirst()
        .sourceSequence()).isEqualTo(-1L);
  }

  @Test
  void fencedArrayPreambleLeavesSourceZeroForTheFirstJournalEvent() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "preamble-owner");
    SafeTradingLabHttpTrace preamble = safeTraceAt("/admin-acceptance");

    writer.appendEvent(
        fence, TradingLabReportSection.API_TRACE, -1L, preamble);
    writer.appendEvent(
        fence, TradingLabReportSection.API_TRACE, -1L, preamble);
    assertCode(
        () -> writer.appendEvent(
            fence,
            TradingLabReportSection.API_TRACE,
            -1L,
            safeTraceAt("/different-acceptance")),
        "TRADING_LAB_REPORT_SINGLETON_CONFLICT");
    assertThatThrownBy(() -> writer.appendEvent(
        fence,
        TradingLabReportSection.MARKET_TICKS,
        -1L,
        Map.of("source", "invalid")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab event source sequence is invalid");
    writer.flush(fence);

    assertThat(writer.highestDurableSourceSequence(
        fence, TradingLabReportSection.API_TRACE)).isEqualTo(-1L);
    writer.appendEvent(
        fence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceAt("/runtime-state"));
    writer.flush(fence);

    assertThat(store.batches).hasSize(2);
    assertThat(store.batches.getFirst().sections().getFirst().appends().getFirst()
        .sourceSequence()).isEqualTo(-1L);
    assertThat(store.batches.get(1).sections().getFirst().appends().getFirst()
        .sourceSequence()).isEqualTo(0L);
    assertThat(writer.highestDurableSourceSequence(
        fence, TradingLabReportSection.API_TRACE)).isEqualTo(0L);
  }

  @Test
  void highestDurableSourceSequenceDoesNotCountTheUnflushedTail() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "sequence-owner");

    assertThat(writer.highestDurableSourceSequence(
        fence, TradingLabReportSection.CHECKPOINTS)).isEqualTo(-1L);
    writer.appendEvent(
        fence, TradingLabReportSection.CHECKPOINTS, 4L, Map.of("step", 4));
    assertThat(writer.highestDurableSourceSequence(
        fence, TradingLabReportSection.CHECKPOINTS)).isEqualTo(-1L);
    writer.flush(fence);
    assertThat(writer.highestDurableSourceSequence(
        fence, TradingLabReportSection.CHECKPOINTS)).isEqualTo(4L);
  }

  @Test
  void fencedMetadataUsesTheReportLifetimeSecretRegistryForLaterAppends() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "metadata-owner");
    String canary = "metadata-secret-" + UUID.randomUUID();

    writer.initializeMetadata(
        fence,
        Map.of("apiToken", canary, "safeEcho", canary));
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("safeEcho", canary));
    writer.flush(fence);

    assertThat(store.metadata.get(reportId).utf8())
        .isEqualTo("{\"safeEcho\":\"[REDACTED]\"}");
    String persisted = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(persisted).contains("[REDACTED]").doesNotContain(canary);
  }

  @Test
  void fencedMetadataReplaysExactlyAndRejectsDifferentCanonicalMeaning() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "metadata-owner");

    writer.initializeMetadata(fence, Map.of("model", "v1"));
    writer.initializeMetadata(fence, Map.of("model", "v1"));

    assertThat(store.metadataWrites).isOne();
    assertCode(
        () -> writer.initializeMetadata(fence, Map.of("model", "v2")),
        "TRADING_LAB_REPORT_METADATA_CONFLICT");
    assertThat(store.metadata.get(reportId).utf8())
        .isEqualTo("{\"model\":\"v1\"}");
  }

  @Test
  void uncertainMetadataCommitRetriesTheOriginalCanonicalValueBeforeLaterInput() {
    for (boolean commitBeforeThrow : List.of(false, true)) {
      FakeStore store = new FakeStore();
      store.failNextMetadata = !commitBeforeThrow;
      store.failNextMetadataAfterMutation = commitBeforeThrow;
      TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
      UUID reportId = UUID.randomUUID();
      TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
          UUID.randomUUID(), reportId, "metadata-owner");

      assertThatThrownBy(() -> writer.initializeMetadata(
          fence, Map.of("model", "original")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("uncertain metadata commit");
      assertCode(
          () -> writer.initializeMetadata(fence, Map.of("model", "later")),
          "TRADING_LAB_REPORT_METADATA_CONFLICT");

      assertThat(store.metadata.get(reportId).utf8())
          .isEqualTo("{\"model\":\"original\"}");
      assertThat(store.metadataWrites).isOne();
    }
  }

  @Test
  void lateTraceSecretQuarantinesAndClearsPreviouslyPersistedMetadata() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "metadata-owner");
    String canary = "late-metadata-secret-" + UUID.randomUUID();

    writer.initializeMetadata(fence, Map.of("safeEcho", canary));
    assertThat(store.metadata.get(reportId).utf8()).contains(canary);

    assertThatThrownBy(() -> writer.appendEvent(
        fence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);

    assertThat(store.metadata).doesNotContainKey(reportId);
    assertThat(store.quarantined).contains(reportId);
  }

  @Test
  void commitUncertaintyRetainsTheSameCanonicalCandidateAndChunkSequence() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    TradingLabCanonicalValue value = canonicalizer.canonicalize(
        TradingLabReportSection.MARKET_TICKS, Map.of("price", "1"));
    FakeStore store = new FakeStore();
    store.failNextPersist = true;
    TradingLabChunkedReportWriter writer = writer(
        canonicalizer, store, value.byteLength(), 1);
    UUID reportId = UUID.randomUUID();

    assertThatThrownBy(() -> writer.append(
        reportId, TradingLabReportSection.MARKET_TICKS, Map.of("price", "1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");

    writer.flush(reportId);

    assertThat(store.attemptedBatches).hasSize(2);
    TradingLabReportSectionWrite first = store.attemptedBatches.get(0).sections().getFirst();
    TradingLabReportSectionWrite retry = store.attemptedBatches.get(1).sections().getFirst();
    assertThat(retry.firstChunkSequence()).isEqualTo(first.firstChunkSequence());
    assertThat(retry.appends()).extracting(TradingLabLogicalAppend::utf8)
        .containsExactlyElementsOf(first.appends().stream()
            .map(TradingLabLogicalAppend::utf8)
            .toList());
  }

  @Test
  void newUnmarkedAppendFirstResolvesTheRetainedUncertainCandidate() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.MARKET_TICKS, Map.of("price", "one"));
    TradingLabCanonicalValue second = canonicalizer.canonicalize(
        TradingLabReportSection.MARKET_TICKS, Map.of("price", "two"));
    FakeStore store = new FakeStore();
    store.failNextPersist = true;
    TradingLabChunkedReportWriter writer = writer(
        canonicalizer, store, first.byteLength(), 1);
    UUID reportId = UUID.randomUUID();

    assertThatThrownBy(() -> writer.append(
        reportId, TradingLabReportSection.MARKET_TICKS, Map.of("price", "one")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");

    writer.append(
        reportId, TradingLabReportSection.MARKET_TICKS, Map.of("price", "two"));

    assertThat(store.attemptedBatches).hasSize(3);
    TradingLabReportSectionWrite firstAttempt =
        store.attemptedBatches.get(0).sections().getFirst();
    TradingLabReportSectionWrite retry =
        store.attemptedBatches.get(1).sections().getFirst();
    TradingLabReportSectionWrite next =
        store.attemptedBatches.get(2).sections().getFirst();
    assertThat(retry.firstChunkSequence()).isEqualTo(firstAttempt.firstChunkSequence());
    assertThat(retry.appends()).extracting(TradingLabLogicalAppend::utf8)
        .containsExactly(first.utf8());
    assertThat(next.appends()).extracting(TradingLabLogicalAppend::utf8)
        .containsExactly(second.utf8());
  }

  @Test
  void commitUncertainRetryPrecedesTraceMergeAndFailClosedPurge() {
    RegistryCapturingCanonicalizer canonicalizer =
        new RegistryCapturingCanonicalizer();
    TradingLabCanonicalValue first = canonicalizer.canonicalize(
        TradingLabReportSection.MARKET_TICKS, Map.of("price", "one"));
    FakeStore store = new FakeStore();
    store.failNextPersist = true;
    TradingLabChunkedReportWriter writer = writer(
        canonicalizer, store, first.byteLength(), 1);
    UUID reportId = UUID.randomUUID();

    assertThatThrownBy(() -> writer.append(
        reportId, TradingLabReportSection.MARKET_TICKS, Map.of("price", "one")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");
    TradingLabReportSecretRegistry captured = canonicalizer.capturedSecrets;
    long generationBeforeRetry = captured.generation();
    store.observeNextPersist = ignored ->
        assertThat(captured.generation()).isEqualTo(generationBeforeRetry);

    String lateSecret = "uncertain-late-secret-" + UUID.randomUUID();
    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(lateSecret)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(lateSecret);

    assertThat(store.attemptedBatches.size()).isGreaterThanOrEqualTo(2);
    TradingLabReportWriteBatch firstAttempt = store.attemptedBatches.get(0);
    TradingLabReportWriteBatch exactRetry = store.attemptedBatches.get(1);
    assertThat(exactRetry.reportId()).isEqualTo(firstAttempt.reportId());
    assertThat(exactRetry.fence()).isEqualTo(firstAttempt.fence());
    assertThat(exactRetry.sections()).isEqualTo(firstAttempt.sections());
    assertThat(captured.generation()).isGreaterThan(generationBeforeRetry);
    assertThat(store.batches).isEmpty();

    writer.fail(reportId, "VALIDATION_FAILED", "summary-" + lateSecret);
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(lateSecret);
  }

  @Test
  void definitiveConflictOnUncertainRetryClearsCandidateAndAllowsFailureTerminal() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("value", "candidate"));
    store.failNextPersist = true;
    assertThatThrownBy(() -> writer.flush(reportId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");
    store.failNextPersistCode = "TRADING_LAB_REPORT_CHUNK_CONFLICT";

    assertCode(
        () -> writer.flush(reportId),
        "TRADING_LAB_REPORT_CHUNK_CONFLICT");
    assertCode(
        () -> writer.complete(reportId),
        "TRADING_LAB_REPORT_INCOMPLETE");
    writer.fail(reportId, "PERSIST_CONFLICT", "controlled summary");

    assertThat(store.attemptedBatches).hasSize(2);
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "PERSIST_CONFLICT", "controlled summary"));
  }

  @Test
  void definitiveFenceLostDuringPersistPreservesSecretsForReplacementFence() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "persist-fence-lost-secret-" + UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");
    writer.appendEvent(
        oldFence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary));
    store.failNextPersistCode = "TRADING_LAB_REPORT_FENCE_LOST";
    assertCode(
        () -> writer.flush(oldFence),
        "TRADING_LAB_REPORT_FENCE_LOST");

    writer.appendEvent(
        replacementFence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("safeEcho", canary));
    writer.flush(replacementFence);

    String persisted = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(persisted).contains("[REDACTED]").doesNotContain(canary);
  }

  @Test
  void definitiveClosedPersistReleasesAdmissionAndUnsafeMarkerTurnsGeneric() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID closedReport = UUID.randomUUID();
    writer.append(
        closedReport,
        TradingLabReportSection.ERRORS,
        Map.of("value", "closed-candidate"));
    store.failNextPersistCode = "TRADING_LAB_REPORT_CLOSED";
    assertCode(
        () -> writer.flush(closedReport),
        "TRADING_LAB_REPORT_CLOSED");

    UUID quarantinedReport = UUID.randomUUID();
    writer.append(
        quarantinedReport,
        TradingLabReportSection.ERRORS,
        Map.of("value", "unsafe-marker-candidate"));
    store.failNextPersistCode = "TRADING_LAB_REPORT_UNSAFE_TRACE";
    assertCode(
        () -> writer.flush(quarantinedReport),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertCode(
        () -> writer.append(
            quarantinedReport,
            TradingLabReportSection.ERRORS,
            Map.of("value", "must-stay-closed")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    writer.fail(quarantinedReport, "IGNORED", "ignored");

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void writerAdmissionIsBoundedUntilTerminalCloseAndCloseFlushesBeforeMeasuring() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    RecordingStreamer streamer = new RecordingStreamer(store);
    TradingLabChunkedReportWriter writer = new TradingLabChunkedReportWriter(
        canonicalizer,
        store,
        streamer,
        new TradingLabFixedValidationSecretProvider(List.of()),
        4096,
        1,
        Duration.ofDays(30));
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    writer.append(first, TradingLabReportSection.ERRORS, Map.of("safe", true));
    assertCode(
        () -> writer.append(second, TradingLabReportSection.ERRORS, Map.of("safe", true)),
        "TRADING_LAB_REPORT_WRITER_LIMIT");

    writer.fail(first, "VALIDATION_FAILED", "controlled summary");

    assertThat(streamer.measuredAfterPersist).isTrue();
    assertThat(store.finalizations).hasSize(1);
    assertThat(store.finalizations.getFirst().outcome())
        .isEqualTo(TradingLabReportOutcome.failed(
            "VALIDATION_FAILED", "controlled summary"));
    assertThat(store.finalizations.getFirst().retention()).isEqualTo(Duration.ofDays(30));

    writer.append(second, TradingLabReportSection.ERRORS, Map.of("safe", true));
    assertThatThrownBy(() -> writer.append(
        first, TradingLabReportSection.ERRORS, Map.of("late", true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_REPORT_CLOSED"));
  }

  @Test
  void redactsSecretsLearnedByEarlierSectionsAndFromLaterTerminalSummaries() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "cross-section-secret-" + UUID.randomUUID();

    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("accessToken", canary, "visible", true));
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("safeEcho", "before-" + canary + "-after"));
    writer.fail(reportId, "CONTROLLED_FAILURE", "summary-" + canary);

    String persisted = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(persisted)
        .contains("[REDACTED]")
        .doesNotContain(canary);
    assertThat(store.finalizations.getFirst().outcome().failureMessage())
        .isEqualTo("summary-[REDACTED]");
  }

  @Test
  void fixedValidationSecretsNeverReachSectionOrTerminalOutput() throws Exception {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    String fixedCode = "FIXED_SECRET_CODE";
    String escapedSecret = "fixed-\"\\secret-" + UUID.randomUUID();

    new ApplicationContextRunner()
        .withBean(TradingLabReportProperties.class, () -> {
          TradingLabReportProperties properties = new TradingLabReportProperties();
          properties.setMaxActiveWriters(2);
          return properties;
        })
        .withBean(TradingLabReportCanonicalizer.class, () -> canonicalizer)
        .withBean(TradingLabReportStore.class, () -> store)
        .withBean(
            TradingLabReportStreamer.class,
            () -> new RecordingStreamer(store))
        .withBean(
            TradingLabFixedValidationSecretProvider.class,
            () -> new TradingLabFixedValidationSecretProvider(
                List.of(fixedCode, escapedSecret)))
        .withBean(TradingLabChunkedReportWriter.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          TradingLabChunkedReportWriter writer =
              context.getBean(TradingLabChunkedReportWriter.class);

          UUID sectionReport = UUID.randomUUID();
          writer.append(
              sectionReport,
              TradingLabReportSection.SCENARIO,
              Map.of(
                  "safeCodeEcho", "before-" + fixedCode + "-after",
                  "safeEscapedEcho", "before-" + escapedSecret + "-after"));
          writer.append(
              sectionReport,
              TradingLabReportSection.ERRORS,
              Map.of("safeEcho", escapedSecret));
          writer.flush(sectionReport);

          UUID failedReport = UUID.randomUUID();
          writer.fail(
              failedReport,
              fixedCode,
              "terminal-" + fixedCode + "-" + escapedSecret);
          UUID cancelledReport = UUID.randomUUID();
          writer.cancel(cancelledReport, "cancel-" + escapedSecret);

          String assembled = store.batches.stream()
              .flatMap(batch -> batch.sections().stream())
              .flatMap(section -> section.appends().stream())
              .map(TradingLabLogicalAppend::utf8)
              .reduce("", String::concat);
          String jsonEscapedSecret = new ObjectMapper()
              .writeValueAsString(escapedSecret);
          jsonEscapedSecret = jsonEscapedSecret.substring(
              1, jsonEscapedSecret.length() - 1);
          assertThat(assembled)
              .contains("[REDACTED]")
              .doesNotContain(fixedCode, escapedSecret, jsonEscapedSecret);
          assertThat(store.finalizations).extracting(Finalization::outcome)
              .containsExactly(
                  TradingLabReportOutcome.failed(
                      "TRADING_LAB_REPORT_UNSAFE_TRACE",
                      "terminal-[REDACTED]-[REDACTED]"),
                  TradingLabReportOutcome.cancelled("cancel-[REDACTED]"));
          assertThat(store.finalizations.toString())
              .doesNotContain(fixedCode, escapedSecret, jsonEscapedSecret);
        });
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void randomFixedCanaryIsAbsentFromCanonicalGzipAssembledAndLogOutput(
      CapturedOutput capturedOutput
  ) throws Exception {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabReportChunkCodec codec = new TradingLabReportChunkCodec(4096);
    String canary = "writer-chain-\"\\secret-" + UUID.randomUUID();
    String jsonEscapedCanary = new ObjectMapper().writeValueAsString(canary);
    jsonEscapedCanary = jsonEscapedCanary.substring(1, jsonEscapedCanary.length() - 1);
    TradingLabChunkedReportWriter writer = new TradingLabChunkedReportWriter(
        canonicalizer,
        store,
        new RecordingStreamer(store),
        new TradingLabFixedValidationSecretProvider(List.of(canary)),
        4096,
        1,
        Duration.ofDays(30));
    UUID reportId = UUID.randomUUID();

    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", "scenario-" + canary));
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("safeEcho", "error-" + canary));
    writer.flush(reportId);

    String canonicalOutput = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(canonicalOutput)
        .contains("[REDACTED]")
        .doesNotContain(canary, jsonEscapedCanary);

    Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks =
        encodeBatches(reportId, store.batches, codec);
    String decodedGzipPayloads = chunks.values().stream()
        .flatMap(List::stream)
        .map(codec::decodeAndVerify)
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .reduce("", String::concat);
    assertThat(decodedGzipPayloads)
        .isEqualTo(canonicalOutput)
        .doesNotContain(canary, jsonEscapedCanary);

    TradingLabReportEntity report = reportForStreaming(reportId, chunks);
    TradingLabReportStreamer actualStreamer = new TradingLabReportStreamer(
        new MapChunkSource(report, chunks),
        codec,
        new NoOpTransactionTemplate(),
        new ObjectMapper());
    TradingLabReportMeasurement measurement = actualStreamer.measureForClose(reportId);
    report.setStatus(TradingLabReportStatus.FAILED.name());
    report.setCompletedAt(Instant.parse("2026-07-19T00:00:00Z"));
    report.setUncompressedBytes(measurement.exactUncompressedBytes());
    ByteArrayOutputStream assembledBytes = new ByteArrayOutputStream();

    actualStreamer.stream(reportId, assembledBytes);

    String assembled = assembledBytes.toString(StandardCharsets.UTF_8);
    assertThat(assembled)
        .doesNotContain(canary, jsonEscapedCanary);
    assertThat(new ObjectMapper().readTree(assembled)
        .path("scenario").path("safeEcho").asText())
        .isEqualTo("scenario-[REDACTED]");
    assertThat(new ObjectMapper().readTree(assembled)
        .path("errors").get(0).path("safeEcho").asText())
        .isEqualTo("error-[REDACTED]");
    assertThat(capturedOutput.getAll())
        .doesNotContain(canary, jsonEscapedCanary);
  }

  @Test
  void apiTraceSecretsRemainRegisteredForLaterErrorSections() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "trace-to-error-secret-" + UUID.randomUUID();
    SafeTradingLabHttpTrace trace = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of())
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            Map.of(),
            Map.of(),
            "application/json",
            Map.of("accessToken", canary, "visible", true),
            null,
            null,
            null,
            null,
            List.of(),
            null));

    writer.append(reportId, TradingLabReportSection.API_TRACE, trace);
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("safeEcho", "later-" + canary));
    writer.flush(reportId);

    String persisted = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(persisted)
        .contains("[REDACTED]")
        .doesNotContain(canary);
  }

  @Test
  void lateTraceSecretsRejectEarlierBufferedTailBeforePersistence() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "tail-before-trace-secret-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));
    SafeTradingLabHttpTrace trace = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of())
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            Map.of(),
            Map.of(),
            "application/json",
            Map.of("accessToken", canary, "visible", true),
            null,
            null,
            null,
            null,
            List.of(),
            null));

    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.API_TRACE,
        trace))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
    assertThat(store.batches).isEmpty();
    assertThat(store.attemptedBatches).isEmpty();
    assertThat(store.nextChunkSequenceCalls).isZero();

    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void lateNonTraceSecretsRejectEarlierBufferedTailBeforePersistence() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "tail-before-config-secret-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));

    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("accessToken", canary)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
    assertThat(store.batches).isEmpty();
    assertThat(store.attemptedBatches).isEmpty();
    assertThat(store.nextChunkSequenceCalls).isZero();

    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void quarantineClearsSingletonButStickyTaintRejectsSameSingletonSection() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = quarantineWithLateNonTraceSecret(
        writer, reportId, "singleton-sticky");

    assertCode(
        () -> writer.append(
            reportId,
            TradingLabReportSection.SCENARIO,
            Map.of("safe", "replacement-after-quarantine")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    assertThat(store.nextChunkSequenceCalls).isZero();
    assertThat(store.attemptedBatches).isEmpty();
    writer.fail(reportId, "VALIDATION_FAILED", "message-" + canary);
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void quarantinedReportsFailAndCancelGenericInSameCallWhileCompleteRejects() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);

    UUID failedReport = UUID.randomUUID();
    String failedSecret = quarantineWithLateNonTraceSecret(
        writer, failedReport, "generic-fail");
    writer.fail(failedReport, "VALIDATION_FAILED", "message-" + failedSecret);

    UUID cancelledReport = UUID.randomUUID();
    String cancelledSecret = quarantineWithLateNonTraceSecret(
        writer, cancelledReport, "generic-cancel");
    writer.cancel(cancelledReport, "reason-" + cancelledSecret);

    UUID completeReport = UUID.randomUUID();
    String completeSecret = quarantineWithLateNonTraceSecret(
        writer, completeReport, "complete-rejected");
    assertCode(
        () -> writer.complete(completeReport),
        "TRADING_LAB_REPORT_INCOMPLETE");

    assertThat(store.nextChunkSequenceCalls).isZero();
    assertThat(store.attemptedBatches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(
            TradingLabReportOutcome.failed(
                "TRADING_LAB_REPORT_UNSAFE_TRACE",
                "Trading Lab report contains unsafe trace evidence"),
            TradingLabReportOutcome.cancelled(
                "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString())
        .doesNotContain(failedSecret, cancelledSecret, completeSecret);
  }

  @Test
  void durableQuarantineDiscoveredDuringCloseUsesTheRequiredOutcomeInTheSameCall() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 3);

    UUID failedReport = UUID.randomUUID();
    writer.append(
        failedReport,
        TradingLabReportSection.ERRORS,
        Map.of("safe", "pending-before-fail"));
    store.quarantined.add(failedReport);
    writer.fail(failedReport, "VALIDATION_FAILED", "controlled summary");

    UUID cancelledReport = UUID.randomUUID();
    writer.append(
        cancelledReport,
        TradingLabReportSection.ERRORS,
        Map.of("safe", "pending-before-cancel"));
    store.quarantined.add(cancelledReport);
    writer.cancel(cancelledReport, "controlled reason");

    UUID completeReport = UUID.randomUUID();
    writer.append(
        completeReport,
        TradingLabReportSection.ERRORS,
        Map.of("safe", "pending-before-complete"));
    store.quarantined.add(completeReport);
    assertCode(
        () -> writer.complete(completeReport),
        "TRADING_LAB_REPORT_INCOMPLETE");

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(
            TradingLabReportOutcome.failed(
                "TRADING_LAB_REPORT_UNSAFE_TRACE",
                "Trading Lab report contains unsafe trace evidence"),
            TradingLabReportOutcome.cancelled(
                "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void lateTraceSecretsDiscardEarlierPersistedChunks() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 1, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "persisted-before-trace-secret-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));
    assertThat(store.batches).hasSize(1);

    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(canary)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);

    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void uncertainEvidenceDiscardStaysClosedWhenRetryFindsNothing() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 1, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "uncertain-discard-secret-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));
    store.failNextDiscardAfterMutation = true;

    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(canary)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain discard");
    assertThat(store.batches).isEmpty();

    assertCode(
        () -> writer.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safeEcho", "must-remain-closed")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    writer.fail(reportId, "VALIDATION_FAILED", "summary-" + canary);

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void knownSecretCannotSpanTwoBufferedAppends() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "abc\"}\n{\"right\":\"def";
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", canary));
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("left", "abc"));

    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("right", "def")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(canary);
    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
  }

  @Test
  void persistCanaryDefenseRunsBeforeSequenceAllocationAndStoreMutation() {
    PersistScanRejectingCanonicalizer canonicalizer =
        new PersistScanRejectingCanonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("safe", "buffered-before-persist-defense"));
    canonicalizer.rejectBufferedScan = true;

    assertThatThrownBy(() -> writer.flush(reportId))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasNoCause();

    assertThat(store.nextChunkSequenceCalls).isZero();
    assertThat(store.attemptedBatches).isEmpty();
    assertCode(
        () -> writer.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safe", "must-remain-quarantined")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
  }

  @Test
  void knownSecretCannotSpanTwoPersistedBatches() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "abc\"}\n{\"right\":\"def";
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", canary));
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("left", "abc"));
    writer.flush(reportId);
    assertThat(store.batches).hasSize(1);
    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("right", "def")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(canary);
    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
  }

  @Test
  void persistedEventReplayDoesNotReplaceTheActualDurableTail() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "owner");
    String canary = "abc\"}\n{\"right\":\"def";
    writer.appendEvent(
        fence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary));
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("old", "older"));
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        1L,
        Map.of("left", "abc"));
    writer.flush(fence);

    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("old", "older"));
    writer.flush(fence);

    assertThatThrownBy(() -> writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        2L,
        Map.of("right", "def")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
    writer.fail(fence, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void bufferedOlderReplayDoesNotObscureTheActualDurableTail() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "owner");
    String canary = "abc\"}\n{\"right\":\"def";
    writer.appendEvent(
        fence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary));
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("old", "older"));
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        1L,
        Map.of("left", "abc"));
    writer.flush(fence);

    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("old", "older"));
    assertThatThrownBy(() -> writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        2L,
        Map.of("right", "def")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
  }

  @Test
  void uncertainEventRetryRetainsTheRealDurableTailBeforeReplay() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "owner");
    String canary = "abc\"}\n{\"right\":\"def";
    writer.appendEvent(
        fence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary));
    writer.flush(fence);
    store.failNextPersist = true;
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("left", "abc"));
    assertThatThrownBy(() -> writer.flush(fence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");

    writer.flush(fence);
    writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("left", "abc"));
    assertThatThrownBy(() -> writer.appendEvent(
        fence,
        TradingLabReportSection.ERRORS,
        1L,
        Map.of("right", "def")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
  }

  @Test
  void uncertainFenceReplacementPermanentlyQuarantinesCommittedOrRolledBackCandidate() {
    for (boolean commitBeforeThrow : List.of(false, true)) {
      TradingLabReportCanonicalizer canonicalizer = canonicalizer();
      FakeStore store = new FakeStore();
      TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
      UUID reportId = UUID.randomUUID();
      TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
          UUID.randomUUID(), reportId, "old-owner");
      TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
          oldFence.runId(), reportId, "replacement-owner");
      String canary = "abc\"}\n{\"right\":\"def";
      writer.appendEvent(
          oldFence,
          TradingLabReportSection.API_TRACE,
          0L,
          safeTraceWithSecret(canary));
      writer.flush(oldFence);
      store.failNextPersist = !commitBeforeThrow;
      store.failNextPersistAfterMutation = commitBeforeThrow;
      writer.appendEvent(
          oldFence,
          TradingLabReportSection.ERRORS,
          0L,
          Map.of("candidate", "old-owner"));
      assertThatThrownBy(() -> writer.flush(oldFence))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("uncertain commit");

      assertCode(
          () -> writer.appendEvent(
              replacementFence,
              TradingLabReportSection.ERRORS,
              0L,
              Map.of("left", "abc")),
          "TRADING_LAB_REPORT_UNSAFE_TRACE");
      assertThat(store.batches).isEmpty();
      assertCode(
          () -> writer.complete(replacementFence),
          "TRADING_LAB_REPORT_INCOMPLETE");
      writer.fail(replacementFence, "TAKEOVER_INCOMPLETE", "controlled summary");
      assertThat(store.finalizations).extracting(Finalization::outcome)
          .containsExactly(TradingLabReportOutcome.failed(
              "TRADING_LAB_REPORT_UNSAFE_TRACE",
              "Trading Lab report contains unsafe trace evidence"));
    }
  }

  @Test
  void uncertainFenceTakeoverIsAppendClosedAndCanOnlyUseGenericTerminal() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");
    writer.appendEvent(
        oldFence, TradingLabReportSection.ERRORS, 0L, Map.of("value", "confirmed"));
    writer.flush(oldFence);
    store.failNextPersist = true;
    writer.appendEvent(
        oldFence, TradingLabReportSection.ERRORS, 1L, Map.of("value", "uncertain"));
    assertThatThrownBy(() -> writer.flush(oldFence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");

    assertCode(
        () -> writer.appendEvent(
            replacementFence,
            TradingLabReportSection.ERRORS,
            0L,
            Map.of("value", "replacement")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    assertCode(
        () -> writer.complete(replacementFence),
        "TRADING_LAB_REPORT_INCOMPLETE");
    writer.fail(replacementFence, "TAKEOVER_INCOMPLETE", "controlled summary");
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void fenceLostOnUncertainRetryCannotEraseTakeoverQuarantine() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");
    writer.appendEvent(
        oldFence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("value", "uncertain-old-owner"));
    store.failNextPersist = true;
    assertThatThrownBy(() -> writer.flush(oldFence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");
    store.failNextPersistCode = "TRADING_LAB_REPORT_FENCE_LOST";

    assertCode(
        () -> writer.flush(oldFence),
        "TRADING_LAB_REPORT_FENCE_LOST");
    assertCode(
        () -> writer.appendEvent(
            replacementFence,
            TradingLabReportSection.ERRORS,
            0L,
            Map.of("value", "replacement-must-stay-closed")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(store.batches).isEmpty();
    assertCode(
        () -> writer.complete(replacementFence),
        "TRADING_LAB_REPORT_INCOMPLETE");
    writer.fail(replacementFence, "TAKEOVER_INCOMPLETE", "controlled summary");
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void uncertainTakeoverQuarantineResponseCannotReopenAfterMutation() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");
    writer.appendEvent(
        oldFence, TradingLabReportSection.ERRORS, 0L, Map.of("value", "confirmed"));
    writer.flush(oldFence);
    store.failNextPersist = true;
    writer.appendEvent(
        oldFence, TradingLabReportSection.ERRORS, 1L, Map.of("value", "uncertain"));
    assertThatThrownBy(() -> writer.flush(oldFence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain commit");
    store.failNextDiscardAfterMutation = true;

    assertThatThrownBy(() -> writer.appendEvent(
        replacementFence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("value", "first-retry")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("uncertain discard");
    assertCode(
        () -> writer.appendEvent(
            replacementFence,
            TradingLabReportSection.ERRORS,
            0L,
            Map.of("value", "after-confirmed-empty")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    assertCode(
        () -> writer.complete(replacementFence),
        "TRADING_LAB_REPORT_INCOMPLETE");
    writer.cancel(replacementFence, "controlled reason");
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.cancelled(
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void finalAssemblyCanaryPurgesEvidenceAndFailsGeneric() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "abc\"},{\"right\":\"def";
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", canary));
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("left", "abc"));
    writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("right", "def"));

    writer.fail(reportId, "VALIDATION_FAILED", "controlled summary");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void staticFinalFramingCanaryCannotBeFinalizedAsIfPurgeMadeItSafe() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = new TradingLabChunkedReportWriter(
        canonicalizer,
        store,
        new RecordingStreamer(store),
        new TradingLabFixedValidationSecretProvider(List.of("metadata")),
        4096,
        1,
        Duration.ofDays(30));
    UUID reportId = UUID.randomUUID();

    assertCode(
        () -> writer.fail(reportId, "VALIDATION_FAILED", "controlled summary"),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).isEmpty();
  }

  @Test
  void traceRegistryOverflowDiscardsUntrustedBufferedTail() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "trace-overflow-tail-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", java.util.stream.IntStream.range(0, 255)
            .mapToObj(index -> "REGISTERED_SECRET_" + index)
            .toList()));
    SafeTradingLabHttpTrace trace = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of(canary, "SECOND_TRACE_OVERFLOW_SECRET"))
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null));

    assertThatThrownBy(() -> writer.append(
        reportId, TradingLabReportSection.API_TRACE, trace))
        .isInstanceOf(TradingLabTraceSecretRegistry.MergeOverflowException.class);
    assertCode(
        () -> writer.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safeEcho", canary)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(store.attemptedBatches).isEmpty();
    assertThat(store.nextChunkSequenceCalls).isZero();
    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void reportRegistryOverflowDiscardsUntrustedBufferedTail() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "report-overflow-tail-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", java.util.stream.IntStream.range(0, 256)
            .mapToObj(index -> "REGISTERED_SECRET_" + index)
            .toList()));

    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("credentials", canary)))
        .isInstanceOf(
            TradingLabReportSecretRegistry.RegistrationOverflowException.class);
    assertCode(
        () -> writer.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safeEcho", canary)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(store.attemptedBatches).isEmpty();
    assertThat(store.nextChunkSequenceCalls).isZero();
    writer.fail(reportId, "VALIDATION_FAILED", "Unsafe evidence was rejected");

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void replacementFenceCarriesDynamicSecretsAcrossTheFreshBuffer() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 1, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "replacement-fence-secret-" + UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");

    writer.appendEvent(
        oldFence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary));
    writer.appendEvent(
        replacementFence,
        TradingLabReportSection.ERRORS,
        1L,
        Map.of("safeEcho", canary));

    String persisted = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(persisted)
        .contains("[REDACTED]")
        .doesNotContain(canary);
  }

  @Test
  void preJournalTraceRegistrySurvivesTakeoverAndRejectsALaterSecretEcho() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "pre-journal-takeover-secret-" + UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");

    writer.initializeMetadata(oldFence, Map.of("credentials", canary));
    boolean accepted = writer.withJournalEvidenceGuard(
        oldFence,
        safeTraceWithSecret(canary),
        guard -> {
          guard.requireSafe(Map.of("status", "safe"));
          return true;
        });
    assertThat(accepted).isTrue();

    assertThatThrownBy(() -> writer.withJournalEvidenceGuard(
        replacementFence,
        null,
        guard -> {
          guard.requireSafe(Map.of("safeEcho", canary));
          return true;
        }))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary);
    assertCode(
        () -> writer.requireSafeJournalEvidence(
            replacementFence, Map.of("status", "already-sealed")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void journalGuardRequiresDurableMetadataAndFreshRecoveryRejectsMetadataOnlyState() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "metadata-owner");
    TradingLabChunkedReportWriter original = writer(canonicalizer, store, 4096, 1);

    original.initializeMetadata(fence, Map.of("run", "durable"));

    TradingLabChunkedReportWriter recovered = writer(canonicalizer, store, 4096, 1);
    assertCode(
        () -> recovered.withJournalEvidenceGuard(
            fence,
            null,
            guard -> {
              guard.requireSafe(Map.of("status", "must-not-run"));
              return true;
            }),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(store.quarantined).containsExactly(reportId);
    assertThat(store.metadata).doesNotContainKey(reportId);
  }

  @Test
  void journalGuardQuarantinesAPristineReportInsteadOfAllowingAnUndurableRegistry() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "pristine-owner");

    assertCode(
        () -> writer.withJournalEvidenceGuard(
            fence,
            safeTraceWithSecret("pristine-secret-" + UUID.randomUUID()),
            guard -> true),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertThat(store.quarantined).containsExactly(reportId);
  }

  @Test
  void recoveredWriterRejectsEvidenceWhenDynamicSecretContextWasLost() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    UUID reportId = UUID.randomUUID();
    String canary = "recovered-writer-secret-" + UUID.randomUUID();
    writer(canonicalizer, store, 1, 1).append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(canary));

    TradingLabChunkedReportWriter recovered = writer(canonicalizer, store, 1, 1);
    assertCode(
        () -> recovered.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safeEcho", canary)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    recovered.fail(reportId, "VALIDATION_FAILED", "summary-" + canary);

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void quarantineMarkerSurvivesAnotherFreshWriterBeforeTerminalClose() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    UUID reportId = UUID.randomUUID();
    String canary = "crash-after-quarantine-secret-" + UUID.randomUUID();
    writer(canonicalizer, store, 1, 1).append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(canary));

    TradingLabChunkedReportWriter recovering = writer(canonicalizer, store, 1, 1);
    assertCode(
        () -> recovering.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safeEcho", canary)),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");

    TradingLabChunkedReportWriter afterCrash = writer(canonicalizer, store, 1, 1);
    assertCode(
        () -> afterCrash.append(
            reportId,
            TradingLabReportSection.ERRORS,
            Map.of("safeEcho", "must-stay-closed")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    assertCode(
        () -> afterCrash.complete(reportId),
        "TRADING_LAB_REPORT_INCOMPLETE");
    afterCrash.fail(reportId, "VALIDATION_FAILED", "summary-" + canary);

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void recoveredTerminalCallCannotEchoLostDynamicSecretContext() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    UUID reportId = UUID.randomUUID();
    String canary = "recovered-terminal-secret-" + UUID.randomUUID();
    writer(canonicalizer, store, 1, 1).append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(canary));

    TradingLabChunkedReportWriter recovered = writer(canonicalizer, store, 1, 1);
    recovered.fail(reportId, "VALIDATION_FAILED", "summary-" + canary);

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void recoveredCancelCannotEchoLostDynamicSecretContext() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    UUID reportId = UUID.randomUUID();
    String canary = "recovered-cancel-secret-" + UUID.randomUUID();
    writer(canonicalizer, store, 1, 1).append(
        reportId,
        TradingLabReportSection.API_TRACE,
        safeTraceWithSecret(canary));

    TradingLabChunkedReportWriter recovered = writer(canonicalizer, store, 1, 1);
    recovered.cancel(reportId, "reason-" + canary);

    assertThat(store.batches).isEmpty();
    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.cancelled(
            "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString()).doesNotContain(canary);
  }

  @Test
  void traceRegistryOverflowTaintsAndForcesGenericFailAndCancelRows() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);

    UUID failedReport = UUID.randomUUID();
    String failedSecret = forceTraceRegistryOverflow(writer, failedReport, "FAILED");
    writer.fail(failedReport, failedSecret, "message-" + failedSecret);

    UUID cancelledReport = UUID.randomUUID();
    String cancelledSecret = forceTraceRegistryOverflow(
        writer, cancelledReport, "CANCELLED");
    writer.cancel(cancelledReport, "reason-" + cancelledSecret);

    UUID knownCodeReport = UUID.randomUUID();
    String knownCode = "KNOWN_SECRET_CODE";
    writer.append(
        knownCodeReport,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("accessToken", knownCode));
    writer.fail(knownCodeReport, knownCode, "controlled");

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(
            TradingLabReportOutcome.failed(
                "TRADING_LAB_REPORT_UNSAFE_TRACE",
                "Trading Lab report contains unsafe trace evidence"),
            TradingLabReportOutcome.cancelled(
                "Trading Lab report contains unsafe trace evidence"),
            TradingLabReportOutcome.failed(
                "TRADING_LAB_REPORT_UNSAFE_TRACE", "controlled"));
    assertThat(store.finalizations.toString())
        .doesNotContain(failedSecret, cancelledSecret, knownCode);
  }

  @Test
  void nonTraceRegistryOverflowTaintsAndForcesGenericFailAndCancelRows() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);

    UUID failedReport = UUID.randomUUID();
    String failedSecret = forceReportRegistryOverflow(
        writer, failedReport, "FAILED");
    writer.fail(failedReport, failedSecret, "message-" + failedSecret);

    UUID cancelledReport = UUID.randomUUID();
    String cancelledSecret = forceReportRegistryOverflow(
        writer, cancelledReport, "CANCELLED");
    writer.cancel(cancelledReport, "reason-" + cancelledSecret);

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(
            TradingLabReportOutcome.failed(
                "TRADING_LAB_REPORT_UNSAFE_TRACE",
                "Trading Lab report contains unsafe trace evidence"),
            TradingLabReportOutcome.cancelled(
                "Trading Lab report contains unsafe trace evidence"));
    assertThat(store.finalizations.toString())
        .doesNotContain(failedSecret, cancelledSecret);
  }

  @Test
  void logicalAppendRejectsACallerSuppliedChecksumThatDoesNotMatchItsBytes() {
    byte[] value = "{\"safe\":true}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThatThrownBy(() -> new TradingLabLogicalAppend(
        1L,
        value,
        "sha256:" + "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab logical append checksum is invalid");
  }

  @Test
  void replacementFenceStartsAFreshBufferAndCannotFlushThePreviousOwnersTail() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");

    writer.appendEvent(
        oldFence, TradingLabReportSection.CHECKPOINTS, 7L, Map.of("owner", "old"));
    writer.appendEvent(
        replacementFence,
        TradingLabReportSection.CHECKPOINTS,
        7L,
        Map.of("owner", "replacement"));
    writer.flush(replacementFence);

    assertThat(store.batches).hasSize(1);
    assertThat(store.batches.getFirst().fence()).isEqualTo(replacementFence);
    assertThat(store.batches.getFirst().sections().getFirst().appends())
        .extracting(TradingLabLogicalAppend::utf8)
        .singleElement()
        .asString()
        .contains("replacement")
        .doesNotContain("old");
  }

  @Test
  void replacementFenceCanFlushBeforeJournalReplayWithoutKeepingOldPendingEvidence() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");

    writer.appendEvent(
        oldFence, TradingLabReportSection.LIFECYCLE, 4L, Map.of("owner", "old"));

    writer.flush(replacementFence);

    assertThat(store.batches).isEmpty();
    writer.appendEvent(
        replacementFence,
        TradingLabReportSection.LIFECYCLE,
        4L,
        Map.of("owner", "replacement"));
    writer.flush(replacementFence);

    assertThat(store.batches).hasSize(1);
    assertThat(store.batches.getFirst().fence()).isEqualTo(replacementFence);
    assertThat(store.batches.getFirst().sections().getFirst().appends())
        .extracting(TradingLabLogicalAppend::utf8)
        .singleElement()
        .asString()
        .contains("replacement")
        .doesNotContain("old");
  }

  @Test
  void fenceLostKeepsDynamicSecretsForTheValidatedReplacementFence() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "fence-lost-dynamic-secret-" + UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");
    writer.appendEvent(
        oldFence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary));
    store.failNextEnsureFenceLost = true;

    assertCode(
        () -> writer.appendEvent(
            oldFence,
            TradingLabReportSection.ERRORS,
            0L,
            Map.of("safe", "rejected-old-owner")),
        "TRADING_LAB_REPORT_FENCE_LOST");
    assertCode(
        () -> writer.append(
            UUID.randomUUID(),
            TradingLabReportSection.ERRORS,
            Map.of("safe", "admission-must-stay-owned")),
        "TRADING_LAB_REPORT_WRITER_LIMIT");

    writer.appendEvent(
        replacementFence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("safeEcho", canary));
    writer.flush(replacementFence);

    String persisted = store.batches.stream()
        .flatMap(batch -> batch.sections().stream())
        .flatMap(section -> section.appends().stream())
        .map(TradingLabLogicalAppend::utf8)
        .reduce("", String::concat);
    assertThat(persisted).contains("[REDACTED]").doesNotContain(canary);
  }

  @Test
  void fenceLostCannotEvictAnAppendClosedGenericReport() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    String canary = "fence-lost-generic-secret-" + UUID.randomUUID();
    TradingLabReportWriteFence oldFence = new TradingLabReportWriteFence(
        UUID.randomUUID(), reportId, "old-owner");
    TradingLabReportWriteFence replacementFence = new TradingLabReportWriteFence(
        oldFence.runId(), reportId, "replacement-owner");
    writer.appendEvent(
        oldFence,
        TradingLabReportSection.ERRORS,
        0L,
        Map.of("safeEcho", canary));
    assertThatThrownBy(() -> writer.appendEvent(
        oldFence,
        TradingLabReportSection.API_TRACE,
        0L,
        safeTraceWithSecret(canary)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(canary);
    store.failNextFinalizeFenceLost = true;

    assertCode(
        () -> writer.fail(oldFence, "VALIDATION_FAILED", "controlled summary"),
        "TRADING_LAB_REPORT_FENCE_LOST");
    assertCode(
        () -> writer.append(
            UUID.randomUUID(),
            TradingLabReportSection.ERRORS,
            Map.of("safe", "admission-must-stay-owned")),
        "TRADING_LAB_REPORT_WRITER_LIMIT");
    assertCode(
        () -> writer.appendEvent(
            replacementFence,
            TradingLabReportSection.ERRORS,
            0L,
            Map.of("safe", "replacement-must-stay-closed")),
        "TRADING_LAB_REPORT_UNSAFE_TRACE");
    writer.fail(replacementFence, "IGNORED", "ignored");

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(TradingLabReportOutcome.failed(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void unsafeAppendTaintsTheReportSoItCannotBeDeclaredComplete() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();

    assertThatThrownBy(() -> writer.append(
        reportId, TradingLabReportSection.METADATA, "wrong-shape"))
        .isInstanceOf(IllegalArgumentException.class);
    assertCode(() -> writer.complete(reportId), "TRADING_LAB_REPORT_INCOMPLETE");
    assertThat(store.finalizations).isEmpty();

    writer.fail(reportId, "UNSAFE_VALUE", "controlled");
    assertThat(store.finalizations.getFirst().outcome().status())
        .isEqualTo(TradingLabReportStatus.FAILED);
  }

  @Test
  void externallyClosedConflictReleasesWriterAdmission() {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    writer.append(first, TradingLabReportSection.ERRORS, Map.of("safe", true));
    store.failNextFinalizeAsClosed = true;

    assertCode(() -> writer.complete(first), "TRADING_LAB_REPORT_CLOSED");
    writer.append(second, TradingLabReportSection.ERRORS, Map.of("safe", true));
  }

  @Test
  void freshTerminalCallsReplayIdenticalCompleteFailAndCancelButRejectDifferences() {
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer(), store, 4096, 3);

    UUID completed = UUID.randomUUID();
    writer.complete(completed);
    writer.complete(completed);
    assertCode(
        () -> writer.fail(completed, "LATE_FAILURE", "different outcome"),
        "TRADING_LAB_REPORT_STATUS_CONFLICT");

    UUID failed = UUID.randomUUID();
    writer.fail(failed, "VALIDATION_FAILED", "controlled summary");
    writer.fail(failed, "VALIDATION_FAILED", "controlled summary");
    assertCode(
        () -> writer.fail(failed, "VALIDATION_FAILED", "different summary"),
        "TRADING_LAB_REPORT_STATUS_CONFLICT");

    UUID cancelled = UUID.randomUUID();
    writer.cancel(cancelled, "controlled reason");
    writer.cancel(cancelled, "controlled reason");
    assertCode(
        () -> writer.cancel(cancelled, "different reason"),
        "TRADING_LAB_REPORT_STATUS_CONFLICT");

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(
            TradingLabReportOutcome.completed(),
            TradingLabReportOutcome.failed(
                "VALIDATION_FAILED", "controlled summary"),
            TradingLabReportOutcome.cancelled("controlled reason"));
  }

  @Test
  void quarantinedTerminalPublicCallsReplayAcrossFreshWriters() {
    FakeStore store = new FakeStore();

    UUID failed = UUID.randomUUID();
    store.quarantined.add(failed);
    writer(canonicalizer(), store, 4096, 1)
        .fail(failed, "CALLER_FAILURE", "caller-controlled summary");
    writer(canonicalizer(), store, 4096, 1)
        .fail(failed, "CALLER_FAILURE", "caller-controlled summary");

    UUID cancelled = UUID.randomUUID();
    store.quarantined.add(cancelled);
    writer(canonicalizer(), store, 4096, 1)
        .cancel(cancelled, "caller-controlled reason");
    writer(canonicalizer(), store, 4096, 1)
        .cancel(cancelled, "caller-controlled reason");

    assertThat(store.finalizations).extracting(Finalization::outcome)
        .containsExactly(
            TradingLabReportOutcome.failed(
                "TRADING_LAB_REPORT_UNSAFE_TRACE",
                "Trading Lab report contains unsafe trace evidence"),
            TradingLabReportOutcome.cancelled(
                "Trading Lab report contains unsafe trace evidence"));
  }

  @Test
  void completeAndAppendAreSerializedWithoutAcceptingAPostCloseTail() throws Exception {
    TradingLabReportCanonicalizer canonicalizer = canonicalizer();
    FakeStore store = new FakeStore();
    TradingLabChunkedReportWriter writer = writer(canonicalizer, store, 4096, 1);
    UUID reportId = UUID.randomUUID();
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<String> append = executor.submit(() -> {
        start.await();
        try {
          writer.append(
              reportId, TradingLabReportSection.ERRORS, Map.of("phase", "concurrent"));
          return "APPENDED";
        } catch (BusinessException exception) {
          return exception.getCode();
        }
      });
      java.util.concurrent.Future<String> complete = executor.submit(() -> {
        start.await();
        writer.complete(reportId);
        return "COMPLETED";
      });

      start.countDown();
      List<String> outcomes = List.of(
          append.get(10, java.util.concurrent.TimeUnit.SECONDS),
          complete.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(outcomes).contains("COMPLETED");
      assertThat(outcomes).anyMatch(outcome ->
          outcome.equals("APPENDED") || outcome.equals("TRADING_LAB_REPORT_CLOSED"));
      assertThat(store.finalizations).hasSize(1);
      if (outcomes.contains("APPENDED")) {
        assertThat(store.batches.stream()
            .flatMap(batch -> batch.sections().stream())
            .flatMap(section -> section.appends().stream())
            .map(TradingLabLogicalAppend::utf8)
            .anyMatch(value -> value.contains("concurrent")))
            .isTrue();
      }
      assertCode(
          () -> writer.append(
              reportId, TradingLabReportSection.ERRORS, Map.of("phase", "late")),
          "TRADING_LAB_REPORT_CLOSED");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
          .isTrue();
    }
  }

  private static String quarantineWithLateNonTraceSecret(
      TradingLabChunkedReportWriter writer,
      UUID reportId,
      String suffix
  ) {
    String canary = "late-quarantine-" + suffix + "-" + UUID.randomUUID();
    writer.append(
        reportId,
        TradingLabReportSection.SCENARIO,
        Map.of("safeEcho", canary));
    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("accessToken", canary)))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsafe Trading Lab report value")
        .hasMessageNotContaining(canary)
        .hasNoCause();
    return canary;
  }

  private static String forceTraceRegistryOverflow(
      TradingLabChunkedReportWriter writer,
      UUID reportId,
      String suffix
  ) {
    List<String> existingSecrets = java.util.stream.IntStream.range(0, 255)
        .mapToObj(index -> "REPORT_SECRET_" + index)
        .toList();
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", existingSecrets));

    String overflowSecret = "OVERFLOW_SECRET_" + suffix;
    SafeTradingLabHttpTrace trace = new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of(overflowSecret, "SECOND_OVERFLOW_SECRET_" + suffix))
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null));
    assertThatThrownBy(() -> writer.append(
        reportId, TradingLabReportSection.API_TRACE, trace))
        .isInstanceOf(TradingLabTraceSecretRegistry.MergeOverflowException.class)
        .hasMessage("Unsafe Trading Lab HTTP trace secret registry");
    return overflowSecret;
  }

  private static String forceReportRegistryOverflow(
      TradingLabChunkedReportWriter writer,
      UUID reportId,
      String suffix
  ) {
    List<String> existingSecrets = java.util.stream.IntStream.range(0, 256)
        .mapToObj(index -> "REPORT_SECRET_" + index)
        .toList();
    writer.append(
        reportId,
        TradingLabReportSection.CONFIG_SNAPSHOT,
        Map.of("credentials", existingSecrets));

    String overflowSecret = "NON_TRACE_REGISTRY_OVERFLOW_" + suffix;
    assertThatThrownBy(() -> writer.append(
        reportId,
        TradingLabReportSection.ERRORS,
        Map.of("credentials", overflowSecret)))
        .isInstanceOf(
            TradingLabReportSecretRegistry.RegistrationOverflowException.class)
        .hasMessage("Unsafe Trading Lab report value");
    return overflowSecret;
  }

  private static Map<TradingLabReportSection, List<TradingLabReportChunkEntity>>
      encodeBatches(
          UUID reportId,
          List<TradingLabReportWriteBatch> batches,
          TradingLabReportChunkCodec codec
      ) {
    Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks =
        new EnumMap<>(TradingLabReportSection.class);
    for (TradingLabReportWriteBatch batch : batches) {
      for (TradingLabReportSectionWrite write : batch.sections()) {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        write.appends().forEach(append -> plain.writeBytes(append.canonicalBytes()));
        TradingLabEncodedChunk encoded = codec.encode(plain.toByteArray());
        TradingLabReportChunkEntity chunk = new TradingLabReportChunkEntity();
        chunk.setId(UUID.randomUUID());
        chunk.setReportId(reportId);
        chunk.setSection(write.section().name());
        chunk.setSequence(write.firstChunkSequence());
        chunk.setEncoding(encoded.encoding());
        chunk.setUncompressedBytes(encoded.uncompressedBytes());
        chunk.setCompressedBytes(encoded.compressedBytes());
        chunk.setPayload(encoded.payload().clone());
        chunk.setChecksum(encoded.checksum());
        chunks.computeIfAbsent(write.section(), ignored -> new ArrayList<>()).add(chunk);
      }
    }
    return chunks;
  }

  private static TradingLabReportEntity reportForStreaming(
      UUID reportId,
      Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks
  ) {
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setStatus(TradingLabReportStatus.WRITING.name());
    report.setModelVersion("model-v1");
    report.setMetadataJson("{}");
    report.setVersion(0L);
    report.setCompressedBytes(chunks.values().stream()
        .flatMap(List::stream)
        .mapToLong(TradingLabReportChunkEntity::getCompressedBytes)
        .sum());
    report.setChunkCount(chunks.values().stream().mapToInt(List::size).sum());
    return report;
  }

  private static final class MapChunkSource implements TradingLabReportChunkSource {
    private final TradingLabReportEntity report;
    private final Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks;

    private MapChunkSource(
        TradingLabReportEntity report,
        Map<TradingLabReportSection, List<TradingLabReportChunkEntity>> chunks
    ) {
      this.report = report;
      this.chunks = chunks;
    }

    @Override
    public TradingLabReportEntity lockReportForStream(UUID reportId) {
      assertThat(reportId).isEqualTo(report.getId());
      return report;
    }

    @Override
    public Cursor<TradingLabReportChunkEntity> openChunks(
        UUID reportId,
        TradingLabReportSection section
    ) {
      assertThat(reportId).isEqualTo(report.getId());
      return new TestCursor<>(chunks.getOrDefault(section, List.of()));
    }
  }

  private static TradingLabChunkedReportWriter writer(
      TradingLabReportCanonicalizer canonicalizer,
      FakeStore store,
      int chunkBytes,
      int maxActiveWriters
  ) {
    return new TradingLabChunkedReportWriter(
        canonicalizer,
        store,
        new RecordingStreamer(store),
        new TradingLabFixedValidationSecretProvider(List.of()),
        chunkBytes,
        maxActiveWriters,
        Duration.ofDays(30));
  }

  private static TradingLabReportCanonicalizer canonicalizer() {
    return new TradingLabReportCanonicalizer(
        new ObjectMapper().findAndRegisterModules(),
        new TradingLabCredentialSanitizer(),
        1024 * 1024);
  }

  private static SafeTradingLabHttpTrace safeTraceWithSecret(String secret) {
    return new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of())
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local/internal/run"),
            Map.of(),
            Map.of(),
            "application/json",
            Map.of("accessToken", secret, "visible", true),
            null,
            null,
            null,
            null,
            List.of(),
            null));
  }

  private static SafeTradingLabHttpTrace safeTraceAt(String path) {
    return new TradingLabHttpTraceSanitizer(
        new TradingLabCredentialSanitizer(),
        List.of())
        .sanitize(new TradingLabHttpTraceInput(
            URI.create("https://validation.local" + path),
            Map.of(),
            Map.of(),
            "application/json",
            Map.of("visible", true),
            null,
            null,
            null,
            null,
            List.of(),
            null));
  }

  private static void assertCode(Runnable invocation, String code) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private static final class RegistryCapturingCanonicalizer
      extends TradingLabReportCanonicalizer {
    private TradingLabReportSecretRegistry capturedSecrets;

    private RegistryCapturingCanonicalizer() {
      super(
          new ObjectMapper().findAndRegisterModules(),
          new TradingLabCredentialSanitizer(),
          1024 * 1024);
    }

    @Override
    TradingLabCanonicalValue canonicalize(
        TradingLabReportSection section,
        Object value,
        TradingLabReportSecretRegistry secrets
    ) {
      capturedSecrets = secrets;
      return super.canonicalize(section, value, secrets);
    }
  }

  private static final class PersistScanRejectingCanonicalizer
      extends TradingLabReportCanonicalizer {
    private boolean rejectBufferedScan;

    private PersistScanRejectingCanonicalizer() {
      super(
          new ObjectMapper().findAndRegisterModules(),
          new TradingLabCredentialSanitizer(),
          1024 * 1024);
    }

    @Override
    void requireNoCanary(
        List<TradingLabLogicalAppend> appends,
        TradingLabReportSecretRegistry secrets
    ) {
      if (rejectBufferedScan) {
        throw new IllegalArgumentException("Unsafe Trading Lab report value");
      }
      super.requireNoCanary(appends, secrets);
    }
  }

  private static final class FakeStore implements TradingLabReportStore {
    private final Map<SectionKey, Long> nextSequences = new HashMap<>();
    private final List<TradingLabReportWriteBatch> attemptedBatches = new ArrayList<>();
    private final List<TradingLabReportWriteBatch> batches = new ArrayList<>();
    private final List<Finalization> finalizations = new ArrayList<>();
    private final Map<UUID, Boolean> closed = new HashMap<>();
    private final Map<UUID, Finalization> terminal = new HashMap<>();
    private final Map<UUID, TradingLabCanonicalValue> metadata = new HashMap<>();
    private final java.util.Set<UUID> quarantined = new java.util.HashSet<>();
    private int nextChunkSequenceCalls;
    private int metadataWrites;
    private boolean failNextMetadata;
    private boolean failNextMetadataAfterMutation;
    private boolean failNextPersist;
    private boolean failNextPersistAfterMutation;
    private boolean failNextDiscardAfterMutation;
    private boolean failNextFinalizeAsClosed;
    private boolean failNextEnsureFenceLost;
    private boolean failNextFinalizeFenceLost;
    private String failNextPersistCode;
    private java.util.function.Consumer<TradingLabReportWriteBatch> observeNextPersist;

    @Override
    public TradingLabReportWriteState ensureWritable(
        UUID reportId,
        TradingLabReportWriteFence fence
    ) {
      if (failNextEnsureFenceLost) {
        failNextEnsureFenceLost = false;
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_FENCE_LOST", "Trading Lab report fence is lost");
      }
      if (Boolean.TRUE.equals(closed.get(reportId))) {
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_CLOSED", "Trading Lab report is closed");
      }
      return quarantined.contains(reportId)
          ? TradingLabReportWriteState.QUARANTINED
          : TradingLabReportWriteState.WRITABLE;
    }

    @Override
    public void initializeMetadata(
        TradingLabReportWriteFence fence,
        TradingLabCanonicalValue candidate
    ) {
      if (quarantined.contains(fence.reportId())) {
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence");
      }
      if (failNextMetadata) {
        failNextMetadata = false;
        throw new IllegalStateException("uncertain metadata commit");
      }
      TradingLabCanonicalValue existing = metadata.get(fence.reportId());
      if (existing == null) {
        metadata.put(fence.reportId(), candidate);
        metadataWrites++;
      } else if (!existing.sameBytes(candidate)) {
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_METADATA_CONFLICT",
            "Trading Lab report metadata already has a different value");
      }
      if (failNextMetadataAfterMutation) {
        failNextMetadataAfterMutation = false;
        throw new IllegalStateException("uncertain metadata commit");
      }
    }

    @Override
    public long highestDurableSourceSequence(
        TradingLabReportWriteFence fence,
        TradingLabReportSection section
    ) {
      return batches.stream()
          .filter(batch -> batch.reportId().equals(fence.reportId()))
          .flatMap(batch -> batch.sections().stream())
          .filter(write -> write.section() == section)
          .flatMap(write -> write.appends().stream())
          .map(TradingLabLogicalAppend::sourceSequence)
          .filter(Objects::nonNull)
          .filter(sequence -> sequence >= 0L)
          .mapToLong(Long::longValue)
          .max()
          .orElse(-1L);
    }

    @Override
    public long nextChunkSequence(UUID reportId, TradingLabReportSection section) {
      nextChunkSequenceCalls++;
      return nextSequences.getOrDefault(new SectionKey(reportId, section), 0L);
    }

    @Override
    public void persist(TradingLabReportWriteBatch batch) {
      if (observeNextPersist != null) {
        java.util.function.Consumer<TradingLabReportWriteBatch> observer =
            observeNextPersist;
        observeNextPersist = null;
        observer.accept(batch);
      }
      attemptedBatches.add(batch);
      if (quarantined.contains(batch.reportId())) {
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence");
      }
      if (failNextPersistCode != null) {
        String code = failNextPersistCode;
        failNextPersistCode = null;
        if ("TRADING_LAB_REPORT_UNSAFE_TRACE".equals(code)) {
          quarantined.add(batch.reportId());
        }
        throw new TradingLabReportException(code, "definitive persist rejection");
      }
      if (failNextPersist) {
        failNextPersist = false;
        throw new IllegalStateException("uncertain commit");
      }
      recordBatch(batch);
      if (failNextPersistAfterMutation) {
        failNextPersistAfterMutation = false;
        throw new IllegalStateException("uncertain commit");
      }
    }

    private void recordBatch(TradingLabReportWriteBatch batch) {
      batches.add(batch);
      for (TradingLabReportSectionWrite section : batch.sections()) {
        nextSequences.put(
            new SectionKey(batch.reportId(), section.section()),
            section.firstChunkSequence() + 1L);
      }
    }

    @Override
    public TradingLabReportRecoveryState discardEvidence(
        UUID reportId,
        TradingLabReportWriteFence fence
    ) {
      if (Boolean.TRUE.equals(closed.get(reportId))) {
        return terminalRecoveryState(reportId);
      }
      if (quarantined.contains(reportId)) {
        return TradingLabReportRecoveryState.QUARANTINED;
      }
      boolean discarded = metadata.remove(reportId) != null;
      discarded |= batches.removeIf(batch -> batch.reportId().equals(reportId));
      nextSequences.keySet().removeIf(key -> key.reportId().equals(reportId));
      if (discarded) {
        quarantined.add(reportId);
      }
      if (failNextDiscardAfterMutation) {
        failNextDiscardAfterMutation = false;
        throw new IllegalStateException("uncertain discard");
      }
      return discarded
          ? TradingLabReportRecoveryState.QUARANTINED
          : TradingLabReportRecoveryState.EMPTY;
    }

    @Override
    public TradingLabReportRecoveryState quarantineEvidence(
        UUID reportId,
        TradingLabReportWriteFence fence
    ) {
      if (Boolean.TRUE.equals(closed.get(reportId))) {
        return terminalRecoveryState(reportId);
      }
      metadata.remove(reportId);
      batches.removeIf(batch -> batch.reportId().equals(reportId));
      nextSequences.keySet().removeIf(key -> key.reportId().equals(reportId));
      quarantined.add(reportId);
      if (failNextDiscardAfterMutation) {
        failNextDiscardAfterMutation = false;
        throw new IllegalStateException("uncertain discard");
      }
      return TradingLabReportRecoveryState.QUARANTINED;
    }

    @Override
    public TradingLabReportFinalizeResult finalizeReport(
        UUID reportId,
        TradingLabReportWriteFence fence,
        TradingLabReportMeasurement measurement,
        TradingLabReportOutcome outcome,
        Duration retention
    ) {
      if (failNextFinalizeFenceLost) {
        failNextFinalizeFenceLost = false;
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_FENCE_LOST", "Trading Lab report fence is lost");
      }
      if (failNextFinalizeAsClosed) {
        failNextFinalizeAsClosed = false;
        closed.put(reportId, true);
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_CLOSED", "Trading Lab report is closed");
      }
      Finalization candidate = new Finalization(
          reportId, fence, measurement, outcome, retention);
      Finalization existing = terminal.get(reportId);
      if (existing != null) {
        if (existing.measurement().equals(measurement)
            && existing.outcome().equals(outcome)
            && existing.retention().equals(retention)) {
          return TradingLabReportFinalizeResult.REPLAY;
        }
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_STATUS_CONFLICT",
            "Trading Lab report already has a different terminal outcome");
      }
      if (quarantined.contains(reportId)
          && !(outcome.status() == TradingLabReportStatus.FAILED
              && "TRADING_LAB_REPORT_UNSAFE_TRACE".equals(outcome.failureCode())
              && "Trading Lab report contains unsafe trace evidence".equals(
                  outcome.failureMessage()))
          && !(outcome.status() == TradingLabReportStatus.CANCELLED
              && "CANCELLED".equals(outcome.failureCode())
              && "Trading Lab report contains unsafe trace evidence".equals(
                  outcome.failureMessage()))) {
        if (outcome.status() == TradingLabReportStatus.COMPLETED) {
          throw new TradingLabReportException(
              "TRADING_LAB_REPORT_INCOMPLETE",
              "Trading Lab report contains rejected evidence");
        }
        throw new TradingLabReportException(
            "TRADING_LAB_REPORT_UNSAFE_TRACE",
            "Trading Lab report contains unsafe trace evidence");
      }
      finalizations.add(candidate);
      terminal.put(reportId, candidate);
      closed.put(reportId, true);
      return TradingLabReportFinalizeResult.CLOSED;
    }

    private TradingLabReportRecoveryState terminalRecoveryState(UUID reportId) {
      Finalization finalization = terminal.get(reportId);
      return finalization != null
              && TradingLabReportQuarantinePolicy.isGenericOutcome(finalization.outcome())
          ? TradingLabReportRecoveryState.QUARANTINED_TERMINAL
          : TradingLabReportRecoveryState.TERMINAL;
    }
  }

  private static final class RecordingStreamer extends TradingLabReportStreamer {
    private final FakeStore store;
    private boolean measuredAfterPersist;

    private RecordingStreamer(FakeStore store) {
      super(
          new TradingLabReportChunkSource() {
            @Override
            public com.fxplatform.tradinglab.entity.TradingLabReportEntity lockReportForStream(
                UUID reportId) {
              throw new UnsupportedOperationException();
            }

            @Override
            public org.apache.ibatis.cursor.Cursor<
                com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity> openChunks(
                    UUID reportId,
                    TradingLabReportSection section) {
              throw new UnsupportedOperationException();
            }
          },
          new TradingLabReportChunkCodec(4096),
          new NoOpTransactionTemplate(),
          new ObjectMapper());
      this.store = store;
    }

    @Override
    public TradingLabReportMeasurement measureForClose(UUID reportId) {
      measuredAfterPersist = !store.batches.isEmpty();
      return new TradingLabReportMeasurement(3L, 100L, 50L, 1);
    }

    @Override
    TradingLabReportMeasurement measureForClose(
        UUID reportId,
      TradingLabReportSecretRegistry secrets
    ) {
      byte[] assembled = assemble(reportId);
      TradingLabCanonicalCanaryScanner.streamingMatcher(secrets.values())
          .accept(assembled, 0, assembled.length);
      return measureForClose(reportId);
    }

    private byte[] assemble(UUID reportId) {
      try {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ObjectMapper mapper = new ObjectMapper();
        output.write('{');
        TradingLabReportSection[] sections = TradingLabReportSection.values();
        for (int index = 0; index < sections.length; index++) {
          TradingLabReportSection section = sections[index];
          if (index > 0) {
            output.write(',');
          }
          output.writeBytes(mapper.writeValueAsBytes(section.jsonKey()));
          output.write(':');
          List<TradingLabLogicalAppend> appends = store.batches.stream()
              .filter(batch -> batch.reportId().equals(reportId))
              .flatMap(batch -> batch.sections().stream())
              .filter(write -> write.section() == section)
              .flatMap(write -> write.appends().stream())
              .toList();
          if (section.isArray()) {
            output.write('[');
            for (int appendIndex = 0; appendIndex < appends.size(); appendIndex++) {
              if (appendIndex > 0) {
                output.write(',');
              }
              byte[] canonical = appends.get(appendIndex).internalBytes();
              if (canonical.length == 0 || canonical[canonical.length - 1] != '\n') {
                throw new IllegalStateException("array append is not NDJSON");
              }
              output.write(canonical, 0, canonical.length - 1);
            }
            output.write(']');
          } else if (appends.isEmpty()) {
            String empty = section.isString() ? "model-v1" : section.emptyJson();
            output.writeBytes(section.isString()
                ? mapper.writeValueAsBytes(empty)
                : empty.getBytes(StandardCharsets.UTF_8));
          } else {
            for (TradingLabLogicalAppend append : appends) {
              output.writeBytes(append.internalBytes());
            }
          }
        }
        output.write('}');
        return output.toByteArray();
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }

  private static final class NoOpTransactionTemplate
      extends org.springframework.transaction.support.TransactionTemplate {
    private NoOpTransactionTemplate() {
      super(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
        @Override
        protected Object doGetTransaction() {
          return new Object();
        }

        @Override
        protected void doBegin(
            Object transaction,
            org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(
            org.springframework.transaction.support.DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(
            org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
      });
    }
  }

  private static final class TestCursor<T> implements Cursor<T> {
    private final List<T> values;
    private boolean open = true;
    private boolean consumed;
    private int currentIndex = -1;

    private TestCursor(List<T> values) {
      this.values = values;
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
          consumed = !hasNext;
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
      open = false;
    }
  }

  private record SectionKey(UUID reportId, TradingLabReportSection section) {
  }

  private record Finalization(
      UUID reportId,
      TradingLabReportWriteFence fence,
      TradingLabReportMeasurement measurement,
      TradingLabReportOutcome outcome,
      Duration retention
  ) {
  }
}
