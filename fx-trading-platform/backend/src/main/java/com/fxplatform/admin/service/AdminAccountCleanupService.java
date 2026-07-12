package com.fxplatform.admin.service;

import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Coordinates account-wide cancellation followed by canonical Admin-origin closes. */
@Service
public class AdminAccountCleanupService {

  private final CancelAllOrderService cancelAllOrderService;
  private final CloseAllPositionService closeAllPositionService;
  private final AuditLogService auditLogService;
  private TradingAccountRepository accountRepository;
  private WalletBalanceRepository walletBalanceRepository;
  private PositionRepository positionRepository;
  private OrderRepository orderRepository;
  private TradingTransactionExecutor transactionExecutor;
  private DemoExecutionGuard demoExecutionGuard;
  private LiquidationSettlementService liquidationSettlementService;

  public AdminAccountCleanupService(
      CancelAllOrderService cancelAllOrderService,
      CloseAllPositionService closeAllPositionService,
      AuditLogService auditLogService
  ) {
    this.cancelAllOrderService = cancelAllOrderService;
    this.closeAllPositionService = closeAllPositionService;
    this.auditLogService = auditLogService;
  }

  @Autowired
  public void setCleanupStateDependencies(
      TradingAccountRepository accountRepository,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      TradingTransactionExecutor transactionExecutor,
      DemoExecutionGuard demoExecutionGuard,
      LiquidationSettlementService liquidationSettlementService
  ) {
    this.accountRepository = accountRepository;
    this.walletBalanceRepository = walletBalanceRepository;
    this.positionRepository = positionRepository;
    this.orderRepository = orderRepository;
    this.transactionExecutor = transactionExecutor;
    this.demoExecutionGuard = demoExecutionGuard;
    this.liquidationSettlementService = liquidationSettlementService;
  }

  public BatchActionResponse cleanup(
      UUID actorUserId,
      UUID accountId,
      String reason,
      String requestId
  ) {
    requireId(actorUserId, "Admin actor is required");
    requireId(accountId, "Account id is required");
    String normalizedReason = requireText(
        reason,
        "ADMIN_CLEANUP_REASON_REQUIRED",
        "Admin cleanup reason is required");
    String normalizedRequestId = requireText(
        requestId,
        "ADMIN_CLEANUP_REQUEST_ID_REQUIRED",
        "Admin cleanup request id is required");
    UUID auditRequestId = parseRequestId(normalizedRequestId);

    auditLogService.recordWithRequestId(
        actorUserId,
        "ADMIN_ACCOUNT_FORCE_CLEANUP_REQUESTED",
        "TRADING_ACCOUNT",
        accountId.toString(),
        auditRequestId,
        AuditDetailsBuilder.create()
            .put("reason", normalizedReason)
            .put("requestId", normalizedRequestId)
            .toJson());

    freezeAccountForCleanup(accountId);

    BatchActionResponse canceled = cancelAllOrderService.cancelForCleanup(
        accountId,
        normalizedRequestId);
    BatchActionResponse closed = closeAllPositionService.closeSystem(
        accountId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        normalizedReason,
        normalizedRequestId);

    List<BatchActionResponse.Item> items = new ArrayList<>();
    items.addAll(items(canceled));
    items.addAll(items(closed));
    BatchActionResponse response = new BatchActionResponse(
        accountId,
        normalizedRequestId,
        items);
    restorePendingAccountAfterSuccessfulCleanup(response);
    auditLogService.recordWithRequestId(
        actorUserId,
        "ADMIN_ACCOUNT_FORCE_CLEANUP",
        "TRADING_ACCOUNT",
        accountId.toString(),
        auditRequestId,
        AuditDetailsBuilder.create()
            .put("reason", normalizedReason)
            .put("requestId", normalizedRequestId)
            .put("itemCount", response.items().size())
            .toJson());
    return response;
  }

  private void restorePendingAccountAfterSuccessfulCleanup(BatchActionResponse response) {
    if (response.items().stream().anyMatch(item -> "FAILED".equals(item.status()))
        || accountRepository == null) {
      return;
    }
    if (liquidationSettlementService != null
        && !liquidationSettlementService.settleIfReadyKeepingPending(response.accountId())) {
      return;
    }
    transactionExecutor.execute(() -> {
      var account = accountRepository.findByIdForUpdate(response.accountId())
          .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
      demoExecutionGuard.requireDemoRiskReductionAccount(account);
      walletBalanceRepository.findByAccountIdForUpdate(response.accountId());
      boolean hasOpenPositions = !positionRepository
          .findOpenByAccountIdForUpdate(response.accountId()).isEmpty();
      boolean hasActiveOrders = !orderRepository
          .findActiveByAccountIdForUpdate(response.accountId()).isEmpty();
      if (hasOpenPositions || hasActiveOrders) {
        return null;
      }
      if (account.getStatus() == AccountStatus.LIQUIDATION_PENDING
          || account.getStatus() == AccountStatus.RISK_REDUCTION_PENDING) {
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
      }
      return null;
    });
  }

  private void freezeAccountForCleanup(UUID accountId) {
    if (accountRepository == null
        || walletBalanceRepository == null
        || positionRepository == null
        || orderRepository == null
        || transactionExecutor == null
        || demoExecutionGuard == null
        || liquidationSettlementService == null) {
      throw new IllegalStateException("Admin cleanup state dependencies are required");
    }
    transactionExecutor.execute(() -> {
      var account = accountRepository.findByIdForUpdate(accountId)
          .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
      demoExecutionGuard.requireDemoRiskReductionAccount(account);
      walletBalanceRepository.findByAccountIdForUpdate(accountId);
      positionRepository.findOpenByAccountIdForUpdate(accountId);
      orderRepository.findActiveByAccountIdForUpdate(accountId);
      if (account.getStatus() != AccountStatus.RISK_REDUCTION_PENDING) {
        account.setStatus(AccountStatus.RISK_REDUCTION_PENDING);
        accountRepository.save(account);
      }
      return null;
    });
  }

  private static List<BatchActionResponse.Item> items(BatchActionResponse response) {
    return response == null || response.items() == null ? List.of() : response.items();
  }

  private static UUID parseRequestId(String requestId) {
    try {
      return UUID.fromString(requestId);
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(
          "ADMIN_CLEANUP_REQUEST_ID_INVALID",
          "Admin cleanup request id must be a UUID");
    }
  }

  private static String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message);
    }
    return value.trim();
  }

  private static void requireId(UUID value, String message) {
    if (value == null) {
      throw new BusinessException("ADMIN_CLEANUP_SCOPE_REQUIRED", message);
    }
  }
}
