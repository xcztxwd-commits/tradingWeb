package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.state.TradingLabRunControlResult;
import com.fxplatform.tradinglab.state.TradingLabRunControlService;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TradingLabAdminRunControlFacadeTest {

  private static final UUID ACTOR_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000801");
  private static final UUID RUN_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000802");
  private static final UUID REQUEST_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000803");
  private static final String CLIENT_IP = "198.51.100.81";

  private final TradingLabRunControlService controls =
      mock(TradingLabRunControlService.class);
  private final TradingLabAuditService audit = mock(TradingLabAuditService.class);
  private final TradingLabAdminRunControlCommand command =
      new TradingLabAdminRunControlCommand(controls, audit);
  private final TradingLabRunControlFailureAuditor failureAuditor =
      new TradingLabRunControlFailureAuditor(audit);
  private final TradingLabAdminRunControlFacade facade =
      new TradingLabAdminRunControlFacade(command, failureAuditor);

  @Test
  void commandMutatesTheDurableFlagAndWritesSuccessAuditInOneTransaction() throws Exception {
    TradingLabRunControlResult durable = new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.RUNNING,
        12L,
        true,
        false);
    when(controls.requestPause(RUN_ID)).thenReturn(durable);

    TradingLabRunControlResult result = command.execute(
        TradingLabRunControlAction.PAUSE,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID);

    assertThat(result).isEqualTo(durable);
    verify(controls).requestPause(RUN_ID);
    ArgumentCaptor<Map<String, Object>> details = mapCaptor();
    verify(audit).record(
        eq(ACTOR_ID),
        eq(CLIENT_IP),
        eq(REQUEST_ID),
        eq(null),
        eq(RUN_ID),
        eq("TRADING_LAB_RUN_CONTROL"),
        eq("SUCCESS"),
        details.capture());
    assertThat(details.getValue())
        .containsEntry("action", "pause")
        .containsEntry("state", "RUNNING")
        .containsEntry("version", 12L)
        .containsEntry("pauseRequested", true)
        .containsEntry("cancelRequested", false);

    Method execute = TradingLabAdminRunControlCommand.class.getDeclaredMethod(
        "execute",
        TradingLabRunControlAction.class,
        UUID.class,
        UUID.class,
        String.class,
        UUID.class);
    assertThat(AnnotatedElementUtils.hasAnnotation(execute, Transactional.class)).isTrue();
  }

  @Test
  void facadeCommitsFailureAuditOnlyAfterTheControlTransactionHasFailed() {
    BusinessException failure = new BusinessException(
        "TRADING_LAB_CONTROL_INVALID_STATE",
        "invalid state");
    TradingLabAdminRunControlCommand proxiedCommand =
        mock(TradingLabAdminRunControlCommand.class);
    TradingLabRunControlFailureAuditor proxiedFailureAuditor =
        mock(TradingLabRunControlFailureAuditor.class);
    TradingLabAdminRunControlFacade proxiedFacade =
        new TradingLabAdminRunControlFacade(proxiedCommand, proxiedFailureAuditor);
    when(proxiedCommand.execute(
        TradingLabRunControlAction.RESUME,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID)).thenThrow(failure);

    assertThatThrownBy(() -> proxiedFacade.execute(
        TradingLabRunControlAction.RESUME,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID)).isSameAs(failure);

    verify(proxiedFailureAuditor).record(
        TradingLabRunControlAction.RESUME,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID,
        failure);
  }

  @Test
  void successDoesNotWriteASecondFailureAudit() {
    TradingLabAdminRunControlCommand proxiedCommand =
        mock(TradingLabAdminRunControlCommand.class);
    TradingLabRunControlFailureAuditor proxiedFailureAuditor =
        mock(TradingLabRunControlFailureAuditor.class);
    TradingLabAdminRunControlFacade proxiedFacade =
        new TradingLabAdminRunControlFacade(proxiedCommand, proxiedFailureAuditor);
    TradingLabRunControlResult durable = new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.PAUSED,
        13L,
        false,
        false);
    when(proxiedCommand.execute(
        TradingLabRunControlAction.RESUME,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID)).thenReturn(durable);

    assertThat(proxiedFacade.execute(
        TradingLabRunControlAction.RESUME,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID)).isEqualTo(durable);
    verifyNoInteractions(proxiedFailureAuditor);
  }

  @Test
  void failureAuditorUsesRequiresNewAndPersistsOnlyBoundedFailureDetails() throws Exception {
    BusinessException failure = new BusinessException(
        "TRADING_LAB_CONTROL_LOST",
        "do not persist this caller-visible message");

    failureAuditor.record(
        TradingLabRunControlAction.CANCEL,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID,
        failure);

    ArgumentCaptor<Map<String, Object>> details = mapCaptor();
    verify(audit).record(
        eq(ACTOR_ID),
        eq(CLIENT_IP),
        eq(REQUEST_ID),
        eq(null),
        eq(RUN_ID),
        eq("TRADING_LAB_RUN_CONTROL"),
        eq("FAILED"),
        details.capture());
    assertThat(details.getValue())
        .containsExactly(
            Map.entry("action", "cancel"),
            Map.entry("failureCode", "TRADING_LAB_CONTROL_LOST"))
        .doesNotContainValue(failure.getMessage());

    Method record = TradingLabRunControlFailureAuditor.class.getDeclaredMethod(
        "record",
        TradingLabRunControlAction.class,
        UUID.class,
        UUID.class,
        String.class,
        UUID.class,
        RuntimeException.class);
    Transactional transactional =
        AnnotatedElementUtils.findMergedAnnotation(record, Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }

  @Test
  void missingRunAuditAvoidsTheRunForeignKeyAndKeepsTheRequestedIdentifierInDetails() {
    BusinessException failure = new BusinessException(
        "TRADING_LAB_RUN_NOT_FOUND",
        "not found");

    failureAuditor.record(
        TradingLabRunControlAction.PAUSE,
        RUN_ID,
        ACTOR_ID,
        CLIENT_IP,
        REQUEST_ID,
        failure);

    ArgumentCaptor<Map<String, Object>> details = mapCaptor();
    verify(audit).record(
        eq(ACTOR_ID),
        eq(CLIENT_IP),
        eq(REQUEST_ID),
        eq(null),
        eq(null),
        eq("TRADING_LAB_RUN_CONTROL"),
        eq("FAILED"),
        details.capture());
    assertThat(details.getValue())
        .containsEntry("action", "pause")
        .containsEntry("failureCode", "TRADING_LAB_RUN_NOT_FOUND")
        .containsEntry("requestedRunId", RUN_ID.toString());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
  }
}
