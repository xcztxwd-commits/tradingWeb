package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically cancels one locked account scope and releases each authoritative hold once. */
@Service
public class CancelAllOrderService {

  private final TradingAccountRepository accountRepository;
  private final WalletBalanceRepository walletBalanceRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final WalletService walletService;
  private final LedgerService ledgerService;
  private final OrderEventService orderEventService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final TradingTransactionExecutor transactionExecutor;
  private final BatchActionRequestService batchActionRequestService;

  @Autowired
  public CancelAllOrderService(
      TradingAccountRepository accountRepository,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      WalletService walletService,
      LedgerService ledgerService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      TradingTransactionExecutor transactionExecutor,
      BatchActionRequestService batchActionRequestService
  ) {
    this.accountRepository = accountRepository;
    this.walletBalanceRepository = walletBalanceRepository;
    this.positionRepository = positionRepository;
    this.orderRepository = orderRepository;
    this.walletService = walletService;
    this.ledgerService = ledgerService;
    this.orderEventService = orderEventService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.transactionExecutor = transactionExecutor;
    this.batchActionRequestService = batchActionRequestService;
  }

  /** Compatibility constructor for focused legacy fixtures. */
  public CancelAllOrderService(
      TradingAccountRepository accountRepository,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      WalletService walletService,
      LedgerService ledgerService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        accountRepository,
        walletBalanceRepository,
        positionRepository,
        orderRepository,
        walletService,
        ledgerService,
        orderEventService,
        demoExecutionGuard,
        transactionExecutor,
        null);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BatchActionResponse cancelUser(
      UUID userId,
      UUID accountId,
      String requestId
  ) {
    requireId(userId, "User id is required");
    requireId(accountId, "Account id is required");
    String normalizedRequestId = requireRequestId(requestId);
    return transactionExecutor.execute(() -> cancelLocked(
        userId,
        accountId,
        normalizedRequestId,
        CancellationScope.ALL,
        null));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BatchActionResponse cancelForCleanup(UUID accountId, String requestId) {
    requireId(accountId, "Account id is required");
    String normalizedRequestId = requireRequestId(requestId);
    return transactionExecutor.execute(() -> cancelLocked(
        null,
        accountId,
        normalizedRequestId,
        CancellationScope.ALL,
        null));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void cancelRiskIncreasingSlot(UUID accountId, UUID positionId) {
    requireId(accountId, "Account id is required");
    requireId(positionId, "Position id is required");
    transactionExecutor.execute(() -> cancelLocked(
        null,
        accountId,
        "liquidation-slot:" + positionId,
        CancellationScope.RISK_INCREASING_SLOT,
        positionId));
  }

  /** Cancels every active Perpetual order in one position slot after liquidation is confirmed. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void cancelActiveSlot(UUID accountId, UUID positionId) {
    requireId(accountId, "Account id is required");
    requireId(positionId, "Position id is required");
    transactionExecutor.execute(() -> cancelLocked(
        null,
        accountId,
        "liquidation-slot-drain:" + positionId,
        CancellationScope.ACTIVE_SLOT,
        positionId));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void cancelActivePerpetual(UUID accountId) {
    requireId(accountId, "Account id is required");
    transactionExecutor.execute(() -> cancelLocked(
        null,
        accountId,
        "liquidation-account:" + accountId,
        CancellationScope.ACTIVE_LINEAR_PERPETUAL,
        null));
  }

  private BatchActionResponse cancelLocked(
      UUID userId,
      UUID accountId,
      String requestId,
      CancellationScope scope,
      UUID positionId
  ) {
    TradingAccountEntity account = userId == null
        ? accountRepository.findByIdForUpdate(accountId)
            .orElseThrow(CancelAllOrderService::accountNotFound)
        : accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
            .orElseThrow(CancelAllOrderService::accountNotFound);
    if (userId == null) {
      demoExecutionGuard.requireDemoRiskReductionAccount(account);
      if (scope != CancellationScope.ALL
          && account.getStatus() == AccountStatus.RISK_REDUCTION_PENDING) {
        throw new BusinessException(
            "ACCOUNT_CLEANUP_PENDING",
            "Liquidation cancellation is suspended while Admin cleanup owns the account");
      }
    } else {
      demoExecutionGuard.requireDemoAccount(account);
    }

    // The account-wide ordering is part of the deadlock-prevention contract.
    walletBalanceRepository.findByAccountIdForUpdate(accountId);
    List<PositionEntity> lockedPositions = safeList(
        positionRepository.findOpenByAccountIdForUpdate(accountId));
    PositionEntity targetPosition = scope == CancellationScope.RISK_INCREASING_SLOT
            || scope == CancellationScope.ACTIVE_SLOT
        ? requireTargetPosition(accountId, positionId, lockedPositions)
        : null;
    List<OrderEntity> lockedOrders = scope == CancellationScope.ALL
        ? safeList(orderRepository.findActiveByAccountIdForUpdate(accountId))
        : safeList(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId));

    List<OrderEntity> selected = lockedOrders.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(OrderEntity::getId))
        .filter(order -> selectedBy(scope, targetPosition, order))
        .toList();
    validateLockedSnapshot(accountId, scope, selected);

    BatchActionRequestService.Execution batchExecution = null;
    if (scope == CancellationScope.ALL && batchActionRequestService != null) {
      String origin = userId == null ? "ADMIN_CLEANUP" : "USER";
      batchExecution = batchActionRequestService.beginCurrent(
          accountId,
          BatchActionRequestService.ACTION_CANCEL_ALL,
          requestId,
          "origin=" + origin + "|reason=CANCEL_ALL",
          selected.stream().map(OrderEntity::getId).toList());
      if (batchExecution.completedResponse() != null) {
        return batchExecution.completedResponse();
      }
      Set<UUID> frozenIds = Set.copyOf(batchExecution.scopeIds());
      selected = selected.stream()
          .filter(order -> frozenIds.contains(order.getId()))
          .toList();
      if (selected.size() != frozenIds.size()) {
        throw new BusinessException(
            "BATCH_REQUEST_CONFLICT",
            "Frozen cancel scope no longer matches the locked request scope");
      }
    }

    Set<UUID> seenOrders = new HashSet<>();
    Set<UUID> releasedHoldOwners = new HashSet<>();
    List<BatchActionResponse.Item> items = new ArrayList<>(selected.size());
    for (OrderEntity order : selected) {
      if (!seenOrders.add(order.getId())) {
        throw new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "The locked cancel scope contains a duplicate order");
      }
      if (terminal(order.getStatus())) {
        continue;
      }
      OrderStatus fromStatus = order.getStatus();
      releaseAuthorityHold(account, order, releasedHoldOwners);
      order.setStatus(OrderStatus.CANCELED);
      order.setCanceledAt(Instant.now());
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setVersion(version(order) + 1L);
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          fromStatus,
          OrderStatus.CANCELED,
          null,
          "Order canceled by account batch action");
      items.add(new BatchActionResponse.Item(
          null,
          order.getId(),
          OrderStatus.CANCELED.name(),
          null,
          null));
    }
    BatchActionResponse response = new BatchActionResponse(accountId, requestId, items);
    if (batchExecution != null) {
      batchActionRequestService.completeCurrent(batchExecution, response);
    }
    return response;
  }

  private void releaseAuthorityHold(
      TradingAccountEntity account,
      OrderEntity order,
      Set<UUID> releasedHoldOwners
  ) {
    BigDecimal hold = orZero(order.getHoldAmount());
    if (hold.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    UUID authorityId = order.getHoldOwnerOrderId() == null
        ? order.getId()
        : order.getHoldOwnerOrderId();
    if (!authorityId.equals(order.getId())) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "A non-owner order cannot release a shared hold");
    }
    if (!releasedHoldOwners.add(authorityId)) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "The authoritative order hold was encountered more than once");
    }

    if (order.getProductType() == ProductType.CRYPTO_SPOT) {
      if (order.getHoldCurrency() == null || order.getHoldCurrency().isBlank()) {
        throw new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "A Spot order hold requires its asset");
      }
      walletService.releaseLockedWithEntryType(
          account.getId(),
          order.getHoldCurrency(),
          hold,
          "ORDER",
          authorityId,
          "Spot order hold released by account batch cancellation",
          "SPOT_ORDER_RELEASE");
      return;
    }

    if (order.getProductType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Only Demo Spot and Linear Perpetual orders may be canceled");
    }
    BigDecimal usedMargin = orZero(account.getUsedMargin());
    if (usedMargin.compareTo(hold) < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "The Perpetual order hold exceeds locked account margin");
    }
    account.setUsedMargin(usedMargin.subtract(hold));
    if (order.getMarginMode() == MarginMode.CROSS) {
      account.setFreeMargin(orZero(account.getFreeMargin()).add(hold));
    } else if (order.getMarginMode() == MarginMode.ISOLATED) {
      boolean positionBacked = order.getParentPositionId() != null;
      if (positionBacked && !Boolean.TRUE.equals(order.getReduceOnly())) {
        throw new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "A position-backed Isolated hold must be reduce-only");
      }
      if (!positionBacked) {
        account.setFreeMargin(orZero(account.getFreeMargin()).add(hold));
      }
    } else {
      throw new BusinessException(
          ErrorCode.INVALID_MARGIN_MODE,
          "A Perpetual order hold requires Cross or Isolated margin mode");
    }
    accountRepository.save(account);
    ledgerService.recordOrderRelease(
        account,
        hold,
        authorityId,
        "Perpetual order hold released by account batch cancellation");
  }

  private static void validateLockedSnapshot(
      UUID accountId,
      CancellationScope scope,
      List<OrderEntity> orders
  ) {
    for (OrderEntity order : orders) {
      if (order.getId() == null || !accountId.equals(order.getAccountId())) {
        throw new BusinessException(
            ErrorCode.ACCOUNT_NOT_FOUND,
            "The locked order does not belong to the requested account");
      }
      if (order.getProductType() != ProductType.CRYPTO_SPOT
          && order.getProductType() != ProductType.LINEAR_PERP) {
        throw new BusinessException(
            ErrorCode.PRODUCT_NOT_ALLOWED,
            "Only Demo Spot and Linear Perpetual orders may be canceled");
      }
      if (scope != CancellationScope.ALL
          && order.getProductType() != ProductType.LINEAR_PERP) {
        throw new BusinessException(
            ErrorCode.PRODUCT_NOT_ALLOWED,
            "The Perpetual cancellation scope contains a non-Perpetual order");
      }
    }
  }

  private static PositionEntity requireTargetPosition(
      UUID accountId,
      UUID positionId,
      List<PositionEntity> positions
  ) {
    return positions.stream()
        .filter(Objects::nonNull)
        .filter(position -> positionId.equals(position.getId()))
        .filter(position -> accountId.equals(position.getAccountId()))
        .filter(position -> position.getProductType() == ProductType.LINEAR_PERP)
        .filter(position -> position.getStatus() == PositionStatus.OPEN)
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            "POSITION_NOT_FOUND",
            "Open Linear Perpetual position not found"));
  }

  private static boolean selectedBy(
      CancellationScope scope,
      PositionEntity targetPosition,
      OrderEntity order
  ) {
    if (scope == CancellationScope.ALL
        || scope == CancellationScope.ACTIVE_LINEAR_PERPETUAL) {
      return true;
    }
    if (order.getProductType() != ProductType.LINEAR_PERP
        || !sameSymbol(targetPosition.getSymbol(), order.getSymbol())
        || effectivePositionMode(targetPosition.getPositionMode())
            != effectivePositionMode(order.getPositionMode())
        || effectivePositionSide(targetPosition.getPositionSide())
            != effectivePositionSide(order.getPositionSide())) {
      return false;
    }
    if (scope == CancellationScope.ACTIVE_SLOT) {
      return true;
    }
    if (Boolean.TRUE.equals(order.getReduceOnly())) {
      return false;
    }
    PositionMode positionMode = effectivePositionMode(targetPosition.getPositionMode());
    OrderSide openingSide = positionMode == PositionMode.HEDGE
        ? (effectivePositionSide(targetPosition.getPositionSide()) == PositionSide.LONG
            ? OrderSide.BUY
            : OrderSide.SELL)
        : targetPosition.getSide();
    if (order.getSide() == openingSide) {
      return true;
    }
    return positionMode == PositionMode.ONE_WAY
        && remainingBase(order).compareTo(orZero(targetPosition.getLots()).abs()) > 0;
  }

  private static BigDecimal remainingBase(OrderEntity order) {
    BigDecimal remaining = order.getRemainingQuantity();
    if (remaining != null && remaining.compareTo(BigDecimal.ZERO) > 0) {
      return remaining;
    }
    return orZero(order.getBaseQuantity()).abs();
  }

  private static boolean sameSymbol(String left, String right) {
    return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
  }

  private static PositionMode effectivePositionMode(PositionMode mode) {
    return mode == null ? PositionMode.ONE_WAY : mode;
  }

  private static PositionSide effectivePositionSide(PositionSide side) {
    return side == null ? PositionSide.BOTH : side;
  }

  private static boolean terminal(OrderStatus status) {
    return status == OrderStatus.FILLED
        || status == OrderStatus.CANCELED
        || status == OrderStatus.CANCELLED
        || status == OrderStatus.REJECTED
        || status == OrderStatus.EXPIRED
        || status == OrderStatus.FAILED;
  }

  private static long version(OrderEntity order) {
    return order.getVersion() == null ? 0L : order.getVersion();
  }

  private static <T> List<T> safeList(List<T> rows) {
    return rows == null ? List.of() : rows;
  }

  private static String requireRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      throw new BusinessException("BATCH_REQUEST_ID_REQUIRED", "Batch request id is required");
    }
    return requestId.trim();
  }

  private static void requireId(UUID id, String message) {
    if (id == null) {
      throw new BusinessException("BATCH_SCOPE_REQUIRED", message);
    }
  }

  private static BusinessException accountNotFound() {
    return new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
  }

  private enum CancellationScope {
    ALL,
    ACTIVE_LINEAR_PERPETUAL,
    ACTIVE_SLOT,
    RISK_INCREASING_SLOT
  }
}
