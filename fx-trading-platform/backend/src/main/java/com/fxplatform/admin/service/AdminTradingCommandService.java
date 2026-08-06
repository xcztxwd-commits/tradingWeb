package com.fxplatform.admin.service;
import cn.hutool.core.date.DateUtil;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.admin.dto.response.AdminOrderResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.OrderEventService;
import com.fxplatform.trading.service.PositionService;
import com.fxplatform.trading.service.SystemCloseOrderService;
import com.fxplatform.wallet.service.WalletService;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminTradingCommandService 承载后台交易写操作。
 *
 * <p>服务只编排后台授权、状态变更和审计，强平等资金结算逻辑继续复用交易领域服务，避免管理端复制核心交易规则。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminTradingCommandService {

  /** 后台允许取消的订单状态集合。 */
  private static final EnumSet<OrderStatus> CANCELABLE_STATUSES = EnumSet.of(
      OrderStatus.RECEIVED,
      OrderStatus.VALIDATING,
      OrderStatus.ACCEPTED,
      OrderStatus.WORKING,
      OrderStatus.PARTIALLY_FILLED,
      OrderStatus.PENDING,
      OrderStatus.CANCEL_PENDING);

  /** 订单仓储，用于加载和保存订单状态。 */
  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final WalletService walletService;
  private final RiskCheckService riskCheckService;
  private final OrderEventService orderEventService;
  /** 持仓领域服务，用于复用现有平仓结算链路。 */
  private final PositionService positionService;
  private final SystemCloseOrderService systemCloseOrderService;
  private final PositionRepository positionRepository;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  /** 审计服务，用于记录后台交易写操作。 */
  private final AuditLogService auditLogService;

  /**
   * 取消尚未最终成交或失败的订单。
   */
  @Transactional
  public AdminOrderResponse cancelOrder(UUID actorUserId, UUID orderId, AdminCancelOrderRequest request) {
    OrderEntity orderSnapshot = findOrder(orderId);
    if (isCanceled(orderSnapshot.getStatus())) {
      auditLogService.record(
          actorUserId,
          "ADMIN_ORDER_CANCEL_IDEMPOTENT",
          "ORDER",
          orderId.toString(),
          details(
              request.reason(),
              orderSnapshot.getStatus().name(),
              orderSnapshot.getStatus().name(),
              request.idempotencyKey()));
      return AdminOrderResponse.from(orderSnapshot);
    }
    requireCancelable(orderSnapshot.getStatus());

    TradingAccountEntity accountSnapshot = accountRepository.findById(orderSnapshot.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    ProductType productType = requestedProduct(orderSnapshot.getSymbol());
    boolean spotWalletHold = riskCheckService.isSpotSymbol(orderSnapshot.getSymbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, orderSnapshot.getSymbol());

    TradingAccountEntity account = accountRepository.findByIdForUpdate(orderSnapshot.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, orderSnapshot.getSymbol());
    if (spotWalletHold) {
      walletBalanceRepository.findByAccountIdForUpdate(account.getId());
    } else {
      positionRepository.findOpenByAccountIdForUpdate(account.getId());
    }
    OrderEntity order = orderRepository.findByIdForUpdate(orderId)
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    if (isCanceled(order.getStatus())) {
      return AdminOrderResponse.from(order);
    }
    requireCancelable(order.getStatus());

    OrderStatus before = order.getStatus();
    releasePendingOrderHold(order, account, spotWalletHold);
    order.setStatus(OrderStatus.CANCELED);
    order.setCanceledAt(DateUtil.date().toInstant());
    order.setRemainingQuantity(BigDecimal.ZERO);
    orderRepository.save(order);
    orderEventService.record(
        order.getId(),
        "ORDER_CANCELED",
        before,
        OrderStatus.CANCELED,
        null,
        "Admin canceled pending order");
    auditLogService.record(
        actorUserId,
        "ADMIN_ORDER_CANCEL",
        "ORDER",
        orderId.toString(),
        details(request.reason(), before.name(), OrderStatus.CANCELED.name(), request.idempotencyKey()));
    return AdminOrderResponse.from(order);
  }

  /**
   * 强制平仓并记录后台审计。
   */
  public PositionResponse forceClosePosition(
      UUID actorUserId,
      UUID positionId,
      AdminForceClosePositionRequest request
  ) {
    AdminActionConfirmation.require(request.confirmationText(), AdminActionConfirmation.CONFIRM_FORCE_CLOSE);
    SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeWhole(
        request.accountId(),
        positionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        request.reason(),
        request.idempotencyKey());
    PositionResponse response = adminPositionResponse(result);
    auditLogService.record(
        actorUserId,
        "ADMIN_POSITION_FORCE_CLOSE",
        "POSITION",
        positionId.toString(),
        details(request.reason(), null, response.status(), request.idempotencyKey()));
    return response;
  }

  private PositionResponse adminPositionResponse(SystemCloseOrderService.CloseResult result) {
    if (result == null || result.position() == null) {
      throw new BusinessException("POSITION_NOT_FOUND", "Position close result is unavailable");
    }
    PositionEntity position = result.position();
    ProductType productType = position.getProductType();
    boolean perpetual = productType == ProductType.LINEAR_PERP
        || productType == ProductType.INVERSE_PERP;
    Integer leverage = position.getLeverage() != null
        ? position.getLeverage()
        : result.account() == null ? null : result.account().getLeverage();
    return new PositionResponse(
        position.getId(),
        position.getSymbol(),
        position.getSide() == null ? null : position.getSide().name(),
        perpetual ? "SWAP" : productType == ProductType.CRYPTO_SPOT ? "SPOT" : "FOREX",
        position.getMarginMode() == null ? null : position.getMarginMode().name(),
        leverage,
        perpetual ? "CONTRACT" : productType == ProductType.CRYPTO_SPOT ? null : "LOT",
        position.getLots(),
        position.getOpenPrice(),
        position.getMarkPrice() == null ? position.getCurrentPrice() : position.getMarkPrice(),
        position.getCurrentPrice(),
        position.getNotional(),
        null,
        position.getOpenPrice(),
        position.getStopLoss(),
        position.getTakeProfit(),
        position.getFloatingPnl(),
        null,
        position.getRealizedPnl(),
        position.getFundingPnl(),
        position.getMarginHeld(),
        position.getInitialMargin(),
        position.getMaintenanceMargin(),
        null,
        null,
        position.getStatus() == null ? null : position.getStatus().name(),
        position.getOpenedAt(),
        position.getClosedAt(),
        productType,
        position.getPositionMode(),
        position.getPositionSide(),
        position.getVersion());
  }

  /**
   * 加载订单，不存在时抛出统一业务异常。
   */
  private OrderEntity findOrder(UUID orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
  }

  /**
   * 判断订单是否已经处于取消终态，兼容历史拼写 CANCELLED。
   */
  private boolean isCanceled(OrderStatus status) {
    return status == OrderStatus.CANCELED || status == OrderStatus.CANCELLED;
  }

  /**
   * 校验订单是否允许后台取消。
   */
  private void requireCancelable(OrderStatus status) {
    if (!CANCELABLE_STATUSES.contains(status)) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Order status is not cancelable");
    }
  }

  private void releasePendingOrderHold(
      OrderEntity order,
      TradingAccountEntity account,
      boolean spotWalletHold
  ) {
    BigDecimal holdAmount = orZero(order.getHoldAmount());
    if (holdAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if (spotWalletHold) {
      walletService.releaseLockedWithEntryType(
          order.getAccountId(),
          order.getHoldCurrency(),
          holdAmount,
          "ORDER",
          order.getId(),
          "Admin canceled pending spot order",
          "SPOT_ORDER_RELEASE");
      order.setHoldAmount(BigDecimal.ZERO);
      return;
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).subtract(holdAmount).max(BigDecimal.ZERO));
    account.setFreeMargin(accountEquity(account).subtract(account.getUsedMargin()));
    accountRepository.save(account);
    ledgerService.recordOrderRelease(account, holdAmount, order.getId(), "Admin canceled pending order");
  }

  private ProductType requestedProduct(String canonicalSymbol) {
    return canonicalSymbol.endsWith("-PERP") ? ProductType.LINEAR_PERP : ProductType.CRYPTO_SPOT;
  }

  /**
   * 构造审计 JSON 明细。
   */
  private String details(String reason, String before, String after, String idempotencyKey) {
    return AuditDetailsBuilder.create()
        .put("reason", reason)
        .put("before", before)
        .put("after", after)
        .put("idempotencyKey", idempotencyKey)
        .toJson();
  }
}
