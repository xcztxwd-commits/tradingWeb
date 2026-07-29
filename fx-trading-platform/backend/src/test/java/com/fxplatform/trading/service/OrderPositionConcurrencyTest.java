package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.CrossLiquidationChargeRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.service.WalletService;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

class OrderPositionConcurrencyTest {

  @Test
  void repositoriesExposeDeterministicPostgresRowLocks() throws Exception {
    assertForUpdate(TradingAccountRepository.class, "findByIdForUpdate", UUID.class);
    assertForUpdate(TradingAccountRepository.class, "findByIdAndUserIdForUpdate", UUID.class, UUID.class);
    assertForUpdate(WalletBalanceRepository.class, "findByAccountIdAndWalletTypeAndAssetForUpdate",
        UUID.class, String.class, String.class);
    assertSortedForUpdate(
        WalletBalanceRepository.class, "findByAccountIdForUpdate", "ORDER BY wallet_type, asset", UUID.class);
    assertForUpdate(PositionRepository.class, "findByIdForUpdate", UUID.class);
    assertSortedForUpdate(
        PositionRepository.class,
        "findOpenByAccountIdForUpdate",
        "ORDER BY symbol, position_side, id",
        UUID.class);
    assertForUpdate(
        PositionRepository.class,
        "findOpenPerpetualSlotForUpdate",
        UUID.class,
        String.class,
        PositionMode.class,
        PositionSide.class);
    assertSortedForUpdate(
        PositionRepository.class,
        "findOpenLinearPerpByAccountIdForUpdate",
        "ORDER BY symbol, position_side, id",
        UUID.class);
    assertSortedForUpdate(
        PositionRepository.class,
        "findOpenLinearPerpBySymbolForUpdate",
        "ORDER BY symbol, position_side, id",
        UUID.class,
        String.class);
    assertForUpdate(
        SpotPositionRepository.class,
        "findBySlotForUpdate",
        UUID.class,
        String.class,
        String.class,
        String.class);
    assertSortedForUpdate(
        SpotPositionRepository.class,
        "findByAccountIdForUpdate",
        "ORDER BY wallet_type, asset, cost_asset, id",
        UUID.class);
    assertForUpdate(OrderRepository.class, "findByIdForUpdate", UUID.class);
    assertSortedForUpdate(OrderRepository.class, "findPendingByAccountIdForUpdate", "ORDER BY id", UUID.class);
    assertSortedForUpdate(
        OrderRepository.class,
        "findActiveLinearPerpByAccountIdForUpdate",
        "ORDER BY id",
        UUID.class);
    assertSortedForUpdate(
        OrderRepository.class,
        "findActiveLinearPerpBySymbolForUpdate",
        "ORDER BY id",
        UUID.class,
        String.class);
    assertSortedForUpdate(
        CrossLiquidationChargeRepository.class,
        "findPendingByAccountIdForUpdate",
        "ORDER BY p.symbol, p.position_side, c.position_id, c.order_id",
        UUID.class);
    assertForUpdate(
        AccountSymbolSettingRepository.class,
        "findByAccountIdAndSymbolForUpdate",
        UUID.class,
        String.class);
  }

  @Test
  void perpetualSettingsLocksUseExactProductScopeAndAllActiveStatuses() throws Exception {
    String accountOrders = selectSql(OrderRepository.class.getMethod(
        "findActiveLinearPerpByAccountIdForUpdate", UUID.class));
    String symbolOrders = selectSql(OrderRepository.class.getMethod(
        "findActiveLinearPerpBySymbolForUpdate", UUID.class, String.class));
    for (String sql : List.of(accountOrders, symbolOrders)) {
      assertThat(sql).contains("product_type = 'LINEAR_PERP'");
      assertThat(sql).contains(
          "'RECEIVED'", "'VALIDATING'", "'ACCEPTED'", "'PENDING_ACTIVATION'",
          "'PENDING'", "'WORKING'", "'PARTIALLY_FILLED'", "'CANCEL_PENDING'");
      assertThat(sql).doesNotContain("'FILLED'", "'CANCELED'", "'REJECTED'", "'EXPIRED'");
    }
  }

