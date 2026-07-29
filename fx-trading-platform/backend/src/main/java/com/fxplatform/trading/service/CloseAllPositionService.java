package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.repository.PositionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Best-effort whole-position closes with one independent system-close transaction per item. */
@Service
public class CloseAllPositionService {

  private static final String USER_CLOSE_REASON = "USER_CLOSE_ALL";
  private static final String FAILED_STATUS = "FAILED";
  private static final String UNEXPECTED_ITEM_ERROR = "BATCH_ITEM_FAILED";

  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final SystemCloseOrderService systemCloseOrderService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final BatchActionRequestService batchActionRequestService;

  @Autowired
  public CloseAllPositionService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      SystemCloseOrderService systemCloseOrderService,
      DemoExecutionGuard demoExecutionGuard,
      BatchActionRequestService batchActionRequestService
  ) {
    this.accountRepository = accountRepository;
    this.positionRepository = positionRepository;
    this.systemCloseOrderService = systemCloseOrderService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.batchActionRequestService = batchActionRequestService;
  }

  /** Compatibility constructor for focused legacy fixtures. */
  public CloseAllPositionService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      SystemCloseOrderService systemCloseOrderService,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this(
        accountRepository,
        positionRepository,
        systemCloseOrderService,
        demoExecutionGuard,
        null);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BatchActionResponse closeUser(
      UUID userId,
      UUID accountId,
      String requestId
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(CloseAllPositionService::accountNotFound);
    demoExecutionGuard.requireDemoAccount(account);
    return closePreparedScope(
        accountId,
        OrderOrigin.BATCH_CLOSE,
        USER_CLOSE_REASON,
        requireRequestId(requestId));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BatchActionResponse closeSystem(
      UUID accountId,
      OrderOrigin origin,
      String reason,
      String requestId
  ) {
    TradingAccountEntity account = accountRepository.selectById(accountId);
    if (account == null) {
      throw accountNotFound();
    }
    demoExecutionGuard.requireDemoRiskReductionAccount(account);
    if (origin == null || origin == OrderOrigin.USER) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "System batch close requires a non-user origin");
    }
    if (reason == null || reason.isBlank()) {
      throw new BusinessException("SYSTEM_REASON_REQUIRED", "System close reason is required");
    }
    return closePreparedScope(
        accountId,
        origin,
        reason.trim(),
        requireRequestId(requestId));
  }

  private BatchActionResponse closePreparedScope(
      UUID accountId,
      OrderOrigin origin,
      String reason,
      String requestId
  ) {
    BatchActionRequestService.Execution execution = batchActionRequestService == null
        ? new BatchActionRequestService.Execution(
            UUID.randomUUID(),
            accountId,
            BatchActionRequestService.ACTION_CLOSE_ALL,
            requestId,
            sortedOpenPositionIds(accountId),
            null)
        : batchActionRequestService.beginIndependent(
            accountId,
            BatchActionRequestService.ACTION_CLOSE_ALL,
            requestId,
            "origin=" + origin.name() + "|reason=" + reason,
            () -> sortedOpenPositionIds(accountId));
    if (execution.completedResponse() != null) {
      return execution.completedResponse();
    }
    List<BatchActionResponse.Item> items = new ArrayList<>(execution.scopeIds().size());
    for (UUID positionId : execution.scopeIds()) {
      items.add(closeOne(accountId, positionId, origin, reason, requestId, execution));
    }
    BatchActionResponse response = new BatchActionResponse(accountId, requestId, items);
    if (batchActionRequestService != null) {
      batchActionRequestService.completeIndependent(execution, response);
    }
    return response;
  }

  private BatchActionResponse.Item closeOne(
      UUID accountId,
      UUID positionId,
      OrderOrigin origin,
      String reason,
      String requestId,
      BatchActionRequestService.Execution execution
  ) {
    if (positionId == null) {
      return failedItem(null, UNEXPECTED_ITEM_ERROR);
    }
    String itemRequestId = OrderIdempotencyKeyPolicy.systemClose(
        accountId,
        positionId,
        origin,
        requestId);
    try {
      SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeWhole(
          accountId,
          positionId,
          origin,
          reason,
          itemRequestId,
          batchActionRequestService == null
              ? null
              : () -> batchActionRequestService.renewOwnershipCurrent(execution));
      OrderEntity order = result == null ? null : result.order();
      if (order == null || order.getId() == null || order.getStatus() == null) {
        return failedItem(positionId, UNEXPECTED_ITEM_ERROR);
      }
      return new BatchActionResponse.Item(
          positionId,
          order.getId(),
          order.getStatus().name(),
          null,
          null);
    } catch (BusinessException exception) {
      return failedItem(positionId, exception.getCode());
    } catch (RuntimeException exception) {
      // Do not expose provider, database or implementation details in an item-level response.
      return failedItem(positionId, UNEXPECTED_ITEM_ERROR);
    }
  }

  private List<UUID> sortedOpenPositionIds(UUID accountId) {
    List<PositionEntity> positions = positionRepository.findOpenLinearPerpByAccountId(accountId);
    if (positions == null || positions.isEmpty()) {
      return List.of();
    }
    return positions.stream()
        .sorted(Comparator
            .comparing(
                PositionEntity::getSymbol,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                PositionEntity::getPositionSide,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                PositionEntity::getId,
                Comparator.nullsLast(Comparator.naturalOrder())))
        .map(PositionEntity::getId)
        .toList();
  }

  private static BatchActionResponse.Item failedItem(UUID positionId, String errorCode) {
    return new BatchActionResponse.Item(
        positionId,
        null,
        FAILED_STATUS,
        errorCode,
        null);
  }

  private static String requireRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "Batch request id is required");
    }
    return requestId.trim();
  }

  private static BusinessException accountNotFound() {
    return new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
  }
}
