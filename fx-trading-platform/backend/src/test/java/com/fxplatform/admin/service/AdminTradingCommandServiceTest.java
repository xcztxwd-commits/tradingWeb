package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.service.OrderEventService;
import com.fxplatform.trading.service.PositionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  private AuditLogService auditLogService;

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

    AdminTradingCommandService service = new AdminTradingCommandService(
        orderRepository,
        accountRepository,
        ledgerService,
        orderEventService,
        positionService,
        auditLogService);

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

    AdminTradingCommandService service = new AdminTradingCommandService(
        orderRepository,
        accountRepository,
        ledgerService,
        orderEventService,
        positionService,
        auditLogService);

    var response = service.cancelOrder(actorUserId, order.getId(), new AdminCancelOrderRequest("retry", "cancel-1"));

    assertThat(response.status()).isEqualTo("CANCELED");
    verify(orderRepository, never()).save(order);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_ORDER_CANCEL_IDEMPOTENT"), eq("ORDER"), eq(order.getId().toString()), contains("retry"));
  }

  @Test
  void forceClosePositionDelegatesToDomainServiceAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionResponse closed = new PositionResponse(
        positionId,
        "EURUSD",
        "BUY",
        "FOREX",
        "CROSS",
        100,
        "LOT",
        new BigDecimal("0.10"),
        new BigDecimal("1.09000"),
        new BigDecimal("1.09100"),
        new BigDecimal("1.09100"),
        null,
        new BigDecimal("1.09000"),
        null,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal("1.00"),
        BigDecimal.ZERO,
        null,
        null,
        "CLOSED",
        null,
        null);
    when(positionService.closeSystemPosition(accountId, positionId)).thenReturn(closed);

    AdminTradingCommandService service = new AdminTradingCommandService(
        orderRepository,
        accountRepository,
        ledgerService,
        orderEventService,
        positionService,
        auditLogService);

    var response = service.forceClosePosition(
        actorUserId,
        positionId,
        new AdminForceClosePositionRequest(accountId, "risk threshold", "force-close-1"));

    assertThat(response.status()).isEqualTo("CLOSED");
    verify(positionService).closeSystemPosition(accountId, positionId);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_POSITION_FORCE_CLOSE"), eq("POSITION"), eq(positionId.toString()), contains("risk threshold"));
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
