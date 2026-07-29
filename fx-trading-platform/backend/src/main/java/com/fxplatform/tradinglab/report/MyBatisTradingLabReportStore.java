package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.entity.TradingLabReportAppendEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.repository.TradingLabReportAppendRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportWriteFenceRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyBatisTradingLabReportStore implements TradingLabReportStore {

  private static final Set<String> UNFENCED_RUN_STATES =
      Set.of("DRAFT", "VALIDATING", "QUEUED");
  private final TradingLabReportRepository reports;
  private final TradingLabReportChunkRepository chunks;
  private final TradingLabReportAppendRepository appends;
  private final TradingLabReportWriteFenceRepository fences;
  private final TradingLabReportChunkCodec codec;

  public MyBatisTradingLabReportStore(
      TradingLabReportRepository reports,
      TradingLabReportChunkRepository chunks,
      TradingLabReportAppendRepository appends,
      TradingLabReportWriteFenceRepository fences,
      TradingLabReportChunkCodec codec
  ) {
    this.reports = Objects.requireNonNull(reports, "reports");
    this.chunks = Objects.requireNonNull(chunks, "chunks");
    this.appends = Objects.requireNonNull(appends, "appends");
    this.fences = Objects.requireNonNull(fences, "fences");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  @Transactional
  public TradingLabReportWriteState ensureWritable(
      UUID reportId,
      TradingLabReportWriteFence fence
  ) {
    TradingLabReportEntity report = lockRunThenReport(reportId, fence);
    requireOpen(report);
    return openWriteState(report);
  }

  @Override
  @Transactional
  public void initializeMetadata(
      TradingLabReportWriteFence fence,
      TradingLabCanonicalValue metadata
  ) {
    Objects.requireNonNull(fence, "fence");
    Objects.requireNonNull(metadata, "metadata");
    String metadataJson = metadata.utf8();
    if (!metadataJson.startsWith("{") || !metadataJson.endsWith("}")) {
      throw new IllegalArgumentException(
          "Trading Lab report metadata must be a canonical object");
    }

    TradingLabReportEntity report = lockRunThenReport(fence.reportId(), fence);
    requireOpen(report);
    if (openWriteState(report) == TradingLabReportWriteState.QUARANTINED) {
      throw unsafeTrace();
    }
    if (reports.metadataMatches(report.getId(), metadataJson)) {
      requireFenceStillLive(fence);
      return;
    }
    if (hasMetadataEvidence(report)) {
      requireFenceStillLive(fence);
      throw metadataConflict();
    }

    int changed = reports.initializeMetadataFenced(
        report.getId(),
        metadataJson,
        requiredVersion(report),
        fence.runId(),
        fence.claimOwner());
    if (changed != 1) {
      requireFenceStillLive(fence);
      throw metadataConflict();
    }
  }

  @Override
  @Transactional
  public long highestDurableSourceSequence(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section
  ) {
    Objects.requireNonNull(fence, "fence");
    Objects.requireNonNull(section, "section");
    if (!section.isArray()) {
      throw new IllegalArgumentException(
          "Trading Lab durable source sequence requires an array section");
    }

    TradingLabReportEntity report = lockRunThenReport(fence.reportId(), fence);
    requireOpen(report);
    if (openWriteState(report) == TradingLabReportWriteState.QUARANTINED) {
      throw unsafeTrace();
    }
    Long highest = appends.findHighestSourceSequence(
        fence.reportId(), section.name());
    requireFenceStillLive(fence);
    return highest == null ? -1L : highest;
  }

  @Override
  @Transactional(readOnly = true)
  public long nextChunkSequence(UUID reportId, TradingLabReportSection section) {
    Objects.requireNonNull(reportId, "reportId");
    Objects.requireNonNull(section, "section");
    return requiredNextSequence(
        chunks.sequenceState(reportId, section.name()));
  }

  @Override
  @Transactional
  public void persist(TradingLabReportWriteBatch batch) {
    Objects.requireNonNull(batch, "batch");
    TradingLabReportEntity report = lockRunThenReport(batch.reportId(), batch.fence());
    requireOpen(report);
    if (openWriteState(report) == TradingLabReportWriteState.QUARANTINED) {
      throw unsafeTrace();
    }
    EnumMap<TradingLabReportSection, Long> nextSequences =
        validateAllChunkSequences(batch.reportId());
    List<TradingLabReportSectionWrite> sectionWrites = orderedSections(batch.sections());

    long insertedRawBytes = 0L;
    long insertedCompressedBytes = 0L;
    int insertedChunkCount = 0;
    for (TradingLabReportSectionWrite sectionWrite : sectionWrites) {
      PersistedTotals totals = persistSection(
          batch.reportId(),
          sectionWrite,
          nextSequences.get(sectionWrite.section()));
      insertedRawBytes = Math.addExact(insertedRawBytes, totals.rawBytes());
      insertedCompressedBytes = Math.addExact(
          insertedCompressedBytes, totals.compressedBytes());
      insertedChunkCount = Math.addExact(insertedChunkCount, totals.chunkCount());
    }

    if (insertedChunkCount == 0) {
      requireFenceStillLive(batch.fence());
      return;
    }
    int changed;
    if (batch.fence() == null) {
      changed = reports.addChunkTotals(
          batch.reportId(),
          insertedRawBytes,
          insertedCompressedBytes,
          insertedChunkCount);
    } else {
      changed = reports.addChunkTotalsFenced(
          batch.reportId(),
          insertedRawBytes,
          insertedCompressedBytes,
          insertedChunkCount,
          batch.fence().runId(),
          batch.fence().claimOwner());
    }
    if (changed != 1) {
      if (batch.fence() != null) {
        throw fenceLost();
      }
      throw error(
          "TRADING_LAB_REPORT_STATUS_CONFLICT",
          "Trading Lab report counters could not be updated");
    }
  }

  @Override
  @Transactional
  public TradingLabReportRecoveryState discardEvidence(
      UUID reportId,
      TradingLabReportWriteFence fence
  ) {
    LockedReportRows locked = lockRunThenReportForFinalize(reportId, fence);
    TradingLabReportEntity report = locked.report();
    if (report.getCompletedAt() != null) {
      return terminalRecoveryState(report);
    }
    requireWritableFence(locked.run(), fence);
    requireOpen(report);
    return quarantineOpenReport(report, fence, false);
  }

  @Override
  @Transactional
  public TradingLabReportRecoveryState quarantineEvidence(
      UUID reportId,
      TradingLabReportWriteFence fence
  ) {
    LockedReportRows locked = lockRunThenReportForFinalize(reportId, fence);
    TradingLabReportEntity report = locked.report();
    if (report.getCompletedAt() != null) {
      return terminalRecoveryState(report);
    }
    requireWritableFence(locked.run(), fence);
    requireOpen(report);
    return quarantineOpenReport(report, fence, true);
  }

  @Override
  @Transactional
  public TradingLabReportFinalizeResult finalizeReport(
      UUID reportId,
      TradingLabReportWriteFence fence,
      TradingLabReportMeasurement measurement,
      TradingLabReportOutcome outcome,
      Duration retention
  ) {
    Objects.requireNonNull(measurement, "measurement");
    Objects.requireNonNull(outcome, "outcome");
    long retentionMillis = requireRetentionMillis(retention);
    LockedReportRows locked = lockRunThenReportForFinalize(reportId, fence);
    TradingLabReportEntity report = locked.report();

    requireUnfencedFinalizeOutcome(locked.run(), fence, outcome);
    if (report.getCompletedAt() != null) {
      if (sameTerminalReport(report, measurement, outcome, retentionMillis)) {
        return TradingLabReportFinalizeResult.REPLAY;
      }
      throw error(
          "TRADING_LAB_REPORT_STATUS_CONFLICT",
          "Trading Lab report already has a different terminal outcome");
    }
    requireWritableFence(locked.run(), fence);
    requireOpen(report);
    requireAllowedOpenOutcome(report, outcome);
    if (requiredVersion(report) != measurement.reportVersion()) {
      return TradingLabReportFinalizeResult.RETRY;
    }
    validateAllChunkSequences(reportId);
    TradingLabReportChunkTotals actual = chunks.totals(reportId);
    if (actual == null
        || actual.compressedBytes() != measurement.compressedBytes()
        || actual.chunkCount() != measurement.chunkCount()) {
      throw new TradingLabReportCorruptionException();
    }
    int changed = fence == null
        ? reports.finalizeReport(
            reportId,
            measurement.reportVersion(),
            outcome.status().name(),
            outcome.failureCode(),
            outcome.failureMessage(),
            measurement.exactUncompressedBytes(),
            measurement.compressedBytes(),
            measurement.chunkCount(),
            retentionMillis)
        : reports.finalizeReportFenced(
            reportId,
            measurement.reportVersion(),
            outcome.status().name(),
            outcome.failureCode(),
            outcome.failureMessage(),
            measurement.exactUncompressedBytes(),
            measurement.compressedBytes(),
            measurement.chunkCount(),
            retentionMillis,
            fence.runId(),
            fence.claimOwner());
    if (changed == 0 && fence != null) {
      throw fenceLost();
    }
    return changed == 1
        ? TradingLabReportFinalizeResult.CLOSED
        : TradingLabReportFinalizeResult.RETRY;
  }

  private PersistedTotals persistSection(
      UUID reportId,
      TradingLabReportSectionWrite write,
      long currentNext
  ) {
    if (write.firstChunkSequence() > currentNext) {
      throw chunkConflict();
    }

    Long durableHighest = null;
    for (TradingLabLogicalAppend append : write.appends()) {
      if (append.sourceSequence() != null && append.sourceSequence() >= 0L) {
        durableHighest = appends.findHighestSourceSequence(
            reportId, write.section().name());
        break;
      }
    }

    List<AppendDecision> decisions = new ArrayList<>(write.appends().size());
    Long previousSemantic = null;
    boolean hasUnmarked = false;
    boolean sawReplay = false;
    boolean sawNewSemantic = false;
    for (TradingLabLogicalAppend append : write.appends()) {
      Long sourceSequence = append.sourceSequence();
      if (sourceSequence != null && sourceSequence >= 0L) {
        if (previousSemantic != null && sourceSequence <= previousSemantic) {
          throw eventConflict(sourceSequence);
        }
        previousSemantic = sourceSequence;
      }
      LedgerDecision decision = writeLedger(reportId, write.section(), append);
      decisions.add(new AppendDecision(append, decision));
      if (decision == LedgerDecision.NONE) {
        hasUnmarked = true;
      }
      if (decision == LedgerDecision.REPLAY) {
        if (sourceSequence != null && sourceSequence >= 0L && sawNewSemantic) {
          throw eventConflict(sourceSequence);
        }
        sawReplay = true;
      } else if (decision == LedgerDecision.NEW
          && sourceSequence != null
          && sourceSequence >= 0L) {
        if (durableHighest != null && sourceSequence <= durableHighest) {
          throw eventConflict(sourceSequence);
        }
        sawNewSemantic = true;
      }
    }

    List<TradingLabLogicalAppend> candidates = new ArrayList<>(decisions.size());
    for (AppendDecision decision : decisions) {
      if (hasUnmarked || decision.decision() != LedgerDecision.REPLAY) {
        candidates.add(decision.append());
      }
    }
    if (candidates.isEmpty()) {
      return PersistedTotals.EMPTY;
    }

    long firstSequence = write.firstChunkSequence();
    if (!hasUnmarked && sawReplay) {
      firstSequence = currentNext;
    } else if (!hasUnmarked && firstSequence != currentNext) {
      throw chunkConflict();
    }

    ChunkAccumulator accumulator = new ChunkAccumulator(
        reportId, write.section(), firstSequence, currentNext);
    for (TradingLabLogicalAppend candidate : candidates) {
      accumulator.append(candidate.internalBytes());
    }
    return accumulator.finish();
  }

  private LedgerDecision writeLedger(
      UUID reportId,
      TradingLabReportSection section,
      TradingLabLogicalAppend append
  ) {
    Long sourceSequence = append.sourceSequence();
    if (sourceSequence == null) {
      return LedgerDecision.NONE;
    }
    int inserted = appends.insertIfAbsent(
        reportId,
        section.name(),
        sourceSequence,
        append.canonicalByteCount(),
        append.canonicalChecksum());
    if (inserted == 1) {
      return LedgerDecision.NEW;
    }
    TradingLabReportAppendEntity existing = appends.findByKey(
            reportId, section.name(), sourceSequence)
        .orElseThrow(() -> eventConflict(sourceSequence));
    if (existing.getCanonicalBytes() == null
        || existing.getCanonicalBytes() != append.canonicalByteCount()
        || !append.canonicalChecksum().equals(existing.getCanonicalChecksum())) {
      throw eventConflict(sourceSequence);
    }
    return LedgerDecision.REPLAY;
  }

  private void requireExactChunk(
      UUID reportId,
      TradingLabReportSection section,
      long sequence,
      byte[] expectedPlain,
      int expectedLength,
      String expectedEncoding,
      long expectedUncompressedBytes,
      long expectedCompressedBytes,
      String expectedChecksum,
      TradingLabReportChunkEntity existing
  ) {
    if (!reportId.equals(existing.getReportId())
        || !section.name().equals(existing.getSection())
        || existing.getSequence() == null
        || existing.getSequence() != sequence
        || !expectedEncoding.equals(existing.getEncoding())
        || existing.getUncompressedBytes() == null
        || existing.getUncompressedBytes() != expectedUncompressedBytes
        || existing.getCompressedBytes() == null
        || existing.getCompressedBytes() != expectedCompressedBytes
        || !expectedChecksum.equals(existing.getChecksum())
        || !codec.decodeAndVerifyMatches(
            existing, expectedPlain, 0, expectedLength)) {
      throw chunkConflict();
    }
  }

  private TradingLabReportEntity lockRunThenReport(
      UUID reportId,
      TradingLabReportWriteFence fence
  ) {
    Objects.requireNonNull(reportId, "reportId");
    if (fence != null && !reportId.equals(fence.reportId())) {
      throw fenceLost();
    }
    if (fence == null) {
      Optional<TradingLabReportRunFenceRow> linkedRun = fences.lockRunForReport(reportId);
      linkedRun.ifPresent(this::requireUnfencedRun);
    } else {
      TradingLabReportRunFenceRow run = fences.lockFencedRun(
              fence.runId(), reportId)
          .orElseThrow(this::fenceLost);
      requireExactFence(run, fence);
    }
    return reports.lockById(reportId).orElseThrow(() -> error(
        "TRADING_LAB_REPORT_NOT_FOUND", "Trading Lab report was not found"));
  }

  private LockedReportRows lockRunThenReportForFinalize(
      UUID reportId,
      TradingLabReportWriteFence fence
  ) {
    Objects.requireNonNull(reportId, "reportId");
    if (fence != null && !reportId.equals(fence.reportId())) {
      throw fenceLost();
    }
    Optional<TradingLabReportRunFenceRow> run;
    if (fence == null) {
      run = fences.lockRunForReport(reportId);
    } else {
      run = Optional.of(fences.lockFencedRun(fence.runId(), reportId)
          .orElseThrow(this::fenceLost));
    }
    TradingLabReportEntity report = reports.lockById(reportId).orElseThrow(() -> error(
        "TRADING_LAB_REPORT_NOT_FOUND", "Trading Lab report was not found"));
    return new LockedReportRows(run, report);
  }

  private void requireWritableFence(
      Optional<TradingLabReportRunFenceRow> run,
      TradingLabReportWriteFence fence
  ) {
    if (fence == null) {
      run.ifPresent(this::requireUnfencedRun);
      return;
    }
    requireExactFence(run.orElseThrow(this::fenceLost), fence);
  }

  private void requireUnfencedFinalizeOutcome(
      Optional<TradingLabReportRunFenceRow> run,
      TradingLabReportWriteFence fence,
      TradingLabReportOutcome outcome
  ) {
    if (fence == null
        && run.isPresent()
        && outcome.status() != TradingLabReportStatus.FAILED) {
      throw fenceLost();
    }
  }

  private void requireUnfencedRun(TradingLabReportRunFenceRow run) {
    if (!UNFENCED_RUN_STATES.contains(run.state())
        || run.leaseKey() != null
        || run.leaseOwner() != null
        || run.leaseUntil() != null) {
      throw fenceLost();
    }
  }

  private void requireExactFence(
      TradingLabReportRunFenceRow run,
      TradingLabReportWriteFence fence
  ) {
    if (!fence.runId().equals(run.runId())
        || !fence.reportId().equals(run.reportId())
        || run.leaseKey() == null
        || run.leaseKey() != 1
        || !fence.claimOwner().equals(run.leaseOwner())
        || !run.leaseLive()) {
      throw fenceLost();
    }
  }

  private void requireFenceStillLive(TradingLabReportWriteFence fence) {
    if (fence != null && !fences.fenceIsLive(
        fence.runId(), fence.reportId(), fence.claimOwner())) {
      throw fenceLost();
    }
  }

  private void requireOpen(TradingLabReportEntity report) {
    if (report.getCompletedAt() != null) {
      throw error(
          "TRADING_LAB_REPORT_CLOSED", "Trading Lab report is already closed");
    }
    if (!"PENDING".equals(report.getStatus())
        && !"WRITING".equals(report.getStatus())) {
      throw error(
          "TRADING_LAB_REPORT_STATUS_CONFLICT",
          "Trading Lab report is not writable");
    }
    requiredVersion(report);
  }

  private TradingLabReportWriteState openWriteState(TradingLabReportEntity report) {
    if (TradingLabReportQuarantinePolicy.hasOpenMarker(report)) {
      return TradingLabReportWriteState.QUARANTINED;
    }
    if (hasNoFailureFields(report)) {
      return TradingLabReportWriteState.WRITABLE;
    }
    throw new TradingLabReportCorruptionException();
  }

  private void requireAllowedOpenOutcome(
      TradingLabReportEntity report,
      TradingLabReportOutcome outcome
  ) {
    if (hasNoFailureFields(report)) {
      return;
    }
    if (!TradingLabReportQuarantinePolicy.hasNormalizedOpenMarker(report)) {
      throw new TradingLabReportCorruptionException();
    }
    if (isGenericQuarantineOutcome(outcome)) {
      return;
    }
    if (outcome.status() == TradingLabReportStatus.COMPLETED) {
      throw incomplete();
    }
    throw unsafeTrace();
  }

  private boolean isGenericQuarantineOutcome(TradingLabReportOutcome outcome) {
    return TradingLabReportQuarantinePolicy.isGenericOutcome(outcome);
  }

  private TradingLabReportRecoveryState quarantineOpenReport(
      TradingLabReportEntity report,
      TradingLabReportWriteFence fence,
      boolean forceQuarantine
  ) {
    boolean normalizedMarker =
        TradingLabReportQuarantinePolicy.hasNormalizedOpenMarker(report);
    boolean cleanFailureFields = hasNoFailureFields(report);
    boolean hadEvidence = hasRecordedEvidence(report);
    int deletedAppends = appends.deleteByReportId(report.getId());
    int deletedChunks = chunks.deleteByReportId(report.getId());

    boolean pristinePending = cleanFailureFields
        && "PENDING".equals(report.getStatus())
        && Long.valueOf(0L).equals(report.getVersion())
        && !hadEvidence
        && deletedAppends == 0
        && deletedChunks == 0;
    if (!forceQuarantine && pristinePending) {
      requireFenceStillLive(fence);
      return TradingLabReportRecoveryState.EMPTY;
    }
    if (normalizedMarker
        && !hadEvidence
        && deletedAppends == 0
        && deletedChunks == 0) {
      requireFenceStillLive(fence);
      return TradingLabReportRecoveryState.QUARANTINED;
    }

    int changed = fence == null
        ? reports.quarantineEvidence(report.getId())
        : reports.quarantineEvidenceFenced(
            report.getId(),
            fence.runId(),
            fence.claimOwner());
    if (changed != 1) {
      if (fence != null) {
        throw fenceLost();
      }
      throw error(
          "TRADING_LAB_REPORT_STATUS_CONFLICT",
          "Trading Lab report evidence could not be quarantined");
    }
    return TradingLabReportRecoveryState.QUARANTINED;
  }

  private TradingLabReportRecoveryState terminalRecoveryState(
      TradingLabReportEntity report
  ) {
    return TradingLabReportQuarantinePolicy.isNormalizedGenericTerminal(report)
        ? TradingLabReportRecoveryState.QUARANTINED_TERMINAL
        : TradingLabReportRecoveryState.TERMINAL;
  }

  private boolean hasNoFailureFields(TradingLabReportEntity report) {
    return report.getFailureCode() == null && report.getFailureMessage() == null;
  }

  private long requiredVersion(TradingLabReportEntity report) {
    if (report.getVersion() == null || report.getVersion() < 0L) {
      throw new TradingLabReportCorruptionException();
    }
    return report.getVersion();
  }

  private boolean hasRecordedEvidence(TradingLabReportEntity report) {
    return hasMetadataEvidence(report)
        || !Long.valueOf(0L).equals(report.getUncompressedBytes())
        || !Long.valueOf(0L).equals(report.getCompressedBytes())
        || !Integer.valueOf(0).equals(report.getChunkCount());
  }

  private boolean hasMetadataEvidence(TradingLabReportEntity report) {
    String metadataJson = report.getMetadataJson();
    return metadataJson != null && !"{}".equals(metadataJson.trim());
  }

  private EnumMap<TradingLabReportSection, Long> validateAllChunkSequences(
      UUID reportId
  ) {
    EnumMap<TradingLabReportSection, Long> sequences =
        new EnumMap<>(TradingLabReportSection.class);
    for (TradingLabReportSection section : TradingLabReportSection.values()) {
      sequences.put(
          section,
          requiredNextSequence(chunks.sequenceState(reportId, section.name())));
    }
    return sequences;
  }

  private long requiredNextSequence(TradingLabReportChunkSequenceState state) {
    if (state == null || state.chunkCount() < 0L) {
      throw new TradingLabReportCorruptionException();
    }
    if (state.chunkCount() == 0L) {
      if (state.minSequence() != null || state.maxSequence() != null) {
        throw new TradingLabReportCorruptionException();
      }
      return 0L;
    }
    long expectedMax = Math.subtractExact(state.chunkCount(), 1L);
    if (state.minSequence() == null
        || state.minSequence() != 0L
        || state.maxSequence() == null
        || state.maxSequence() != expectedMax) {
      throw new TradingLabReportCorruptionException();
    }
    return state.chunkCount();
  }

  private List<TradingLabReportSectionWrite> orderedSections(
      List<TradingLabReportSectionWrite> sectionWrites
  ) {
    List<TradingLabReportSectionWrite> ordered = new ArrayList<>(sectionWrites);
    ordered.sort(Comparator.comparingInt(write -> write.section().ordinal()));
    EnumSet<TradingLabReportSection> seen =
        EnumSet.noneOf(TradingLabReportSection.class);
    for (TradingLabReportSectionWrite write : ordered) {
      if (!seen.add(write.section())) {
        throw new IllegalArgumentException("Duplicate Trading Lab report section write");
      }
    }
    return ordered;
  }

  private boolean sameTerminalReport(
      TradingLabReportEntity report,
      TradingLabReportMeasurement measurement,
      TradingLabReportOutcome outcome,
      long retentionMillis
  ) {
    return outcome.status().name().equals(report.getStatus())
        && Objects.equals(outcome.failureCode(), report.getFailureCode())
        && Objects.equals(outcome.failureMessage(), report.getFailureMessage())
        && report.getUncompressedBytes() != null
        && report.getUncompressedBytes() == measurement.exactUncompressedBytes()
        && report.getCompressedBytes() != null
        && report.getCompressedBytes() == measurement.compressedBytes()
        && report.getChunkCount() != null
        && report.getChunkCount() == measurement.chunkCount()
        && report.getRetainedUntil() != null
        && report.getCompletedAt() != null
        && Duration.between(report.getCompletedAt(), report.getRetainedUntil())
            .equals(Duration.ofMillis(retentionMillis));
  }

  private long requireRetentionMillis(Duration retention) {
    Objects.requireNonNull(retention, "retention");
    long millis = retention.toMillis();
    if (retention.isNegative() || retention.isZero() || millis < 1L) {
      throw new IllegalArgumentException("Trading Lab report retention must be positive");
    }
    return millis;
  }

  private TradingLabReportException eventConflict(long sourceSequence) {
    return error(
        sourceSequence == -1L
            ? "TRADING_LAB_REPORT_SINGLETON_CONFLICT"
            : "TRADING_LAB_REPORT_EVENT_CONFLICT",
        sourceSequence == -1L
            ? "Trading Lab singleton section already has a different value"
            : "Trading Lab report source event already has a different value");
  }

  private TradingLabReportException chunkConflict() {
    return error(
        "TRADING_LAB_REPORT_CHUNK_CONFLICT",
        "Trading Lab report chunk already has a different value");
  }

  private TradingLabReportException metadataConflict() {
    return error(
        "TRADING_LAB_REPORT_METADATA_CONFLICT",
        "Trading Lab report metadata already has a different value");
  }

  private TradingLabReportException fenceLost() {
    return error(
        "TRADING_LAB_REPORT_FENCE_LOST",
        "Trading Lab report write fence is no longer valid");
  }

  private TradingLabReportException unsafeTrace() {
    return error(
        TradingLabReportQuarantinePolicy.FAILURE_CODE,
        "Trading Lab report is quarantined after unsafe evidence");
  }

  private TradingLabReportException incomplete() {
    return error(
        "TRADING_LAB_REPORT_INCOMPLETE",
        "Trading Lab report contains rejected evidence and cannot be completed");
  }

  private static TradingLabReportException error(String code, String message) {
    return new TradingLabReportException(code, message);
  }

  private final class ChunkAccumulator {

    private final UUID reportId;
    private final TradingLabReportSection section;
    private final byte[] pending = new byte[Math.addExact(codec.chunkBytes(), 4)];
    private long sequence;
    private long currentNext;
    private int pendingBytes;
    private long rawBytes;
    private long compressedBytes;
    private int chunkCount;

    private ChunkAccumulator(
        UUID reportId,
        TradingLabReportSection section,
        long sequence,
        long currentNext
    ) {
      this.reportId = reportId;
      this.section = section;
      this.sequence = sequence;
      this.currentNext = currentNext;
    }

    private void append(byte[] value) {
      int offset = 0;
      while (offset < value.length) {
        int copied = Math.min(value.length - offset, pending.length - pendingBytes);
        if (copied == 0) {
          emitBoundary();
          continue;
        }
        System.arraycopy(value, offset, pending, pendingBytes, copied);
        pendingBytes += copied;
        offset += copied;
        while (pendingBytes > codec.chunkBytes()) {
          emitBoundary();
        }
      }
    }

    private PersistedTotals finish() {
      if (pendingBytes > 0) {
        emitPrefix(pendingBytes);
      }
      return new PersistedTotals(rawBytes, compressedBytes, chunkCount);
    }

    private void emitBoundary() {
      int boundary = codec.chunkBytes();
      while (boundary > 0 && (pending[boundary] & 0xc0) == 0x80) {
        boundary--;
      }
      if (boundary == 0) {
        throw new IllegalArgumentException(
            "Trading Lab chunk cannot preserve UTF-8 boundaries");
      }
      emitPrefix(boundary);
    }

    private void emitPrefix(int length) {
      TradingLabEncodedChunk encoded = codec.encode(pending, 0, length);
      String expectedEncoding = encoded.encoding();
      long expectedUncompressedBytes = encoded.uncompressedBytes();
      long expectedCompressedBytes = encoded.compressedBytes();
      String expectedChecksum = encoded.checksum();
      int inserted = chunks.insertIfAbsent(
          UUID.randomUUID(),
          reportId,
          section.name(),
          sequence,
          expectedEncoding,
          expectedUncompressedBytes,
          expectedCompressedBytes,
          encoded.payload(),
          expectedChecksum);
      encoded = null;
      if (inserted == 1) {
        if (sequence != currentNext) {
          throw chunkConflict();
        }
        currentNext = Math.addExact(currentNext, 1L);
        rawBytes = Math.addExact(rawBytes, expectedUncompressedBytes);
        compressedBytes = Math.addExact(
            compressedBytes, expectedCompressedBytes);
        chunkCount = Math.addExact(chunkCount, 1);
      } else {
        TradingLabReportChunkEntity existing = chunks.findByKey(
                reportId, section.name(), sequence)
            .orElseThrow(MyBatisTradingLabReportStore.this::chunkConflict);
        requireExactChunk(
            reportId,
            section,
            sequence,
            pending,
            length,
            expectedEncoding,
            expectedUncompressedBytes,
            expectedCompressedBytes,
            expectedChecksum,
            existing);
        if (sequence == currentNext) {
          currentNext = Math.addExact(currentNext, 1L);
        }
      }
      sequence = Math.addExact(sequence, 1L);
      int remaining = pendingBytes - length;
      System.arraycopy(pending, length, pending, 0, remaining);
      pendingBytes = remaining;
    }
  }

  private record AppendDecision(
      TradingLabLogicalAppend append,
      LedgerDecision decision
  ) {
  }

  private record LockedReportRows(
      Optional<TradingLabReportRunFenceRow> run,
      TradingLabReportEntity report
  ) {
  }

  private enum LedgerDecision {
    NONE,
    NEW,
    REPLAY
  }

  private record PersistedTotals(long rawBytes, long compressedBytes, int chunkCount) {
    private static final PersistedTotals EMPTY = new PersistedTotals(0L, 0L, 0);
  }
}
