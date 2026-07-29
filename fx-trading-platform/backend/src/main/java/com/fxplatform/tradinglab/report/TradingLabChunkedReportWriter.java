package com.fxplatform.tradinglab.report;

import com.fxplatform.common.exception.BusinessException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TradingLabChunkedReportWriter
    implements TradingLabReportWriter, TradingLabFencedReportWriter {

  private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z0-9_]{1,80}");
  private static final int MAX_FINALIZE_ATTEMPTS = 16;
  private static final int MAX_BUFFERED_APPENDS_PER_SECTION = 1024;
  private static final String GENERIC_UNSAFE_TRACE_CODE =
      TradingLabReportQuarantinePolicy.FAILURE_CODE;
  private static final String GENERIC_UNSAFE_TRACE_SUMMARY =
      TradingLabReportQuarantinePolicy.FAILURE_MESSAGE;

  private final TradingLabReportCanonicalizer canonicalizer;
  private final TradingLabReportStore store;
  private final TradingLabReportStreamer streamer;
  private final List<String> fixedValidationSecrets;
  private final int chunkBytes;
  private final int maxActiveWriters;
  private final Duration retention;
  private final Object monitor = new Object();
  private final Map<UUID, ReportBuffer> active = new LinkedHashMap<>();

  @Autowired
  public TradingLabChunkedReportWriter(
      TradingLabReportCanonicalizer canonicalizer,
      TradingLabReportStore store,
      TradingLabReportStreamer streamer,
      TradingLabFixedValidationSecretProvider fixedValidationSecrets,
      TradingLabReportProperties properties
  ) {
    this(
        canonicalizer,
        store,
        streamer,
        fixedValidationSecrets,
        properties.chunkBytes(),
        properties.maxActiveWriters(),
        properties.retention());
  }

  TradingLabChunkedReportWriter(
      TradingLabReportCanonicalizer canonicalizer,
      TradingLabReportStore store,
      TradingLabReportStreamer streamer,
      TradingLabFixedValidationSecretProvider fixedValidationSecrets,
      int chunkBytes,
      int maxActiveWriters,
      Duration retention
  ) {
    this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    this.store = Objects.requireNonNull(store, "store");
    this.streamer = Objects.requireNonNull(streamer, "streamer");
    this.fixedValidationSecrets = Objects.requireNonNull(
        fixedValidationSecrets, "fixedValidationSecrets").secrets();
    if (chunkBytes < 1 || maxActiveWriters < 1) {
      throw new IllegalArgumentException("Trading Lab writer limits must be positive");
    }
    this.chunkBytes = chunkBytes;
    this.maxActiveWriters = maxActiveWriters;
    this.retention = Objects.requireNonNull(retention, "retention");
  }

  @Override
  public void registerTraceSecrets(
      TradingLabReportWriteFence fence,
      SafeTradingLabHttpTrace trace
  ) {
    Objects.requireNonNull(trace, "trace");
    synchronized (monitor) {
      ReportBuffer report = prepareJournalEvidence(fence);
      registerTraceSecrets(report, trace);
    }
  }

  @Override
  public void requireSafeJournalEvidence(
      TradingLabReportWriteFence fence,
      Object value
  ) {
    synchronized (monitor) {
      ReportBuffer report = prepareJournalEvidence(fence);
      requireSafeJournalEvidence(report, value);
    }
  }

  @Override
  public <T> T withJournalEvidenceGuard(
      TradingLabReportWriteFence fence,
      SafeTradingLabHttpTrace trace,
      Function<TradingLabJournalEvidenceGuard, T> transaction
  ) {
    Objects.requireNonNull(transaction, "transaction");
    synchronized (monitor) {
      ReportBuffer report = prepareJournalEvidence(fence);
      requireJournalReady(report);
      if (trace != null) {
        registerTraceSecrets(report, trace);
      }
      try {
        return transaction.apply(value -> scanJournalEvidence(report, value));
      } catch (UnsafeJournalEvidenceException exception) {
        report.tainted = true;
        sealUnsafe(report);
        throw unsafeValue();
      }
    }
  }

  private void requireJournalReady(ReportBuffer report) {
    if (report.metadataInitialized) {
      return;
    }
    sealUnsafe(report);
    throw unsafeTrace();
  }

  private void registerTraceSecrets(
      ReportBuffer report,
      SafeTradingLabHttpTrace trace
  ) {
    long generationBefore = report.secrets.generation();
    try {
      trace.registerSecrets(report.secrets);
      if (report.secrets.generation() != generationBefore) {
        requireLateSecretsSafe(report);
      }
    } catch (TradingLabTraceSecretRegistry.MergeOverflowException
        | TradingLabReportSecretRegistry.RegistrationOverflowException exception) {
      sealUnsafe(report);
      throw exception;
    } catch (RuntimeException exception) {
      report.tainted = true;
      throw exception;
    }
  }

  private void requireSafeJournalEvidence(ReportBuffer report, Object value) {
    try {
      canonicalizer.requireNoCanary(value, report.secrets);
    } catch (RuntimeException exception) {
      report.tainted = true;
      sealUnsafe(report);
      throw unsafeValue();
    }
  }

  private void scanJournalEvidence(ReportBuffer report, Object value) {
    try {
      canonicalizer.requireNoCanary(value, report.secrets);
    } catch (RuntimeException exception) {
      throw new UnsafeJournalEvidenceException();
    }
  }

  private ReportBuffer prepareJournalEvidence(TradingLabReportWriteFence fence) {
    Objects.requireNonNull(fence, "fence");
    UUID reportId = fence.reportId();
    ReportBuffer report = active.get(reportId);
    if (report != null && report.matchesFence(fence)) {
      requireEvidenceOpen(report);
      retryUncertain(report);
      retryUncertainMetadata(report);
    }

    TradingLabReportWriteState writeState;
    try {
      writeState = store.ensureWritable(reportId, fence);
    } catch (BusinessException exception) {
      if (!"TRADING_LAB_REPORT_FENCE_LOST".equals(exception.getCode())
          || report == null
          || report.matchesFence(fence)) {
        evictIfStale(reportId, exception);
      }
      throw exception;
    }

    if (report != null && !report.matchesFence(fence)) {
      report = replaceFence(report, fence);
      active.put(reportId, report);
    }
    if (report == null) {
      if (active.size() >= maxActiveWriters) {
        throw error(
            "TRADING_LAB_REPORT_WRITER_LIMIT",
            "Trading Lab report writer admission limit is reached");
      }
      report = new ReportBuffer(reportId, fence, newSecretRegistry());
      active.put(reportId, report);
      if (writeState == TradingLabReportWriteState.QUARANTINED) {
        markQuarantined(report);
        throw unsafeTrace();
      }
      try {
        probeFreshRecovery(report);
      } catch (BusinessException exception) {
        evictIfStale(reportId, exception);
        throw exception;
      }
      if (report.terminalRecovery) {
        active.remove(reportId);
        throw closed();
      }
    }
    if (writeState == TradingLabReportWriteState.QUARANTINED) {
      markQuarantined(report);
      throw unsafeTrace();
    }
    requireEvidenceOpen(report);
    retryUncertainMetadata(report);
    return report;
  }

  @Override
  public void append(UUID reportId, TradingLabReportSection section, Object value) {
    appendInternal(reportId, null, section, null, value);
  }

  @Override
  public void initializeMetadata(
      TradingLabReportWriteFence fence,
      Object metadata
  ) {
    Objects.requireNonNull(fence, "fence");
    synchronized (monitor) {
      UUID reportId = fence.reportId();
      ReportBuffer report = active.get(reportId);
      if (report != null && report.matchesFence(fence)) {
        requireEvidenceOpen(report);
        retryUncertain(report);
        retryUncertainMetadata(report);
      }

      TradingLabReportWriteState writeState;
      try {
        writeState = store.ensureWritable(reportId, fence);
      } catch (BusinessException exception) {
        if (!"TRADING_LAB_REPORT_FENCE_LOST".equals(exception.getCode())
            || report == null
            || report.matchesFence(fence)) {
          evictIfStale(reportId, exception);
        }
        throw exception;
      }

      if (report != null && !report.matchesFence(fence)) {
        report = replaceFence(report, fence);
        active.put(reportId, report);
      }
      if (report == null) {
        if (active.size() >= maxActiveWriters) {
          throw error(
              "TRADING_LAB_REPORT_WRITER_LIMIT",
              "Trading Lab report writer admission limit is reached");
        }
        report = new ReportBuffer(reportId, fence, newSecretRegistry());
        active.put(reportId, report);
        if (writeState == TradingLabReportWriteState.QUARANTINED) {
          markQuarantined(report);
          throw unsafeTrace();
        }
        try {
          probeFreshRecovery(report);
        } catch (BusinessException exception) {
          evictIfStale(reportId, exception);
          throw exception;
        }
        if (report.terminalRecovery) {
          active.remove(reportId);
          throw closed();
        }
      }

      if (writeState == TradingLabReportWriteState.QUARANTINED) {
        markQuarantined(report);
        throw unsafeTrace();
      }
      requireEvidenceOpen(report);
      retryUncertainMetadata(report);

      long generationBefore = report.secrets.generation();
      TradingLabCanonicalValue canonical;
      try {
        canonical = canonicalizer.canonicalize(
            TradingLabReportSection.METADATA, metadata, report.secrets);
      } catch (TradingLabTraceSecretRegistry.MergeOverflowException exception) {
        sealUnsafe(report);
        throw exception;
      } catch (TradingLabReportSecretRegistry.RegistrationOverflowException exception) {
        sealUnsafe(report);
        throw exception;
      } catch (RuntimeException exception) {
        report.tainted = true;
        if (report.secrets.generation() != generationBefore) {
          requireLateSecretsSafe(report);
        }
        throw exception;
      }
      if (report.secrets.generation() != generationBefore) {
        try {
          requireLateSecretsSafe(report);
        } catch (RuntimeException exception) {
          report.tainted = true;
          throw exception;
        }
      }
      persistMetadata(report, canonical);
    }
  }

  @Override
  public void appendSingleton(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section,
      Object value
  ) {
    Objects.requireNonNull(fence, "fence");
    Objects.requireNonNull(section, "section");
    if (section.isArray()) {
      throw new IllegalArgumentException(
          "Trading Lab fenced singleton requires a non-array section");
    }
    appendInternal(fence.reportId(), fence, section, -1L, value);
  }

  @Override
  public void appendEvent(
      TradingLabReportWriteFence fence,
      TradingLabReportSection section,
      long sourceSequence,
      Object value
  ) {
    Objects.requireNonNull(fence, "fence");
    Objects.requireNonNull(section, "section");
    boolean preamble = sourceSequence == -1L
        && section == TradingLabReportSection.API_TRACE;
    if ((sourceSequence < 0L && !preamble) || !section.isArray()) {
      throw new IllegalArgumentException(
          sourceSequence < 0L
              ? "Trading Lab event source sequence is invalid"
              : "Trading Lab fenced events require an array section");
    }
    appendInternal(fence.reportId(), fence, section, sourceSequence, value);
  }

  @Override
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
    synchronized (monitor) {
      ReportBuffer report = active.get(fence.reportId());
      if (report != null && report.matchesFence(fence)) {
        requireEvidenceOpen(report);
        retryUncertain(report);
        retryUncertainMetadata(report);
      }
      try {
        return store.highestDurableSourceSequence(fence, section);
      } catch (BusinessException exception) {
        evictIfStale(fence.reportId(), exception);
        throw exception;
      }
    }
  }

  private void appendInternal(
      UUID reportId,
      TradingLabReportWriteFence fence,
      TradingLabReportSection section,
      Long sourceSequence,
      Object value
  ) {
    Objects.requireNonNull(reportId, "reportId");
    synchronized (monitor) {
      ReportBuffer report = active.get(reportId);
      if (report != null && report.matchesFence(fence)) {
        requireEvidenceOpen(report);
        retryUncertain(report);
        retryUncertainMetadata(report);
      }

      Objects.requireNonNull(section, "section");
      if (sourceSequence != null
          && !((sourceSequence >= 0L && section.isArray())
              || (sourceSequence == -1L
                  && (!section.isArray()
                      || section == TradingLabReportSection.API_TRACE)))) {
        throw new IllegalArgumentException(
            sourceSequence < 0L
                ? "Trading Lab event source sequence is invalid"
                : "Trading Lab fenced events require an array section");
      }

      TradingLabReportWriteState writeState;
      try {
        writeState = store.ensureWritable(reportId, fence);
      } catch (BusinessException exception) {
        if (!"TRADING_LAB_REPORT_FENCE_LOST".equals(exception.getCode())
            || report == null
            || report.matchesFence(fence)) {
          evictIfStale(reportId, exception);
        }
        throw exception;
      }

      if (report != null && !report.matchesFence(fence)) {
        report = replaceFence(report, fence);
        active.put(reportId, report);
      }
      if (report == null) {
        if (active.size() >= maxActiveWriters) {
          throw error(
              "TRADING_LAB_REPORT_WRITER_LIMIT",
              "Trading Lab report writer admission limit is reached");
        }
        report = new ReportBuffer(reportId, fence, newSecretRegistry());
        active.put(reportId, report);
        if (writeState == TradingLabReportWriteState.QUARANTINED) {
          markQuarantined(report);
          throw unsafeTrace();
        }
        try {
          probeFreshRecovery(report);
        } catch (BusinessException exception) {
          evictIfStale(reportId, exception);
          throw exception;
        }
        if (report.terminalRecovery) {
          active.remove(reportId);
          throw closed();
        }
      }

      if (writeState == TradingLabReportWriteState.QUARANTINED) {
        markQuarantined(report);
        throw unsafeTrace();
      }

      requireEvidenceOpen(report);
      retryUncertainMetadata(report);

      long generationBefore = report.secrets.generation();
      TradingLabCanonicalValue canonical;
      try {
        if (section == TradingLabReportSection.API_TRACE
            && value instanceof SafeTradingLabHttpTrace trace) {
          trace.registerSecrets(report.secrets);
        }
        canonical = canonicalizer.canonicalize(section, value, report.secrets);
      } catch (TradingLabTraceSecretRegistry.MergeOverflowException exception) {
        sealUnsafe(report);
        throw exception;
      } catch (TradingLabReportSecretRegistry.RegistrationOverflowException exception) {
        sealUnsafe(report);
        throw exception;
      } catch (RuntimeException exception) {
        report.tainted = true;
        if (report.secrets.generation() != generationBefore) {
          requireLateSecretsSafe(report);
        }
        throw exception;
      }
      if (report.secrets.generation() != generationBefore) {
        try {
          requireLateSecretsSafe(report);
        } catch (RuntimeException exception) {
          report.tainted = true;
          throw exception;
        }
      }

      SectionBuffer buffer = report.sections.get(section);
      Long semanticSequence = section.isArray()
          ? sourceSequence
          : Long.valueOf(-1L);
      TradingLabLogicalAppend append = new TradingLabLogicalAppend(semanticSequence, canonical);
      if (!acceptReplayOrRegister(buffer, append)) {
        return;
      }
      boolean contributesToTail = contributesToTail(buffer, append);
      if (contributesToTail) {
        try {
          canonicalizer.requireNoCanary(
              buffer.acceptedTail, append.internalBytes(), report.secrets);
        } catch (IllegalArgumentException exception) {
          sealUnsafe(report);
          throw unsafeValue();
        }
      }
      buffer.appends.add(append);
      if (contributesToTail) {
        buffer.tailAppends.add(append);
      }
      buffer.bufferedBytes = Math.addExact(
          buffer.bufferedBytes, canonical.byteLength());
      if (contributesToTail) {
        buffer.acceptedTail = appendTail(
            buffer.acceptedTail,
            append.internalBytes(),
            report.secrets.canaryTailBytes());
      }
      if (buffer.bufferedBytes >= chunkBytes
          || buffer.appends.size() >= MAX_BUFFERED_APPENDS_PER_SECTION) {
        persistFresh(report, fence, section);
      }
    }
  }

  private boolean acceptReplayOrRegister(
      SectionBuffer buffer,
      TradingLabLogicalAppend append
  ) {
    if (append.sourceSequence() != null && append.sourceSequence() == -1L) {
      AppendIdentity identity = AppendIdentity.from(append);
      if (buffer.singleton != null) {
        if (buffer.singleton.equals(identity)) {
          return false;
        }
        throw error(
            "TRADING_LAB_REPORT_SINGLETON_CONFLICT",
            "Trading Lab singleton section already has a different value");
      }
      buffer.singleton = identity;
      return true;
    }
    if (append.sourceSequence() != null) {
      TradingLabLogicalAppend existing = buffer.events.get(append.sourceSequence());
      if (existing != null) {
        if (existing.sameCanonical(append)) {
          return false;
        }
        throw error(
            "TRADING_LAB_REPORT_EVENT_CONFLICT",
            "Trading Lab report source event already has a different value");
      }
      buffer.events.put(append.sourceSequence(), append);
    }
    return true;
  }

  private static boolean contributesToTail(
      SectionBuffer buffer,
      TradingLabLogicalAppend append
  ) {
    Long sourceSequence = append.sourceSequence();
    return sourceSequence == null
        || sourceSequence < 0L
        || buffer.durableHighestSourceSequence == null
        || sourceSequence > buffer.durableHighestSourceSequence;
  }

  @Override
  public void flush(UUID reportId) {
    flushInternal(reportId, null);
  }

  @Override
  public void flush(TradingLabReportWriteFence fence) {
    Objects.requireNonNull(fence, "fence");
    flushInternal(fence.reportId(), fence);
  }

  private void flushInternal(UUID reportId, TradingLabReportWriteFence fence) {
    Objects.requireNonNull(reportId, "reportId");
    synchronized (monitor) {
      ReportBuffer report = active.get(reportId);
      if (report != null) {
        if (!report.matchesFence(fence)) {
          if (fence == null) {
            throw fenceLost();
          }
          TradingLabReportWriteState writeState;
          try {
            writeState = store.ensureWritable(reportId, fence);
          } catch (BusinessException exception) {
            if (!"TRADING_LAB_REPORT_FENCE_LOST".equals(exception.getCode())) {
              evictIfStale(reportId, exception);
            }
            throw exception;
          }
          report = replaceFence(report, fence);
          active.put(reportId, report);
          if (writeState == TradingLabReportWriteState.QUARANTINED) {
            markQuarantined(report);
            throw unsafeTrace();
          }
        }
        requireEvidenceOpen(report);
        retryUncertain(report);
        retryUncertainMetadata(report);
        if (!report.empty()) {
          persistFresh(report, fence, null);
        }
      }
    }
  }

  private void persistFresh(
      ReportBuffer report,
      TradingLabReportWriteFence fence,
      TradingLabReportSection onlySection
  ) {
    requireEvidenceOpen(report);
    requireBufferedSafe(report);
    List<TradingLabReportSectionWrite> writes = new ArrayList<>();
    List<SectionBuffer> sequenceAllocations = new ArrayList<>();
    TradingLabReportWriteBatch batch;
    try {
      for (TradingLabReportSection section : TradingLabReportSection.values()) {
        if (onlySection != null && onlySection != section) {
          continue;
        }
        SectionBuffer buffer = report.sections.get(section);
        if (buffer.appends.isEmpty()) {
          continue;
        }
        if (buffer.firstChunkSequence == null) {
          buffer.firstChunkSequence = store.nextChunkSequence(report.reportId, section);
          sequenceAllocations.add(buffer);
        }
        writes.add(new TradingLabReportSectionWrite(
            section,
            buffer.firstChunkSequence,
            buffer.appends));
      }
      if (writes.isEmpty()) {
        return;
      }
      batch = new TradingLabReportWriteBatch(report.reportId, fence, writes);
    } catch (RuntimeException exception) {
      sequenceAllocations.forEach(buffer -> buffer.firstChunkSequence = null);
      throw exception;
    }

    try {
      store.persist(batch);
    } catch (BusinessException exception) {
      handleDefinitivePersistFailure(report, batch, exception);
      throw exception;
    } catch (RuntimeException exception) {
      report.uncertainBatch = batch;
      throw exception;
    }
    acknowledgeBatch(report, batch);
  }

  private void persistMetadata(
      ReportBuffer report,
      TradingLabCanonicalValue metadata
  ) {
    try {
      store.initializeMetadata(report.fence, metadata);
    } catch (BusinessException exception) {
      if (GENERIC_UNSAFE_TRACE_CODE.equals(exception.getCode())) {
        markQuarantined(report);
      }
      evictIfStale(report.reportId, exception);
      throw exception;
    } catch (RuntimeException exception) {
      report.uncertainMetadata = metadata;
      throw exception;
    }
    report.uncertainMetadata = null;
    report.metadataInitialized = true;
  }

  private void retryUncertainMetadata(ReportBuffer report) {
    TradingLabCanonicalValue metadata = report.uncertainMetadata;
    if (metadata == null) {
      return;
    }
    try {
      store.initializeMetadata(report.fence, metadata);
    } catch (BusinessException exception) {
      if ("TRADING_LAB_REPORT_FENCE_LOST".equals(exception.getCode())) {
        throw exception;
      }
      report.uncertainMetadata = null;
      if (GENERIC_UNSAFE_TRACE_CODE.equals(exception.getCode())) {
        markQuarantined(report);
      }
      evictIfStale(report.reportId, exception);
      throw exception;
    }
    report.uncertainMetadata = null;
    report.metadataInitialized = true;
  }

  private void retryUncertain(ReportBuffer report) {
    TradingLabReportWriteBatch batch = report.uncertainBatch;
    if (batch == null) {
      return;
    }
    try {
      store.persist(batch);
    } catch (BusinessException exception) {
      if ("TRADING_LAB_REPORT_FENCE_LOST".equals(exception.getCode())) {
        throw exception;
      }
      handleDefinitivePersistFailure(report, batch, exception);
      throw exception;
    }
    acknowledgeBatch(report, batch);
  }

  private void handleDefinitivePersistFailure(
      ReportBuffer report,
      TradingLabReportWriteBatch batch,
      BusinessException exception
  ) {
    report.uncertainBatch = null;
    switch (exception.getCode()) {
      case GENERIC_UNSAFE_TRACE_CODE -> markQuarantined(report);
      case "TRADING_LAB_REPORT_FENCE_LOST" -> {
        // Preserve the old owner's bounded state so a validated replacement fence can inherit
        // its dynamic-secret registry before dropping the rejected pending evidence.
      }
      case "TRADING_LAB_REPORT_CLOSED",
           "TRADING_LAB_REPORT_STATUS_CONFLICT",
           "TRADING_LAB_REPORT_NOT_FOUND" -> active.remove(report.reportId);
      default -> rejectPendingBatch(report, batch);
    }
  }

  private static void rejectPendingBatch(
      ReportBuffer report,
      TradingLabReportWriteBatch batch
  ) {
    for (TradingLabReportSectionWrite write : batch.sections()) {
      SectionBuffer buffer = report.sections.get(write.section());
      clearBuffered(buffer);
      buffer.singleton = null;
      buffer.acceptedTail = buffer.durableTail.clone();
    }
    report.tainted = true;
  }

  private static void acknowledgeBatch(
      ReportBuffer report,
      TradingLabReportWriteBatch batch
  ) {
    for (TradingLabReportSectionWrite write : batch.sections()) {
      SectionBuffer buffer = report.sections.get(write.section());
      buffer.durableTail = buffer.acceptedTail.clone();
      buffer.durableHighestSourceSequence = highestSourceSequence(
          buffer.durableHighestSourceSequence, write.appends());
      clearBuffered(buffer);
    }
    report.uncertainBatch = null;
  }

  private static void clearEvidenceState(ReportBuffer report) {
    report.uncertainBatch = null;
    report.uncertainMetadata = null;
    report.metadataInitialized = false;
    for (SectionBuffer buffer : report.sections.values()) {
      clearBuffered(buffer);
      buffer.singleton = null;
      buffer.acceptedTail = new byte[0];
      buffer.durableTail = new byte[0];
      buffer.durableHighestSourceSequence = null;
    }
  }

  private void requireLateSecretsSafe(ReportBuffer report) {
    final TradingLabReportRecoveryState recovery;
    try {
      recovery = store.discardEvidence(report.reportId, report.fence);
    } catch (RuntimeException exception) {
      markUnsafe(report);
      throw exception;
    }
    if (recovery.terminal()) {
      active.remove(report.reportId);
      throw closed();
    }
    if (recovery.quarantined()) {
      markQuarantined(report);
      throw unsafeValue();
    }
    for (SectionBuffer buffer : report.sections.values()) {
      buffer.acceptedTail = new byte[0];
      buffer.durableTail = new byte[0];
      buffer.durableHighestSourceSequence = null;
      buffer.tailAppends.clear();
      buffer.tailAppends.addAll(buffer.appends);
    }
    try {
      scanAllUnpersisted(report);
    } catch (IllegalArgumentException exception) {
      sealUnsafe(report);
      throw unsafeValue();
    }
    rebuildAcceptedTails(report);
  }

  private void requireBufferedSafe(ReportBuffer report) {
    try {
      scanAllUnpersisted(report);
    } catch (IllegalArgumentException exception) {
      sealUnsafe(report);
      throw unsafeValue();
    }
  }

  private void scanAllUnpersisted(ReportBuffer report) {
    for (SectionBuffer buffer : report.sections.values()) {
      if (buffer.appends.isEmpty()) {
        continue;
      }
      canonicalizer.requireNoCanary(buffer.appends, report.secrets);
      if (buffer.durableTail.length > 0 && !buffer.tailAppends.isEmpty()) {
        canonicalizer.requireNoCanary(
            buffer.durableTail,
            buffer.tailAppends.getFirst().internalBytes(),
            report.secrets);
      }
      canonicalizer.requireNoCanary(buffer.tailAppends, report.secrets);
    }
  }

  private void requireEvidenceOpen(ReportBuffer report) {
    retryDiscard(report);
    if (report.genericTerminal) {
      throw unsafeTrace();
    }
  }

  private void probeFreshRecovery(ReportBuffer report) {
    try {
      TradingLabReportRecoveryState recovery =
          store.discardEvidence(report.reportId, report.fence);
      if (recovery.quarantined()) {
        markQuarantined(report);
      }
      if (recovery.terminal()) {
        report.terminalRecovery = true;
      }
    } catch (RuntimeException exception) {
      markUnsafe(report);
      throw exception;
    }
  }

  private void sealUnsafe(ReportBuffer report) {
    markUnsafe(report);
    retryDiscard(report);
  }

  private static void markUnsafe(ReportBuffer report) {
    report.tainted = true;
    report.genericTerminal = true;
    report.discardRequired = true;
    clearEvidenceState(report);
  }

  private static void markQuarantined(ReportBuffer report) {
    report.tainted = true;
    report.genericTerminal = true;
    report.discardRequired = false;
    clearEvidenceState(report);
  }

  private void retryDiscard(ReportBuffer report) {
    if (!report.discardRequired) {
      return;
    }
    clearEvidenceState(report);
    TradingLabReportRecoveryState recovery =
        store.quarantineEvidence(report.reportId, report.fence);
    if (recovery.terminal()) {
      active.remove(report.reportId);
      throw closed();
    }
    if (recovery.quarantined()) {
      report.discardRequired = false;
      return;
    }
    throw new IllegalStateException("Trading Lab report quarantine was not persisted");
  }

  private static void rebuildAcceptedTails(ReportBuffer report) {
    int maxTailBytes = report.secrets.canaryTailBytes();
    for (SectionBuffer buffer : report.sections.values()) {
      buffer.acceptedTail = buffer.durableTail.clone();
      for (TradingLabLogicalAppend append : buffer.tailAppends) {
        buffer.acceptedTail = appendTail(
            buffer.acceptedTail, append.internalBytes(), maxTailBytes);
      }
    }
  }

  private static byte[] appendTail(
      byte[] prefix,
      byte[] canonical,
      int maxTailBytes
  ) {
    if (maxTailBytes <= 0) {
      return new byte[0];
    }
    int canonicalBytes = Math.min(canonical.length, maxTailBytes);
    int prefixBytes = Math.min(prefix.length, maxTailBytes - canonicalBytes);
    byte[] tail = new byte[Math.addExact(prefixBytes, canonicalBytes)];
    System.arraycopy(
        prefix, prefix.length - prefixBytes, tail, 0, prefixBytes);
    System.arraycopy(
        canonical,
        canonical.length - canonicalBytes,
        tail,
        prefixBytes,
        canonicalBytes);
    return tail;
  }

  private static void clearBuffered(SectionBuffer buffer) {
    buffer.appends.clear();
    buffer.tailAppends.clear();
    buffer.events.clear();
    buffer.bufferedBytes = 0L;
    buffer.firstChunkSequence = null;
  }

  @Override
  public void complete(UUID reportId) {
    close(
        reportId,
        null,
        true,
        (ignored, genericTerminal) -> TradingLabReportOutcome.completed());
  }

  @Override
  public void complete(TradingLabReportWriteFence fence) {
    Objects.requireNonNull(fence, "fence");
    close(
        fence.reportId(),
        fence,
        true,
        (ignored, genericTerminal) -> TradingLabReportOutcome.completed());
  }

  @Override
  public void fail(UUID reportId, String failureCode, String failureMessage) {
    close(
        reportId,
        null,
        false,
        (secrets, genericTerminal) -> {
          if (genericTerminal) {
            return genericFailure();
          }
          requireFailureCode(failureCode);
          return TradingLabReportOutcome.failed(
              sanitizeFailureCode(failureCode, secrets),
              canonicalizer.sanitizeSummary(failureMessage, secrets));
        });
  }

  @Override
  public void fail(
      TradingLabReportWriteFence fence,
      String failureCode,
      String failureMessage
  ) {
    Objects.requireNonNull(fence, "fence");
    close(
        fence.reportId(),
        fence,
        false,
        (secrets, genericTerminal) -> {
          if (genericTerminal) {
            return genericFailure();
          }
          requireFailureCode(failureCode);
          return TradingLabReportOutcome.failed(
              sanitizeFailureCode(failureCode, secrets),
              canonicalizer.sanitizeSummary(failureMessage, secrets));
        });
  }

  @Override
  public void cancel(UUID reportId, String reason) {
    close(
        reportId,
        null,
        false,
        (secrets, genericTerminal) -> TradingLabReportOutcome.cancelled(
            genericTerminal
                ? GENERIC_UNSAFE_TRACE_SUMMARY
                : canonicalizer.sanitizeSummary(reason, secrets)));
  }

  @Override
  public void cancel(TradingLabReportWriteFence fence, String reason) {
    Objects.requireNonNull(fence, "fence");
    close(
        fence.reportId(),
        fence,
        false,
        (secrets, genericTerminal) -> TradingLabReportOutcome.cancelled(
            genericTerminal
                ? GENERIC_UNSAFE_TRACE_SUMMARY
                : canonicalizer.sanitizeSummary(reason, secrets)));
  }

  private void requireFailureCode(String code) {
    if (code == null || !FAILURE_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Trading Lab report failure code is invalid");
    }
  }

  private String sanitizeFailureCode(
      String code,
      TradingLabReportSecretRegistry secrets
  ) {
    String sanitized = canonicalizer.sanitizeSummary(code, secrets);
    return code.equals(sanitized) ? code : GENERIC_UNSAFE_TRACE_CODE;
  }

  private static TradingLabReportOutcome genericFailure() {
    return TradingLabReportOutcome.failed(
        GENERIC_UNSAFE_TRACE_CODE, GENERIC_UNSAFE_TRACE_SUMMARY);
  }

  private void close(
      UUID reportId,
      TradingLabReportWriteFence fence,
      boolean successfulCompletion,
      OutcomeFactory outcomeFactory
  ) {
    Objects.requireNonNull(reportId, "reportId");
    synchronized (monitor) {
      ReportBuffer report = active.get(reportId);
      if (report != null) {
        if (!report.matchesFence(fence)) {
          throw fenceLost();
        }
        retryDiscard(report);
        if (!report.genericTerminal) {
          retryUncertain(report);
          retryUncertainMetadata(report);
        }
      } else {
        if (active.size() >= maxActiveWriters) {
          throw error(
              "TRADING_LAB_REPORT_WRITER_LIMIT",
              "Trading Lab report writer admission limit is reached");
        }
        report = new ReportBuffer(reportId, fence, newSecretRegistry());
        active.put(reportId, report);
        try {
          probeFreshRecovery(report);
        } catch (BusinessException exception) {
          evictIfStale(reportId, exception);
          throw exception;
        }
      }
      if (report != null) {
        quarantineIfUnsafe(report);
        if (successfulCompletion && report.tainted) {
          throw incomplete();
        }
      }
      TradingLabReportSecretRegistry secrets = report.secrets;
      TradingLabReportOutcome outcome;
      long generationBefore = secrets.generation();
      try {
        outcome = outcomeFactory.create(
            secrets, report.genericTerminal);
      } catch (TradingLabTraceSecretRegistry.MergeOverflowException
          | TradingLabReportSecretRegistry.RegistrationOverflowException exception) {
        sealUnsafe(report);
        if (successfulCompletion) {
          throw incomplete();
        }
        outcome = outcomeFactory.create(secrets, true);
      } catch (RuntimeException exception) {
        if (report != null) {
          report.tainted = true;
        }
        throw exception;
      }

      if (secrets.generation() != generationBefore) {
        boolean quarantined = quarantineIfUnsafe(report);
        if (!quarantined) {
          rebuildAcceptedTails(report);
        } else if (successfulCompletion) {
          throw incomplete();
        } else {
          outcome = outcomeFactory.create(secrets, true);
        }
      }
      if (successfulCompletion && report.tainted) {
        throw incomplete();
      }

      if (!report.empty()) {
        try {
          persistFresh(report, fence, null);
        } catch (BusinessException exception) {
          if (!GENERIC_UNSAFE_TRACE_CODE.equals(exception.getCode())
              || !report.genericTerminal) {
            throw exception;
          }
          if (successfulCompletion) {
            throw incomplete();
          }
          outcome = outcomeFactory.create(secrets, true);
        }
      }
      try {
        for (int attempt = 0; attempt < MAX_FINALIZE_ATTEMPTS; attempt++) {
          TradingLabReportMeasurement measurement;
          try {
            measurement = streamer.measureForClose(reportId, secrets);
          } catch (TradingLabCanonicalCanaryScanner.CanaryDetectedException exception) {
            if (report.genericTerminal) {
              throw unsafeTrace();
            }
            sealUnsafe(report);
            if (successfulCompletion) {
              throw incomplete();
            }
            outcome = outcomeFactory.create(secrets, true);
            continue;
          }
          TradingLabReportFinalizeResult result;
          try {
            result = store.finalizeReport(
                reportId, fence, measurement, outcome, retention);
          } catch (BusinessException exception) {
            if (!GENERIC_UNSAFE_TRACE_CODE.equals(exception.getCode())) {
              throw exception;
            }
            markQuarantined(report);
            if (successfulCompletion) {
              throw incomplete();
            }
            outcome = outcomeFactory.create(secrets, true);
            continue;
          }
          if (result == TradingLabReportFinalizeResult.RETRY) {
            continue;
          }
          active.remove(reportId);
          return;
        }
      } catch (BusinessException exception) {
        evictIfStale(reportId, exception);
        throw exception;
      }
      throw error(
          "TRADING_LAB_REPORT_STATUS_CONFLICT",
          "Trading Lab report could not be closed from a stable version");
    }
  }

  private boolean quarantineIfUnsafe(ReportBuffer report) {
    if (report.genericTerminal) {
      return true;
    }
    try {
      scanAllUnpersisted(report);
      return false;
    } catch (IllegalArgumentException exception) {
      sealUnsafe(report);
      return true;
    }
  }

  private TradingLabReportSecretRegistry newSecretRegistry() {
    TradingLabReportSecretRegistry secrets = new TradingLabReportSecretRegistry();
    secrets.registerAll(fixedValidationSecrets);
    return secrets;
  }

  private static ReportBuffer replaceFence(
      ReportBuffer existing,
      TradingLabReportWriteFence fence
  ) {
    ReportBuffer replacement = new ReportBuffer(
        existing.reportId, fence, existing.secrets);
    boolean commitWasUncertain = existing.uncertainBatch != null;
    replacement.uncertainMetadata = existing.uncertainMetadata;
    replacement.metadataInitialized = existing.metadataInitialized;
    replacement.tainted = existing.tainted || commitWasUncertain;
    replacement.genericTerminal = existing.genericTerminal || commitWasUncertain;
    replacement.discardRequired = existing.discardRequired || commitWasUncertain;
    for (TradingLabReportSection section : TradingLabReportSection.values()) {
      SectionBuffer source = existing.sections.get(section);
      SectionBuffer target = replacement.sections.get(section);
      byte[] safetyTail = commitWasUncertain ? new byte[0] : source.durableTail;
      target.durableTail = safetyTail.clone();
      target.acceptedTail = safetyTail.clone();
      target.durableHighestSourceSequence = commitWasUncertain
          ? null
          : source.durableHighestSourceSequence;
      if (source.appends.isEmpty()) {
        target.singleton = source.singleton;
      }
    }
    return replacement;
  }

  private static Long highestSourceSequence(
      Long current,
      List<TradingLabLogicalAppend> appends
  ) {
    Long highest = current;
    for (TradingLabLogicalAppend append : appends) {
      Long sourceSequence = append.sourceSequence();
      if (sourceSequence != null && sourceSequence >= 0L) {
        highest = highest == null ? sourceSequence : Math.max(highest, sourceSequence);
      }
    }
    return highest;
  }

  private void evictIfStale(UUID reportId, BusinessException exception) {
    if (switch (exception.getCode()) {
      case "TRADING_LAB_REPORT_CLOSED",
           "TRADING_LAB_REPORT_STATUS_CONFLICT",
           "TRADING_LAB_REPORT_NOT_FOUND" -> true;
      default -> false;
    }) {
      active.remove(reportId);
    }
  }

  private static TradingLabReportException fenceLost() {
    return error(
        "TRADING_LAB_REPORT_FENCE_LOST",
        "Trading Lab report write fence is no longer valid");
  }

  private static TradingLabReportException unsafeTrace() {
    return error(
        GENERIC_UNSAFE_TRACE_CODE,
        "Trading Lab report is quarantined after unsafe evidence");
  }

  private static TradingLabReportException closed() {
    return error(
        "TRADING_LAB_REPORT_CLOSED",
        "Trading Lab report is already closed");
  }

  private static IllegalArgumentException unsafeValue() {
    return new IllegalArgumentException("Unsafe Trading Lab report value");
  }

  private static TradingLabReportException incomplete() {
    return error(
        "TRADING_LAB_REPORT_INCOMPLETE",
        "Trading Lab report contains rejected evidence and cannot be completed");
  }

  private static TradingLabReportException error(String code, String message) {
    return new TradingLabReportException(code, message);
  }

  @FunctionalInterface
  private interface OutcomeFactory {
    TradingLabReportOutcome create(
        TradingLabReportSecretRegistry secrets,
        boolean genericTerminal
    );
  }

  private static final class ReportBuffer {
    private final UUID reportId;
    private final TradingLabReportWriteFence fence;
    private final TradingLabReportSecretRegistry secrets;
    private final EnumMap<TradingLabReportSection, SectionBuffer> sections =
        new EnumMap<>(TradingLabReportSection.class);
    private TradingLabReportWriteBatch uncertainBatch;
    private TradingLabCanonicalValue uncertainMetadata;
    private boolean metadataInitialized;
    private boolean tainted;
    private boolean genericTerminal;
    private boolean discardRequired;
    private boolean terminalRecovery;

    private ReportBuffer(UUID reportId, TradingLabReportWriteFence fence,
        TradingLabReportSecretRegistry secrets) {
      this.reportId = reportId;
      this.fence = fence;
      this.secrets = Objects.requireNonNull(secrets, "secrets");
      Arrays.stream(TradingLabReportSection.values())
          .forEach(section -> sections.put(section, new SectionBuffer()));
    }

    private boolean matchesFence(TradingLabReportWriteFence candidate) {
      return Objects.equals(fence, candidate);
    }

    private boolean empty() {
      return sections.values().stream().allMatch(section -> section.appends.isEmpty());
    }
  }

  private static final class SectionBuffer {
    private final List<TradingLabLogicalAppend> appends = new ArrayList<>();
    private final List<TradingLabLogicalAppend> tailAppends = new ArrayList<>();
    private final Map<Long, TradingLabLogicalAppend> events = new HashMap<>();
    private AppendIdentity singleton;
    private Long firstChunkSequence;
    private long bufferedBytes;
    private byte[] acceptedTail = new byte[0];
    private byte[] durableTail = new byte[0];
    private Long durableHighestSourceSequence;
  }

  private static final class UnsafeJournalEvidenceException extends RuntimeException {

    private UnsafeJournalEvidenceException() {
      super(null, null, false, false);
    }
  }

  private record AppendIdentity(long canonicalBytes, String canonicalChecksum) {
    private static AppendIdentity from(TradingLabLogicalAppend append) {
      return new AppendIdentity(
          append.canonicalByteCount(), append.canonicalChecksum());
    }
  }
}
