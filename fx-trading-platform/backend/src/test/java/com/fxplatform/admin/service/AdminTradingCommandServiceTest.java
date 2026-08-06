package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.OrderEventService;
import com.fxplatform.trading.service.PositionService;
import com.fxplatform.trading.service.SystemCloseOrderService;
import com.fxplatform.wallet.service.WalletService;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminTradingCommandServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private OrderEventService orderEventService;

  @Mock
  private PositionService positionService;

  @Mock
  private SystemCloseOrderService systemCloseOrderService;

  @Mock
  private AuditLogService auditLogService;

  @Mock
  private RiskCheckService riskCheckService;

  @Mock
  private WalletService walletService;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @InjectMocks
  private AdminTradingCommandService service;

  @BeforeEach
  void lockedRowsReuseTheReadFixtures() {
    org.mockito.Mockito.lenient().when(accountRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
    org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> orderRepository.findById(invocation.getArgument(0)));
  }

  @Test
  void forceCloseRequiresConfirmationBeforeDelegatingToSystemCloseService() {
    assertThatThrownBy(() -> service.forceClosePosition(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AdminForceClosePositionRequest(UUID.randomUUID(), "risk threshold", "force-close-1")))
        .isInstanceOf(com.fxplatform.common.exception.BusinessException.class)
        .extracting("code")
        .isEqualTo("ADMIN_CONFIRMATION_REQUIRED");

    verifyNoInteractions(systemCloseOrderService, positionService, auditLogService);
  }

  @Test
  void cancelsPendingOrderWithAudit() {
    UUID actorUserId = UUID.randomUUID();
    OrderEntity order = pendingOrder(UUID.randomUUID());
    TradingAccountEntity account = account(order.getAccountId());
    account.setUsedMargin(new BigDecimal("10.80000000"));
    account.setFreeMargin(new BigDecimal("9989.20000000"));
    order.setHoldAmount(new BigDecimal("10.80000000"));
    order.setHoldCurrency("USD");
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));

    var response = service.cancelOrder(actorUserId, order.getId(), new AdminCancelOrderRequest("client requested", "cancel-1"));

    assertThat(response.status()).isEqualTo("CANCELED");
    assertThat(response.canceledAt()).isNotNull();
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10000.00000000");
    verify(orderRepository).save(order);
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(eq(account), eq(new BigDecimal("10.80000000")), eq(order.getId()), eq("Admin canceled pending order"));
    verify(orderEventService).record(
        eq(order.getId()),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Admin canceled pending order"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_ORDER_CANCEL"), eq("ORDER"), eq(order.getId().toString()), contains("client requested"));
  }

  @Test
  void cancelOrderIsIdempotentWhenAlreadyCanceled() {
    UUID actorUserId = UUID.randomUUID();
    OrderEntity order = pendingOrder(UUID.randomUUID());
    order.setStatus(OrderStatus.CANCELED);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    var response = service.cancelOrder(actorUserId, order.getId(), new AdminCancelOrderRequest("retry", "cancel-1"));

    assertThat(response.status()).isEqualTo("CANCELED");
    verify(orderRepository, never()).save(order);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_ORDER_CANCEL_IDEMPOTENT"), eq("ORDER"), eq(order.getId().toString()), contains("retry"));
  }

  @Test
  void forceClosePositionUsesAdminOriginAndPassesTheRequestIdToSystemClose() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    String requestId = "force-close-1";
    OrderEntity closeOrder = new OrderEntity();
    closeOrder.setId(UUID.randomUUID());
    closeOrder.setOrderOrigin(OrderOrigin.ADMIN_FORCE_CLOSE);
    closeOrder.setStatus(OrderStatus.FILLED);
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setInitialMargin(new BigDecimal("42.00000000"));
    TradingAccountEntity account = account(accountId);
    when(systemCloseOrderService.closeWhole(
        accountId,
        positionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "risk threshold",
        requestId))
        .thenReturn(new SystemCloseOrderService.CloseResult(
            closeOrder,
            position,
            account,
            false));

    var response = service.forceClosePosition(
        actorUserId,
        positionId,
        new AdminForceClosePositionRequest(
            accountId,
            "risk threshold",
            requestId,
            "CONFIRM_FORCE_CLOSE"));

    assertThat(response).isNotNull();
    assertThat(response.initialMargin()).isEqualByComparingTo("42.00000000");
    verify(systemCloseOrderService).closeWhole(
        accountId,
        positionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "risk threshold",
        requestId);
    verifyNoInteractions(positionService);
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_POSITION_FORCE_CLOSE"),
        eq("POSITION"),
        eq(positionId.toString()),
        argThat(details -> details.contains("risk threshold") && details.contains(requestId)));
  }

  @Test
  void cancelSpotPendingOrderReleasesWalletHoldWithoutMarginLedger() {
    UUID actorUserId = UUID.randomUUID();
    OrderEntity order = pendingOrder(UUID.randomUUID());
    order.setSymbol("BTCUSDT");
    order.setHoldAmount(new BigDecimal("5000.00000000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(order.getAccountId());
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);

    var response = service.cancelOrder(actorUserId, order.getId(), new AdminCancelOrderRequest("client requested", "cancel-spot-1"));

    assertThat(response.status()).isEqualTo("CANCELED");
    verify(walletService).releaseLockedWithEntryType(
        eq(order.getAccountId()),
        eq("USDT"),
        eq(new BigDecimal("5000.00000000")),
        eq("ORDER"),
        eq(order.getId()),
        eq("Admin canceled pending spot order"),
        eq("SPOT_ORDER_RELEASE"));
    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletBalanceRepository, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdForUpdate(order.getAccountId());
    accountWalletOrder.verify(walletBalanceRepository).findByAccountIdForUpdate(order.getAccountId());
    accountWalletOrder.verify(orderRepository).findByIdForUpdate(order.getId());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), any());
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_ORDER_CANCEL"), eq("ORDER"), eq(order.getId().toString()), contains("client requested"));
  }

  private OrderEntity pendingOrder(UUID orderId) {
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setSymbol("EURUSD");
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.10"));
    order.setQuantity(new BigDecimal("0.10"));
    order.setPrice(new BigDecimal("1.08000"));
    order.setRemainingQuantity(new BigDecimal("0.10"));
    return order;
  }

  private TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
    return account;
  }
}
