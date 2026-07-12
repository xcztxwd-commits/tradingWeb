package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.CancelAllOrderService;
import com.fxplatform.trading.service.CloseAllPositionService;
import com.fxplatform.trading.service.LiquidationSettlementService;
import com.fxplatform.trading.service.TradingTransactionExecutor;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAccountCleanupServiceTest {

  @Mock
  private CancelAllOrderService cancelAllOrderService;

  @Mock
  private CloseAllPositionService closeAllPositionService;

  @Mock
  private AuditLogService auditLogService;

  @Mock private TradingAccountRepository accountRepository;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private OrderRepository orderRepository;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private LiquidationSettlementService liquidationSettlementService;

  @BeforeEach
  void setUpCleanupGate() {
    lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation ->
        ((Supplier<?>) invocation.getArgument(0)).get());
    lenient().when(accountRepository.findByIdForUpdate(any())).thenAnswer(invocation -> {
      TradingAccountEntity account = new TradingAccountEntity();
      account.setId(invocation.getArgument(0));
      account.setStatus(AccountStatus.ACTIVE);
      return Optional.of(account);
    });
    lenient().when(walletBalanceRepository.findByAccountIdForUpdate(any()))
        .thenReturn(List.of());
    lenient().when(positionRepository.findOpenByAccountIdForUpdate(any()))
        .thenReturn(List.of());
    lenient().when(orderRepository.findActiveByAccountIdForUpdate(any()))
        .thenReturn(List.of());
    lenient().when(liquidationSettlementService.settleIfReadyKeepingPending(any()))
        .thenReturn(true);
  }

  @Test
  void cleanupCancelsBeforeAdminOriginCloseAndAuditsActorReasonAndRequest() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID canceledOrderId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    UUID closeOrderId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setStatus(AccountStatus.ACTIVE);
    BatchActionResponse canceled = response(accountId, requestId, List.of(
        new BatchActionResponse.Item(
            null, canceledOrderId, "CANCELED", null, null)));
    BatchActionResponse closed = response(accountId, requestId, List.of(
        new BatchActionResponse.Item(
            positionId, closeOrderId, "FILLED", null, null)));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(cancelAllOrderService.cancelForCleanup(accountId, requestId.toString()))
        .thenAnswer(invocation -> {
          assertThat(account.getStatus()).isEqualTo(AccountStatus.RISK_REDUCTION_PENDING);
          return canceled;
        });
    when(closeAllPositionService.closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "account cleanup requested",
        requestId.toString()))
        .thenAnswer(invocation -> {
          assertThat(account.getStatus()).isEqualTo(AccountStatus.RISK_REDUCTION_PENDING);
          return closed;
        });
    when(liquidationSettlementService.settleIfReadyKeepingPending(accountId))
        .thenAnswer(invocation -> {
          assertThat(account.getStatus()).isEqualTo(AccountStatus.RISK_REDUCTION_PENDING);
          return true;
        });

    BatchActionResponse result = configuredService().cleanup(
        actorUserId,
        accountId,
        "account cleanup requested",
        requestId.toString());

    assertThat(result.accountId()).isEqualTo(accountId);
    assertThat(result.requestId()).isEqualTo(requestId.toString());
    assertThat(result.items()).containsExactlyElementsOf(
        List.of(canceled.items().getFirst(), closed.items().getFirst()));
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    InOrder ordered = inOrder(cancelAllOrderService, closeAllPositionService, auditLogService);
    ordered.verify(auditLogService).recordWithRequestId(
        eq(actorUserId),
        eq("ADMIN_ACCOUNT_FORCE_CLEANUP_REQUESTED"),
        eq("TRADING_ACCOUNT"),
        eq(accountId.toString()),
        eq(requestId),
        contains("account cleanup requested"));
    ordered.verify(cancelAllOrderService).cancelForCleanup(accountId, requestId.toString());
    ordered.verify(closeAllPositionService).closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "account cleanup requested",
        requestId.toString());
    ordered.verify(auditLogService).recordWithRequestId(
        eq(actorUserId),
        eq("ADMIN_ACCOUNT_FORCE_CLEANUP"),
        eq("TRADING_ACCOUNT"),
        eq(accountId.toString()),
        eq(requestId),
        contains("account cleanup requested"));
  }

  @Test
  void incompleteCleanupPreservesCloseItemErrorForAdminFollowUp() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    when(cancelAllOrderService.cancelForCleanup(accountId, requestId.toString()))
        .thenReturn(response(accountId, requestId, List.of()));
    when(closeAllPositionService.closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "stale symbol cleanup",
        requestId.toString()))
        .thenReturn(response(accountId, requestId, List.of(
            new BatchActionResponse.Item(
                positionId,
                null,
                "FAILED",
                "MARKET_DATA_STALE",
                "Market data is stale"))));

    BatchActionResponse result = configuredService().cleanup(
        actorUserId,
        accountId,
        "stale symbol cleanup",
        requestId.toString());

    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.positionId()).isEqualTo(positionId);
      assertThat(item.status()).isEqualTo("FAILED");
      assertThat(item.errorCode()).isEqualTo("MARKET_DATA_STALE");
    });
  }

  @Test
  void replayPassesTheSameRequestToBothIdempotentBatchServices() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    BatchActionResponse canceled = response(accountId, requestId, List.of());
    BatchActionResponse closed = response(accountId, requestId, List.of(
        new BatchActionResponse.Item(
            UUID.randomUUID(), UUID.randomUUID(), "FILLED", null, null)));
    when(cancelAllOrderService.cancelForCleanup(accountId, requestId.toString()))
        .thenReturn(canceled);
    when(closeAllPositionService.closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "replay cleanup",
        requestId.toString()))
        .thenReturn(closed);

    BatchActionResponse first = configuredService().cleanup(
        actorUserId, accountId, "replay cleanup", requestId.toString());
    BatchActionResponse replay = configuredService().cleanup(
        actorUserId, accountId, "replay cleanup", requestId.toString());

    assertThat(replay).isEqualTo(first);
    verify(cancelAllOrderService, times(2))
        .cancelForCleanup(accountId, requestId.toString());
    verify(closeAllPositionService, times(2)).closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "replay cleanup",
        requestId.toString());
  }

  @Test
  void cleanupRejectsBlankReasonBeforeAnyMutation() {
    assertThatThrownBy(() -> configuredService().cleanup(
        UUID.randomUUID(), UUID.randomUUID(), " ", UUID.randomUUID().toString()))
        .isInstanceOf(com.fxplatform.common.exception.BusinessException.class);

    verifyNoInteractions(cancelAllOrderService, closeAllPositionService, auditLogService);
  }

  @Test
  void cleanupRejectsBlankRequestIdBeforeAnyMutation() {
    assertThatThrownBy(() -> configuredService().cleanup(
        UUID.randomUUID(), UUID.randomUUID(), "account cleanup", " "))
        .isInstanceOf(com.fxplatform.common.exception.BusinessException.class);

    verifyNoInteractions(cancelAllOrderService, closeAllPositionService, auditLogService);
  }

  @Test
  void successfulPendingCleanupSettlesChargesBeforeRestoringActive() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setStatus(AccountStatus.LIQUIDATION_PENDING);
    when(cancelAllOrderService.cancelForCleanup(accountId, requestId.toString()))
        .thenAnswer(invocation -> {
          assertThat(account.getStatus()).isEqualTo(AccountStatus.RISK_REDUCTION_PENDING);
          return response(accountId, requestId, List.of());
        });
    when(closeAllPositionService.closeSystem(
        accountId, OrderOrigin.ADMIN_FORCE_CLOSE, "finish pending cleanup", requestId.toString()))
        .thenReturn(response(accountId, requestId, List.of()));
    when(liquidationSettlementService.settleIfReadyKeepingPending(accountId)).thenReturn(true);
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(walletBalanceRepository.findByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(positionRepository.findOpenByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(orderRepository.findActiveByAccountIdForUpdate(accountId)).thenReturn(List.of());

    configuredService().cleanup(
        actorUserId, accountId, "finish pending cleanup", requestId.toString());

    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    InOrder settlementBeforeRestore = inOrder(
        liquidationSettlementService, transactionExecutor, accountRepository);
    settlementBeforeRestore.verify(liquidationSettlementService)
        .settleIfReadyKeepingPending(accountId);
    settlementBeforeRestore.verify(transactionExecutor).execute(any());
    settlementBeforeRestore.verify(accountRepository).findByIdForUpdate(accountId);
    verify(accountRepository, times(2)).save(account);
  }

  @Test
  void pendingCleanupDoesNotRestoreAccountWhenChargesCannotSettle() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    when(cancelAllOrderService.cancelForCleanup(accountId, requestId.toString()))
        .thenReturn(response(accountId, requestId, List.of()));
    when(closeAllPositionService.closeSystem(
        accountId, OrderOrigin.ADMIN_FORCE_CLOSE, "pending charge", requestId.toString()))
        .thenReturn(response(accountId, requestId, List.of()));
    when(liquidationSettlementService.settleIfReadyKeepingPending(accountId)).thenReturn(false);

    configuredService().cleanup(actorUserId, accountId, "pending charge", requestId.toString());

    verify(transactionExecutor, times(1)).execute(any());
    verify(accountRepository, times(1)).findByIdForUpdate(accountId);
  }

  private AdminAccountCleanupService service() {
    return new AdminAccountCleanupService(
        cancelAllOrderService,
        closeAllPositionService,
        auditLogService);
  }

  private AdminAccountCleanupService configuredService() {
    AdminAccountCleanupService service = service();
    service.setCleanupStateDependencies(
        accountRepository,
        walletBalanceRepository,
        positionRepository,
        orderRepository,
        transactionExecutor,
        demoExecutionGuard,
        liquidationSettlementService);
    return service;
  }

  private BatchActionResponse response(
      UUID accountId,
      UUID requestId,
      List<BatchActionResponse.Item> items
  ) {
    return new BatchActionResponse(accountId, requestId.toString(), items);
  }
}
