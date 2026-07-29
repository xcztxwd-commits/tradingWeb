package com.fxplatform.tradinglab.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TradingLabReportAdminServiceTest {

  private static final long THRESHOLD = 52_428_800L;
  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final UUID REPORT_ID = UUID.randomUUID();
  private static final UUID RUN_ID = UUID.randomUUID();
  private static final UUID SCENARIO_ID = UUID.randomUUID();
  private static final UUID REQUEST_ID = UUID.randomUUID();
  private static final TradingLabAdminRequestContext REQUEST =
      new TradingLabAdminRequestContext(ACTOR_ID, "127.0.0.1", REQUEST_ID);

  private TradingLabReportRepository reports;
  private TradingLabReportChunkRepository chunks;
  private TradingLabReportAppendRepository appends;
  private TradingLabReportWriteFenceRepository fences;
  private TradingLabReportStreamer streamer;
  private TradingLabPrettyReportStreamer prettyStreamer;
  private TradingLabAuditService audit;
  private TradingLabPrintConfirmationService confirmations;
  private TradingLabReportAdminService service;

  @BeforeEach
  void setUp() {
    reports = mock(TradingLabReportRepository.class);
    chunks = mock(TradingLabReportChunkRepository.class);
    appends = mock(TradingLabReportAppendRepository.class);
    fences = mock(TradingLabReportWriteFenceRepository.class);
    streamer = mock(TradingLabReportStreamer.class);
    prettyStreamer = mock(TradingLabPrettyReportStreamer.class);
    audit = mock(TradingLabAuditService.class);
    confirmations = mock(TradingLabPrintConfirmationService.class);
    service = new TradingLabReportAdminService(
        reports,
        chunks,
        appends,
        fences,
        streamer,
        prettyStreamer,
        audit,
        confirmations);
  }

  @Test
  void deleteUsesRunThenReportLockOrderDeletesOwnedRowsAndAuditsAtomically() {
    TradingLabReportRunFenceRow run = run("COMPLETED");
    TradingLabReportEntity report = report(false, 7L);
    when(fences.lockRunForReport(REPORT_ID)).thenReturn(Optional.of(run));
    when(reports.lockById(REPORT_ID)).thenReturn(Optional.of(report));
    when(appends.deleteByReportId(REPORT_ID)).thenReturn(4);
    when(chunks.deleteByReportId(REPORT_ID)).thenReturn(3);
    when(reports.deleteTerminalByIdAndVersion(REPORT_ID, 7L)).thenReturn(1);

    service.delete(REQUEST, REPORT_ID);

    InOrder ordered = inOrder(fences, reports, appends, chunks, audit);
    ordered.verify(fences).lockRunForReport(REPORT_ID);
    ordered.verify(reports).lockById(REPORT_ID);
    ordered.verify(appends).deleteByReportId(REPORT_ID);
    ordered.verify(chunks).deleteByReportId(REPORT_ID);
    ordered.verify(reports).deleteTerminalByIdAndVersion(REPORT_ID, 7L);
    ordered.verify(audit).record(
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        SCENARIO_ID,
        RUN_ID,
        "TRADING_LAB_REPORT_DELETE",
        "SUCCESS",
        Map.of(
            "reportId", REPORT_ID.toString(),
            "reportVersion", 7L,
            "deletedAppends", 4,
            "deletedChunks", 3));
  }

  @Test
  void deleteRejectsNonTerminalRunBeforeReportLockOrMutation() {
    when(fences.lockRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("CLEANING")));

    assertThatThrownBy(() -> service.delete(REQUEST, REPORT_ID))
        .isInstanceOf(TradingLabReportAdminException.class)
        .extracting("code")
        .isEqualTo("TRADING_LAB_REPORT_STATE_CONFLICT");

    verify(reports, never()).lockById(REPORT_ID);
    verifyNoInteractions(chunks, appends, audit);
  }

  @Test
  void permanentTrueReplayIsIdempotentAndDoesNotAdvanceVersion() {
    when(fences.lockRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("FAILED")));
    when(reports.lockById(REPORT_ID))
        .thenReturn(Optional.of(report(true, 11L)));

    TradingLabPermanentResponse response =
        service.setPermanent(REQUEST, REPORT_ID, true);

    assertThat(response)
        .isEqualTo(new TradingLabPermanentResponse(REPORT_ID, true, 11L));
    verify(reports, never()).setPermanent(REPORT_ID, 11L, true);
  }

  @Test
  void permanentFalseUpdatesDurableValueAndVersion() {
    when(fences.lockRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("CANCELLED")));
    when(reports.lockById(REPORT_ID))
        .thenReturn(Optional.of(report(true, 11L)));
    when(reports.setPermanent(REPORT_ID, 11L, false)).thenReturn(1);

    TradingLabPermanentResponse response =
        service.setPermanent(REQUEST, REPORT_ID, false);

    assertThat(response)
        .isEqualTo(new TradingLabPermanentResponse(REPORT_ID, false, 12L));
    verify(reports).setPermanent(REPORT_ID, 11L, false);
  }

  @Test
  void printInfoUsesExactCompactBytesCeilingAndStrictThresholdBoundary() {
    assertPrintInfo(0L, 1L, false);
    assertPrintInfo(1L, 1L, false);
    assertPrintInfo(4096L, 1L, false);
    assertPrintInfo(4097L, 2L, false);
    assertPrintInfo(THRESHOLD, 12_800L, false);
    assertPrintInfo(THRESHOLD + 1L, 12_801L, true);
  }

  @Test
  void largePrintConsumesActorAndReportBoundTokenBeforeReturningTicket() {
    TradingLabReportReadTicket ticket =
        ticket(THRESHOLD + 1L, 19L);
    when(fences.findRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("COMPLETED")));
    when(streamer.prepare(REPORT_ID)).thenReturn(ticket);

    TradingLabReportReadTicket prepared =
        service.preparePrint(ACTOR_ID, REPORT_ID, "secret-confirmation");

    assertThat(prepared).isSameAs(ticket);
    verify(confirmations).consume(
        "secret-confirmation", ACTOR_ID, REPORT_ID);
  }

  @Test
  void smallPrintDoesNotNeedOrConsumeAConfirmation() {
    TradingLabReportReadTicket ticket = ticket(THRESHOLD, 19L);
    when(fences.findRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("COMPLETED")));
    when(streamer.prepare(REPORT_ID)).thenReturn(ticket);

    assertThat(service.preparePrint(ACTOR_ID, REPORT_ID, null)).isSameAs(ticket);

    verifyNoInteractions(confirmations);
  }

  @Test
  void confirmationCanOnlyBeIssuedForACurrentlyPreparedLargeReport() {
    when(fences.findRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("COMPLETED")));
    when(streamer.prepare(REPORT_ID)).thenReturn(ticket(THRESHOLD, 20L));

    assertThatThrownBy(() -> service.issuePrintConfirmation(ACTOR_ID, REPORT_ID))
        .isInstanceOf(TradingLabReportAdminException.class)
        .extracting("code")
        .isEqualTo("TRADING_LAB_PRINT_CONFIRMATION_NOT_REQUIRED");

    verifyNoInteractions(confirmations);
  }

  private void assertPrintInfo(
      long bytes,
      long expectedPages,
      boolean expectedConfirmation
  ) {
    when(fences.findRunForReport(REPORT_ID))
        .thenReturn(Optional.of(run("COMPLETED")));
    when(streamer.prepare(REPORT_ID)).thenReturn(ticket(bytes, 7L));

    assertThat(service.printInfo(REPORT_ID))
        .isEqualTo(new TradingLabPrintInfoResponse(
            bytes,
            expectedPages,
            THRESHOLD,
            expectedConfirmation));
  }

  private static TradingLabReportReadTicket ticket(long bytes, long version) {
    return new TradingLabReportReadTicket(REPORT_ID, version, bytes, 80L, 2);
  }

  private static TradingLabReportRunFenceRow run(String state) {
    return new TradingLabReportRunFenceRow(
        RUN_ID,
        REPORT_ID,
        state,
        null,
        null,
        null,
        false);
  }

  private static TradingLabReportEntity report(boolean permanent, long version) {
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(REPORT_ID);
    report.setScenarioId(SCENARIO_ID);
    report.setStatus("COMPLETED");
    report.setModelVersion("model-v1");
    report.setConfigSnapshotHash("a".repeat(64));
    report.setCodeVersion("working-tree:test");
    report.setUncompressedBytes(123L);
    report.setCompressedBytes(80L);
    report.setChunkCount(2);
    report.setRetainedUntil(Instant.parse("2026-08-24T00:00:00Z"));
    report.setPermanent(permanent);
    report.setCreatedAt(Instant.parse("2026-07-24T00:00:00Z"));
    report.setCompletedAt(Instant.parse("2026-07-24T01:00:00Z"));
    report.setVersion(version);
    return report;
  }
}
