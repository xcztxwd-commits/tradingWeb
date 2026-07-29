package com.fxplatform.tradinglab.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TradingLabRunControlServiceTest {

  private static final UUID RUN_ID = UUID.fromString("8f17c8f2-f591-42bf-a9e6-1e63423b9f1d");
  private static final String LEASE_OWNER =
      "worker-a:7c257b25-461b-44eb-bc2e-bbd9b006e872";
  private static final Instant LEASE_UNTIL = Instant.parse("2026-07-19T01:00:00Z");
  private static final Set<TradingLabRunState> CANCELLABLE_STATES = Set.of(
      TradingLabRunState.QUEUED,
      TradingLabRunState.RESETTING,
      TradingLabRunState.RUNNING,
      TradingLabRunState.PAUSED,
      TradingLabRunState.CANCELLING);

  private final TradingLabRunRepository runRepository = mock(TradingLabRunRepository.class);
  private final TradingLabRunControlService service =
      new TradingLabRunControlService(runRepository);

  @Test
  void exposesOnlyRunIdControlMethodsAndTheFrozenResultType() throws NoSuchMethodException {
    assertThat(TradingLabRunControlService.class.getConstructor(TradingLabRunRepository.class))
        .isNotNull();

    for (String methodName : Set.of("requestPause", "requestResume", "requestCancel")) {
      Method method = TradingLabRunControlService.class.getDeclaredMethod(methodName, UUID.class);
      assertThat(method.getReturnType()).isEqualTo(TradingLabRunControlResult.class);
      assertThat(method.getParameterTypes()).containsExactly(UUID.class);
    }
  }

  @Test
  void runningPauseUsesStateAndVersionCasAndKeepsTheWorkerLeaseUntouched() {
    TradingLabRunEntity run = run(TradingLabRunState.RUNNING, 12L, false, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(runRepository.compareAndSetPauseRequested(RUN_ID, "RUNNING", 12L, true))
        .thenReturn(1);

    TradingLabRunControlResult result = service.requestPause(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.RUNNING,
        13L,
        true,
        false));
    verify(runRepository).compareAndSetPauseRequested(RUN_ID, "RUNNING", 12L, true);
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void alreadyRequestedRunningPauseReplaysTheCurrentVersionWithoutAnyWrite() {
    TradingLabRunEntity run = run(TradingLabRunState.RUNNING, 13L, true, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

    TradingLabRunControlResult result = service.requestPause(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.RUNNING,
        13L,
        true,
        false));
    verifyNoControlFlagWrites();
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void pauseReplayAfterWorkerReachedPausedUsesTheNewAuthoritativeVersionWithoutWriting() {
    TradingLabRunEntity run = run(TradingLabRunState.PAUSED, 14L, true, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

    TradingLabRunControlResult result = service.requestPause(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.PAUSED,
        14L,
        true,
        false));
    verifyNoControlFlagWrites();
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void pausedResumeClearsTheFlagWithStateAndVersionCasAndKeepsTheLeaseUntouched() {
    TradingLabRunEntity run = run(TradingLabRunState.PAUSED, 20L, true, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(runRepository.compareAndSetPauseRequested(RUN_ID, "PAUSED", 20L, false))
        .thenReturn(1);

    TradingLabRunControlResult result = service.requestResume(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.PAUSED,
        21L,
        false,
        false));
    verify(runRepository).compareAndSetPauseRequested(RUN_ID, "PAUSED", 20L, false);
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void alreadyClearedPausedResumeReplaysTheCurrentVersionWithoutAnyWrite() {
    TradingLabRunEntity run = run(TradingLabRunState.PAUSED, 21L, false, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

    TradingLabRunControlResult result = service.requestResume(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        TradingLabRunState.PAUSED,
        21L,
        false,
        false));
    verifyNoControlFlagWrites();
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @ParameterizedTest
  @EnumSource(
      value = TradingLabRunState.class,
      names = {"QUEUED", "RESETTING", "RUNNING", "PAUSED", "CANCELLING"})
  void cancelSetsTheFlagWithStateAndVersionCasInEveryCancellableState(
      TradingLabRunState state) {
    TradingLabRunEntity run = run(state, 30L, state == TradingLabRunState.PAUSED, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(runRepository.compareAndSetCancelRequested(RUN_ID, state.name(), 30L))
        .thenReturn(1);

    TradingLabRunControlResult result = service.requestCancel(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        state,
        31L,
        state == TradingLabRunState.PAUSED,
        true));
    verify(runRepository).compareAndSetCancelRequested(RUN_ID, state.name(), 30L);
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @ParameterizedTest
  @EnumSource(
      value = TradingLabRunState.class,
      names = {"QUEUED", "RESETTING", "RUNNING", "PAUSED", "CANCELLING"})
  void alreadyRequestedCancelIncludingCancellingReplaysWithoutVersionDriftOrWrites(
      TradingLabRunState state) {
    TradingLabRunEntity run = run(state, 31L, state == TradingLabRunState.PAUSED, true);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

    TradingLabRunControlResult result = service.requestCancel(RUN_ID);

    assertThat(result).isEqualTo(new TradingLabRunControlResult(
        RUN_ID,
        state,
        31L,
        state == TradingLabRunState.PAUSED,
        true));
    verifyNoControlFlagWrites();
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @ParameterizedTest(name = "invalid {0} from {1}")
  @MethodSource("invalidControlStates")
  void invalidStatesFailClosedEvenWhenTheRequestedFlagAlreadyLooksSatisfied(
      ControlOperation operation,
      TradingLabRunState state) {
    boolean pauseRequested = switch (operation) {
      case PAUSE -> true;
      case RESUME -> false;
      case CANCEL -> false;
    };
    boolean cancelRequested = operation == ControlOperation.CANCEL;
    TradingLabRunEntity run = run(state, 40L, pauseRequested, cancelRequested);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

    assertBusinessCode(
        () -> invoke(operation),
        "TRADING_LAB_CONTROL_INVALID_STATE");

    verifyNoControlFlagWrites();
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void pausedRunWithClearedPauseFlagCannotStartANewPauseRequest() {
    TradingLabRunEntity run = run(TradingLabRunState.PAUSED, 41L, false, false);
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

    assertBusinessCode(
        () -> service.requestPause(RUN_ID),
        "TRADING_LAB_CONTROL_INVALID_STATE");

    verifyNoControlFlagWrites();
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @ParameterizedTest
  @EnumSource(ControlOperation.class)
  void zeroRowControlCasTreatsAConcurrentStateOrVersionChangeAsLostWithoutFallbackWrites(
      ControlOperation operation) {
    TradingLabRunEntity run = switch (operation) {
      case PAUSE -> run(TradingLabRunState.RUNNING, 50L, false, false);
      case RESUME -> run(TradingLabRunState.PAUSED, 50L, true, false);
      case CANCEL -> run(TradingLabRunState.RUNNING, 50L, false, false);
    };
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    stubControlCas(operation, 0);

    assertBusinessCode(
        () -> invoke(operation),
        "TRADING_LAB_CONTROL_LOST");

    verifyExpectedControlCas(operation, 50L);
    assertLeaseUnchanged(run);
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void missingRunFailsClosedForEveryControlWithoutAnyMutation() {
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());

    assertBusinessCode(
        () -> service.requestPause(RUN_ID),
        "TRADING_LAB_RUN_NOT_FOUND");
    assertBusinessCode(
        () -> service.requestResume(RUN_ID),
        "TRADING_LAB_RUN_NOT_FOUND");
    assertBusinessCode(
        () -> service.requestCancel(RUN_ID),
        "TRADING_LAB_RUN_NOT_FOUND");

    verify(runRepository, times(3)).findById(RUN_ID);
    verifyNoControlFlagWrites();
    verifyNoGenericOrStateTransitionWrites();
  }

  @Test
  void nullRunIdIsRejectedBeforeRepositoryAccessForEveryControl() {
    assertThatThrownBy(() -> service.requestPause(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requestResume(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requestCancel(null))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(runRepository);
  }

  @Test
  void mapperControlSqlUsesStateAndVersionCasAndCannotTouchWorkerLeaseColumns()
      throws NoSuchMethodException {
    Method pause = TradingLabRunRepository.class.getDeclaredMethod(
        "compareAndSetPauseRequested",
        UUID.class,
        String.class,
        long.class,
        boolean.class);
    Method cancel = TradingLabRunRepository.class.getDeclaredMethod(
        "compareAndSetCancelRequested",
        UUID.class,
        String.class,
        long.class);

    assertControlSql(pause, "PAUSE_REQUESTED = #{PAUSEREQUESTED}");
    assertControlSql(cancel, "CANCEL_REQUESTED = TRUE");
    assertThat(pause.getParameterTypes())
        .containsExactly(UUID.class, String.class, long.class, boolean.class);
    assertThat(cancel.getParameterTypes())
        .containsExactly(UUID.class, String.class, long.class);
  }

  private void stubControlCas(ControlOperation operation, int updatedRows) {
    switch (operation) {
      case PAUSE -> when(runRepository.compareAndSetPauseRequested(
          RUN_ID,
          "RUNNING",
          50L,
          true)).thenReturn(updatedRows);
      case RESUME -> when(runRepository.compareAndSetPauseRequested(
          RUN_ID,
          "PAUSED",
          50L,
          false)).thenReturn(updatedRows);
      case CANCEL -> when(runRepository.compareAndSetCancelRequested(
          RUN_ID,
          "RUNNING",
          50L)).thenReturn(updatedRows);
    }
  }

  private void verifyExpectedControlCas(ControlOperation operation, long version) {
    switch (operation) {
      case PAUSE -> verify(runRepository).compareAndSetPauseRequested(
          RUN_ID,
          "RUNNING",
          version,
          true);
      case RESUME -> verify(runRepository).compareAndSetPauseRequested(
          RUN_ID,
          "PAUSED",
          version,
          false);
      case CANCEL -> verify(runRepository).compareAndSetCancelRequested(
          RUN_ID,
          "RUNNING",
          version);
    }
  }

  private TradingLabRunControlResult invoke(ControlOperation operation) {
    return switch (operation) {
      case PAUSE -> service.requestPause(RUN_ID);
      case RESUME -> service.requestResume(RUN_ID);
      case CANCEL -> service.requestCancel(RUN_ID);
    };
  }

  private void verifyNoControlFlagWrites() {
    verify(runRepository, never())
        .compareAndSetPauseRequested(any(), any(), anyLong(), anyBoolean());
    verify(runRepository, never())
        .compareAndSetCancelRequested(any(), any(), anyLong());
  }

  private void verifyNoGenericOrStateTransitionWrites() {
    verify(runRepository, never()).save(any(TradingLabRunEntity.class));
    verify(runRepository, never()).updateById(any(TradingLabRunEntity.class));
    verify(runRepository, never())
        .compareAndSetUnleasedState(any(), any(), any(), anyLong());
    verify(runRepository, never())
        .compareAndSetFencedState(any(), any(), any(), anyLong(), any());
  }

  private static void assertLeaseUnchanged(TradingLabRunEntity run) {
    assertThat(run.getLeaseKey()).isEqualTo((short) 1);
    assertThat(run.getLeaseOwner()).isEqualTo(LEASE_OWNER);
    assertThat(run.getLeaseUntil()).isEqualTo(LEASE_UNTIL);
  }

  private static void assertControlSql(Method method, String expectedAssignment) {
    Update update = method.getAnnotation(Update.class);
    assertThat(update).as("@Update on %s", method.getName()).isNotNull();
    String sql = String.join(" ", update.value())
        .replaceAll("\\s+", " ")
        .trim()
        .toUpperCase(Locale.ROOT);
    assertThat(sql)
        .contains("UPDATE TRADING_LAB.RUNS")
        .contains("SET " + expectedAssignment)
        .contains("VERSION = VERSION + 1")
        .contains("UPDATED_AT = NOW()")
        .contains("WHERE ID = #{RUNID}")
        .contains("STATE = #{EXPECTEDSTATE}")
        .contains("VERSION = #{EXPECTEDVERSION}")
        .doesNotContain("LEASE_KEY")
        .doesNotContain("LEASE_OWNER")
        .doesNotContain("LEASE_UNTIL");
  }

  private static Stream<Arguments> invalidControlStates() {
    return Arrays.stream(TradingLabRunState.values()).flatMap(state -> Stream.of(
        state != TradingLabRunState.RUNNING && state != TradingLabRunState.PAUSED
            ? Arguments.of(ControlOperation.PAUSE, state)
            : null,
        state != TradingLabRunState.PAUSED
            ? Arguments.of(ControlOperation.RESUME, state)
            : null,
        !CANCELLABLE_STATES.contains(state)
            ? Arguments.of(ControlOperation.CANCEL, state)
            : null).filter(argument -> argument != null));
  }

  private static TradingLabRunEntity run(
      TradingLabRunState state,
      long version,
      boolean pauseRequested,
      boolean cancelRequested) {
    TradingLabRunEntity run = new TradingLabRunEntity();
    run.setId(RUN_ID);
    run.setState(state.name());
    run.setVersion(version);
    run.setPauseRequested(pauseRequested);
    run.setCancelRequested(cancelRequested);
    run.setLeaseKey((short) 1);
    run.setLeaseOwner(LEASE_OWNER);
    run.setLeaseUntil(LEASE_UNTIL);
    return run;
  }

  private static void assertBusinessCode(ThrowingCall call, String expectedCode) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
  }

  private enum ControlOperation {
    PAUSE,
    RESUME,
    CANCEL
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }
}