  @Test
  void manualPerpetualCloseDelegatesWithoutLegacyQuoteOrRowLocks() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(
        userId, accountId, new BigDecimal("10000.00000000"), new BigDecimal("80.00000000"));
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("50000.00000000"));
    position.setCurrentPrice(new BigDecimal("50000.00000000"));
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("BTCUSDT-PERP");
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("LINEAR_PERPETUAL");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");

    TradingAccountRepository accountRepository = mock(TradingAccountRepository.class);
    PositionRepository positionRepository = mock(PositionRepository.class);
    QuoteService quoteService = mock(QuoteService.class);
    PnLCalculator pnlCalculator = mock(PnLCalculator.class);
    LedgerService ledgerService = mock(LedgerService.class);
    SymbolRepository symbolRepository = mock(SymbolRepository.class);
    DemoExecutionGuard guard = mock(DemoExecutionGuard.class);
    SystemCloseOrderService systemCloseOrderService = mock(SystemCloseOrderService.class);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(systemCloseOrderService.closeUserWhole(
        userId,
        accountId,
        positionId,
        "position-close-" + positionId))
        .thenReturn(new SystemCloseOrderService.CloseResult(
            new OrderEntity(), position, account, false));

    PositionService service = new PositionService(
        positionRepository,
        accountRepository,
        quoteService,
        pnlCalculator,
        ledgerService,
        symbolRepository,
        guard);
    service.setSystemCloseOrderService(systemCloseOrderService);

    service.closePosition(userId, accountId, positionId);

    verify(systemCloseOrderService).closeUserWhole(
        userId,
        accountId,
        positionId,
        "position-close-" + positionId);
    verify(quoteService, never()).freshQuote(any());
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(positionRepository, never()).findByIdForUpdate(any());
    verify(positionRepository, never()).closeIfOpen(any());
  }

  @Test
  void concurrentLimitOrdersForSameAccountCannotReserveMoreThanFreeMargin() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    AtomicReference<TradingAccountEntity> accountState = new AtomicReference<>(
        account(userId, accountId, new BigDecimal("100.00000000"), BigDecimal.ZERO));
    CountDownLatch staleReads = new CountDownLatch(2);
    TradingAccountRepository accountRepository = accountRepository(accountState, staleReads);
    OrderRepository orderRepository = orderRepository(null, null);
    LedgerService ledgerService = mock(LedgerService.class);
    RiskCheckService riskCheckService = mock(RiskCheckService.class);
    when(riskCheckService.checkOrder(any(TradingAccountEntity.class), any(CreateOrderRequest.class)))
        .thenAnswer(invocation -> {
          TradingAccountEntity account = invocation.getArgument(0);
          BigDecimal requiredMargin = new BigDecimal("80.00000000");
          if (account.getFreeMargin().compareTo(requiredMargin) < 0) {
            throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
          }
          return requiredMargin;
        });
    OrderService service = orderService(orderRepository, accountRepository, riskCheckService, ledgerService);
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");

    List<Attempt> attempts = runConcurrently(
        () -> service.createOrder(principal, limitOrder(accountId, "client-concurrent-1")),
        () -> service.createOrder(principal, limitOrder(accountId, "client-concurrent-2")));

    assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
    assertThat(accountState.get().getUsedMargin()).isEqualByComparingTo("80.00000000");
    assertThat(accountState.get().getFreeMargin()).isEqualByComparingTo("20.00000000");
    verify(ledgerService, times(1)).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void concurrentCancelSamePendingOrderOnlyReleasesHoldOnce() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    AtomicReference<TradingAccountEntity> accountState = new AtomicReference<>(
        account(userId, accountId, new BigDecimal("1000.00000000"), new BigDecimal("80.00000000")));
    AtomicReference<OrderEntity> orderState = new AtomicReference<>(pendingOrder(userId, accountId, orderId));
    CountDownLatch staleReads = new CountDownLatch(2);
    OrderRepository orderRepository = orderRepository(orderState, staleReads);
    TradingAccountRepository accountRepository = accountRepository(accountState, null);
    LedgerService ledgerService = mock(LedgerService.class);
    OrderService service = orderService(orderRepository, accountRepository, mock(RiskCheckService.class), ledgerService);
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");

    List<Attempt> attempts = runConcurrently(
        () -> service.cancelOrder(principal, orderId),
        () -> service.cancelOrder(principal, orderId));

    assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
    assertThat(orderState.get().getStatus()).isEqualTo(OrderStatus.CANCELED);
    assertThat(accountState.get().getUsedMargin()).isEqualByComparingTo("0");
    assertThat(accountState.get().getFreeMargin()).isEqualByComparingTo("1000.00000000");
    verify(ledgerService, times(1)).recordOrderRelease(any(), any(), any(), any());
  }

  @Test
  void concurrentCloseSamePositionOnlySettlesAccountOnce() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    AtomicReference<TradingAccountEntity> accountState = new AtomicReference<>(
        account(userId, accountId, new BigDecimal("10000.00000000"), new BigDecimal("80.00000000")));
    AtomicReference<PositionEntity> positionState = new AtomicReference<>(openPosition(accountId, positionId));
    CountDownLatch staleReads = new CountDownLatch(2);
    PositionRepository positionRepository = positionRepository(positionState, staleReads);
    TradingAccountRepository accountRepository = accountRepository(accountState, null);
    QuoteService quoteService = mock(QuoteService.class);
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    PnLCalculator pnlCalculator = mock(PnLCalculator.class);
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(new BigDecimal("10.00000000"));
    LedgerService ledgerService = mock(LedgerService.class);
    SymbolRepository symbolRepository = mock(SymbolRepository.class);
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    PositionService service = new PositionService(
        positionRepository,
        accountRepository,
        quoteService,
        pnlCalculator,
        ledgerService,
        symbolRepository,
        mock(DemoExecutionGuard.class));

    List<Attempt> attempts = runConcurrently(
        () -> service.closePosition(userId, accountId, positionId),
        () -> service.closePosition(userId, accountId, positionId));

    assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
    assertThat(positionState.get().getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(accountState.get().getBalance()).isEqualByComparingTo("10010.00000000");
    assertThat(accountState.get().getUsedMargin()).isEqualByComparingTo("0");
    assertThat(accountState.get().getFreeMargin()).isEqualByComparingTo("10010.00000000");
    verify(ledgerService, times(1)).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, times(1)).recordTradePnl(any(), any(), any(), any());
  }

  @SafeVarargs
  private static List<Attempt> runConcurrently(Callable<?>... calls) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(calls.length);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Attempt>> futures = java.util.Arrays.stream(calls)
          .map(call -> executor.submit(() -> {
            start.await(2, TimeUnit.SECONDS);
            try {
              call.call();
              return new Attempt(true, null);
            } catch (Throwable error) {
              return new Attempt(false, error);
            }
          }))
          .toList();
      start.countDown();
      return futures.stream().map(future -> {
        try {
          return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }).toList();
    } finally {
      executor.shutdownNow();
    }
  }

  private static TradingAccountRepository accountRepository(
      AtomicReference<TradingAccountEntity> accountState,
      CountDownLatch staleReads
  ) {
    Object lock = new Object();
    Answer<Object> answer = invocation -> {
      String name = invocation.getMethod().getName();
      if ("findByIdAndUserId".equals(name) || "findById".equals(name)) {
        awaitBothIfNeeded(staleReads);
        return Optional.of(copy(accountState.get()));
      }
      if ("findByIdAndUserIdForUpdate".equals(name) || "findByIdForUpdate".equals(name)) {
        return Optional.of(copy(accountState.get()));
      }
      if ("reserveMarginIfAvailable".equals(name)) {
        BigDecimal amount = invocation.getArgument(1);
        synchronized (lock) {
          TradingAccountEntity account = accountState.get();
          if (account.getFreeMargin().compareTo(amount) < 0) {
            return 0;
          }
          TradingAccountEntity updated = copy(account);
          updated.setUsedMargin(updated.getUsedMargin().add(amount));
          updated.setFreeMargin(updated.getEquity().subtract(updated.getUsedMargin()));
          accountState.set(updated);
          return 1;
        }
      }
      if ("save".equals(name)) {
        TradingAccountEntity saved = copy((TradingAccountEntity) invocation.getArgument(0));
        accountState.set(saved);
        return invocation.getArgument(0);
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    };
    return mock(TradingAccountRepository.class, answer);
  }

  private static OrderRepository orderRepository(
      AtomicReference<OrderEntity> orderState,
      CountDownLatch staleReads
  ) {
    Object lock = new Object();
    Answer<Object> answer = invocation -> {
      String name = invocation.getMethod().getName();
      if ("findByUserIdAndAccountIdAndClientOrderId".equals(name) || "findByUserIdAndIdempotencyKey".equals(name)) {
        return Optional.empty();
      }
      if ("findByUserIdAndId".equals(name)) {
        awaitBothIfNeeded(staleReads);
        return Optional.of(copy(orderState.get()));
      }
      if ("findByIdForUpdate".equals(name)) {
        return Optional.of(copy(orderState.get()));
      }
      if ("cancelPending".equals(name)) {
        synchronized (lock) {
          OrderEntity current = orderState.get();
          if (current.getStatus() != OrderStatus.PENDING) {
            return 0;
          }
          OrderEntity update = invocation.getArgument(0);
          current.setStatus(OrderStatus.CANCELED);
          current.setCanceledAt(update.getCanceledAt());
          current.setRemainingQuantity(BigDecimal.ZERO);
          orderState.set(current);
          return 1;
        }
      }
      if ("save".equals(name)) {
        OrderEntity order = invocation.getArgument(0);
        if (order.getId() == null) {
          order.setId(UUID.randomUUID());
        }
        if (orderState != null) {
          orderState.set(copy(order));
        }
        return order;
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    };
    return mock(OrderRepository.class, answer);
  }

  private static PositionRepository positionRepository(
      AtomicReference<PositionEntity> positionState,
      CountDownLatch staleReads
  ) {
    Object lock = new Object();
    Answer<Object> answer = invocation -> {
      String name = invocation.getMethod().getName();
      if ("findById".equals(name)) {
        awaitBothIfNeeded(staleReads);
        return Optional.of(copy(positionState.get()));
      }
      if ("findByIdForUpdate".equals(name)) {
        return Optional.of(copy(positionState.get()));
      }
      if ("closeIfOpen".equals(name)) {
        synchronized (lock) {
          PositionEntity current = positionState.get();
          if (current.getStatus() != PositionStatus.OPEN) {
            return 0;
          }
          PositionEntity update = invocation.getArgument(0);
          positionState.set(copy(update));
          return 1;
        }
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    };
    return mock(PositionRepository.class, answer);
  }

  private static void awaitBothIfNeeded(CountDownLatch staleReads) throws InterruptedException {
    if (staleReads == null) {
      return;
    }
    staleReads.countDown();
    if (!staleReads.await(2, TimeUnit.SECONDS)) {
      throw new AssertionError("Timed out waiting for concurrent stale reads");
    }
  }

  private static OrderService orderService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      RiskCheckService riskCheckService,
      LedgerService ledgerService
  ) {
    return new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        mock(ExecutionAdapter.class),
        new OrderFillService(orderRepository, mock(TradeRepository.class), mock(PositionRepository.class), accountRepository, ledgerService),
        ledgerService,
        mock(WalletService.class),
        mock(OrderEventService.class),
        new OrderCommandFactory(),
        new OrderEntityFactory(),
        new OrderResponseMapper(),
        new OrderStatusPolicy(),
        mock(DemoExecutionGuard.class),
        mock(WalletBalanceRepository.class),
        mock(PositionRepository.class),
        mock(SpotPositionService.class));
  }

  private static CreateOrderRequest limitOrder(UUID accountId, String clientOrderId) {
    return new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        null,
        clientOrderId,
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"));
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId, BigDecimal balance, BigDecimal usedMargin) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBaseCurrency("USD");
    account.setBalance(balance);
    account.setEquity(balance);
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(balance.subtract(usedMargin));
    account.setLeverage(100);
    return account;
  }

  private static OrderEntity pendingOrder(UUID userId, UUID accountId, UUID orderId) {
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
    order.setHoldAmount(new BigDecimal("80.00000000"));
    order.setHoldCurrency("USD");
    order.setClientOrderId("client-pending-concurrent");
    order.setIdempotencyKey("client-pending-concurrent");
    return order;
  }

  private static PositionEntity openPosition(UUID accountId, UUID positionId) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10020"));
    position.setCurrentPrice(new BigDecimal("1.10020"));
    position.setMarginHeld(new BigDecimal("80.00000000"));
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setRealizedPnl(BigDecimal.ZERO);
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static SymbolEntity forexSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProductType(ProductType.FX_MARGIN);
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency("EUR");
    symbol.setQuoteCurrency("USD");
    symbol.setLotSize(new BigDecimal("100000"));
    symbol.setLeverage(100);
    return symbol;
  }

  private static TradingAccountEntity copy(TradingAccountEntity source) {
    TradingAccountEntity copy = new TradingAccountEntity();
    copy.setId(source.getId());
    copy.setUserId(source.getUserId());
    copy.setAccountType(source.getAccountType());
    copy.setBaseCurrency(source.getBaseCurrency());
    copy.setBalance(source.getBalance());
    copy.setEquity(source.getEquity());
    copy.setUsedMargin(source.getUsedMargin());
    copy.setFreeMargin(source.getFreeMargin());
    copy.setLeverage(source.getLeverage());
    copy.setStatus(source.getStatus());
    return copy;
  }

  private static OrderEntity copy(OrderEntity source) {
    OrderEntity copy = new OrderEntity();
    copy.setId(source.getId());
    copy.setUserId(source.getUserId());
    copy.setAccountId(source.getAccountId());
    copy.setSymbol(source.getSymbol());
    copy.setSide(source.getSide());
    copy.setOrderType(source.getOrderType());
    copy.setStatus(source.getStatus());
    copy.setLots(source.getLots());
    copy.setQuantity(source.getQuantity());
    copy.setRequestedPrice(source.getRequestedPrice());
    copy.setPrice(source.getPrice());
    copy.setFilledQuantity(source.getFilledQuantity());
    copy.setRemainingQuantity(source.getRemainingQuantity());
    copy.setHoldAmount(source.getHoldAmount());
    copy.setHoldCurrency(source.getHoldCurrency());
    copy.setClientOrderId(source.getClientOrderId());
    copy.setIdempotencyKey(source.getIdempotencyKey());
    copy.setCanceledAt(source.getCanceledAt());
    return copy;
  }

  private static PositionEntity copy(PositionEntity source) {
    PositionEntity copy = new PositionEntity();
    copy.setId(source.getId());
    copy.setAccountId(source.getAccountId());
    copy.setSymbol(source.getSymbol());
    copy.setSide(source.getSide());
    copy.setLots(source.getLots());
    copy.setOpenPrice(source.getOpenPrice());
    copy.setCurrentPrice(source.getCurrentPrice());
    copy.setFloatingPnl(source.getFloatingPnl());
    copy.setRealizedPnl(source.getRealizedPnl());
    copy.setMarginHeld(source.getMarginHeld());
    copy.setStatus(source.getStatus());
    copy.setClosedAt(source.getClosedAt());
    return copy;
  }

  private record Attempt(boolean success, Throwable error) {
  }

  private static void assertForUpdate(Class<?> repositoryType, String methodName, Class<?>... parameterTypes)
      throws Exception {
    String sql = selectSql(repositoryType.getMethod(methodName, parameterTypes));
    assertThat(sql).containsIgnoringCase("FOR UPDATE");
  }

  private static void assertSortedForUpdate(
      Class<?> repositoryType,
      String methodName,
      String expectedOrderBy,
      Class<?>... parameterTypes
  ) throws Exception {
    String sql = selectSql(repositoryType.getMethod(methodName, parameterTypes));
    assertThat(sql).containsIgnoringCase(expectedOrderBy);
    assertThat(sql).containsIgnoringCase("FOR UPDATE");
  }

  private static String selectSql(Method method) {
    Select select = method.getAnnotation(Select.class);
    assertThat(select).as("%s must declare mapper SQL", method).isNotNull();
    return String.join(" ", select.value()).replaceAll("\\s+", " ");
  }
}
