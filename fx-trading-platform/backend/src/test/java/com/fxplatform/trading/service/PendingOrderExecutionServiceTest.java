package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingOrderExecutionServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private RiskCheckService riskCheckService;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private OrderEventService orderEventService;

  @Test
  void executesBuyLimitWhenAskTouchesRequestedPrice() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));
    TradingAccountEntity account = account(accountId);
    BigDecimal requiredMargin = new BigDecimal("10.80000000");
    QuoteResponse quote = quote(new BigDecimal("1.07996"), new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), argThat(request ->
        request.orderType() == OrderType.LIMIT
            && request.side() == OrderSide.BUY
            && request.requestedPrice().compareTo(new BigDecimal("1.08000")) == 0)))
        .thenReturn(requiredMargin);
    when(orderRepository.claimPending(order.getId())).thenReturn(1);
    when(accountRepository.reserveMarginIfAvailable(accountId, requiredMargin)).thenReturn(1);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService);

    int filled = service.executePendingOrders();

    assertThat(filled).isEqualTo(1);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.getExecutionPrice()).isEqualByComparingTo("1.08000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(requiredMargin);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9989.20000000");

    verify(orderRepository).save(order);
    verify(tradeRepository).save(any(TradeEntity.class));
    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    assertThat(positionCaptor.getValue().getMarginHeld()).isEqualByComparingTo(requiredMargin);
    verify(ledgerService).recordMarginHold(eq(account), eq(requiredMargin), any(UUID.class), eq("Pending order margin hold"));
    verify(orderEventService).record(
        eq(order.getId()),
        eq("ORDER_FILLED"),
        eq(OrderStatus.WORKING),
        eq(OrderStatus.FILLED),
        eq(null),
        eq("Pending order filled"));
  }

  @Test
  void executesAlreadyHeldPendingOrderWithoutDoubleHoldingMarginOrLedger() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));
    order.setHoldAmount(new BigDecimal("10.80000000"));
    order.setHoldCurrency("USD");
    TradingAccountEntity account = account(accountId);
    account.setUsedMargin(new BigDecimal("10.80000000"));
    account.setFreeMargin(new BigDecimal("9989.20000000"));
    QuoteResponse quote = quote(new BigDecimal("1.07996"), new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.claimPending(order.getId())).thenReturn(1);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService);

    int filled = service.executePendingOrders();

    assertThat(filled).isEqualTo(1);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(account.getUsedMargin()).isEqualByComparingTo("10.80000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9989.20000000");
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
    verify(orderEventService).record(
        eq(order.getId()),
        eq("ORDER_FILLED"),
        eq(OrderStatus.WORKING),
        eq(OrderStatus.FILLED),
        eq(null),
        eq("Pending order filled"));
  }

  @Test
  void skipsTriggeredPendingOrderWhenClaimIsLost() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote(new BigDecimal("1.07996"), new BigDecimal("1.08000")));
    when(orderRepository.claimPending(order.getId())).thenReturn(0);

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService);

    int filled = service.executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(accountRepository, never()).findById(any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository, never()).save(order);
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void keepsBuyLimitPendingWhenAskStaysAboveRequestedPrice() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote(new BigDecimal("1.08010"), new BigDecimal("1.08012")));

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService);

    int filled = service.executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(accountRepository, never()).findById(any());
    verify(orderRepository, never()).save(order);
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void keepsPendingOrderWhenQuoteIsStale() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(quoteService.freshQuote("EURUSD")).thenThrow(new BusinessException("QUOTE_STALE", "Quote is stale"));

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService);

    int filled = service.executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(accountRepository, never()).findById(any());
  }

  private static OrderEntity pendingOrder(UUID accountId, OrderSide side, OrderType type, BigDecimal requestedPrice) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol("EURUSD");
    order.setSide(side);
    order.setOrderType(type);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.01"));
    order.setRequestedPrice(requestedPrice);
    order.setIdempotencyKey("pending-test");
    order.setCreatedAt(Instant.parse("2026-06-05T12:00:00Z"));
    return order;
  }

  private static QuoteResponse quote(BigDecimal bid, BigDecimal ask) {
    return new QuoteResponse("quote", "EURUSD", bid, ask, bid.add(ask).divide(new BigDecimal("2")), ask.subtract(bid), "test", 1780660000000L);
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
    account.setLeverage(100);
    return account;
  }
}
