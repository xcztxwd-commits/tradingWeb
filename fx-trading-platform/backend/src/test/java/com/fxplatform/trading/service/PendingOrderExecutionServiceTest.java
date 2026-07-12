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
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
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
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private WalletService walletService;

  @Mock
  private SpotPositionService spotPositionService;

  @Mock
  private TradingTransactionExecutor transactionExecutor;

  @Mock
  private MarketBundleResolver marketBundleResolver;

  @Mock
  private FullFillCoordinator fullFillCoordinator;

  @Mock
  private PendingOrderExecutionProcessor pendingOrderExecutionProcessor;

  @BeforeEach
  void rowLockQueriesReturnTheScannedFixtures() {
    org.mockito.Mockito.lenient().when(accountRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
    org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> orderRepository.findByStatus(OrderStatus.PENDING).stream()
            .filter(order -> order.getId().equals(invocation.getArgument(0)))
            .findFirst());
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void spotScannerRetriesOneFreshBundleAndDelegatesWithoutAnOuterTransaction() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = p0PendingOrder(accountId, "scanner-retry");
    TradingAccountEntity account = account(accountId);
    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenReturn(spotBundle(), spotBundle());
    when(pendingOrderExecutionProcessor.process(eq(order), any()))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "first bundle expired"))
        .thenReturn(true);

    PendingOrderExecutionService service = p0Service();
    service.setPendingOrderExecutionProcessor(pendingOrderExecutionProcessor);

    assertThat(service.executePendingOrders()).isEqualTo(1);
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(pendingOrderExecutionProcessor, org.mockito.Mockito.times(2)).process(eq(order), any());
    verify(transactionExecutor, never()).execute(any());
    verify(riskCheckService, never()).checkOrder(any(), any());
  }

  @Test
  void failedP0CandidateDoesNotPreventTheNextCandidateFromFilling() {
    UUID firstAccountId = UUID.randomUUID();
    UUID secondAccountId = UUID.randomUUID();
    OrderEntity first = p0PendingOrder(firstAccountId, "first");
    OrderEntity second = p0PendingOrder(secondAccountId, "second");
    TradingAccountEntity firstAccount = account(firstAccountId);
    TradingAccountEntity secondAccount = account(secondAccountId);
    AtomicBoolean insideMutation = new AtomicBoolean();

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(first, second));
    when(accountRepository.findById(firstAccountId)).thenReturn(Optional.of(firstAccount));
    when(accountRepository.findById(secondAccountId)).thenReturn(Optional.of(secondAccount));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenAnswer(invocation -> {
          assertThat(insideMutation.get()).isFalse();
          return spotBundle();
        });
    when(fullFillCoordinator.execute(any(), any()))
        .thenThrow(new IllegalStateException("first locked mutation failed"))
        .thenReturn(fullFill());
    when(orderRepository.claimPending(second.getId())).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.doAnswer(invocation -> {
      insideMutation.set(true);
      try {
        return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideMutation.set(false);
      }
    }).when(transactionExecutor).execute(any());

    int filled = p0Service().executePendingOrders();

    assertThat(filled).isEqualTo(1);
    assertThat(first.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(first.getHoldAmount()).isEqualByComparingTo("100");
    assertThat(second.getStatus()).isEqualTo(OrderStatus.FILLED);
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(TradeEntity.class));
  }

  @Test
  void staleP0SnapshotAfterLocksLeavesPendingHoldAndNeverClaims() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = p0PendingOrder(accountId, "stale");
    TradingAccountEntity account = account(accountId);

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(spotBundle());
    when(fullFillCoordinator.execute(any(), any()))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "expired after row locks"));

    int filled = p0Service().executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("100");
    verify(orderRepository, never()).claimPending(order.getId());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.InOrder guardBeforeProvider = org.mockito.Mockito.inOrder(
        demoExecutionGuard, marketBundleResolver);
    guardBeforeProvider.verify(demoExecutionGuard)
        .requireDemo(account, ProductType.CRYPTO_SPOT, "BTCUSDT");
    guardBeforeProvider.verify(marketBundleResolver).resolveSpot(eq("BTCUSDT"), any());
    org.mockito.InOrder lockAndValidate = org.mockito.Mockito.inOrder(
        accountRepository,
        walletBalanceRepository,
        spotPositionService,
        orderRepository,
        fullFillCoordinator);
    lockAndValidate.verify(accountRepository).findByIdForUpdate(accountId);
    lockAndValidate.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    lockAndValidate.verify(spotPositionService).lockExisting(accountId);
    lockAndValidate.verify(orderRepository).findByIdForUpdate(order.getId());
    lockAndValidate.verify(fullFillCoordinator).execute(any(), any());
    verify(walletService, never()).lockBalancesInOrder(any(), any());
    verify(spotPositionService, never()).lockOrCreate(any(), any(), any());
  }

  @Test
  void p0StatusChangeAfterExistingStateLocksDoesNotEnsureMissingSpotRows() {
    UUID accountId = UUID.randomUUID();
    OrderEntity scanned = p0PendingOrder(accountId, "status-changed");
    OrderEntity locked = p0PendingOrder(accountId, "status-changed");
    locked.setId(scanned.getId());
    locked.setStatus(OrderStatus.CANCELED);
    TradingAccountEntity account = account(accountId);

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(scanned));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    org.mockito.Mockito.doReturn(Optional.of(locked))
        .when(orderRepository).findByIdForUpdate(scanned.getId());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(spotBundle());

    assertThat(p0Service().executePendingOrders()).isZero();

    verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    verify(spotPositionService).lockExisting(accountId);
    verify(walletService, never()).lockBalancesInOrder(any(), any());
    verify(spotPositionService, never()).lockOrCreate(any(), any(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
  }

  @Test
  void p0TriggerChangeAfterOrderLockDoesNotEnsureMissingSpotRows() {
    UUID accountId = UUID.randomUUID();
    OrderEntity scanned = p0PendingOrder(accountId, "trigger-changed");
    OrderEntity locked = p0PendingOrder(accountId, "trigger-changed");
    locked.setId(scanned.getId());
    locked.setRequestedPrice(new BigDecimal("99"));
    locked.setPrice(new BigDecimal("99"));
    TradingAccountEntity account = account(accountId);

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(scanned));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    org.mockito.Mockito.doReturn(Optional.of(locked))
        .when(orderRepository).findByIdForUpdate(scanned.getId());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(spotBundle());

    assertThat(p0Service().executePendingOrders()).isZero();

    verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    verify(spotPositionService).lockExisting(accountId);
    verify(walletService, never()).lockBalancesInOrder(any(), any());
    verify(spotPositionService, never()).lockOrCreate(any(), any(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
  }

  @Test
  void p0FinalFreshnessFailureAfterEnsureLeavesClaimOrderAndTradeUntouchedInRequiredOrder() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = p0PendingOrder(accountId, "final-stale");
    TradingAccountEntity account = account(accountId);
    FullFillResult result = fullFill();

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(spotBundle());
    when(fullFillCoordinator.execute(any(), any())).thenReturn(result);
    org.mockito.Mockito.doThrow(new BusinessException("MARKET_DATA_STALE", "expired after ensure"))
        .when(fullFillCoordinator).requireFresh(result);

    assertThat(p0Service().executePendingOrders()).isZero();

    org.mockito.InOrder sequence = org.mockito.Mockito.inOrder(
        accountRepository,
        walletBalanceRepository,
        spotPositionService,
        orderRepository,
        fullFillCoordinator,
        walletService);
    sequence.verify(accountRepository).findByIdForUpdate(accountId);
    sequence.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    sequence.verify(spotPositionService).lockExisting(accountId);
    sequence.verify(orderRepository).findByIdForUpdate(order.getId());
    sequence.verify(fullFillCoordinator).execute(any(), any());
    sequence.verify(walletService).lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    sequence.verify(spotPositionService).lockOrCreate(accountId, "BTC", "USDT");
    sequence.verify(fullFillCoordinator).requireFresh(result);
    verify(orderRepository, never()).claimPending(order.getId());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void concurrentP0WorkersCreateAtMostOneTradeForOnePendingOrder() throws Exception {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = p0PendingOrder(accountId, "concurrent");
    TradingAccountEntity account = account(accountId);
    AtomicBoolean claimed = new AtomicBoolean();
    CountDownLatch start = new CountDownLatch(1);

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(spotBundle());
    when(fullFillCoordinator.execute(any(), any())).thenReturn(fullFill());
    when(orderRepository.claimPending(order.getId()))
        .thenAnswer(invocation -> claimed.compareAndSet(false, true) ? 1 : 0);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PendingOrderExecutionService service = p0Service();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> {
        start.await(2, TimeUnit.SECONDS);
        return service.executePendingOrders();
      });
      var second = workers.submit(() -> {
        start.await(2, TimeUnit.SECONDS);
        return service.executePendingOrders();
      });
      start.countDown();

      assertThat(first.get(3, TimeUnit.SECONDS) + second.get(3, TimeUnit.SECONDS)).isEqualTo(1);
    }

    verify(orderRepository, org.mockito.Mockito.atLeastOnce()).claimPending(order.getId());
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(TradeEntity.class));
    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator, org.mockito.Mockito.atLeastOnce()).execute(request.capture(), any());
    assertThat(request.getAllValues())
        .allSatisfy(value -> assertThat(value.executionPath()).isEqualTo(FullFillExecutionPath.RESTING_LIMIT));
  }

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
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor);

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
    verify(demoExecutionGuard, org.mockito.Mockito.times(2))
        .requireDemo(account, ProductType.CRYPTO_SPOT, "EURUSD");
    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletBalanceRepository, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdForUpdate(accountId);
    accountWalletOrder.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    accountWalletOrder.verify(orderRepository).findByIdForUpdate(order.getId());
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
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor);

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
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.claimPending(order.getId())).thenReturn(0);

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor);

    int filled = service.executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(accountRepository, org.mockito.Mockito.times(2)).findById(accountId);
    verify(accountRepository).findByIdForUpdate(accountId);
    verify(riskCheckService).checkOrder(any(), any());
    verify(orderRepository, never()).save(order);
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void spotPendingLocksExactWalletAssetsBeforeTheOrderRow() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(
        accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("50000.00000000"));
    order.setSymbol("BTCUSDT");
    TradingAccountEntity account = account(accountId);

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote(new BigDecimal("49999.00000000"), new BigDecimal("50000.00000000")));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(any(), any())).thenReturn(new BigDecimal("500.00000000"));
    when(orderRepository.claimPending(order.getId())).thenReturn(0);

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor);

    assertThat(service.executePendingOrders()).isZero();

    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletService, spotPositionService, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdForUpdate(accountId);
    accountWalletOrder.verify(walletService).lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    accountWalletOrder.verify(spotPositionService).lockOrCreate(accountId, "BTC", "USDT");
    accountWalletOrder.verify(orderRepository).findByIdForUpdate(order.getId());
  }

  @Test
  void keepsBuyLimitPendingWhenAskStaysAboveRequestedPrice() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId)));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote(new BigDecimal("1.08010"), new BigDecimal("1.08012")));

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor);

    int filled = service.executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(accountRepository).findById(accountId);
    verify(orderRepository, never()).save(order);
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void keepsPendingOrderWhenQuoteIsStale() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = pendingOrder(accountId, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("1.08000"));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId)));
    when(quoteService.freshQuote("EURUSD")).thenThrow(new BusinessException("QUOTE_STALE", "Quote is stale"));

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor);

    int filled = service.executePendingOrders();

    assertThat(filled).isZero();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(accountRepository).findById(accountId);
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

  private PendingOrderExecutionService p0Service() {
    return new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        new OrderFillService(
            orderRepository,
            tradeRepository,
            positionRepository,
            accountRepository,
            ledgerService),
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        marketBundleResolver,
        fullFillCoordinator);
  }

  private static OrderEntity p0PendingOrder(UUID accountId, String clientOrderId) {
    OrderEntity order = pendingOrder(
        accountId,
        OrderSide.BUY,
        OrderType.LIMIT,
        new BigDecimal("101"));
    order.setSymbol("BTCUSDT");
    order.setClientOrderId(clientOrderId);
    order.setIdempotencyKey(clientOrderId);
    order.setLots(new BigDecimal("0.01"));
    order.setQuantity(new BigDecimal("0.01"));
    order.setBaseQuantity(new BigDecimal("0.01"));
    order.setRemainingQuantity(new BigDecimal("0.01"));
    order.setHoldAmount(new BigDecimal("100"));
    order.setHoldCurrency("USDT");
    order.setLeverage(1);
    order.setProductType(ProductType.CRYPTO_SPOT);
    return order;
  }

  private static SpotMarketBundle spotBundle() {
    Instant now = Instant.now();
    return new SpotMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        null,
        List.of(),
        List.of(),
        now.minusSeconds(1),
        now.plusSeconds(2));
  }

  private static FullFillResult fullFill() {
    Instant now = Instant.now();
    return new FullFillResult(
        new BigDecimal("100"),
        now,
        new BigDecimal("0.01"),
        BigDecimal.ZERO,
        new BigDecimal("0.0002"),
        new BigDecimal("0.000002"),
        "BTC",
        com.fxplatform.trading.enums.LiquidityRole.MAKER,
        BigDecimal.ZERO,
        MarketSourceMode.PUBLIC_EXTERNAL,
        "binance",
        "BTCUSDT",
        now.minusSeconds(1),
        now.plusSeconds(2));
  }
}
