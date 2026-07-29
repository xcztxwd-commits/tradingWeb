package com.fxplatform.tradinglab.admin.report;

import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.report.TradingLabPrettyReportStreamer;
import com.fxplatform.tradinglab.report.TradingLabReportReadTicket;
import com.fxplatform.tradinglab.report.TradingLabReportRunFenceRow;
import com.fxplatform.tradinglab.report.TradingLabReportStreamer;
import com.fxplatform.tradinglab.repository.TradingLabReportAppendRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportWriteFenceRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingLabReportAdminService {

  public static final long PRINT_CONFIRMATION_THRESHOLD_BYTES = 52_428_800L;
  private static final long ESTIMATED_PAGE_BYTES = 4096L;
  private static final Set<String> TERMINAL_STATES =
      Set.of("COMPLETED", "FAILED", "CANCELLED");

  private final TradingLabReportRepository reports;
  private final TradingLabReportChunkRepository chunks;
  private final TradingLabReportAppendRepository appends;
  private final TradingLabReportWriteFenceRepository fences;
  private final TradingLabReportStreamer streamer;
  private final TradingLabPrettyReportStreamer prettyStreamer;
  private final TradingLabAuditService audit;
  private final TradingLabPrintConfirmationService confirmations;

  public TradingLabReportAdminService(
      TradingLabReportRepository reports,
      TradingLabReportChunkRepository chunks,
      TradingLabReportAppendRepository appends,
      TradingLabReportWriteFenceRepository fences,
      TradingLabReportStreamer streamer,
      TradingLabPrettyReportStreamer prettyStreamer,
      TradingLabAuditService audit,
      TradingLabPrintConfirmationService confirmations
  ) {
    this.reports = Objects.requireNonNull(reports, "reports");
    this.chunks = Objects.requireNonNull(chunks, "chunks");
    this.appends = Objects.requireNonNull(appends, "appends");
    this.fences = Objects.requireNonNull(fences, "fences");
    this.streamer = Objects.requireNonNull(streamer, "streamer");
    this.prettyStreamer = Objects.requireNonNull(prettyStreamer, "prettyStreamer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
  }

  public TradingLabReportResponse detail(UUID reportId) {
    TradingLabReportRunFenceRow run = requireReadableRun(reportId);
    TradingLabReportReadTicket ticket = streamer.prepare(reportId);
    TradingLabReportEntity report = reports.findById(reportId)
        .orElseThrow(TradingLabReportAdminException::notFound);
    requireTicketMatches(report, ticket);
    return response(report, run.runId());
  }

  public TradingLabReportReadTicket prepareDownload(UUID reportId) {
    requireReadableRun(reportId);
    return streamer.prepare(reportId);
  }

  public TradingLabPrintInfoResponse printInfo(UUID reportId) {
    TradingLabReportReadTicket ticket = prepareReadable(reportId);
    long bytes = ticket.uncompressedBytes();
    return new TradingLabPrintInfoResponse(
        bytes,
        estimatedPages(bytes),
        PRINT_CONFIRMATION_THRESHOLD_BYTES,
        requiresConfirmation(bytes));
  }

  public TradingLabReportReadTicket preparePrint(
      UUID actorId,
      UUID reportId,
      String confirmationToken
  ) {
    Objects.requireNonNull(actorId, "actorId");
    TradingLabReportReadTicket ticket = prepareReadable(reportId);
    if (requiresConfirmation(ticket.uncompressedBytes())) {
      confirmations.consume(confirmationToken, actorId, reportId);
    }
    return ticket;
  }

  public TradingLabPrintConfirmationResponse issuePrintConfirmation(
      UUID actorId,
      UUID reportId
  ) {
    Objects.requireNonNull(actorId, "actorId");
    TradingLabReportReadTicket ticket = prepareReadable(reportId);
    if (!requiresConfirmation(ticket.uncompressedBytes())) {
      throw new TradingLabReportAdminException(
          "TRADING_LAB_PRINT_CONFIRMATION_NOT_REQUIRED",
          "Trading Lab report does not require large-print confirmation",
          409);
    }
    TradingLabIssuedPrintConfirmation issued =
        confirmations.issue(actorId, reportId);
    return new TradingLabPrintConfirmationResponse(
        reportId,
        issued.token(),
        issued.expiresAt());
  }

  public void streamCompact(
      TradingLabReportReadTicket ticket,
      OutputStream output
  ) throws IOException {
    streamer.stream(ticket, output);
  }

  public void streamPretty(
      TradingLabReportReadTicket ticket,
      OutputStream output
  ) throws IOException {
    prettyStreamer.stream(ticket, output);
  }

  @Transactional
  public void delete(
      TradingLabAdminRequestContext request,
      UUID reportId
  ) {
    Objects.requireNonNull(request, "request");
    TradingLabReportRunFenceRow run = lockTerminalRun(reportId);
    TradingLabReportEntity report = reports.lockById(reportId)
        .orElseThrow(TradingLabReportAdminException::notFound);
    requireTerminalReport(report);
    long version = requiredVersion(report);

    int deletedAppends = appends.deleteByReportId(reportId);
    int deletedChunks = chunks.deleteByReportId(reportId);
    if (reports.deleteTerminalByIdAndVersion(reportId, version) != 1) {
      throw TradingLabReportAdminException.conflict(
          "Trading Lab report changed while it was being deleted");
    }
    audit.record(
        request.actorId(),
        request.clientIp(),
        request.requestId(),
        report.getScenarioId(),
        run.runId(),
        "TRADING_LAB_REPORT_DELETE",
        "SUCCESS",
        Map.of(
            "reportId", reportId.toString(),
            "reportVersion", version,
            "deletedAppends", deletedAppends,
            "deletedChunks", deletedChunks));
  }

  @Transactional
  public TradingLabPermanentResponse setPermanent(
      TradingLabAdminRequestContext request,
      UUID reportId,
      boolean permanent
  ) {
    Objects.requireNonNull(request, "request");
    TradingLabReportRunFenceRow run = lockTerminalRun(reportId);
    TradingLabReportEntity report = reports.lockById(reportId)
        .orElseThrow(TradingLabReportAdminException::notFound);
    requireTerminalReport(report);
    long version = requiredVersion(report);
    boolean previous = Boolean.TRUE.equals(report.getPermanent());
    long durableVersion = version;
    if (previous != permanent) {
      if (reports.setPermanent(reportId, version, permanent) != 1) {
        throw TradingLabReportAdminException.conflict(
            "Trading Lab report permanent status changed concurrently");
      }
      durableVersion = Math.addExact(version, 1L);
    }
    audit.record(
        request.actorId(),
        request.clientIp(),
        request.requestId(),
        report.getScenarioId(),
        run.runId(),
        "TRADING_LAB_REPORT_PERMANENT",
        "SUCCESS",
        Map.of(
            "reportId", reportId.toString(),
            "previousPermanent", previous,
            "permanent", permanent,
            "reportVersion", durableVersion));
    return new TradingLabPermanentResponse(
        reportId,
        permanent,
        durableVersion);
  }

  private TradingLabReportReadTicket prepareReadable(UUID reportId) {
    requireReadableRun(reportId);
    return streamer.prepare(reportId);
  }

  private TradingLabReportRunFenceRow requireReadableRun(UUID reportId) {
    if (reportId == null) {
      throw new IllegalArgumentException("Trading Lab report ID is required");
    }
    TradingLabReportRunFenceRow run = fences.findRunForReport(reportId)
        .orElseThrow(TradingLabReportAdminException::notFound);
    requireTerminalRun(run);
    return run;
  }

  private TradingLabReportRunFenceRow lockTerminalRun(UUID reportId) {
    if (reportId == null) {
      throw new IllegalArgumentException("Trading Lab report ID is required");
    }
    TradingLabReportRunFenceRow run = fences.lockRunForReport(reportId)
        .orElseThrow(TradingLabReportAdminException::notFound);
    requireTerminalRun(run);
    return run;
  }

  private void requireTerminalRun(TradingLabReportRunFenceRow run) {
    if (!TERMINAL_STATES.contains(run.state())) {
      throw TradingLabReportAdminException.conflict(
          "Trading Lab report is linked to a non-terminal run");
    }
  }

  private void requireTerminalReport(TradingLabReportEntity report) {
    if (!TERMINAL_STATES.contains(report.getStatus())
        || report.getCompletedAt() == null) {
      throw TradingLabReportAdminException.conflict(
          "Trading Lab report is not terminal and closed");
    }
    requiredVersion(report);
  }

  private void requireTicketMatches(
      TradingLabReportEntity report,
      TradingLabReportReadTicket ticket
  ) {
    requireTerminalReport(report);
    if (!report.getId().equals(ticket.reportId())
        || requiredVersion(report) != ticket.reportVersion()
        || !Long.valueOf(ticket.uncompressedBytes())
            .equals(report.getUncompressedBytes())
        || !Long.valueOf(ticket.compressedBytes())
            .equals(report.getCompressedBytes())
        || !Integer.valueOf(ticket.chunkCount()).equals(report.getChunkCount())) {
      throw TradingLabReportAdminException.conflict(
          "Trading Lab report changed while it was being read");
    }
  }

  private long requiredVersion(TradingLabReportEntity report) {
    Long version = report.getVersion();
    if (version == null || version < 0L) {
      throw TradingLabReportAdminException.conflict(
          "Trading Lab report version is corrupt");
    }
    return version;
  }

  private TradingLabReportResponse response(
      TradingLabReportEntity report,
      UUID runId
  ) {
    return new TradingLabReportResponse(
        report.getId(),
        runId,
        report.getScenarioId(),
        report.getStatus(),
        report.getModelVersion(),
        report.getConfigSnapshotHash(),
        report.getCodeVersion(),
        report.getUncompressedBytes(),
        report.getCompressedBytes(),
        report.getChunkCount(),
        report.getRetainedUntil(),
        Boolean.TRUE.equals(report.getPermanent()),
        report.getFailureCode(),
        report.getFailureMessage(),
        report.getCreatedAt(),
        report.getCompletedAt(),
        requiredVersion(report));
  }

  private static boolean requiresConfirmation(long bytes) {
    return bytes > PRINT_CONFIRMATION_THRESHOLD_BYTES;
  }

  private static long estimatedPages(long bytes) {
    long normalized = Math.max(bytes, 1L);
    return Math.floorDiv(normalized - 1L, ESTIMATED_PAGE_BYTES) + 1L;
  }
}
