package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.repository.TradingLabReportAppendRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportWriteFenceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MyBatisTradingLabReportStoreTest {

  @Test
  void locksTheRunBeforeTheReportAndRejectsUnfencedRecoverableWork() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID reportId = UUID.randomUUID();
    when(fences.lockRunForReport(reportId)).thenReturn(Optional.of(
        new TradingLabReportRunFenceRow(
            UUID.randomUUID(),
            reportId,
            "RESETTING",
            null,
            null,
            null,
            false)));
    MyBatisTradingLabReportStore store = new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(4096));

    assertThatThrownBy(() -> store.ensureWritable(reportId, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_REPORT_FENCE_LOST"));

    verify(fences).lockRunForReport(reportId);
    verify(reports, never()).lockById(reportId);
  }

  @Test
  void rejectsExpiredFencedClaimBeforeLockingTheReport() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID reportId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    TradingLabReportWriteFence fence = new TradingLabReportWriteFence(
        runId, reportId, "expired-owner");
    when(fences.lockFencedRun(runId, reportId)).thenReturn(Optional.of(
        new TradingLabReportRunFenceRow(
            runId,
            reportId,
            "RUNNING",
            1,
            "expired-owner",
            Instant.parse("2026-07-19T00:00:00Z"),
            false)));
    MyBatisTradingLabReportStore store = new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(4096));

    assertThatThrownBy(() -> store.ensureWritable(reportId, fence))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_REPORT_FENCE_LOST"));

    verify(fences).lockFencedRun(runId, reportId);
    verify(reports, never()).lockById(reportId);
  }

  @Test
  void linkedEarlyRunAllowsOnlyUnfencedFailureFinalization() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID reportId = UUID.randomUUID();
    when(fences.lockRunForReport(reportId)).thenReturn(Optional.of(
        new TradingLabReportRunFenceRow(
            UUID.randomUUID(),
            reportId,
            "DRAFT",
            null,
            null,
            null,
            false)));
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setStatus("PENDING");
    report.setVersion(0L);
    when(reports.lockById(reportId)).thenReturn(Optional.of(report));
    MyBatisTradingLabReportStore store = new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(4096));
    TradingLabReportMeasurement empty = new TradingLabReportMeasurement(0L, 123L, 0L, 0);

    assertThatThrownBy(() -> store.finalizeReport(
        reportId,
        null,
        empty,
        TradingLabReportOutcome.completed(),
        Duration.ofDays(30)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_REPORT_FENCE_LOST"));
    assertThatThrownBy(() -> store.finalizeReport(
        reportId,
        null,
        empty,
        TradingLabReportOutcome.cancelled("cancelled"),
        Duration.ofDays(30)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_REPORT_FENCE_LOST"));

    verify(chunks, never()).totals(reportId);
    verify(reports, never()).finalizeReport(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void legacyQuarantineMarkerMustRewriteAnUnsafeModelVersion() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID reportId = UUID.randomUUID();
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setStatus("WRITING");
    report.setModelVersion("legacy-secret-model");
    report.setFailureCode("TRADING_LAB_REPORT_UNSAFE_TRACE");
    report.setFailureMessage("Trading Lab report contains unsafe trace evidence");
    report.setUncompressedBytes(0L);
    report.setCompressedBytes(0L);
    report.setChunkCount(0);
    report.setVersion(1L);
    when(reports.lockById(reportId)).thenReturn(Optional.of(report));
    when(reports.quarantineEvidence(reportId)).thenReturn(1);
    MyBatisTradingLabReportStore store = new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(4096));

    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);

    verify(reports).quarantineEvidence(reportId);
  }

  @Test
  void distinguishesNormalizedQuarantineTerminalsFromUnsafeLegacyRows() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID failedReportId = UUID.randomUUID();
    UUID cancelledReportId = UUID.randomUUID();
    UUID legacyReportId = UUID.randomUUID();
    TradingLabReportEntity failed = genericTerminal(failedReportId, false, "[REDACTED]");
    TradingLabReportEntity cancelled = genericTerminal(
        cancelledReportId, true, "[REDACTED]");
    TradingLabReportEntity legacy = genericTerminal(
        legacyReportId, false, "legacy-secret-model");
    when(reports.lockById(failedReportId)).thenReturn(Optional.of(failed));
    when(reports.lockById(cancelledReportId)).thenReturn(Optional.of(cancelled));
    when(reports.lockById(legacyReportId)).thenReturn(Optional.of(legacy));
    MyBatisTradingLabReportStore store = new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(4096));

    assertThat(store.discardEvidence(failedReportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED_TERMINAL);
    assertThat(store.quarantineEvidence(cancelledReportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED_TERMINAL);
    assertThat(store.discardEvidence(legacyReportId, null))
        .isEqualTo(TradingLabReportRecoveryState.TERMINAL);
  }

  @Test
  void initializesCanonicalMetadataOnlyThroughTheLiveFencedReportRow() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID runId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    String owner = "metadata-owner";
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabReportEntity report = writableReport(reportId, "{}");
    TradingLabCanonicalValue metadata = canonicalizer().canonicalize(
        TradingLabReportSection.METADATA, java.util.Map.of("model", "v1"));
    when(fences.lockFencedRun(runId, reportId))
        .thenReturn(Optional.of(liveFence(runId, reportId, owner)));
    when(reports.lockById(reportId)).thenReturn(Optional.of(report));
    when(reports.metadataMatches(reportId, metadata.utf8())).thenReturn(false);
    when(reports.initializeMetadataFenced(
        reportId, metadata.utf8(), 0L, runId, owner)).thenReturn(1);
    MyBatisTradingLabReportStore store = store(reports, chunks, appends, fences);

    store.initializeMetadata(fence, metadata);

    verify(reports).initializeMetadataFenced(
        reportId, metadata.utf8(), 0L, runId, owner);
  }

  @Test
  void canonicalMetadataReplayIsANoOpButDifferentMeaningConflicts() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID runId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    String owner = "metadata-owner";
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, reportId, owner);
    TradingLabCanonicalValue metadata = canonicalizer().canonicalize(
        TradingLabReportSection.METADATA, java.util.Map.of("model", "v1"));
    TradingLabReportEntity replay = writableReport(reportId, "{\"model\":\"v1\"}");
    when(fences.lockFencedRun(runId, reportId))
        .thenReturn(Optional.of(liveFence(runId, reportId, owner)));
    when(reports.lockById(reportId)).thenReturn(Optional.of(replay));
    when(reports.metadataMatches(reportId, metadata.utf8())).thenReturn(true);
    when(fences.fenceIsLive(runId, reportId, owner)).thenReturn(true);
    MyBatisTradingLabReportStore store = store(reports, chunks, appends, fences);

    store.initializeMetadata(fence, metadata);

    verify(reports, never()).initializeMetadataFenced(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());

    TradingLabCanonicalValue conflicting = canonicalizer().canonicalize(
        TradingLabReportSection.METADATA, java.util.Map.of("model", "v2"));
    when(reports.metadataMatches(reportId, conflicting.utf8())).thenReturn(false);
    assertThatThrownBy(() -> store.initializeMetadata(fence, conflicting))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("TRADING_LAB_REPORT_METADATA_CONFLICT"));
  }

  @Test
  void nonEmptyMetadataIsEvidenceAndCannotBeRecoveredAsPristine() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID reportId = UUID.randomUUID();
    TradingLabReportEntity report = writableReport(
        reportId, "{\"safeEcho\":\"persisted\"}");
    when(reports.lockById(reportId)).thenReturn(Optional.of(report));
    when(reports.quarantineEvidence(reportId)).thenReturn(1);
    MyBatisTradingLabReportStore store = store(reports, chunks, appends, fences);

    assertThat(store.discardEvidence(reportId, null))
        .isEqualTo(TradingLabReportRecoveryState.QUARANTINED);

    verify(reports).quarantineEvidence(reportId);
  }

  @Test
  void highestDurableSourceSequenceUsesTheLiveFenceAndAppendLedger() {
    TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
    TradingLabReportChunkRepository chunks = mock(TradingLabReportChunkRepository.class);
    TradingLabReportAppendRepository appends = mock(TradingLabReportAppendRepository.class);
    TradingLabReportWriteFenceRepository fences =
        mock(TradingLabReportWriteFenceRepository.class);
    UUID runId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    String owner = "sequence-owner";
    TradingLabReportWriteFence fence =
        new TradingLabReportWriteFence(runId, reportId, owner);
    when(fences.lockFencedRun(runId, reportId))
        .thenReturn(Optional.of(liveFence(runId, reportId, owner)));
    when(reports.lockById(reportId))
        .thenReturn(Optional.of(writableReport(reportId, "{}")));
    when(fences.fenceIsLive(runId, reportId, owner)).thenReturn(true);
    when(appends.findHighestSourceSequence(
        reportId, TradingLabReportSection.CHECKPOINTS.name()))
        .thenReturn(null, 9L);
    MyBatisTradingLabReportStore store = store(reports, chunks, appends, fences);

    assertThat(store.highestDurableSourceSequence(
        fence, TradingLabReportSection.CHECKPOINTS)).isEqualTo(-1L);
    assertThat(store.highestDurableSourceSequence(
        fence, TradingLabReportSection.CHECKPOINTS)).isEqualTo(9L);

    verify(fences, times(2)).fenceIsLive(runId, reportId, owner);
  }

  private static MyBatisTradingLabReportStore store(
      TradingLabReportRepository reports,
      TradingLabReportChunkRepository chunks,
      TradingLabReportAppendRepository appends,
      TradingLabReportWriteFenceRepository fences
  ) {
    return new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(4096));
  }

  private static TradingLabReportCanonicalizer canonicalizer() {
    return new TradingLabReportCanonicalizer(
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
        new com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer(),
        1024 * 1024);
  }

  private static TradingLabReportEntity writableReport(
      UUID reportId,
      String metadataJson
  ) {
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setStatus("PENDING");
    report.setMetadataJson(metadataJson);
    report.setUncompressedBytes(0L);
    report.setCompressedBytes(0L);
    report.setChunkCount(0);
    report.setVersion(0L);
    return report;
  }

  private static TradingLabReportRunFenceRow liveFence(
      UUID runId,
      UUID reportId,
      String owner
  ) {
    return new TradingLabReportRunFenceRow(
        runId,
        reportId,
        "RUNNING",
        1,
        owner,
        Instant.parse("2026-07-23T00:00:00Z"),
        true);
  }

  private static TradingLabReportEntity genericTerminal(
      UUID reportId,
      boolean cancelled,
      String modelVersion
  ) {
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setStatus(cancelled ? "CANCELLED" : "FAILED");
    report.setModelVersion(modelVersion);
    report.setFailureCode(cancelled ? "CANCELLED" : "TRADING_LAB_REPORT_UNSAFE_TRACE");
    report.setFailureMessage("Trading Lab report contains unsafe trace evidence");
    report.setUncompressedBytes(100L);
    report.setCompressedBytes(0L);
    report.setChunkCount(0);
    report.setVersion(1L);
    report.setCompletedAt(Instant.parse("2026-07-22T00:00:00Z"));
    return report;
  }
}
