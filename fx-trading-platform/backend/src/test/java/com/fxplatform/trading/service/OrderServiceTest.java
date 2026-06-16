package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEventEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private RiskCheckService riskCheckService;

  @Mock
  private ExecutionAdapter executionAdapter;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private WalletService walletService;

  @Test
  void marketOrderRecordsMarginLedgerAfterRiskAndExecution() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-1");
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("110.00000000");
    BigDecimal filledMargin = new BigDecimal("110.02000000");
    Instant filledAt = Instant.parse("2026-06-05T12:00:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-1")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, filledMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(new BigDecimal("1.10020"), filledAt));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("1.10020");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(filledMargin);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9889.98000000");

    ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
    assertThat(orderCaptor.getAllValues().getLast().getStatus()).isEqualTo(OrderStatus.FILLED);

    verify(tradeRepository).save(any(TradeEntity.class));
    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    assertThat(positionCaptor.getValue().getMarginHeld()).isEqualByComparingTo(filledMargin);
    verify(accountRepository).reserveMarginIfAvailable(accountId, filledMargin);
    verify(ledgerService).recordMarginHold(eq(account), eq(filledMargin), any(UUID.class), eq("Market order margin hold"));
  }

  @Test
  void marketOrderStoresRequestedLeverageOnCreatedPosition() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-leverage-20", 20);
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("550.10000000");
    Instant filledAt = Instant.parse("2026-06-05T12:00:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-leverage-20")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any(CreateOrderRequest.class))).thenReturn(20);
    when(accountRepository.reserveMarginIfAvailable(accountId, requiredMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(new BigDecimal("1.10020"), filledAt));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.leverage()).isEqualTo(20);

    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    assertThat(positionCaptor.getValue().getLeverage()).isEqualTo(20);
  }

  @Test
  void rejectedRiskCheckDoesNotExecuteOrWriteLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-2");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-2")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class)))
        .thenThrow(new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough"));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");

    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void createOrderRiskFailureReplayDoesNotPersistExecuteOrWriteLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-risk-failure-replay");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-risk-failure-replay"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class)))
        .thenThrow(new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough"));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");

    verify(riskCheckService, org.mockito.Mockito.times(2)).checkOrder(eq(account), any(CreateOrderRequest.class));
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void partialMarketExecutionRecordsFeeSlippageAndPartialPosition() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-partial-1");
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("100.00000000");
    Instant filledAt = Instant.parse("2026-06-05T12:05:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-partial-1")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, new BigDecimal("44.04000000"))).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(
        new BigDecimal("1.10100"),
        filledAt,
        new BigDecimal("0.04"),
        new BigDecimal("0.06"),
        new BigDecimal("0.44"),
        new BigDecimal("0.00010"),
        null,
        null));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo("0.04");
    assertThat(response.remainingQuantity()).isEqualByComparingTo("0.06");
    assertThat(response.fee()).isEqualByComparingTo("0.44");
    assertThat(response.slippage()).isEqualByComparingTo("0.00010");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("44.04000000");
    assertThat(account.getBalance()).isEqualByComparingTo("9999.56000000");
    assertThat(account.getEquity()).isEqualByComparingTo("9999.56000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9955.52000000");

    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    assertThat(positionCaptor.getValue().getLots()).isEqualByComparingTo("0.04");
    assertThat(positionCaptor.getValue().getMarginHeld()).isEqualByComparingTo("44.04000000");
    verify(ledgerService).recordMarginHold(eq(account), eq(new BigDecimal("44.04000000")), any(UUID.class), eq("Market order margin hold"));
    verify(ledgerService).recordTradeFee(eq(account), eq(new BigDecimal("0.44")), any(UUID.class), eq("Trade fee charged"));
  }

  @Test
  void marketExecutionFeeUsesBalanceFallbackOnceWhenEquityIsNull() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-fee-null-equity");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setEquity(null);
    BigDecimal requiredMargin = new BigDecimal("100.00000000");
    BigDecimal filledMargin = new BigDecimal("110.10000000");
    Instant filledAt = Instant.parse("2026-06-05T12:06:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-fee-null-equity")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, filledMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(
        new BigDecimal("1.10100"),
        filledAt,
        null,
        null,
        new BigDecimal("0.44"),
        BigDecimal.ZERO,
        null,
        null));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    service.createOrder(principal, request);

    assertThat(account.getBalance()).isEqualByComparingTo("9999.56000000");
    assertThat(account.getEquity()).isEqualByComparingTo("9999.56000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("110.10000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9889.46000000");
    verify(ledgerService).recordTradeFee(eq(account), eq(new BigDecimal("0.44")), any(UUID.class), eq("Trade fee charged"));
  }

  @Test
  void rejectedExecutionRecordsOrderReasonWithoutTradePositionOrLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-reject-1");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-reject-1")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(new BigDecimal("110.02000000"));
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(ExecutionResult.rejected(
        "EXECUTION_REJECTED",
        "Demo execution rejected"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.REJECTED.name());
    assertThat(response.rejectCode()).isEqualTo("EXECUTION_REJECTED");
    assertThat(response.rejectMessage()).isEqualTo("Demo execution rejected");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10000.00000000");
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradeFee(any(), any(), any(), any());
    verify(orderEventService).record(
        any(UUID.class),
        eq("ORDER_REJECTED"),
        eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.REJECTED),
        eq("EXECUTION_REJECTED"),
        eq("Demo execution rejected"));
  }

  @Test
  void limitOrderUsesPendingStatusAndCompatibleResponseFields() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        null,
        "client-limit-1",
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"));
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("10.80000000");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "client-limit-1"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(holdAmount);
    when(accountRepository.reserveMarginIfAvailable(accountId, holdAmount)).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.lots()).isEqualByComparingTo("0.10");
    assertThat(response.quantity()).isEqualByComparingTo("0.10");
    assertThat(response.price()).isEqualByComparingTo("1.08000");
    assertThat(response.filledQuantity()).isEqualByComparingTo("0");
    assertThat(response.remainingQuantity()).isEqualByComparingTo("0.10");
    assertThat(response.holdAmount()).isEqualByComparingTo(holdAmount);
    assertThat(account.getUsedMargin()).isEqualByComparingTo(holdAmount);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9989.20000000");

    ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(orderCaptor.capture());
    assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(orderCaptor.getValue().getClientOrderId()).isEqualTo("client-limit-1");
    assertThat(orderCaptor.getValue().getQuantity()).isEqualByComparingTo("0.10");
    assertThat(orderCaptor.getValue().getRemainingQuantity()).isEqualByComparingTo("0.10");
    verify(executionAdapter, never()).execute(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository).reserveMarginIfAvailable(accountId, holdAmount);
    verify(ledgerService).recordOrderHold(eq(account), eq(holdAmount), any(UUID.class), eq("Pending order margin reserved"));
    verify(orderEventService).record(
        any(UUID.class),
        eq("ORDER_PENDING"),
        eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PENDING),
        eq(null),
        eq("Pending order accepted and waiting"));
  }

  @Test
  void spotLimitOrderLocksQuoteWalletInsteadOfMargin() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        "idem-spot-limit",
        "client-spot-limit",
        new BigDecimal("0.10"),
        new BigDecimal("50000.00000000"),
        1);
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("5000.00000000");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "client-spot-limit"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(holdAmount);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any(CreateOrderRequest.class))).thenReturn(1);
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(riskCheckService.resolveHoldCurrency(eq(account), any(CreateOrderRequest.class))).thenReturn("USDT");
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo(holdAmount);
    assertThat(response.holdCurrency()).isEqualTo("USDT");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId),
        eq("USDT"),
        eq(holdAmount),
        eq("ORDER"),
        any(UUID.class),
        eq("Pending spot order wallet locked"),
        eq("SPOT_ORDER_LOCK"));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderEventService).record(
        any(UUID.class),
        eq("ORDER_PENDING"),
        eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PENDING),
        eq(null),
        eq("Pending order accepted and waiting"));
  }

  @Test
  void spotSellLimitOrderLocksBaseWalletInsteadOfMargin() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.SELL,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        "idem-spot-sell-limit",
        "client-spot-sell-limit",
        new BigDecimal("0.10"),
        new BigDecimal("55000.00000000"),
        1);
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("0.10000000");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "client-spot-sell-limit"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(holdAmount);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any(CreateOrderRequest.class))).thenReturn(1);
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(riskCheckService.resolveHoldCurrency(eq(account), any(CreateOrderRequest.class))).thenReturn("BTC");
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo(holdAmount);
    assertThat(response.holdCurrency()).isEqualTo("BTC");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId),
        eq("BTC"),
        eq(holdAmount),
        eq("ORDER"),
        any(UUID.class),
        eq("Pending spot order wallet locked"),
        eq("SPOT_ORDER_LOCK"));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(executionAdapter, never()).execute(any());
  }

  @Test
  void pendingOrderWithoutRequestedPriceIsRejectedBeforeRiskCheck() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        new BigDecimal("0.10"),
        null,
        null,
        null,
        "idem-limit-without-price",
        null,
        null,
        null);
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-limit-without-price"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("requested price");

    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void createOrderReturnsExistingOrderWhenUniqueConstraintWinsRace() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID existingOrderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-race");
    TradingAccountEntity account = demoAccount(userId, accountId);
    OrderEntity existing = pendingOrderEntity(userId, accountId, existingOrderId);
    existing.setStatus(OrderStatus.PENDING);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-race"))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-race")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(new BigDecimal("110.02000000"));
    when(orderRepository.save(any(OrderEntity.class))).thenThrow(new DataIntegrityViolationException("duplicate order"));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.id()).isEqualTo(existingOrderId);
    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    verify(executionAdapter, never()).execute(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void createOrderReplayReturnsExistingOrderWithoutSecondExecutionOrLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-replay");
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("110.02000000");
    AtomicReference<OrderEntity> storedOrder = new AtomicReference<>();

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-replay"))
        .thenAnswer(invocation -> Optional.ofNullable(storedOrder.get()));
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-replay")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, requiredMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class)))
        .thenReturn(new ExecutionResult(new BigDecimal("1.10020"), Instant.parse("2026-06-05T12:00:00Z")));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(orderId);
      }
      storedOrder.set(order);
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse first = service.createOrder(principal, request);
    OrderResponse replay = service.createOrder(principal, request);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(executionAdapter).execute(any(CreateOrderRequest.class));
    verify(tradeRepository).save(any(TradeEntity.class));
    verify(positionRepository).save(any(PositionEntity.class));
    verify(ledgerService).recordMarginHold(eq(account), eq(requiredMargin), any(UUID.class), eq("Market order margin hold"));
  }

  @Test
  void orderEventsRequireOwnedOrderAndReturnTimeline() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    Instant createdAt = Instant.parse("2026-06-05T12:30:00Z");
    OrderEventResponse event = new OrderEventResponse(
        UUID.randomUUID(),
        orderId,
        "ORDER_PENDING",
        OrderStatus.ACCEPTED.name(),
        OrderStatus.PENDING.name(),
        null,
        "Pending order accepted and waiting",
        createdAt);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(orderEventService.events(orderId)).thenReturn(List.of(event));

    OrderService service = orderService(orderEventService);

    List<OrderEventResponse> events = service.orderEvents(principal, orderId);

    assertThat(events).containsExactly(event);
  }

  @Test
  void cancelPendingOrderReleasesHoldAndRecordsEvent() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setUsedMargin(new BigDecimal("10.80000000"));
    account.setFreeMargin(new BigDecimal("9989.20000000"));
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setHoldAmount(new BigDecimal("10.80000000"));
    order.setHoldCurrency("USD");

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.cancelPending(any(OrderEntity.class))).thenReturn(1);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.cancelOrder(principal, orderId);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(response.canceledAt()).isNotNull();
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10000.00000000");
    verify(orderRepository).cancelPending(order);
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(eq(account), eq(new BigDecimal("10.80000000")), eq(orderId), eq("Pending order canceled"));
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Pending order canceled"));
  }

  @Test
  void cancelSpotPendingOrderReleasesWalletHoldWithoutMarginLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setSymbol("BTCUSDT");
    order.setHoldAmount(new BigDecimal("5000.00000000"));
    order.setHoldCurrency("USDT");

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.cancelPending(any(OrderEntity.class))).thenReturn(1);
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.cancelOrder(principal, orderId);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(response.holdAmount()).isEqualByComparingTo("0");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    verify(walletService).releaseLockedWithEntryType(
        eq(accountId),
        eq("USDT"),
        eq(new BigDecimal("5000.00000000")),
        eq("ORDER"),
        eq(orderId),
        eq("Pending spot order canceled"),
        eq("SPOT_ORDER_RELEASE"));
    verify(accountRepository, never()).save(account);
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), any());
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Pending order canceled"));
  }

  @Test
  void cancelFilledOrderIsRejected() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setStatus(OrderStatus.FILLED);

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelOrder(principal, orderId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Only pending orders can be canceled");

    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), any());
  }

  @Test
  void cancelPendingOrderReplayIsRejectedWithoutSecondRelease() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setUsedMargin(new BigDecimal("10.80000000"));
    account.setFreeMargin(new BigDecimal("9989.20000000"));
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setHoldAmount(new BigDecimal("10.80000000"));
    order.setHoldCurrency("USD");

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.cancelPending(any(OrderEntity.class))).thenReturn(1);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse first = service.cancelOrder(principal, orderId);

    assertThat(first.status()).isEqualTo(OrderStatus.CANCELED.name());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelOrder(principal, orderId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Only pending orders can be canceled");
    verify(ledgerService).recordOrderRelease(eq(account), eq(new BigDecimal("10.80000000")), eq(orderId), eq("Pending order canceled"));
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Pending order canceled"));
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
  }

  @Test
  void modifyPendingOrderAdjustsHoldDeltaAndRecordsEvent() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("9990.00000000"));
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setHoldAmount(new BigDecimal("10.00000000"));
    UpdateOrderRequest update = new UpdateOrderRequest(
        new BigDecimal("0.20"),
        new BigDecimal("1.07900"),
        new BigDecimal("1.07000"),
        new BigDecimal("1.09000"));

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(any(TradingAccountEntity.class), any(CreateOrderRequest.class)))
        .thenReturn(new BigDecimal("20.00000000"));
    when(accountRepository.reserveMarginIfAvailable(accountId, new BigDecimal("10.00000000"))).thenReturn(1);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.modifyOrder(principal, orderId, update);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.quantity()).isEqualByComparingTo("0.20");
    assertThat(response.price()).isEqualByComparingTo("1.07900");
    assertThat(order.getStopLoss()).isEqualByComparingTo("1.07000");
    assertThat(order.getTakeProfit()).isEqualByComparingTo("1.09000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("20.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("20.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9980.00000000");
    verify(orderRepository).save(order);
    verify(accountRepository).reserveMarginIfAvailable(accountId, new BigDecimal("10.00000000"));
    verify(ledgerService).recordOrderHold(eq(account), eq(new BigDecimal("10.00000000")), eq(orderId), eq("Pending order margin increased"));
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_MODIFIED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.PENDING),
        eq(null),
        eq("Pending order modified"));
  }

  private static CreateOrderRequest marketOrder(UUID accountId, String idempotencyKey) {
    return marketOrder(accountId, idempotencyKey, null);
  }

  private static CreateOrderRequest marketOrder(UUID accountId, String idempotencyKey, Integer leverage) {
    return new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.MARKET,
        new BigDecimal("0.10"),
        null,
        null,
        null,
        idempotencyKey,
        null,
        null,
        null,
        leverage);
  }

  private static TradingAccountEntity demoAccount(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
    account.setLeverage(100);
    return account;
  }

  private static OrderEntity pendingOrderEntity(UUID userId, UUID accountId, UUID orderId) {
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol("EURUSD");
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.10"));
    order.setQuantity(new BigDecimal("0.10"));
    order.setRequestedPrice(new BigDecimal("1.08000"));
    order.setPrice(new BigDecimal("1.08000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("0.10"));
    order.setClientOrderId("client-pending-1");
    order.setIdempotencyKey("client-pending-1");
    return order;
  }

  private OrderService orderService(OrderEventService orderEventService) {
    return new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        ledgerService,
        walletService,
        orderEventService,
        new OrderCommandFactory(),
        new OrderEntityFactory(),
        new OrderResponseMapper(),
        new OrderStatusPolicy());
  }
}
