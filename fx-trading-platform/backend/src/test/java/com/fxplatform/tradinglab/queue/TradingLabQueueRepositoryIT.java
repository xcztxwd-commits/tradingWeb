package com.fxplatform.tradinglab.queue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.state.RunTransitionCommand;
import com.fxplatform.tradinglab.state.RunTransitionResult;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import com.fxplatform.tradinglab.state.TradingLabRunTransitionService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("database-it")
@Testcontainers
class TradingLabQueueRepositoryIT {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TradingLabLeaseService leaseService;
  @Autowired private TradingLabRunTransitionService transitionService;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  private UUID actorId;
  private UUID scenarioId;

  @BeforeEach
  void prepareIsolatedFixture() {
    assertThat(Arrays.stream(flyway.info().applied())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion()))
        .contains("60", "61");
    deleteTradingLabFixture();
    jdbcTemplate.update("delete from auth.users where email like '%@queue-it.test'");
    actorId = insertUser();
    scenarioId = insertScenario(actorId);
  }

  @AfterEach
  void removeIsolatedFixture() {
    deleteTradingLabFixture();
    jdbcTemplate.update("delete from auth.users where email like '%@queue-it.test'");
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void twoConcurrentWorkersProduceExactlyOneSingletonClaim() throws Exception {
    RunRow first = insertRun(TradingLabRunState.QUEUED);
    RunRow second = insertRun(TradingLabRunState.QUEUED);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);

    try {
      Future<Optional<TradingLabRunClaim>> workerA = workers.submit(
          () -> acquireAfterBarrier(start, "queue-worker-a"));
      Future<Optional<TradingLabRunClaim>> workerB = workers.submit(
          () -> acquireAfterBarrier(start, "queue-worker-b"));

      List<TradingLabRunClaim> claims = List.of(workerA.get(20, SECONDS), workerB.get(20, SECONDS))
          .stream()
          .flatMap(Optional::stream)
          .toList();

      assertThat(claims).hasSize(1);
      assertThat(claims.getFirst().runId()).isEqualTo(first.id());
      assertThat(claims.getFirst().claimOwner())
          .matches("queue-worker-[ab]:[0-9a-f-]{36}");
      assertThat(singletonLeaseCount()).isOne();
      assertThat(leaseOwner(first.id())).isEqualTo(claims.getFirst().claimOwner());
      assertThat(leaseOwner(second.id())).isNull();
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  @Test
  void newQueueClaimsAreFifoByDatabaseQueueSequence() {
    RunRow oldest = insertRun(TradingLabRunState.QUEUED);
    RunRow middle = insertRun(TradingLabRunState.QUEUED);
    RunRow newest = insertRun(TradingLabRunState.QUEUED);

    TradingLabRunClaim claim = requireClaim("fifo-worker");

    assertThat(oldest.queueSequence())
        .isLessThan(middle.queueSequence());
    assertThat(middle.queueSequence()).isLessThan(newest.queueSequence());
    assertThat(claim.runId()).isEqualTo(oldest.id());
    assertThat(claim.queueSequence()).isEqualTo(oldest.queueSequence());
    assertThat(leaseOwner(middle.id())).isNull();
    assertThat(leaseOwner(newest.id())).isNull();
  }

  @Test
  void expiredActiveLeaseIsReclaimedBeforeLaterQueuedRun() {
    String expiredOwner = "crashed-worker:" + UUID.randomUUID();
    RunRow active = insertLeasedRun(
        TradingLabRunState.RESETTING, expiredOwner, -30, 7L);
    insertTransition(
        active.id(),
        TradingLabRunState.QUEUED,
        TradingLabRunState.RESETTING,
        7L,
        "entered-reset-before-crash");
    RunRow laterQueued = insertRun(TradingLabRunState.QUEUED);

    TradingLabRunClaim reclaimed = requireClaim("recovery-worker");

    assertThat(reclaimed.runId()).isEqualTo(active.id());
    assertThat(reclaimed.state()).isEqualTo(TradingLabRunState.RESETTING);
    assertThat(reclaimed.runVersion()).isGreaterThan(7L);
    assertThat(reclaimed.claimOwner()).startsWith("recovery-worker:").isNotEqualTo(expiredOwner);
    assertThat(databaseLeaseIsLive(active.id())).isTrue();
    assertThat(transitionCount(active.id())).isOne();
    assertThat(leaseOwner(laterQueued.id())).isNull();
  }

  @Test
  void unleasedAbandonedActiveRunIsRecoveredBeforeQueuedWork() {
    RunRow abandoned = insertRun(TradingLabRunState.RUNNING, 4L);
    insertTransition(
        abandoned.id(),
        TradingLabRunState.RESETTING,
        TradingLabRunState.RUNNING,
        4L,
        "last-durable-running-transition");
    RunRow queued = insertRun(TradingLabRunState.QUEUED);

    TradingLabRunClaim recovered = requireClaim("abandoned-recovery-worker");

    assertThat(recovered.runId()).isEqualTo(abandoned.id());
    assertThat(recovered.state()).isEqualTo(TradingLabRunState.RUNNING);
    assertThat(recovered.runVersion()).isGreaterThan(4L);
    assertThat(transitionCount(abandoned.id())).isOne();
    assertThat(leaseOwner(queued.id())).isNull();
  }

  @Test
  void crashAfterClaimResumesLastCommittedTransitionWithoutDuplicatingIt() {
    RunRow first = insertRun(TradingLabRunState.QUEUED);
    RunRow later = insertRun(TradingLabRunState.QUEUED);
    TradingLabRunClaim crashedClaim = requireClaim("crashing-worker");
    RunTransitionCommand enterReset = command(
        first.id(),
        TradingLabRunState.QUEUED,
        TradingLabRunState.RESETTING,
        "claim-enter-reset",
        "reset started");
    RunTransitionResult committed = transitionService.transitionFenced(
        enterReset, crashedClaim.claimOwner());
    expireLease(first.id(), crashedClaim.claimOwner());

    TradingLabRunClaim recovered = requireClaim("replacement-worker");
    RunTransitionResult replayed = transitionService.transitionFenced(
        enterReset, recovered.claimOwner());

    assertThat(recovered.runId()).isEqualTo(first.id());
    assertThat(recovered.state()).isEqualTo(TradingLabRunState.RESETTING);
    assertThat(recovered.runVersion()).isGreaterThan(committed.runVersion());
    assertThat(recovered.claimOwner()).isNotEqualTo(crashedClaim.claimOwner());
    assertThat(replayed).isEqualTo(committed);
    assertThat(transitionCount(first.id())).isOne();
    assertThat(storedState(first.id())).isEqualTo(TradingLabRunState.RESETTING.name());
    assertThat(leaseOwner(later.id())).isNull();
  }

  @Test
  void uniqueClaimOwnerFenceBlocksOldTransitionRenewAndRelease() {
    RunRow run = insertRun(TradingLabRunState.QUEUED);
    TradingLabRunClaim oldClaim = requireClaim("same-worker-instance");
    expireLease(run.id(), oldClaim.claimOwner());
    TradingLabRunClaim currentClaim = requireClaim("same-worker-instance");
    RunTransitionCommand transition = command(
        run.id(),
        TradingLabRunState.QUEUED,
        TradingLabRunState.RESETTING,
        "old-owner-must-lose",
        "fence proof");

    assertThat(currentClaim.claimOwner())
        .startsWith("same-worker-instance:")
        .isNotEqualTo(oldClaim.claimOwner());
    assertThat(leaseService.renew(run.id(), oldClaim.claimOwner(), LEASE_DURATION)).isEmpty();
    assertThat(leaseService.releaseIfTerminal(run.id(), oldClaim.claimOwner())).isFalse();
    assertThatThrownBy(() -> transitionService.transitionFenced(
        transition, oldClaim.claimOwner()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("TRADING_LAB_FENCE_LOST"));

    assertThat(transitionCount(run.id())).isZero();
    assertThat(storedState(run.id())).isEqualTo(TradingLabRunState.QUEUED.name());
    assertThat(storedVersion(run.id())).isEqualTo(currentClaim.runVersion());
    assertThat(leaseOwner(run.id())).isEqualTo(currentClaim.claimOwner());

    RunTransitionResult winner = transitionService.transitionFenced(
        transition, currentClaim.claimOwner());
    assertThat(winner.to()).isEqualTo(TradingLabRunState.RESETTING);
    assertThat(transitionCount(run.id())).isOne();
  }

  @ParameterizedTest
  @EnumSource(value = TradingLabRunState.class, names = {"PAUSED", "CANCELLING"})
  void pausedAndCancellingRetainSingletonLease(TradingLabRunState activeState) {
    String owner = "holding-worker:" + UUID.randomUUID();
    RunRow active = insertLeasedRun(activeState, owner, 60, 5L);
    RunRow queued = insertRun(TradingLabRunState.QUEUED);

    Optional<TradingLabRunClaim> renewed = leaseService.renew(
        active.id(), owner, LEASE_DURATION);

    assertThat(renewed).isPresent();
    assertThat(renewed.orElseThrow().state()).isEqualTo(activeState);
    assertThat(renewed.orElseThrow().runVersion()).isGreaterThan(5L);
    assertThat(leaseService.acquireNext("blocked-worker", LEASE_DURATION)).isEmpty();
    assertThat(leaseService.releaseIfTerminal(active.id(), owner)).isFalse();
    assertThat(singletonLeaseCount()).isOne();
    assertThat(leaseOwner(active.id())).isEqualTo(owner);
    assertThat(leaseOwner(queued.id())).isNull();
  }

  @Test
  void terminalTransitionClearsLeaseAtomicallyAndPermitsNextFifoRun() {
    String owner = "cleaning-worker:" + UUID.randomUUID();
    RunRow cleaning = insertLeasedRun(TradingLabRunState.CLEANING, owner, 60, 8L);
    insertTransition(
        cleaning.id(),
        TradingLabRunState.CANCELLING,
        TradingLabRunState.CLEANING,
        8L,
        "entered-cleaning");
    RunRow next = insertRun(TradingLabRunState.QUEUED);
    RunRow later = insertRun(TradingLabRunState.QUEUED);
    RunTransitionCommand complete = command(
        cleaning.id(),
        TradingLabRunState.CLEANING,
        TradingLabRunState.COMPLETED,
        "cleaning-completed",
        "cleanup complete");

    RunTransitionResult terminal = transitionService.transitionFenced(complete, owner);

    assertThat(terminal.to()).isEqualTo(TradingLabRunState.COMPLETED);
    assertThat(storedState(cleaning.id())).isEqualTo(TradingLabRunState.COMPLETED.name());
    assertThat(leaseOwner(cleaning.id())).isNull();
    assertThat(singletonLeaseCount()).isZero();

    TradingLabRunClaim nextClaim = requireClaim("next-fifo-worker");
    assertThat(nextClaim.runId()).isEqualTo(next.id());
    assertThat(nextClaim.queueSequence()).isLessThan(later.queueSequence());
    assertThat(leaseOwner(later.id())).isNull();
  }

  @Test
  void liveTerminalSingletonIsClearedBeforeAQueuedRunIsClaimed() {
    String terminalOwner = "finished-worker:" + UUID.randomUUID();
    RunRow terminal = insertLeasedRun(
        TradingLabRunState.COMPLETED, terminalOwner, 60, 9L);
    RunRow queued = insertRun(TradingLabRunState.QUEUED);

    TradingLabRunClaim claim = requireClaim("terminal-cleanup-worker");

    assertThat(claim.runId()).isEqualTo(queued.id());
    assertThat(claim.claimOwner()).startsWith("terminal-cleanup-worker:");
    assertThat(leaseFieldsAreCleared(terminal.id())).isTrue();
    assertThat(storedVersion(terminal.id())).isGreaterThan(9L);
    assertThat(singletonLeaseCount()).isOne();
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void lockedLiveSingletonCausesABoundedNoOpWithoutClaimingQueuedWork() throws Exception {
    String owner = "locked-live-owner:" + UUID.randomUUID();
    RunRow liveSingleton = insertLeasedRun(
        TradingLabRunState.RUNNING, owner, 60, 6L);
    RunRow queued = insertRun(TradingLabRunState.QUEUED);
    RunSnapshot singletonBefore = storedRunSnapshot(liveSingleton.id());
    RunSnapshot queuedBefore = storedRunSnapshot(queued.id());

    assertAcquireIsBoundedNoOpWhileLocked(
        liveSingleton.id(),
        "locked-live-singleton-worker");

    assertThat(storedRunSnapshot(liveSingleton.id())).isEqualTo(singletonBefore);
    assertThat(storedRunSnapshot(queued.id())).isEqualTo(queuedBefore);
    assertThat(leaseOwner(liveSingleton.id())).isEqualTo(owner);
    assertThat(leaseOwner(queued.id())).isNull();
    assertThat(singletonLeaseCount()).isOne();
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void lockedOldestRecoverableCausesABoundedNoOpWithoutClaimingLaterWork()
      throws Exception {
    RunRow oldestRecoverable = insertRun(TradingLabRunState.RUNNING, 4L);
    RunRow laterRecoverable = insertRun(TradingLabRunState.RESETTING, 5L);
    RunRow queued = insertRun(TradingLabRunState.QUEUED);
    RunSnapshot oldestBefore = storedRunSnapshot(oldestRecoverable.id());
    RunSnapshot laterBefore = storedRunSnapshot(laterRecoverable.id());
    RunSnapshot queuedBefore = storedRunSnapshot(queued.id());
    assertThat(oldestRecoverable.queueSequence())
        .isLessThan(laterRecoverable.queueSequence());

    assertAcquireIsBoundedNoOpWhileLocked(
        oldestRecoverable.id(),
        "locked-recovery-worker");

    assertThat(storedRunSnapshot(oldestRecoverable.id())).isEqualTo(oldestBefore);
    assertThat(storedRunSnapshot(laterRecoverable.id())).isEqualTo(laterBefore);
    assertThat(storedRunSnapshot(queued.id())).isEqualTo(queuedBefore);
    assertThat(leaseOwner(oldestRecoverable.id())).isNull();
    assertThat(leaseOwner(laterRecoverable.id())).isNull();
    assertThat(leaseOwner(queued.id())).isNull();
    assertThat(singletonLeaseCount()).isZero();
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void lockedOldestQueuedCausesABoundedNoOpWithoutClaimingTheNewerRun() throws Exception {
    RunRow oldest = insertRun(TradingLabRunState.QUEUED);
    RunRow newer = insertRun(TradingLabRunState.QUEUED);
    RunSnapshot oldestBefore = storedRunSnapshot(oldest.id());
    RunSnapshot newerBefore = storedRunSnapshot(newer.id());
    assertThat(oldest.queueSequence()).isLessThan(newer.queueSequence());

    assertAcquireIsBoundedNoOpWhileLocked(oldest.id(), "locked-fifo-worker");

    assertThat(storedRunSnapshot(oldest.id())).isEqualTo(oldestBefore);
    assertThat(storedRunSnapshot(newer.id())).isEqualTo(newerBefore);
    assertThat(leaseOwner(oldest.id())).isNull();
    assertThat(leaseOwner(newer.id())).isNull();
    assertThat(singletonLeaseCount()).isZero();
  }

  @Test
  void postgresUniqueKeyProvidesExactReplayAndRejectsConflictingMeaning() {
    RunRow run = insertRun(TradingLabRunState.QUEUED);
    TradingLabRunClaim claim = requireClaim("idempotency-worker");
    RunTransitionCommand original = command(
        run.id(),
        TradingLabRunState.QUEUED,
        TradingLabRunState.RESETTING,
        "postgres-duplicate-key",
        "original meaning");

    RunTransitionResult first = transitionService.transitionFenced(original, claim.claimOwner());
    RunTransitionResult exactReplay = transitionService.transitionFenced(
        original, claim.claimOwner());

    assertThat(exactReplay).isEqualTo(first);
    assertThat(transitionCount(run.id())).isOne();
    assertThatThrownBy(() -> transitionService.transitionFenced(
        command(
            run.id(),
            TradingLabRunState.QUEUED,
            TradingLabRunState.CANCELLING,
            original.idempotencyKey(),
            original.reason()),
        claim.claimOwner()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.getCode())
                .isEqualTo("TRADING_LAB_IDEMPOTENCY_CONFLICT"));
    assertThatThrownBy(() -> transitionService.transitionFenced(
        command(
            run.id(),
            TradingLabRunState.QUEUED,
            TradingLabRunState.RESETTING,
            original.idempotencyKey(),
            "conflicting meaning"),
        claim.claimOwner()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.getCode())
                .isEqualTo("TRADING_LAB_IDEMPOTENCY_CONFLICT"));

    assertThat(transitionCount(run.id())).isOne();
    assertThat(storedVersion(run.id())).isEqualTo(first.runVersion());
    assertThat(storedState(run.id())).isEqualTo(TradingLabRunState.RESETTING.name());
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void concurrentExactTransitionCommandsReturnTheSameCommittedResult() throws Exception {
    RunRow run = insertRun(TradingLabRunState.DRAFT);
    RunTransitionCommand command = command(
        run.id(),
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        "concurrent-exact-transition",
        "same transition meaning");
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);

    try {
      Future<RunTransitionResult> first = workers.submit(
          () -> transitionAfterBarrier(start, command));
      Future<RunTransitionResult> second = workers.submit(
          () -> transitionAfterBarrier(start, command));

      RunTransitionResult firstResult = first.get(20, SECONDS);
      RunTransitionResult secondResult = second.get(20, SECONDS);

      assertThat(secondResult).isEqualTo(firstResult);
      assertThat(transitionCount(run.id())).isOne();
      assertThat(storedState(run.id())).isEqualTo(TradingLabRunState.VALIDATING.name());
      assertThat(storedVersion(run.id())).isEqualTo(1L);
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void concurrentDifferentMeaningsProduceOneCommitAndOneExplicitIdempotencyConflict()
      throws Exception {
    RunRow run = insertRun(TradingLabRunState.DRAFT);
    String sharedKey = "concurrent-conflicting-transition";
    RunTransitionCommand firstMeaning = command(
        run.id(),
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        sharedKey,
        "first legal meaning");
    RunTransitionCommand secondMeaning = command(
        run.id(),
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        sharedKey,
        "second legal meaning");
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);

    try {
      Future<TransitionAttempt> first = workers.submit(
          () -> attemptTransitionAfterBarrier(start, firstMeaning));
      Future<TransitionAttempt> second = workers.submit(
          () -> attemptTransitionAfterBarrier(start, secondMeaning));

      List<TransitionAttempt> attempts = List.of(
          first.get(20, SECONDS),
          second.get(20, SECONDS));
      List<RunTransitionResult> committed = attempts.stream()
          .map(TransitionAttempt::result)
          .filter(result -> result != null)
          .toList();
      List<String> rejectedCodes = attempts.stream()
          .map(TransitionAttempt::businessCode)
          .filter(code -> code != null)
          .toList();

      assertThat(committed).hasSize(1);
      assertThat(committed.getFirst().reason())
          .isIn(firstMeaning.reason(), secondMeaning.reason());
      assertThat(rejectedCodes).containsExactly("TRADING_LAB_IDEMPOTENCY_CONFLICT");
      assertThat(transitionCount(run.id())).isOne();
      assertThat(storedState(run.id())).isEqualTo(TradingLabRunState.VALIDATING.name());
      assertThat(storedVersion(run.id())).isEqualTo(1L);
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  @Test
  void transitionVersionCollisionRollsBackTheRunCompareAndSetAtomically() {
    RunRow run = insertRun(TradingLabRunState.DRAFT, 9L);
    insertTransition(
        run.id(),
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        10L,
        "preoccupied-next-run-version");
    RunSnapshot before = storedRunSnapshot(run.id());
    RunTransitionCommand command = command(
        run.id(),
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        "must-roll-back-run-cas",
        "insert must collide on run version");

    assertThatThrownBy(() -> transitionService.transition(command))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(storedRunSnapshot(run.id())).isEqualTo(before);
    assertThat(transitionCount(run.id())).isOne();
  }

  private Optional<TradingLabRunClaim> acquireAfterBarrier(
      CyclicBarrier start, String workerInstanceId) throws Exception {
    start.await(10, SECONDS);
    return leaseService.acquireNext(workerInstanceId, LEASE_DURATION);
  }

  private void assertAcquireIsBoundedNoOpWhileLocked(
      UUID lockedRunId,
      String workerInstanceId) throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    CountDownLatch acquisitionStarted = new CountDownLatch(1);

    try {
      try (Connection locker = dataSource.getConnection()) {
        locker.setAutoCommit(false);
        try {
          lockRun(locker, lockedRunId);
          Future<Optional<TradingLabRunClaim>> attempt = worker.submit(() -> {
            acquisitionStarted.countDown();
            return leaseService.acquireNext(workerInstanceId, LEASE_DURATION);
          });
          assertThat(acquisitionStarted.await(5, SECONDS)).isTrue();
          assertThat(attempt.get(2, SECONDS))
              .as("locked priority work must cause a bounded no-op")
              .isEmpty();
        } finally {
          if (!locker.getAutoCommit()) {
            locker.rollback();
          }
        }
      }
    } finally {
      worker.shutdownNow();
      assertThat(worker.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  private RunTransitionResult transitionAfterBarrier(
      CyclicBarrier start,
      RunTransitionCommand command) throws Exception {
    start.await(10, SECONDS);
    return transitionService.transition(command);
  }

  private TransitionAttempt attemptTransitionAfterBarrier(
      CyclicBarrier start,
      RunTransitionCommand command) throws Exception {
    start.await(10, SECONDS);
    try {
      return new TransitionAttempt(transitionService.transition(command), null);
    } catch (BusinessException exception) {
      return new TransitionAttempt(null, exception.getCode());
    }
  }

  private TradingLabRunClaim requireClaim(String workerInstanceId) {
    return leaseService.acquireNext(workerInstanceId, LEASE_DURATION).orElseThrow();
  }

  private RunTransitionCommand command(
      UUID runId,
      TradingLabRunState expected,
      TradingLabRunState target,
      String idempotencyKey,
      String reason) {
    return new RunTransitionCommand(
        runId, expected, target, idempotencyKey, reason, Instant.parse("2026-07-19T00:00:00Z"));
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'queue-it-hash', 'ACTIVE', 'ADMIN')
        """, id, "actor-" + id + "@queue-it.test");
    return id;
  }

  private UUID insertScenario(UUID creatorId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version)
        values (
          ?, 'Queue repository IT', 'DRAFT', false, 'queue-it-seed', 'queue-it-model',
          '{"seed":"queue-it-seed"}'::jsonb, '{}'::jsonb, ?,
          'queue-it-symbols', 'queue-it-code', ?, ?, 0)
        """, id, "a".repeat(64), creatorId, creatorId);
    return id;
  }

  private RunRow insertRun(TradingLabRunState state) {
    return insertRun(state, 0L);
  }

  private RunRow insertRun(TradingLabRunState state, long version) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version, created_by, version)
        values (
          ?, ?, ?,
          false, false,
          0, 10, 1.000000, 0,
          '{}'::jsonb, '{}'::jsonb, ?,
          'queue-it-model', 'queue-it-symbols', 'queue-it-code', ?, ?)
        """, id, scenarioId, state.name(), "b".repeat(64), actorId, version);
    return new RunRow(id, queueSequence(id));
  }

  private RunRow insertLeasedRun(
      TradingLabRunState state,
      String owner,
      int leaseOffsetSeconds,
      long version) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.runs (
          id, scenario_id, state, lease_key, lease_owner, lease_until,
          cancel_requested, pause_requested,
          processed_ticks, total_ticks, speed_multiplier, current_step,
          scenario_snapshot_json, config_snapshot_json, config_snapshot_hash,
          model_version, symbol_config_version, code_version, created_by, version)
        values (
          ?, ?, ?, 1, ?, clock_timestamp() + (? * interval '1 second'),
          false, false,
          0, 10, 1.000000, 0,
          '{}'::jsonb, '{}'::jsonb, ?,
          'queue-it-model', 'queue-it-symbols', 'queue-it-code', ?, ?)
        """,
        id,
        scenarioId,
        state.name(),
        owner,
        leaseOffsetSeconds,
        "c".repeat(64),
        actorId,
        version);
    return new RunRow(id, queueSequence(id));
  }

  private void insertTransition(
      UUID runId,
      TradingLabRunState from,
      TradingLabRunState to,
      long runVersion,
      String idempotencyKey) {
    jdbcTemplate.update("""
        insert into trading_lab.run_transitions (
          id, run_id, from_state, to_state, run_version,
          reason, idempotency_key, virtual_time, actor_id, details_json)
        values (?, ?, ?, ?, ?, 'queue fixture', ?, null, ?, '{}'::jsonb)
        """,
        UUID.randomUUID(),
        runId,
        from.name(),
        to.name(),
        runVersion,
        idempotencyKey,
        actorId);
  }

  private void expireLease(UUID runId, String owner) {
    int changed = jdbcTemplate.update("""
        update trading_lab.runs
        set lease_until = clock_timestamp() - interval '1 second'
        where id = ? and lease_key = 1 and lease_owner = ?
        """, runId, owner);
    assertThat(changed).isOne();
  }

  private void lockRun(Connection connection, UUID runId) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("""
        select id
        from trading_lab.runs
        where id = ?
        for update
        """)) {
      statement.setObject(1, runId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  private long queueSequence(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select queue_sequence from trading_lab.runs where id = ?", Long.class, runId);
  }

  private long storedVersion(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select version from trading_lab.runs where id = ?", Long.class, runId);
  }

  private String storedState(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select state from trading_lab.runs where id = ?", String.class, runId);
  }

  private RunSnapshot storedRunSnapshot(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select state, version, updated_at from trading_lab.runs where id = ?",
        (row, rowNumber) -> new RunSnapshot(
            row.getString("state"),
            row.getLong("version"),
            row.getTimestamp("updated_at").toInstant()),
        runId);
  }

  private String leaseOwner(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select lease_owner from trading_lab.runs where id = ?", String.class, runId);
  }

  private boolean leaseFieldsAreCleared(UUID runId) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select lease_key is null and lease_owner is null and lease_until is null
        from trading_lab.runs
        where id = ?
        """, Boolean.class, runId));
  }

  private boolean databaseLeaseIsLive(UUID runId) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select lease_key = 1 and lease_until > clock_timestamp()
        from trading_lab.runs
        where id = ?
        """, Boolean.class, runId));
  }

  private long singletonLeaseCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from trading_lab.runs where lease_key = 1", Long.class);
  }

  private long transitionCount(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from trading_lab.run_transitions where run_id = ?", Long.class, runId);
  }

  private void deleteTradingLabFixture() {
    jdbcTemplate.update("delete from trading_lab.audit_events");
    jdbcTemplate.update("delete from trading_lab.report_chunks");
    jdbcTemplate.update("delete from trading_lab.run_events");
    jdbcTemplate.update("delete from trading_lab.run_transitions");
    jdbcTemplate.update("delete from trading_lab.runs");
    jdbcTemplate.update("delete from trading_lab.reports");
    jdbcTemplate.update("delete from trading_lab.scenarios");
  }

  private record RunRow(UUID id, long queueSequence) {}

  private record RunSnapshot(String state, long version, Instant updatedAt) {}

  private record TransitionAttempt(RunTransitionResult result, String businessCode) {}
}
