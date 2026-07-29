package com.fxplatform.tradinglab.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunTransitionEntity;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunTransitionRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class TradingLabRunTransitionServiceTest {

  private static final UUID RUN_ID = UUID.fromString("a5b27057-52d4-4c3f-96b1-a6c2a639f89d");
  private static final UUID TRANSITION_ID =
      UUID.fromString("2c8b5674-f51c-4ca6-b485-31e8c9ae404a");
  private static final Instant VIRTUAL_TIME = Instant.parse("2026-07-18T12:00:00Z");
  private static final Instant REAL_TIME = Instant.parse("2026-07-18T11:59:59Z");
  private static final String IDEMPOTENCY_KEY = "validate-run-1";

  private final TradingLabRunRepository runRepository = mock(TradingLabRunRepository.class);
  private final TradingLabRunTransitionRepository transitionRepository =
      mock(TradingLabRunTransitionRepository.class);
  private final TradingLabRunTransitionService service = new TradingLabRunTransitionService(
      runRepository,
      transitionRepository,
      new TradingLabStateMachine());

  @Test
  void checksIdempotencyBeforeLoadingOrChangingTheRunAndReturnsTheOriginalTransition() {
    TradingLabRunTransitionEntity existing = transition(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        8L,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(existing));

    RunTransitionResult result = service.transition(command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME));

    assertThat(result).isEqualTo(resultOf(existing));
    verifyNoInteractions(runRepository);
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
  }

  @Test
  void replayAcceptsThePostgresMicrosecondValueForASubMicrosecondVirtualTime() {
    Instant requestedVirtualTime = VIRTUAL_TIME.plusNanos(123);
    TradingLabRunTransitionEntity existing = transition(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        8L,
        IDEMPOTENCY_KEY,
        "validate",
        postgresMicroseconds(requestedVirtualTime));
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(existing));

    RunTransitionResult result = service.transition(command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        requestedVirtualTime));

    assertThat(result).isEqualTo(resultOf(existing));
    verifyNoInteractions(runRepository);
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
  }

  @ParameterizedTest(name = "conflicting replay {0}")
  @MethodSource("semanticConflicts")
  void rejectsAReusedIdempotencyKeyWithDifferentCommandMeaning(
      String description,
      RunTransitionCommand conflictingCommand) {
    TradingLabRunTransitionEntity existing = transition(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        8L,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(existing));

    assertBusinessCode(
        () -> service.transition(conflictingCommand),
        "TRADING_LAB_IDEMPOTENCY_CONFLICT");

    verifyNoInteractions(runRepository);
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
  }

  @Test
  void lockedIdempotencyRecheckRejectsThePriorEdgeBeforeLoadingOrChangingTheRun() {
    RunTransitionCommand laterEdge = command(
        TradingLabRunState.VALIDATING,
        TradingLabRunState.QUEUED,
        IDEMPOTENCY_KEY,
        "queue after validation",
        VIRTUAL_TIME);
    TradingLabRunTransitionEntity committedPriorEdge = transition(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        8L,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty(), Optional.of(committedPriorEdge));

    assertBusinessCode(
        () -> service.transition(laterEdge),
        "TRADING_LAB_IDEMPOTENCY_CONFLICT");

    InOrder reads = inOrder(transitionRepository, runRepository);
    reads.verify(transitionRepository)
        .findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY);
    reads.verify(runRepository).lockForStateTransition(RUN_ID);
    reads.verify(transitionRepository)
        .findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY);
    verify(runRepository, never()).findById(RUN_ID);
    verify(runRepository, never()).compareAndSetUnleasedState(any(), any(), any(), anyLong());
    verify(runRepository, never())
        .compareAndSetFencedState(any(), any(), any(), anyLong(), any());
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void performsUnleasedCompareAndSetThenRecordsTheNewAuthoritativeVersion() {
    RunTransitionCommand command = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    TradingLabRunEntity run = run(TradingLabRunState.DRAFT, 7L, null, null);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(runRepository.compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L)).thenReturn(1);
    when(transitionRepository.insert(any(TradingLabRunTransitionEntity.class))).thenReturn(1);

    RunTransitionResult result = service.transition(command);

    ArgumentCaptor<TradingLabRunTransitionEntity> inserted =
        ArgumentCaptor.forClass(TradingLabRunTransitionEntity.class);
    verify(transitionRepository).insert(inserted.capture());
    assertThat(inserted.getValue().getId()).isNotNull();
    assertThat(inserted.getValue().getRunId()).isEqualTo(RUN_ID);
    assertThat(inserted.getValue().getFromState()).isEqualTo("DRAFT");
    assertThat(inserted.getValue().getToState()).isEqualTo("VALIDATING");
    assertThat(inserted.getValue().getRunVersion()).isEqualTo(8L);
    assertThat(inserted.getValue().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(inserted.getValue().getReason()).isEqualTo("validate");
    assertThat(inserted.getValue().getVirtualTime()).isEqualTo(VIRTUAL_TIME);
    assertThat(inserted.getValue().getRealTime()).isNotNull();
    assertThat(result.runVersion()).isEqualTo(8L);
    assertThat(result.from()).isEqualTo(TradingLabRunState.DRAFT);
    assertThat(result.to()).isEqualTo(TradingLabRunState.VALIDATING);

    InOrder writes = inOrder(transitionRepository, runRepository);
    writes.verify(transitionRepository)
        .findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY);
    writes.verify(runRepository).lockForStateTransition(RUN_ID);
    writes.verify(transitionRepository)
        .findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY);
    writes.verify(runRepository).findById(RUN_ID);
    writes.verify(runRepository)
        .compareAndSetUnleasedState(RUN_ID, "DRAFT", "VALIDATING", 7L);
    writes.verify(transitionRepository).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void firstTransitionResultMatchesPostgresMicrosecondReadBack() {
    Instant requestedVirtualTime = VIRTUAL_TIME.plusNanos(123);
    RunTransitionCommand command = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        requestedVirtualTime);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.DRAFT, 7L, null, null)));
    when(runRepository.compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L)).thenReturn(1);
    when(transitionRepository.insert(any(TradingLabRunTransitionEntity.class))).thenReturn(1);

    RunTransitionResult result = service.transition(command);

    ArgumentCaptor<TradingLabRunTransitionEntity> inserted =
        ArgumentCaptor.forClass(TradingLabRunTransitionEntity.class);
    verify(transitionRepository).insert(inserted.capture());
    assertThat(result.realTime())
        .as("real time must equal the value PostgreSQL returns after a timestamp(6) insert")
        .isEqualTo(postgresMicroseconds(inserted.getValue().getRealTime()));
    assertThat(result.virtualTime())
        .as("virtual time must equal the value PostgreSQL returns after a timestamp(6) insert")
        .isEqualTo(postgresMicroseconds(inserted.getValue().getVirtualTime()));
  }

  @ParameterizedTest(name = "fenced regression {0} -> {1}")
  @MethodSource("fencedRegressionEdges")
  void performsExplicitFencedPausedResumeAndRunningCancellationTransitions(
      TradingLabRunState expected,
      TradingLabRunState target) {
    String owner = "worker-a:8e759bf7-f55e-4c7b-bf53-b1bdc45bfc20";
    String key = expected + "-to-" + target;
    RunTransitionCommand command = command(expected, target, key, "worker boundary", VIRTUAL_TIME);
    TradingLabRunEntity run = run(
        expected,
        41L,
        owner,
        Instant.parse("2026-07-18T12:05:00Z"));
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, key))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(runRepository.compareAndSetFencedState(
        RUN_ID,
        expected.name(),
        target.name(),
        41L,
        owner)).thenReturn(1);
    when(transitionRepository.insert(any(TradingLabRunTransitionEntity.class))).thenReturn(1);

    RunTransitionResult result = service.transitionFenced(command, owner);

    assertThat(result.from()).isEqualTo(expected);
    assertThat(result.to()).isEqualTo(target);
    assertThat(result.runVersion()).isEqualTo(42L);
    verify(runRepository).compareAndSetFencedState(
        RUN_ID,
        expected.name(),
        target.name(),
        41L,
        owner);
    verifyNoGenericRunWrites();
  }

  @Test
  void fencedCompareAndSetUsesWallClockTimestampInsteadOfTransactionTime()
      throws NoSuchMethodException {
    Method fencedCas = TradingLabRunRepository.class.getDeclaredMethod(
        "compareAndSetFencedState",
        UUID.class,
        String.class,
        String.class,
        long.class,
        String.class);
    Update update = fencedCas.getAnnotation(Update.class);

    assertThat(update).isNotNull();
    String sql = String.join("\n", update.value()).toLowerCase(Locale.ROOT);
    assertThat(sql)
        .contains("lease_until > clock_timestamp()")
        .doesNotContain("now()");
  }

  @Test
  void stateTransitionRowLockFlushesTheMyBatisSessionCacheBeforeTheLockedRecheck()
      throws NoSuchMethodException {
    Method rowLock = TradingLabRunRepository.class.getDeclaredMethod(
        "lockForStateTransition",
        UUID.class);
    Options options = rowLock.getAnnotation(Options.class);

    assertThat(options).isNotNull();
    assertThat(options.flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
    assertThat(options.useCache()).isFalse();
  }

  @Test
  void rejectsAnIllegalGraphEdgeBeforeAnyCompareAndSetOrInsert() {
    RunTransitionCommand illegal = command(
        TradingLabRunState.QUEUED,
        TradingLabRunState.COMPLETED,
        IDEMPOTENCY_KEY,
        "skip cleanup",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.QUEUED, 3L, null, null)));

    assertBusinessCode(
        () -> service.transition(illegal),
        "TRADING_LAB_INVALID_TRANSITION");

    verify(runRepository, never()).compareAndSetUnleasedState(any(), any(), any(), anyLong());
    verify(runRepository, never())
        .compareAndSetFencedState(any(), any(), any(), anyLong(), any());
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void rejectsAStaleLoadedStateWithoutMutatingTheRun() {
    RunTransitionCommand stale = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.VALIDATING, 8L, null, null)));

    assertBusinessCode(
        () -> service.transition(stale),
        "TRADING_LAB_STALE_STATE");

    verify(runRepository, never()).compareAndSetUnleasedState(any(), any(), any(), anyLong());
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void zeroRowUnleasedCasTreatsAConcurrentVersionChangeAsLostWithoutAnInsert() {
    RunTransitionCommand command = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.DRAFT, 7L, null, null)));
    when(runRepository.compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L)).thenReturn(0);

    assertBusinessCode(
        () -> service.transition(command),
        "TRADING_LAB_TRANSITION_LOST");

    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void zeroRowUnleasedCasReturnsAnExactReplayInsertedByTheConcurrentWinner() {
    RunTransitionCommand command = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    TradingLabRunTransitionEntity concurrentWinner = transition(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        8L,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.empty(),
            Optional.empty(),
            Optional.of(concurrentWinner));
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.DRAFT, 7L, null, null)));
    when(runRepository.compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L)).thenReturn(0);

    RunTransitionResult replay = service.transition(command);

    assertThat(replay).isEqualTo(resultOf(concurrentWinner));
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void zeroRowUnleasedCasRejectsAConflictingMeaningInsertedByTheConcurrentWinner() {
    RunTransitionCommand command = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "requested meaning",
        VIRTUAL_TIME);
    TradingLabRunTransitionEntity concurrentWinner = transition(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        8L,
        IDEMPOTENCY_KEY,
        "different winning meaning",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.empty(),
            Optional.empty(),
            Optional.of(concurrentWinner));
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.DRAFT, 7L, null, null)));
    when(runRepository.compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L)).thenReturn(0);

    assertBusinessCode(
        () -> service.transition(command),
        "TRADING_LAB_IDEMPOTENCY_CONFLICT");

    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void staleClaimOwnerLosesTheFencedCasWithoutAnInsert() {
    assertFenceLossWithoutMutation(
        "old-worker:3f97a1c5-50d8-456f-ad70-39132bd78252",
        "new-worker:0469174a-86fe-4ab2-a755-f4668a2e95bc",
        Instant.parse("2026-07-18T12:05:00Z"));
  }

  @Test
  void expiredClaimLosesTheDatabaseTimeFencedCasWithoutAnInsert() {
    String owner = "worker-a:3f97a1c5-50d8-456f-ad70-39132bd78252";
    assertFenceLossWithoutMutation(
        owner,
        owner,
        Instant.parse("2026-07-18T11:59:00Z"));
  }

  @Test
  void workerOwnedEdgesCannotUseTheUnleasedEntryPoint() {
    RunTransitionCommand command = command(
        TradingLabRunState.RUNNING,
        TradingLabRunState.PAUSED,
        IDEMPOTENCY_KEY,
        "pause boundary",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());

    assertBusinessCode(
        () -> service.transition(command),
        "TRADING_LAB_FENCE_REQUIRED");

    verify(runRepository).lockForStateTransition(RUN_ID);
    verify(runRepository, never()).findById(RUN_ID);
    verify(runRepository, never()).compareAndSetUnleasedState(any(), any(), any(), anyLong());
    verify(runRepository, never())
        .compareAndSetFencedState(any(), any(), any(), anyLong(), any());
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  @Test
  void transitionInsertFailureEscapesTheTransactionSoTheSuccessfulCasRollsBack()
      throws NoSuchMethodException {
    assertTransactionalBoundary();
    RunTransitionCommand command = command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        "validate",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID))
        .thenReturn(Optional.of(run(TradingLabRunState.DRAFT, 7L, null, null)));
    when(runRepository.compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L)).thenReturn(1);
    DataIntegrityViolationException insertFailure =
        new DataIntegrityViolationException("transition insert failed");
    when(transitionRepository.insert(any(TradingLabRunTransitionEntity.class)))
        .thenThrow(insertFailure);

    assertThatThrownBy(() -> service.transition(command)).isSameAs(insertFailure);

    verify(runRepository).compareAndSetUnleasedState(
        RUN_ID,
        "DRAFT",
        "VALIDATING",
        7L);
    verifyNoGenericRunWrites();
  }

  @Test
  void rejectsMissingCommandFieldsAndInvalidIdempotencyKeysBeforeDatabaseAccess() {
    assertInvalidInput(() -> service.transition(null));
    assertInvalidInput(() -> service.transition(new RunTransitionCommand(
        null,
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        null,
        null)));
    assertInvalidInput(() -> service.transition(new RunTransitionCommand(
        RUN_ID,
        null,
        TradingLabRunState.VALIDATING,
        IDEMPOTENCY_KEY,
        null,
        null)));
    assertInvalidInput(() -> service.transition(new RunTransitionCommand(
        RUN_ID,
        TradingLabRunState.DRAFT,
        null,
        IDEMPOTENCY_KEY,
        null,
        null)));
    assertInvalidInput(() -> service.transition(command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        null,
        null,
        null)));
    assertInvalidInput(() -> service.transition(command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        " \t\r\n ",
        null,
        null)));
    assertInvalidInput(() -> service.transition(command(
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        "x".repeat(161),
        null,
        null)));

    verifyNoInteractions(runRepository, transitionRepository);
  }

  @Test
  void rejectsMissingBlankOrOversizedClaimOwnersBeforeDatabaseAccess() {
    RunTransitionCommand command = command(
        TradingLabRunState.RUNNING,
        TradingLabRunState.PAUSED,
        IDEMPOTENCY_KEY,
        "pause boundary",
        VIRTUAL_TIME);

    assertInvalidInput(() -> service.transitionFenced(command, null));
    assertInvalidInput(() -> service.transitionFenced(command, " \t\r\n "));
    assertInvalidInput(() -> service.transitionFenced(command, "x".repeat(161)));

    verifyNoInteractions(runRepository, transitionRepository);
  }

  private void assertFenceLossWithoutMutation(
      String attemptedOwner,
      String persistedOwner,
      Instant persistedLeaseUntil) {
    RunTransitionCommand command = command(
        TradingLabRunState.RUNNING,
        TradingLabRunState.PAUSED,
        IDEMPOTENCY_KEY,
        "pause boundary",
        VIRTUAL_TIME);
    when(transitionRepository.findByRunIdAndIdempotencyKey(RUN_ID, IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty());
    when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(
        TradingLabRunState.RUNNING,
        19L,
        persistedOwner,
        persistedLeaseUntil)));
    when(runRepository.compareAndSetFencedState(
        RUN_ID,
        "RUNNING",
        "PAUSED",
        19L,
        attemptedOwner)).thenReturn(0);

    assertBusinessCode(
        () -> service.transitionFenced(command, attemptedOwner),
        "TRADING_LAB_FENCE_LOST");

    verify(runRepository).compareAndSetFencedState(
        RUN_ID,
        "RUNNING",
        "PAUSED",
        19L,
        attemptedOwner);
    verify(transitionRepository, never()).insert(any(TradingLabRunTransitionEntity.class));
    verifyNoGenericRunWrites();
  }

  private void assertTransactionalBoundary() throws NoSuchMethodException {
    Method transition = TradingLabRunTransitionService.class.getDeclaredMethod(
        "transition",
        RunTransitionCommand.class);
    assertThat(
        AnnotatedElementUtils.hasAnnotation(transition, Transactional.class)
            || AnnotatedElementUtils.hasAnnotation(
                TradingLabRunTransitionService.class,
                Transactional.class))
        .as("run CAS and transition insert must share one Spring transaction")
        .isTrue();
  }

  private void verifyNoGenericRunWrites() {
    verify(runRepository, never()).save(any(TradingLabRunEntity.class));
    verify(runRepository, never()).updateById(any(TradingLabRunEntity.class));
  }

  private void assertInvalidInput(ThrowingCall call) {
    assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertBusinessCode(ThrowingCall call, String expectedCode) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
  }

  private static Stream<Arguments> semanticConflicts() {
    return Stream.of(
        Arguments.of("expected state", command(
            TradingLabRunState.VALIDATING,
            TradingLabRunState.VALIDATING,
            IDEMPOTENCY_KEY,
            "validate",
            VIRTUAL_TIME)),
        Arguments.of("target state", command(
            TradingLabRunState.DRAFT,
            TradingLabRunState.QUEUED,
            IDEMPOTENCY_KEY,
            "validate",
            VIRTUAL_TIME)),
        Arguments.of("reason", command(
            TradingLabRunState.DRAFT,
            TradingLabRunState.VALIDATING,
            IDEMPOTENCY_KEY,
            "different reason",
            VIRTUAL_TIME)),
        Arguments.of("virtual time", command(
            TradingLabRunState.DRAFT,
            TradingLabRunState.VALIDATING,
            IDEMPOTENCY_KEY,
            "validate",
            VIRTUAL_TIME.plusSeconds(1))));
  }

  private static Stream<Arguments> fencedRegressionEdges() {
    return Stream.of(
        Arguments.of(TradingLabRunState.PAUSED, TradingLabRunState.RUNNING),
        Arguments.of(TradingLabRunState.RUNNING, TradingLabRunState.CANCELLING));
  }

  private static RunTransitionCommand command(
      TradingLabRunState expected,
      TradingLabRunState target,
      String idempotencyKey,
      String reason,
      Instant virtualTime) {
    return new RunTransitionCommand(
        RUN_ID,
        expected,
        target,
        idempotencyKey,
        reason,
        virtualTime);
  }

  private static TradingLabRunEntity run(
      TradingLabRunState state,
      long version,
      String leaseOwner,
      Instant leaseUntil) {
    TradingLabRunEntity run = new TradingLabRunEntity();
    run.setId(RUN_ID);
    run.setState(state.name());
    run.setVersion(version);
    run.setLeaseKey(leaseOwner == null ? null : (short) 1);
    run.setLeaseOwner(leaseOwner);
    run.setLeaseUntil(leaseUntil);
    return run;
  }

  private static TradingLabRunTransitionEntity transition(
      TradingLabRunState from,
      TradingLabRunState to,
      long runVersion,
      String idempotencyKey,
      String reason,
      Instant virtualTime) {
    TradingLabRunTransitionEntity transition = new TradingLabRunTransitionEntity();
    transition.setId(TRANSITION_ID);
    transition.setRunId(RUN_ID);
    transition.setFromState(from.name());
    transition.setToState(to.name());
    transition.setRunVersion(runVersion);
    transition.setIdempotencyKey(idempotencyKey);
    transition.setReason(reason);
    transition.setRealTime(REAL_TIME);
    transition.setVirtualTime(virtualTime);
    transition.setDetailsJson("{}");
    return transition;
  }

  private static RunTransitionResult resultOf(TradingLabRunTransitionEntity transition) {
    return new RunTransitionResult(
        transition.getId(),
        transition.getRunId(),
        TradingLabRunState.valueOf(transition.getFromState()),
        TradingLabRunState.valueOf(transition.getToState()),
        transition.getRunVersion(),
        transition.getIdempotencyKey(),
        transition.getReason(),
        transition.getRealTime(),
        transition.getVirtualTime());
  }

  private static Instant postgresMicroseconds(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }
}
