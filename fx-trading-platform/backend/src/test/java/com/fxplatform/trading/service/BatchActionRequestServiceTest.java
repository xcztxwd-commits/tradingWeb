package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.BatchActionRequestEntity;
import com.fxplatform.trading.repository.BatchActionRequestRepository;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchActionRequestServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

  @Mock BatchActionRequestRepository repository;
  @Mock TradingTransactionExecutor transactionExecutor;

  private final AtomicReference<BatchActionRequestEntity> stored = new AtomicReference<>();
  private BatchActionRequestService service;

  @BeforeEach
  void setUp() {
    when(transactionExecutor.execute(any())).thenAnswer(
        invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    when(repository.findForUpdate(any(), any(), any()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(repository.insertIfAbsent(any())).thenAnswer(invocation -> {
      BatchActionRequestEntity candidate = invocation.getArgument(0);
      return stored.compareAndSet(null, candidate) ? 1 : 0;
    });
    lenient().when(repository.claimExpired(
        any(), any(), any(Instant.class), any(Instant.class))).thenAnswer(invocation -> {
      BatchActionRequestEntity row = stored.get();
      if (!"PROCESSING".equals(row.getStatus()) || row.getLeaseUntil().isAfter(NOW)) {
        return 0;
      }
      row.setOwnerToken(invocation.getArgument(1));
      row.setLeaseUntil(invocation.getArgument(2));
      return 1;
    });
    lenient().when(repository.renewLease(
        any(), any(), any(Instant.class), any(Instant.class))).thenAnswer(invocation -> {
      BatchActionRequestEntity row = stored.get();
      UUID ownerToken = invocation.getArgument(1);
      Instant now = invocation.getArgument(3);
      if (!"PROCESSING".equals(row.getStatus())
          || !row.getOwnerToken().equals(ownerToken)
          || !row.getLeaseUntil().isAfter(now)) {
        return 0;
      }
      row.setLeaseUntil(invocation.getArgument(2));
      return 1;
    });
    lenient().when(repository.markCompleted(
        any(), any(), any(), any(Instant.class))).thenAnswer(invocation -> {
      BatchActionRequestEntity row = stored.get();
      if (!row.getOwnerToken().equals(invocation.getArgument(1))) {
        return 0;
      }
      row.setStatus("COMPLETED");
      row.setResponsePayload(invocation.getArgument(2));
      return 1;
    });
    service = new BatchActionRequestService(
        repository,
        new ObjectMapper().findAndRegisterModules(),
        transactionExecutor,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void completedReplayReturnsStrictPersistedResponseWithoutEvaluatingLaterScope() {
    UUID accountId = id(1);
    UUID first = id(11);
    UUID second = id(22);
    String requestId = "batch-replay-1";

    BatchActionRequestService.Execution execution = service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        requestId,
        "origin=BATCH_CLOSE|reason=USER_CLOSE_ALL",
        () -> List.of(second, first, second));
    assertThat(execution.scopeIds()).containsExactly(second, first);
    assertThat(execution.completedResponse()).isNull();

    BatchActionResponse response = new BatchActionResponse(
        accountId,
        requestId,
        List.of(
            new BatchActionResponse.Item(second, null, "FAILED", "MARKET_DATA_STALE", null),
            new BatchActionResponse.Item(first, id(101), "FILLED", null, null)));
    service.completeIndependent(execution, response);

    AtomicBoolean lateScopeWasRead = new AtomicBoolean();
    BatchActionRequestService.Execution replay = service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        requestId,
        "origin=BATCH_CLOSE|reason=USER_CLOSE_ALL",
        () -> {
          lateScopeWasRead.set(true);
          return List.of(id(33));
        });

    assertThat(lateScopeWasRead).isFalse();
    assertThat(replay.scopeIds()).containsExactly(second, first);
    assertThat(replay.completedResponse()).isEqualTo(response);
    assertThat(stored.get().getScopeIds()).startsWith("[\"000");
    assertThat(stored.get().getResponsePayload()).contains("MARKET_DATA_STALE");
  }

  @Test
  void sameAccountActionAndRequestRejectsChangedOriginOrReasonFingerprint() {
    UUID accountId = id(2);
    service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        "same-request",
        "origin=ADMIN_FORCE_CLOSE|reason=case-a",
        () -> List.of(id(44)));

    assertThatThrownBy(() -> service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        "same-request",
        "origin=ADMIN_FORCE_CLOSE|reason=case-b",
        () -> List.of(id(55))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("BATCH_REQUEST_CONFLICT"));
  }

  @Test
  void activeLeaseRejectsConcurrentReplayWithoutEvaluatingItsScope() {
    UUID accountId = id(3);
    BatchActionRequestService.Execution owner = service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        "active-lease",
        "origin=BATCH_CLOSE|reason=USER_CLOSE_ALL",
        () -> List.of(id(66)));
    AtomicBoolean concurrentScopeWasRead = new AtomicBoolean();

    assertThatThrownBy(() -> service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        "active-lease",
        "origin=BATCH_CLOSE|reason=USER_CLOSE_ALL",
        () -> {
          concurrentScopeWasRead.set(true);
          return List.of(id(77));
        }))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("BATCH_REQUEST_IN_PROGRESS"));

    assertThat(concurrentScopeWasRead).isFalse();
    assertThat(owner.ownerToken()).isNotNull();
    assertThat(owner.leaseUntil()).isAfter(NOW);
  }

  @Test
  void expiredLeaseIsClaimedByOneNewOwnerAndOldOwnerCannotComplete() {
    UUID accountId = id(4);
    UUID positionId = id(88);
    String requestId = "expired-lease";
    BatchActionRequestService.Execution oldOwner = service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        requestId,
        "origin=BATCH_CLOSE|reason=USER_CLOSE_ALL",
        () -> List.of(positionId));
    stored.get().setLeaseUntil(NOW.minusSeconds(1));

    BatchActionRequestService.Execution newOwner = service.beginIndependent(
        accountId,
        "CLOSE_ALL",
        requestId,
        "origin=BATCH_CLOSE|reason=USER_CLOSE_ALL",
        () -> List.of(id(99)));
    assertThat(newOwner.ownerToken()).isNotEqualTo(oldOwner.ownerToken());
    assertThat(newOwner.scopeIds()).containsExactly(positionId);

    assertThatThrownBy(() -> service.renewOwnershipCurrent(oldOwner))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("BATCH_REQUEST_OWNERSHIP_LOST"));
    service.renewOwnershipCurrent(newOwner);

    BatchActionResponse response = new BatchActionResponse(
        accountId,
        requestId,
        List.of(new BatchActionResponse.Item(positionId, id(188), "FILLED", null, null)));
    assertThatThrownBy(() -> service.completeIndependent(oldOwner, response))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("BATCH_REQUEST_OWNERSHIP_LOST"));

    service.completeIndependent(newOwner, response);
    assertThat(stored.get().getStatus()).isEqualTo("COMPLETED");
  }

  private static UUID id(long suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }
}
