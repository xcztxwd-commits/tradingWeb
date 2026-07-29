package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Task6SpotOrderServiceTest {

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
  @Mock private TradeRepository tradeRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private LedgerService ledgerService;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private SpotPositionService spotPositionService;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private SymbolRepository symbolRepository;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private OrderHoldCalculator orderHoldCalculator;

  @BeforeEach
  void runMutationsInline() {
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    WalletBalanceEntity quoteBalance = new WalletBalanceEntity();
    quoteBalance.setTotal(new BigDecimal("1000000.00000000"));
    quoteBalance.setAvailable(new BigDecimal("1000000.00000000"));
    quoteBalance.setLocked(BigDecimal.ZERO);
    org.mockito.Mockito.lenient().when(walletService.getOrCreateBalance(
        any(UUID.class), eq(WalletType.SPOT), eq("USDT")))
        .thenReturn(quoteBalance);
  }

  @Test
  void marketBuyPreservesQuoteInputButExecutesCanonicalBaseFromOneBundle() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
        "100.00", null, null, "quote-market");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillPricingProjection pricing = pricing("100.0100", LiquidityRole.TAKER);
    FullFillResult canonicalFill = fill("0.9994", "100.0100", LiquidityRole.TAKER);

    stubNewOrder(userId, accountId, "quote-market", account, symbol, bundle);
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.MARKET,
        null,
        ExecutableMarketSnapshot.from(bundle))).thenReturn(pricing);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(canonicalFill);

    OrderResponse response = service().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.quantity()).isEqualByComparingTo("100.00");
    assertThat(response.originalQuantity()).isEqualByComparingTo("100.00");
    assertThat(response.quantityUnit()).isEqualTo(QuantityUnit.QUOTE);
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.9994");
    assertThat(response.marginMode()).isEqualTo(MarginMode.CASH);
    assertThat(response.positionSide()).isEqualTo(PositionSide.BOTH);

    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    ArgumentCaptor<ExecutableMarketSnapshot> executionSnapshot =
        ArgumentCaptor.forClass(ExecutableMarketSnapshot.class);
    verify(fullFillCoordinator).execute(fillRequest.capture(), executionSnapshot.capture());
    assertThat(fillRequest.getValue().requestedBaseQuantity()).isEqualByComparingTo("0.9994");
    assertThat(fillRequest.getValue().executionIntent().quantity()).isEqualByComparingTo("0.9994");
    assertThat(fillRequest.getValue().executionIntent().quantityUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(executionSnapshot.getValue()).isEqualTo(ExecutableMarketSnapshot.from(bundle));
    verify(marketBundleResolver).resolveSpot(eq("BTCUSDT"), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(tradeRepository).save(any(TradeEntity.class));
    org.mockito.InOrder stateLocks = org.mockito.Mockito.inOrder(
        fullFillCoordinator, walletBalanceRepository, walletService,
        spotPositionService, orderRepository);
    stateLocks.verify(fullFillCoordinator).requireFresh(ExecutableMarketSnapshot.from(bundle));
    stateLocks.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    stateLocks.verify(walletService).lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    stateLocks.verify(spotPositionService).lockExisting(accountId);
    stateLocks.verify(spotPositionService).lockOrCreate(accountId, "BTC", "USDT");
    stateLocks.verify(fullFillCoordinator).execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class));
    stateLocks.verify(fullFillCoordinator).requireFresh(canonicalFill);
    stateLocks.verify(orderRepository, org.mockito.Mockito.times(2)).save(any(OrderEntity.class));
  }

  @Test
  void marketSellPreservesBaseInputAndUsesCanonicalMarketTakerPath() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.SELL, OrderType.MARKET, QuantityUnit.BASE,
        "0.2500", null, null, "base-market-sell");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillPricingProjection pricing = pricing("98.9901", LiquidityRole.TAKER);
    FullFillResult fill = new FullFillResult(
        new BigDecimal("98.9901"), Instant.now(), new BigDecimal("0.2500"),
        BigDecimal.ZERO, new BigDecimal("0.0005"), new BigDecimal("0.01237376"),
        "USDT", LiquidityRole.TAKER, new BigDecimal("0.0099"),
        MarketSourceMode.PUBLIC_EXTERNAL, "binance", "BTCUSDT",
        Instant.now().minusSeconds(1), Instant.now().plusSeconds(30));

    stubNewOrder(userId, accountId, "base-market-sell", account, symbol, bundle);
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT, OrderSide.SELL, FullFillExecutionPath.MARKET,
        null, ExecutableMarketSnapshot.from(bundle))).thenReturn(pricing);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(fill);

    OrderResponse response = service().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.quantity()).isEqualByComparingTo("0.2500");
    assertThat(response.originalQuantity()).isEqualByComparingTo("0.2500");
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.2500");
    assertThat(response.quantityUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(response.feeAsset()).isEqualTo("USDT");
    assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER);

    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(fillRequest.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(fillRequest.getValue().executionPath()).isEqualTo(FullFillExecutionPath.MARKET);
    assertThat(fillRequest.getValue().requestedBaseQuantity()).isEqualByComparingTo("0.2500");
    assertThat(fillRequest.getValue().executionIntent().quantityUnit()).isEqualTo(QuantityUnit.BASE);
    verify(marketBundleResolver).resolveSpot(eq("BTCUSDT"), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(tradeRepository).save(any(TradeEntity.class));
  }

  @Test
  void nonMarketableLimitUsesSameBundleForRulesAndExactWalletHold() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "90", null, "resting-limit");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    OrderHoldCalculator.OrderHold hold =
        new OrderHoldCalculator.OrderHold(new BigDecimal("9.00450000"), "USDT");

    stubNewOrder(userId, accountId, "resting-limit", account, symbol, bundle);
    when(orderHoldCalculator.limit(
        OrderSide.BUY,
        new BigDecimal("0.1000"),
        new BigDecimal("90"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(hold);

    OrderResponse response = service().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo("9.00450000");
    assertThat(response.holdCurrency()).isEqualTo("USDT");
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.1000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(hold.amount()), eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void depthNonMarketableLimitUsesDepthBookInsteadOfSnapshotTopAndKeepsExactHold() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "105", null, "depth-resting-limit");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle snapshotTopWouldTake = bundle("99", "100", "99.5");
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("98"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("110"), new BigDecimal("5"))),
        null);
    DepthOrderExecutionService depthExecution = new DepthOrderExecutionService(
        () -> depthPolicy,
        tradeRepository,
        org.mockito.Mockito.mock(OrderFillService.class),
        orderEventService);

    stubNewOrder(
        userId,
        accountId,
        "depth-resting-limit",
        account,
        symbol,
        snapshotTopWouldTake);
    OrderService service = service();
    service.setDepthOrderExecutionService(depthExecution);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo("105.21000000");
    assertThat(response.holdCurrency()).isEqualTo("USDT");
    assertThat(response.baseQuantity()).isEqualByComparingTo("1.0000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("105.21000000")),
        eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(fullFillCoordinator, never()).project(any(), any(), any(), any(), any());
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void depthMarketableLimitAppliesTwoFillsWithExactCumulativeState() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "105", null, "depth-two-fill");
    TradingAccountEntity account = account(userId, accountId);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(
            new DemoBookLevel(new BigDecimal("100"), new BigDecimal("0.4")),
            new DemoBookLevel(new BigDecimal("101"), new BigDecimal("0.6"))),
        null);
    stubNewOrder(
        userId,
        accountId,
        "depth-two-fill",
        account,
        symbol(),
        bundle("99", "110", "99.5"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo("1.00000000");
    assertThat(response.remainingQuantity()).isZero();
    assertThat(response.avgFillPrice()).isEqualByComparingTo("100.60000000");
    assertThat(response.fee()).isEqualByComparingTo("0.20120000");
    assertThat(response.holdAmount()).isZero();
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("100.80120000")),
        eq("ORDER"), any(UUID.class),
        eq("Spot order wallet locked for DEPTH execution"), eq("SPOT_ORDER_LOCK"));
    verify(tradeRepository, org.mockito.Mockito.times(2)).save(any(TradeEntity.class));
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_PARTIALLY_FILLED"), eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PARTIALLY_FILLED), eq(null), any());
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_FILLED"), eq(OrderStatus.PARTIALLY_FILLED),
        eq(OrderStatus.FILLED), eq(null), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthIocPartialReleasesExactTailAndPreservesFillStateWhenCancelled() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "105", null, "depth-ioc-partial", TimeInForce.IOC, false);
    TradingAccountEntity account = account(userId, accountId);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("100"), new BigDecimal("0.4"))),
        null);
    stubNewOrder(
        userId,
        accountId,
        "depth-ioc-partial",
        account,
        symbol(),
        bundle("99", "110", "99.5"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo("0.40000000");
    assertThat(response.remainingQuantity()).isZero();
    assertThat(response.avgFillPrice()).isEqualByComparingTo("100.00000000");
    assertThat(response.fee()).isEqualByComparingTo("0.08000000");
    assertThat(response.holdAmount()).isZero();
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("103.20600000")),
        eq("ORDER"), any(UUID.class),
        eq("Spot order wallet locked for DEPTH execution"), eq("SPOT_ORDER_LOCK"));
    verify(walletService).releaseLockedWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("63.12600000")),
        eq("ORDER"), any(UUID.class),
        eq("DEPTH IOC Spot remainder released"), eq("SPOT_ORDER_RELEASE"));
    verify(tradeRepository).save(any(TradeEntity.class));
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_PARTIALLY_FILLED"), eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PARTIALLY_FILLED), eq(null), any());
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_CANCELED"), eq(OrderStatus.PARTIALLY_FILLED),
        eq(OrderStatus.CANCELLED), eq(null), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthMarketBuyUsesEffectivePersistenceStepInsteadOfRawInstrumentStep() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
        "0.02000000", null, null, "depth-effective-step");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("1"))),
        List.of(new DemoBookLevel(new BigDecimal("100"), new BigDecimal("0.0001"))),
        null);
    stubNewOrder(
        userId,
        accountId,
        "depth-effective-step",
        account,
        symbol,
        bundle());
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rulesWithStep("0.00001"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.quantity()).isEqualByComparingTo("0.02000000");
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.0001");
    assertThat(response.filledQuantity()).isEqualByComparingTo("0.00010000");
    verify(fullFillCoordinator, never()).project(any(), any(), any(), any(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthMarketBuyMapsEmptyBookToStableMarketDataError() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
        "100.00000000", null, null, "depth-empty-book");
    TradingAccountEntity account = account(userId, accountId);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null);
    stubNewOrderPlanning(
        userId,
        accountId,
        "depth-empty-book",
        account,
        symbol(),
        bundle());

    assertThatThrownBy(() -> service(depthPolicy).createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARKET_BUNDLE_INCOMPLETE"));

    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
  }

  @Test
  void depthPolicyRotationDuringPlanningRetriesWithOneCurrentPolicyInstance() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "105", null, "depth-policy-rotation");
    DemoExecutionPolicy first = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(), List.of(new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE)), null);
    DemoExecutionPolicy current = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(), List.of(new DemoBookLevel(new BigDecimal("110"), BigDecimal.ONE)), null);
    AtomicInteger policyReads = new AtomicInteger();
    DemoExecutionPolicyProvider rotating = () ->
        policyReads.incrementAndGet() == 1 ? first : current;
    stubNewOrder(
        userId,
        accountId,
        "depth-policy-rotation",
        account(userId, accountId),
        symbol(),
        bundle());

    OrderResponse response = service(rotating, Clock.systemUTC()).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo("105.21000000");
    assertThat(policyReads.get()).isGreaterThanOrEqualTo(3);
    verify(orderRepository).save(any(OrderEntity.class));
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void depthPendingPlanThatExpiresWhileWalletLocksWaitFailsBeforeAnyWrite() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "105", null, "depth-stale-after-locks");
    Instant now = Instant.parse("2026-07-17T15:30:00Z");
    MutableClock clock = new MutableClock(now, ZoneId.of("UTC"));
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(), List.of(new DemoBookLevel(new BigDecimal("110"), BigDecimal.ONE)), null);
    TradingAccountEntity account = account(userId, accountId);
    SpotMarketBundle expiring = new SpotMarketBundle(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"), new BigDecimal("100"), new BigDecimal("99.5"),
        null, List.of(), List.of(), now.minusSeconds(1), now.plusSeconds(1));
    stubNewOrderPlanning(
        userId,
        accountId,
        "depth-stale-after-locks",
        account,
        symbol(),
        expiring);
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(walletBalanceRepository.findByAccountIdForUpdate(accountId))
        .thenAnswer(invocation -> {
          clock.set(now.plusSeconds(2));
          return List.of();
        });

    assertThatThrownBy(() -> service(() -> depthPolicy, clock).createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARKET_DATA_STALE"));

    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockBalancesInOrder(eq(accountId), any());
    verify(spotPositionService, never()).lockOrCreate(any(), any(), any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void depthFokMultiLevelFullAppliesEveryFillAndBypassesFullFillCoordinator() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.5000", "105", null, "depth-fok-full", TimeInForce.FOK, false);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(
            new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE),
            new DemoBookLevel(new BigDecimal("102"), BigDecimal.ONE)),
        null);
    stubNewOrder(
        userId, accountId, "depth-fok-full", account(userId, accountId), symbol(),
        bundle("99", "110", "99.5"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo("1.50000000");
    assertThat(response.remainingQuantity()).isZero();
    assertThat(response.avgFillPrice()).isEqualByComparingTo("100.66666667");
    assertThat(response.fee()).isEqualByComparingTo("0.30200000");
    assertThat(response.holdAmount()).isZero();
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("151.30200000")),
        eq("ORDER"), any(UUID.class),
        eq("Spot order wallet locked for DEPTH execution"), eq("SPOT_ORDER_LOCK"));
    verify(tradeRepository, org.mockito.Mockito.times(2)).save(any(TradeEntity.class));
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_PARTIALLY_FILLED"), eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PARTIALLY_FILLED), eq(null), any());
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_FILLED"), eq(OrderStatus.PARTIALLY_FILLED),
        eq(OrderStatus.FILLED), eq(null), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(fullFillCoordinator, never()).project(any(), any(), any(), any(), any());
  }

  @Test
  void depthCapPartialGtcRetainsExactFutureTickHold() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "2.0000", "105", null, "depth-cap-partial");
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(
            new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE),
            new DemoBookLevel(new BigDecimal("102"), BigDecimal.ONE)),
        new BigDecimal("1.5000"));
    stubNewOrder(
        userId, accountId, "depth-cap-partial", account(userId, accountId), symbol(),
        bundle("99", "110", "99.5"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo("1.50000000");
    assertThat(response.remainingQuantity()).isEqualByComparingTo("0.50000000");
    assertThat(response.avgFillPrice()).isEqualByComparingTo("100.66666667");
    assertThat(response.fee()).isEqualByComparingTo("0.30200000");
    assertThat(response.holdAmount()).isEqualByComparingTo("52.60500000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("203.90700000")),
        eq("ORDER"), any(UUID.class),
        eq("Spot order wallet locked for DEPTH execution"), eq("SPOT_ORDER_LOCK"));
    verify(tradeRepository, org.mockito.Mockito.times(2)).save(any(TradeEntity.class));
    verify(walletService, never()).releaseLockedWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthFokRejectsWhenPerTickCapPreventsFullExecution() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "2.0000", "105", null, "depth-fok-cap-reject", TimeInForce.FOK, false);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(
            new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE),
            new DemoBookLevel(new BigDecimal("102"), BigDecimal.ONE)),
        new BigDecimal("1.5000"));
    stubNewOrderPlanning(
        userId, accountId, "depth-fok-cap-reject", account(userId, accountId), symbol(),
        bundle("99", "110", "99.5"));

    assertThatThrownBy(() -> service(depthPolicy).createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void depthIocZeroFillUsesDepthBookAndCancelsWithoutFinancialMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "100", null, "depth-ioc-zero", TimeInForce.IOC, false);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("101"), new BigDecimal("2"))),
        null);
    stubNewOrder(
        userId, accountId, "depth-ioc-zero", account(userId, accountId), symbol(),
        bundle("89", "90", "89.5"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name());
    assertThat(response.filledQuantity()).isZero();
    assertThat(response.remainingQuantity()).isZero();
    assertThat(response.holdAmount()).isZero();
    verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    verify(spotPositionService).lockExisting(accountId);
    verify(walletService, never()).lockBalancesInOrder(any(), any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderEventService).record(
        any(UUID.class), eq("ORDER_CANCELED"), eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.CANCELLED), eq(null), any());
  }

  @Test
  void depthPostOnlyAcceptsWhenSnapshotWouldTakeButDepthBookWouldRest() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "100", null, "depth-post-only-rest", TimeInForce.GTC, true);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("110"), new BigDecimal("2"))),
        null);
    stubNewOrder(
        userId, accountId, "depth-post-only-rest", account(userId, accountId), symbol(),
        bundle("89", "90", "89.5"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.postOnly()).isTrue();
    assertThat(response.holdAmount()).isEqualByComparingTo("100.20000000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("100.20000000")),
        eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthPostOnlyRejectsWhenSnapshotWouldRestButDepthBookWouldTake() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "1.0000", "100", null, "depth-post-only-take", TimeInForce.GTC, true);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("98"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("2"))),
        null);
    stubNewOrderPlanning(
        userId, accountId, "depth-post-only-take", account(userId, accountId), symbol(),
        bundle("109", "110", "109.5"));

    assertThatThrownBy(() -> service(depthPolicy).createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void depthMarketBuyMapsTooSmallBudgetToStableQuantityError() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
        "0.00500000", null, null, "depth-budget-too-small");
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE)),
        null);
    stubNewOrderPlanning(
        userId, accountId, "depth-budget-too-small", account(userId, accountId), symbol(),
        bundle());

    assertThatThrownBy(() -> service(depthPolicy).createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("QUANTITY_CONVERTS_TO_ZERO"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
  }

  @Test
  void depthPendingStopLimitPreservesSourceContractAndActivationHold() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_LIMIT, QuantityUnit.BASE,
        "1.0000", "105", "120", "depth-stop-limit-pending", TimeInForce.GTC, false);
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("100"), new BigDecimal("2"))),
        null);
    stubNewOrder(
        userId, accountId, "depth-stop-limit-pending", account(userId, accountId), symbol(),
        bundle("99", "100", "100"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());
    assertThat(response.orderType()).isEqualTo(OrderType.STOP_LIMIT.name());
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.LIMIT);
    assertThat(response.triggerPrice()).isEqualByComparingTo("120");
    assertThat(response.holdAmount()).isEqualByComparingTo("105.21000000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("105.21000000")),
        eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
    verify(tradeRepository, never()).save(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthTriggeredStopMarketExecutesAsMarketButPersistsSourceContract() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.BUY, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "1.0000", null, "99", "depth-stop-market-triggered");
    DemoExecutionPolicy depthPolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.001"), new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), new BigDecimal("5"))),
        List.of(new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE)),
        null);
    stubNewOrder(
        userId, accountId, "depth-stop-market-triggered", account(userId, accountId), symbol(),
        bundle("99", "100", "100"));

    OrderResponse response = service(depthPolicy).createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.orderType()).isEqualTo(OrderType.STOP_MARKET.name());
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(response.executionPrice()).isEqualByComparingTo("100");
    assertThat(response.fee()).isEqualByComparingTo("0.20000000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("100.20000000")),
        eq("ORDER"), any(UUID.class),
        eq("Spot order wallet locked for DEPTH execution"), eq("SPOT_ORDER_LOCK"));
    verify(tradeRepository).save(any(TradeEntity.class));
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void marketablePostOnlyLimitRejectsBeforeAnyPersistenceOrFinancialMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "100", null, "post-only-take", TimeInForce.GTC, true);
    stubBeforeMutation(
        userId, accountId, "post-only-take", account(userId, accountId), symbol(), bundle());

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));

    verify(orderRepository, never()).save(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void restingPostOnlyLimitPersistsPendingWithItsNormalHold() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "99", null, "post-only-rest", TimeInForce.GTC, true);
    SpotMarketBundle bundle = bundle();
    OrderHoldCalculator.OrderHold hold =
        new OrderHoldCalculator.OrderHold(new BigDecimal("9.90495000"), "USDT");
    stubNewOrder(
        userId, accountId, "post-only-rest", account(userId, accountId), symbol(), bundle);
    when(orderHoldCalculator.limit(
        OrderSide.BUY, new BigDecimal("0.1000"), new BigDecimal("99"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(hold);

    OrderResponse response = service().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.postOnly()).isTrue();
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(response.holdAmount()).isEqualByComparingTo(hold.amount());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(hold.amount()), eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
  }

  @Test
  void iocNonMarketableLimitPersistsOnlyOneTerminalCancelledLifecycle() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "99", null, "ioc-cancel", TimeInForce.IOC, false);
    stubNewOrder(
        userId, accountId, "ioc-cancel", account(userId, accountId), symbol(), bundle());

    OrderResponse response = service().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.IOC);
    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    verify(orderEventService).record(
        eq(saved.getValue().getId()), eq("ORDER_CANCELED"), eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.CANCELLED), eq(null), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void fokNonMarketableLimitRejectsBeforeAnyPersistenceOrFinancialMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "99", null, "fok-reject", TimeInForce.FOK, false);
    stubBeforeMutation(
        userId, accountId, "fok-reject", account(userId, accountId), symbol(), bundle());

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));

    verify(orderRepository, never()).save(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void stopMarketRejectsNonGtcTimeInForceBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "0.1000", null, "110", "stop-market-ioc", TimeInForce.IOC, false);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "stop-market-ioc")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "stop-market-ioc"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_SPOT_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void stopMarketRejectsPostOnlyBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "0.1000", null, "110", "stop-market-post-only", TimeInForce.GTC, true);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "stop-market-post-only")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "stop-market-post-only"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_SPOT_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void marketRejectsPostOnlyBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
        "100", null, null, "market-post-only", TimeInForce.GTC, true);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "market-post-only")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "market-post-only"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_SPOT_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void limitRejectsPostOnlyWithIocBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "99", null, "limit-post-only-ioc", TimeInForce.IOC, true);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "limit-post-only-ioc")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "limit-post-only-ioc"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_SPOT_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void iocAndFokMarketOrMarketableLimitFillFullyAsTaker() {
    UUID userId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    UUID iocMarketAccount = UUID.randomUUID();
    UUID iocLimitAccount = UUID.randomUUID();
    UUID fokMarketAccount = UUID.randomUUID();
    UUID fokLimitAccount = UUID.randomUUID();
    List<CreateOrderRequest> requests = List.of(
        advancedRequest(
            iocMarketAccount, OrderSide.SELL, OrderType.MARKET, QuantityUnit.BASE,
            "0.1000", null, null, "ioc-market", TimeInForce.IOC, false),
        advancedRequest(
            iocLimitAccount, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
            "0.1000", "100", null, "ioc-limit", TimeInForce.IOC, false),
        advancedRequest(
            fokMarketAccount, OrderSide.SELL, OrderType.MARKET, QuantityUnit.BASE,
            "0.2000", null, null, "fok-market", TimeInForce.FOK, false),
        advancedRequest(
            fokLimitAccount, OrderSide.SELL, OrderType.LIMIT, QuantityUnit.BASE,
            "0.2000", "99", null, "fok-limit", TimeInForce.FOK, false));
    SpotMarketBundle bundle = bundle();
    stubNewOrder(
        userId, iocMarketAccount, "ioc-market",
        account(userId, iocMarketAccount), symbol(), bundle);
    stubSecondOrderIdentity(userId, iocLimitAccount, "ioc-limit");
    stubSecondOrderIdentity(userId, fokMarketAccount, "fok-market");
    stubSecondOrderIdentity(userId, fokLimitAccount, "fok-limit");
    when(fullFillCoordinator.execute(
        any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(
            fillForSide(OrderSide.SELL, "0.1000", "99", LiquidityRole.TAKER),
            fillForSide(OrderSide.BUY, "0.1000", "100", LiquidityRole.TAKER),
            fillForSide(OrderSide.SELL, "0.2000", "99", LiquidityRole.TAKER),
            fillForSide(OrderSide.SELL, "0.2000", "99", LiquidityRole.TAKER));
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.SELL,
        FullFillExecutionPath.MARKET,
        null,
        ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("99", LiquidityRole.TAKER));

    OrderService service = service();
    List<OrderResponse> responses = requests.stream()
        .map(request -> service.createOrder(principal, request))
        .toList();

    assertThat(responses).extracting(OrderResponse::status)
        .containsOnly(OrderStatus.FILLED.name());
    assertThat(responses).extracting(OrderResponse::timeInForce)
        .containsExactly(TimeInForce.IOC, TimeInForce.IOC, TimeInForce.FOK, TimeInForce.FOK);
    assertThat(responses).extracting(OrderResponse::liquidityRole)
        .containsOnly(LiquidityRole.TAKER);
    ArgumentCaptor<FullFillRequest> fills = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator, org.mockito.Mockito.times(4)).execute(
        fills.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(fills.getAllValues()).extracting(FullFillRequest::executionPath)
        .containsExactly(
            FullFillExecutionPath.MARKET,
            FullFillExecutionPath.IMMEDIATE_LIMIT,
            FullFillExecutionPath.MARKET,
            FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(fills.getAllValues()).allSatisfy(fill ->
        assertThat(fill.executionIntent().timeInForce())
            .isIn(TimeInForce.IOC, TimeInForce.FOK));
    verify(orderHoldCalculator, never()).limit(any(), any(), any(), any());
  }

  @Test
  void fokMarketabilityRetriesOneStaleSnapshotBeforeRejectingWithoutMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "99", null, "fok-stale", TimeInForce.FOK, false);
    SpotMarketBundle stale = bundle();
    SpotMarketBundle fresh = bundle();
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "fok-stale")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "fok-stale"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenReturn(stale, fresh);
    org.mockito.Mockito.doThrow(new BusinessException(
            ErrorCode.MARKET_DATA_STALE,
            "first FOK snapshot expired"))
        .doNothing()
        .when(fullFillCoordinator)
        .requireFresh(any(ExecutableMarketSnapshot.class));

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));

    verify(marketBundleResolver, org.mockito.Mockito.times(2))
        .resolveSpot(eq("BTCUSDT"), any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
  }

  @Test
  void immediatelyMarketableLimitBuyAndSellUseTakerFullFillPath() {
    UUID userId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    UUID buyAccountId = UUID.randomUUID();
    UUID sellAccountId = UUID.randomUUID();
    CreateOrderRequest buy = request(
        buyAccountId, OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
        "0.1000", "101", null, "immediate-limit-buy");
    CreateOrderRequest sell = request(
        sellAccountId, OrderSide.SELL, OrderType.LIMIT, QuantityUnit.BASE,
        "0.2000", "98", null, "immediate-limit-sell");
    SpotMarketBundle bundle = bundle();
    SymbolEntity sharedSymbol = symbol();
    stubNewOrder(userId, buyAccountId, "immediate-limit-buy",
        account(userId, buyAccountId), sharedSymbol, bundle);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, sellAccountId, "immediate-limit-sell")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "immediate-limit-sell"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(sellAccountId, userId))
        .thenReturn(Optional.of(account(userId, sellAccountId)));
    when(accountRepository.findByIdAndUserIdForUpdate(sellAccountId, userId))
        .thenReturn(Optional.of(account(userId, sellAccountId)));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(
            fillForSide(OrderSide.BUY, "0.1000", "100", LiquidityRole.TAKER),
            fillForSide(OrderSide.SELL, "0.2000", "99", LiquidityRole.TAKER));

    OrderService service = service();
    OrderResponse buyResponse = service.createOrder(principal, buy);
    OrderResponse sellResponse = service.createOrder(principal, sell);

    assertThat(buyResponse.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(sellResponse.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(buyResponse.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(sellResponse.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    ArgumentCaptor<FullFillRequest> requests = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator, org.mockito.Mockito.times(2)).execute(
        requests.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(requests.getAllValues()).extracting(FullFillRequest::executionPath)
        .containsExactly(FullFillExecutionPath.IMMEDIATE_LIMIT, FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(requests.getAllValues()).extracting(FullFillRequest::limitPrice)
        .containsExactly(new BigDecimal("101"), new BigDecimal("98"));
    verify(orderHoldCalculator, never()).limit(any(), any(), any(), any());
  }

  @Test
  void nonMarketableLimitSellLocksExactBaseHoldWithoutExecuting() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = request(
        accountId, OrderSide.SELL, OrderType.LIMIT, QuantityUnit.BASE,
        "0.2000", "101", null, "resting-limit-sell");
    SpotMarketBundle bundle = bundle();
    OrderHoldCalculator.OrderHold hold =
        new OrderHoldCalculator.OrderHold(new BigDecimal("0.2000"), "BTC");
    stubNewOrder(userId, accountId, "resting-limit-sell",
        account(userId, accountId), symbol(), bundle);
    when(orderHoldCalculator.limit(
        OrderSide.SELL, new BigDecimal("0.2000"), new BigDecimal("101"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(hold);

    OrderResponse response = service().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo("0.2000");
    assertThat(response.holdCurrency()).isEqualTo("BTC");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("BTC"), eq(hold.amount()), eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void alreadyTriggeredStopMarketBuyAndSellUseMarketFillNotTriggerPrice() {
    UUID userId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    UUID buyAccountId = UUID.randomUUID();
    UUID sellAccountId = UUID.randomUUID();
    CreateOrderRequest buy = request(
        buyAccountId, OrderSide.BUY, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "0.1000", null, "99", "triggered-stop-buy");
    CreateOrderRequest sell = request(
        sellAccountId, OrderSide.SELL, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "0.2000", null, "100", "triggered-stop-sell");
    SpotMarketBundle bundle = bundle();
    SymbolEntity sharedSymbol = symbol();
    stubNewOrder(userId, buyAccountId, "triggered-stop-buy",
        account(userId, buyAccountId), sharedSymbol, bundle);
    stubSecondOrderIdentity(userId, sellAccountId, "triggered-stop-sell");
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT, OrderSide.BUY, FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null, ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("100.0100", LiquidityRole.TAKER));
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT, OrderSide.SELL, FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null, ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("98.9901", LiquidityRole.TAKER));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(
            fillForSide(OrderSide.BUY, "0.1000", "100.0100", LiquidityRole.TAKER),
            fillForSide(OrderSide.SELL, "0.2000", "98.9901", LiquidityRole.TAKER));

    OrderService service = service();
    OrderResponse buyResponse = service.createOrder(principal, buy);
    OrderResponse sellResponse = service.createOrder(principal, sell);

    assertThat(buyResponse.executionPrice()).isEqualByComparingTo("100.0100");
    assertThat(sellResponse.executionPrice()).isEqualByComparingTo("98.9901");
    assertThat(buyResponse.executionPrice()).isNotEqualByComparingTo(buy.triggerPrice());
    assertThat(sellResponse.executionPrice()).isNotEqualByComparingTo(sell.triggerPrice());
    assertThat(buyResponse.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(sellResponse.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(buyResponse.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(sellResponse.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    ArgumentCaptor<FullFillRequest> requests = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator, org.mockito.Mockito.times(2)).execute(
        requests.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(requests.getAllValues()).allSatisfy(value -> {
      assertThat(value.executionPath()).isEqualTo(FullFillExecutionPath.TRIGGERED_STOP_MARKET);
      assertThat(value.limitPrice()).isNull();
      assertThat(value.executionIntent().triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    });
  }

  @Test
  void pendingStopMarketBuyAndSellPersistLastPriceAndExactHolds() {
    UUID userId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    UUID buyAccountId = UUID.randomUUID();
    UUID sellAccountId = UUID.randomUUID();
    CreateOrderRequest buy = request(
        buyAccountId, OrderSide.BUY, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "0.1000", null, "101", "pending-stop-buy");
    CreateOrderRequest sell = request(
        sellAccountId, OrderSide.SELL, OrderType.STOP_MARKET, QuantityUnit.BASE,
        "0.2000", null, "98", "pending-stop-sell");
    SpotMarketBundle bundle = bundle();
    SymbolEntity sharedSymbol = symbol();
    OrderHoldCalculator.OrderHold buyHold =
        new OrderHoldCalculator.OrderHold(new BigDecimal("10.10606050"), "USDT");
    OrderHoldCalculator.OrderHold sellHold =
        new OrderHoldCalculator.OrderHold(new BigDecimal("0.2000"), "BTC");
    stubNewOrder(userId, buyAccountId, "pending-stop-buy",
        account(userId, buyAccountId), sharedSymbol, bundle);
    stubSecondOrderIdentity(userId, sellAccountId, "pending-stop-sell");
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT, OrderSide.BUY, FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null, ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("100.0100", LiquidityRole.TAKER));
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT, OrderSide.SELL, FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null, ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("98.9901", LiquidityRole.TAKER));
    when(orderHoldCalculator.stopMarket(
        OrderSide.BUY, new BigDecimal("0.1000"), new BigDecimal("101"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(buyHold);
    when(orderHoldCalculator.stopMarket(
        OrderSide.SELL, new BigDecimal("0.2000"), new BigDecimal("98"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(sellHold);

    OrderService service = service();
    OrderResponse buyResponse = service.createOrder(principal, buy);
    OrderResponse sellResponse = service.createOrder(principal, sell);

    assertThat(buyResponse.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(sellResponse.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(buyResponse.holdAmount()).isEqualByComparingTo(buyHold.amount());
    assertThat(sellResponse.holdAmount()).isEqualByComparingTo(sellHold.amount());
    assertThat(buyResponse.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(sellResponse.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(buyResponse.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(sellResponse.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(walletService).lockAvailableWithEntryType(
        eq(buyAccountId), eq("USDT"), eq(buyHold.amount()), eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
    verify(walletService).lockAvailableWithEntryType(
        eq(sellAccountId), eq("BTC"), eq(sellHold.amount()), eq("ORDER"), any(UUID.class),
        eq("Pending spot order wallet locked"), eq("SPOT_ORDER_LOCK"));
  }

  @Test
  void stopLimitCreationAlwaysPersistsPendingActivationWithLimitHold() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_LIMIT, QuantityUnit.BASE,
        "0.1000", "98", "99", "spot-stop-limit", TimeInForce.GTC, false);
    CreateOrderRequest changedTrigger = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_LIMIT, QuantityUnit.BASE,
        "0.1000", "98", "101", "spot-stop-limit", TimeInForce.GTC, false);
    CreateOrderRequest changedTimeInForce = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_LIMIT, QuantityUnit.BASE,
        "0.1000", "98", "99", "spot-stop-limit", TimeInForce.IOC, false);
    SpotMarketBundle bundle = bundle("99", "100", "99");
    OrderHoldCalculator.OrderHold hold =
        new OrderHoldCalculator.OrderHold(new BigDecimal("9.80490000"), "USDT");
    stubNewOrder(
        userId, accountId, "spot-stop-limit", account(userId, accountId), symbol(), bundle);
    when(orderHoldCalculator.limit(
        OrderSide.BUY,
        new BigDecimal("0.1000"),
        new BigDecimal("98"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(hold);

    OrderService service = service();
    OrderResponse response = service.createOrder(principal, request);
    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(saved.capture());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "spot-stop-limit"))
        .thenReturn(Optional.of(saved.getValue()));

    OrderResponse replay = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());
    assertThat(replay.id()).isEqualTo(response.id());
    assertThat(replay.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());
    assertThat(saved.getValue().getRequestFingerprint()).isNotBlank();
    assertThat(response.orderType()).isEqualTo(OrderType.STOP_LIMIT.name());
    assertThat(response.triggerPrice()).isEqualByComparingTo("99");
    assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.LIMIT);
    assertThat(response.price()).isEqualByComparingTo("98");
    assertThat(response.holdAmount()).isEqualByComparingTo(hold.amount());
    verify(orderHoldCalculator).limit(
        OrderSide.BUY,
        new BigDecimal("0.1000"),
        new BigDecimal("98"),
        ExecutableMarketSnapshot.from(bundle));
    verify(orderHoldCalculator, never()).stopMarket(any(), any(), any(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    for (CreateOrderRequest conflict : List.of(changedTrigger, changedTimeInForce)) {
      assertThatThrownBy(() -> service.createOrder(principal, conflict))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));
    }
    verify(orderRepository, org.mockito.Mockito.times(1)).save(any(OrderEntity.class));
    verify(transactionExecutor, org.mockito.Mockito.times(1)).execute(any());
    verify(marketBundleResolver, org.mockito.Mockito.times(1))
        .resolveSpot(eq("BTCUSDT"), any());
  }

  @Test
  void stopLimitSpotRejectsNonGtcContractBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_LIMIT, QuantityUnit.BASE,
        "0.1000", "98", "99", "spot-stop-limit-ioc", TimeInForce.IOC, false);
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "spot-stop-limit-ioc")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "spot-stop-limit-ioc"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("STOP_LIMIT_CONTRACT_INVALID"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
  }

  @Test
  void liveAccountGuardRejectsBeforeSpotProviderTransactionOrFinancialMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String key = "spot-live-rejected";
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity live = account(userId, accountId);
    live.setAccountType(AccountType.LIVE);
    CreateOrderRequest request = advancedRequest(
        accountId, OrderSide.BUY, OrderType.STOP_LIMIT, QuantityUnit.BASE,
        "0.1000", "98", "99", key, TimeInForce.GTC, false);
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(live));
    org.mockito.Mockito.doThrow(new BusinessException(
            ErrorCode.DEMO_ACCOUNT_REQUIRED,
            "Demo account required"))
        .when(demoExecutionGuard)
        .requireDemo(live, ProductType.CRYPTO_SPOT, "BTCUSDT");

    assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DEMO_ACCOUNT_REQUIRED));

    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(symbolRepository, never()).findBySymbol(any());
    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectsNonCashSpotContractBeforeBundleResolutionOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId, "BTCUSDT", OrderSide.BUY, OrderType.MARKET,
        null, null, null, null, "non-cash", "non-cash",
        new BigDecimal("100"), null, 1, PositionSide.BOTH, QuantityUnit.QUOTE,
        MarginMode.CROSS, null, null, false, List.of());
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "non-cash"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "non-cash"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().createOrder(principal, request))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_ALLOWED"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void rejectsCompletePredictableSpotContractMatrixBeforeMarketOrMutation() {
    UUID userId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    record InvalidContract(CreateOrderRequest request, String code) {}
    List<InvalidContract> invalid = List.of(
        new InvalidContract(contractRequest(
            "market-buy-base", OrderSide.BUY, OrderType.MARKET, QuantityUnit.BASE,
            MarginMode.CASH, PositionSide.BOTH, false, null, null, null, null, null, List.of()),
            "INVALID_QUANTITY_UNIT"),
        new InvalidContract(contractRequest(
            "market-sell-quote", OrderSide.SELL, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, null, null, null, null, null, List.of()),
            "INVALID_QUANTITY_UNIT"),
        new InvalidContract(contractRequest(
            "limit-quote", OrderSide.BUY, OrderType.LIMIT, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, new BigDecimal("90"), null, null,
            null, null, List.of()), "INVALID_QUANTITY_UNIT"),
        new InvalidContract(contractRequest(
            "stop-quote", OrderSide.SELL, OrderType.STOP_MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, null, new BigDecimal("98"), null,
            null, null, List.of()), "INVALID_QUANTITY_UNIT"),
        new InvalidContract(contractRequest(
            "contracts", OrderSide.SELL, OrderType.LIMIT, QuantityUnit.CONTRACTS,
            MarginMode.CASH, PositionSide.BOTH, false, new BigDecimal("101"), null, null,
            null, null, List.of()), "INVALID_QUANTITY_UNIT"),
        new InvalidContract(contractRequest(
            "cross", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CROSS, PositionSide.BOTH, false, null, null, null,
            null, null, List.of()), "PRODUCT_NOT_ALLOWED"),
        new InvalidContract(contractRequest(
            "long", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.LONG, false, null, null, null,
            null, null, List.of()), "PRODUCT_NOT_ALLOWED"),
        new InvalidContract(contractRequest(
            "reduce", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, true, null, null, null,
            null, null, List.of()), "INVALID_SPOT_ORDER_FIELDS"),
        new InvalidContract(contractRequest(
            "legacy-stop-loss", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, null, null, null,
            new BigDecimal("90"), null, List.of()), "PRODUCT_NOT_ALLOWED"),
        new InvalidContract(contractRequest(
            "legacy-take-profit", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, null, null, null,
            null, new BigDecimal("110"), List.of()), "PRODUCT_NOT_ALLOWED"),
        new InvalidContract(contractRequest(
            "attached", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, null, null, null,
            null, null, List.of(new CreateOrderRequest.AttachedProtectionRequest(
                ProtectionType.STOP_LOSS, new BigDecimal("90"), TriggerPriceType.LAST_PRICE,
                TriggerExecutionType.MARKET, null))), "INVALID_SPOT_ORDER_FIELDS"),
        new InvalidContract(
            contractRequestWithLeverage("leverage", 2),
            "INVALID_SPOT_ORDER_FIELDS"),
        new InvalidContract(contractRequest(
            "market-trigger-type", OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            MarginMode.CASH, PositionSide.BOTH, false, null, null, TriggerPriceType.LAST_PRICE,
            null, null, List.of()), "INVALID_SPOT_ORDER_FIELDS"),
        new InvalidContract(contractRequest(
            "limit-trigger-type", OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
            MarginMode.CASH, PositionSide.BOTH, false, new BigDecimal("90"), null,
            TriggerPriceType.LAST_PRICE, null, null, List.of()), "INVALID_SPOT_ORDER_FIELDS"),
        new InvalidContract(contractRequest(
            "legacy-stop", OrderSide.SELL, OrderType.STOP, QuantityUnit.BASE,
            MarginMode.CASH, PositionSide.BOTH, false, new BigDecimal("98"), null, null,
            null, null, List.of()), "INVALID_SPOT_ORDER_TYPE"),
        new InvalidContract(contractRequest(
            "stop-limit-price", OrderSide.SELL, OrderType.STOP_MARKET, QuantityUnit.BASE,
            MarginMode.CASH, PositionSide.BOTH, false, new BigDecimal("97"),
            new BigDecimal("98"), null, null, null, List.of()), "INVALID_SPOT_ORDER_FIELDS"),
        new InvalidContract(contractRequest(
            "stop-mark-price", OrderSide.SELL, OrderType.STOP_MARKET, QuantityUnit.BASE,
            MarginMode.CASH, PositionSide.BOTH, false, null, new BigDecimal("98"),
            TriggerPriceType.MARK_PRICE, null, null, List.of()), "INVALID_SPOT_ORDER_FIELDS"));

    for (InvalidContract contract : invalid) {
      CreateOrderRequest request = contract.request();
      UUID accountId = request.accountId();
      when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
          userId, accountId, request.clientOrderId())).thenReturn(Optional.empty());
      when(orderRepository.findByUserIdAndIdempotencyKey(userId, request.idempotencyKey()))
          .thenReturn(Optional.empty());
      when(accountRepository.findByIdAndUserId(accountId, userId))
          .thenReturn(Optional.of(account(userId, accountId)));
    }

    OrderService service = service();
    for (InvalidContract contract : invalid) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.createOrder(principal, contract.request()))
          .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo(contract.code()));
    }

    verify(symbolRepository, never()).findBySymbol(any());
    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void normalCancelRoutesOcoLegToAtomicGroupCancel() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    UUID groupId = UUID.randomUUID();
    OrderEntity limit = ocoLeg(userId, accountId, groupId, OrderType.LIMIT);
    OrderEntity stop = ocoLeg(userId, accountId, groupId, OrderType.STOP_MARKET);
    OcoOrderGroupResponse group = new OcoOrderGroupResponse(
        groupId, new OrderResponseMapper().toResponse(limit), new OrderResponseMapper().toResponse(stop));
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    when(orderRepository.findByUserIdAndId(userId, stop.getId())).thenReturn(Optional.of(stop));
    when(ocoOrderService.cancelByLeg(principal, stop.getId())).thenReturn(group);
    OrderService service = service();
    service.setOcoOrderService(ocoOrderService);

    OrderResponse response = service.cancelOrder(principal, stop.getId());

    assertThat(response.id()).isEqualTo(stop.getId());
    verify(ocoOrderService).cancelByLeg(principal, stop.getId());
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
  }

  @Test
  void pendingSpotCancelReleasesTheExactWalletHoldOnlyOnce() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    order.setContingencyGroupId(null);
    order.setHoldAmount(new BigDecimal("10.00500000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPending(order)).thenReturn(1);

    OrderService service = service();
    OrderResponse canceled = service.cancelOrder(principal, order.getId());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.cancelOrder(principal, order.getId()))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_CANCELABLE"));
    verify(walletService, org.mockito.Mockito.times(1)).releaseLockedWithEntryType(
        accountId, "USDT", new BigDecimal("10.00500000"), "ORDER", order.getId(),
        "Pending spot order canceled", "SPOT_ORDER_RELEASE");
    verify(orderRepository, org.mockito.Mockito.times(1)).cancelPending(order);
    verify(orderEventService, org.mockito.Mockito.times(1)).record(
        eq(order.getId()), eq("ORDER_CANCELED"), eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED), eq(null), eq("Pending order canceled"));
  }

  @Test
  void spotStopLimitCanCancelBeforeActivationWithMatchingCasAndOneHoldRelease() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, null, OrderType.STOP_LIMIT);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.USER);
    order.setPrice(new BigDecimal("90"));
    order.setRequestedPrice(new BigDecimal("90"));
    order.setTriggerPrice(new BigDecimal("110"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setHoldAmount(new BigDecimal("9.00450000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPendingActivation(order)).thenReturn(1);

    OrderResponse canceled = service().cancelOrder(principal, order.getId());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(orderRepository).cancelPendingActivation(order);
    verify(orderRepository, never()).cancelPending(any());
    verify(walletService).releaseLockedWithEntryType(
        accountId, "USDT", new BigDecimal("9.00450000"), "ORDER", order.getId(),
        "Pending spot order canceled", "SPOT_ORDER_RELEASE");
    verify(orderEventService).record(
        order.getId(), "ORDER_CANCELED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.CANCELED, null, "Pending order canceled");
  }

  @Test
  void activatedSpotStopLimitCancelReplayReleasesItsHoldOnlyOnce() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = spotStopLimit(userId, accountId, OrderStatus.PENDING);
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPending(order)).thenReturn(1);

    OrderResponse canceled = service().cancelOrder(principal, order.getId());
    assertThatThrownBy(() -> service().cancelOrder(principal, order.getId()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_CANCELABLE"));

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name());
    verify(orderRepository, org.mockito.Mockito.times(1)).cancelPending(order);
    verify(walletService, org.mockito.Mockito.times(1)).releaseLockedWithEntryType(
        accountId, "USDT", new BigDecimal("9.00450000"), "ORDER", order.getId(),
        "Pending spot order canceled", "SPOT_ORDER_RELEASE");
  }

  @Test
  void spotCancelLosesToActivationCasWithoutReleasingOrChangingCandidate() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = spotStopLimit(userId, accountId, OrderStatus.PENDING_ACTIVATION);
    order.setRemainingQuantity(new BigDecimal("0.1"));
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPendingActivation(order)).thenReturn(0);

    assertThatThrownBy(() -> service().cancelOrder(principal, order.getId()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_CANCELABLE"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.1");
    assertThat(order.getCanceledAt()).isNull();
    assertThat(order.getHoldAmount()).isEqualByComparingTo("9.00450000");
    verify(walletService, never()).releaseLockedWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void restingPostOnlySpotModifyToMarketableRejectsBeforeAnyFinancialMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, null, OrderType.LIMIT);
    order.setPrice(new BigDecimal("90"));
    order.setRequestedPrice(new BigDecimal("90"));
    order.setHoldAmount(new BigDecimal("9.00450000"));
    order.setHoldCurrency("USDT");
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(true);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol()));
    when(instrumentRulesEngine.rules(any(SymbolEntity.class))).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle());

    assertThatThrownBy(() -> service().modifyOrder(
            principal,
            order.getId(),
            new UpdateOrderRequest(null, new BigDecimal("100"), null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));

    verify(transactionExecutor, never()).execute(any());
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    org.mockito.Mockito.verifyNoInteractions(walletService, spotPositionService, ledgerService);
  }

  @Test
  void spotStopLimitModifyBeforeActivationChangesQuantityLimitAndTriggerWithoutActivating() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = spotStopLimit(userId, accountId, OrderStatus.PENDING_ACTIVATION);
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(orderHoldCalculator.limit(
        OrderSide.BUY,
        new BigDecimal("0.2"),
        new BigDecimal("89"),
        ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("17.80890000"), "USDT"));

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(
            new BigDecimal("0.2"), new BigDecimal("89"), new BigDecimal("90"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());
    assertThat(response.orderType()).isEqualTo(OrderType.STOP_LIMIT.name());
    assertThat(response.quantity()).isEqualByComparingTo("0.2");
    assertThat(response.price()).isEqualByComparingTo("89");
    assertThat(response.triggerPrice()).isEqualByComparingTo("90");
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.GTC);
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderEventService).record(
        order.getId(), "ORDER_MODIFIED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION, null, "Pending Spot order modified");
  }

  @Test
  void activatedSpotStopLimitRejectsEvenSameExplicitTriggerBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = spotStopLimit(userId, accountId, OrderStatus.PENDING);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service().modifyOrder(
            principal,
            order.getId(),
            new UpdateOrderRequest(null, new BigDecimal("89"), new BigDecimal("110"), null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_MODIFIABLE"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(walletService, spotPositionService, ledgerService);
  }

  @Test
  void activatedSpotStopLimitModifyToMarketableFillsWithCanonicalLimitIntent() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = spotStopLimit(userId, accountId, OrderStatus.PENDING);
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillResult fill = fillForSide(OrderSide.BUY, "0.1000", "100", LiquidityRole.TAKER);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(fullFillCoordinator.execute(
        any(FullFillRequest.class), eq(ExecutableMarketSnapshot.from(bundle)))).thenReturn(fill);

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("100"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.orderType()).isEqualTo(OrderType.STOP_LIMIT.name());
    assertThat(response.triggerPrice()).isEqualByComparingTo("110");
    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(request.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(request.getValue().executionPath()).isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(request.getValue().executionIntent().orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(request.getValue().executionIntent().triggerPrice()).isNull();
    assertThat(request.getValue().executionIntent().triggerPriceType()).isNull();
    assertThat(request.getValue().limitPrice()).isEqualByComparingTo("100");
  }

  @Test
  void ordinaryModifyRejectsAnOcoLegBeforeRiskOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity leg = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    when(orderRepository.findByUserIdAndId(userId, leg.getId())).thenReturn(Optional.of(leg));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().modifyOrder(
        principal, leg.getId(), new UpdateOrderRequest(null, new BigDecimal("90"), null, null)))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_ORDER_NOT_MODIFIABLE"));

    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void modernP0SpotLimitModifyUsesCanonicalRulesAndHoldDeltaWithoutLegacyQuote()
      throws NoSuchMethodException {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    order.setContingencyGroupId(null);
    order.setPrice(new BigDecimal("90"));
    order.setRequestedPrice(new BigDecimal("90"));
    order.setHoldAmount(new BigDecimal("9.00450000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    OrderHoldCalculator.OrderHold modifiedHold = new OrderHoldCalculator.OrderHold(
        new BigDecimal("17.80890000"), "USDT");
    when(orderHoldCalculator.limit(
        OrderSide.BUY,
        new BigDecimal("0.2"),
        new BigDecimal("89"),
        ExecutableMarketSnapshot.from(bundle))).thenReturn(modifiedHold);

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(new BigDecimal("0.2"), new BigDecimal("89"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.quantity()).isEqualByComparingTo("0.2");
    assertThat(response.originalQuantity()).isEqualByComparingTo("0.2");
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.2");
    assertThat(response.quantityUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(response.price()).isEqualByComparingTo("89");
    assertThat(response.holdAmount()).isEqualByComparingTo("17.80890000");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("8.80440000")),
        eq("ORDER_MODIFICATION"), any(UUID.class),
        eq("Pending spot order hold increased"), eq("SPOT_ORDER_LOCK"));
    verify(instrumentRulesEngine).validateCanonicalOrder(
        any(CreateOrderRequest.class), eq(symbol), eq(new BigDecimal("0.2")), eq(new BigDecimal("89")));
    verify(marketBundleResolver).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor).execute(any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository).save(order);
    assertThat(OrderService.class.getDeclaredMethod(
        "modifyOrder", UserPrincipal.class, UUID.class, UpdateOrderRequest.class)
        .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
  }

  @Test
  void limitBuyModifyToMarketableFillsImmediatelyFromActualSpendWithOneTakerTrade() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    order.setContingencyGroupId(null);
    order.setPrice(new BigDecimal("90"));
    order.setRequestedPrice(new BigDecimal("90"));
    order.setHoldAmount(new BigDecimal("9.00450000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillResult canonicalFill =
        fillForSide(OrderSide.BUY, "0.1000", "100", LiquidityRole.TAKER);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(fullFillCoordinator.execute(
        any(FullFillRequest.class), eq(ExecutableMarketSnapshot.from(bundle))))
        .thenReturn(canonicalFill);

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(new BigDecimal("0.1000"), new BigDecimal("101"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("100");
    assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(response.fee()).isEqualByComparingTo("0.00500000");
    assertThat(response.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        fillRequest.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(fillRequest.getValue().executionPath())
        .isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(fillRequest.getValue().limitPrice()).isEqualByComparingTo("101");
    verify(orderHoldCalculator, never()).limit(any(), any(), any(), any());
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("1.00050000")),
        eq("ORDER_MODIFICATION"), any(UUID.class),
        eq("Pending spot order hold increased for immediate fill"), eq("SPOT_ORDER_LOCK"));
    verify(walletService).debitLockedWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("10.00000000")),
        eq("TRADE"), any(UUID.class), eq("Spot buy quote spent"), eq("SPOT_BUY_DEBIT"));
    verify(walletService).debitLockedWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("0.00500000")),
        eq("TRADE"), any(UUID.class), eq("Spot buy fee charged in USDT"), eq("TRADE_FEE"));
    verify(walletService, never()).debitAvailableWithEntryType(
        eq(accountId), eq("USDT"), any(BigDecimal.class), any(String.class), any(UUID.class),
        any(String.class), any(String.class));
    ArgumentCaptor<TradeEntity> trade = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(trade.capture());
    assertThat(trade.getValue().getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(trade.getValue().getFee()).isEqualByComparingTo("0.00500000");
  }

  @Test
  void limitSellModifyToMarketableFillsImmediatelyAndOnlyTopsUpActualBaseSpend() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    order.setContingencyGroupId(null);
    order.setSide(OrderSide.SELL);
    order.setPrice(new BigDecimal("110"));
    order.setRequestedPrice(new BigDecimal("110"));
    order.setHoldAmount(new BigDecimal("0.10000000"));
    order.setHoldCurrency("BTC");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillResult canonicalFill =
        fillForSide(OrderSide.SELL, "0.2000", "99", LiquidityRole.TAKER);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(fullFillCoordinator.execute(
        any(FullFillRequest.class), eq(ExecutableMarketSnapshot.from(bundle))))
        .thenReturn(canonicalFill);

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(new BigDecimal("0.2000"), new BigDecimal("98"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("99");
    assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(response.fee()).isEqualByComparingTo("0.00990000");
    verify(orderHoldCalculator, never()).limit(any(), any(), any(), any());
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("BTC"), eq(new BigDecimal("0.10000000")),
        eq("ORDER_MODIFICATION"), any(UUID.class),
        eq("Pending spot order hold increased for immediate fill"), eq("SPOT_ORDER_LOCK"));
    verify(walletService).debitLockedWithEntryType(
        eq(accountId), eq("BTC"), eq(new BigDecimal("0.20000000")),
        eq("TRADE"), any(UUID.class), eq("Spot sell base spent"), eq("SPOT_SELL_DEBIT"));
    verify(walletService, never()).debitAvailableWithEntryType(
        eq(accountId), eq("BTC"), any(BigDecimal.class), any(String.class), any(UUID.class),
        any(String.class), any(String.class));
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(TradeEntity.class));
  }

  @Test
  void stopMarketBuyModifyToSatisfiedLastTriggerFillsImmediatelyAtMarket() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.STOP_MARKET);
    order.setContingencyGroupId(null);
    order.setTriggerPrice(new BigDecimal("110"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    order.setHoldAmount(new BigDecimal("11.00600000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillResult canonicalFill =
        fillForSide(OrderSide.BUY, "0.1000", "100.0100", LiquidityRole.TAKER);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null,
        ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("100.0100", LiquidityRole.TAKER));
    when(fullFillCoordinator.execute(
        any(FullFillRequest.class), eq(ExecutableMarketSnapshot.from(bundle))))
        .thenReturn(canonicalFill);

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("99"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("100.0100");
    assertThat(response.triggerPrice()).isEqualByComparingTo("99");
    assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    verify(orderHoldCalculator, never()).stopMarket(any(), any(), any(), any());
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        fillRequest.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(fillRequest.getValue().executionPath())
        .isEqualTo(FullFillExecutionPath.TRIGGERED_STOP_MARKET);
    assertThat(fillRequest.getValue().limitPrice()).isNull();
    verify(walletService).releaseLockedWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("0.99999950"),
        "ORDER",
        order.getId(),
        "Spot pending order remaining hold released",
        "ORDER_RELEASE");
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(TradeEntity.class));
  }

  @Test
  void stopMarketSellModifyToSatisfiedLastTriggerFillsImmediatelyAtMarket() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.STOP_MARKET);
    order.setContingencyGroupId(null);
    order.setSide(OrderSide.SELL);
    order.setTriggerPrice(new BigDecimal("90"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    order.setHoldAmount(new BigDecimal("0.10000000"));
    order.setHoldCurrency("BTC");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle bundle = bundle();
    FullFillResult canonicalFill =
        fillForSide(OrderSide.SELL, "0.1000", "98.9901", LiquidityRole.TAKER);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.SELL,
        FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null,
        ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(pricing("98.9901", LiquidityRole.TAKER));
    when(fullFillCoordinator.execute(
        any(FullFillRequest.class), eq(ExecutableMarketSnapshot.from(bundle))))
        .thenReturn(canonicalFill);

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("100"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("98.9901");
    assertThat(response.triggerPrice()).isEqualByComparingTo("100");
    assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    verify(orderHoldCalculator, never()).stopMarket(any(), any(), any(), any());
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(TradeEntity.class));
  }

  @Test
  void spotModifyRecomputesExecutionPathFromEachRetrySnapshotWithoutFirstAttemptMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    order.setContingencyGroupId(null);
    order.setPrice(new BigDecimal("90"));
    order.setRequestedPrice(new BigDecimal("90"));
    order.setHoldAmount(new BigDecimal("9.00450000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle firstImmediate = bundle("99", "100", "99.5");
    SpotMarketBundle secondResting = bundle("101", "102", "101.5");
    ExecutableMarketSnapshot firstSnapshot = ExecutableMarketSnapshot.from(firstImmediate);
    ExecutableMarketSnapshot secondSnapshot = ExecutableMarketSnapshot.from(secondResting);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenReturn(firstImmediate, secondResting);
    when(orderHoldCalculator.limit(
        OrderSide.BUY,
        new BigDecimal("0.1000"),
        new BigDecimal("101"),
        secondSnapshot))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("10.10505000"), "USDT"));
    org.mockito.Mockito.doThrow(new com.fxplatform.common.exception.BusinessException(
            com.fxplatform.common.exception.ErrorCode.MARKET_DATA_STALE,
            "first immediate snapshot expired while waiting for account lock"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(any(ExecutableMarketSnapshot.class));

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(new BigDecimal("0.1000"), new BigDecimal("101"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.price()).isEqualByComparingTo("101");
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(orderHoldCalculator, org.mockito.Mockito.times(1)).limit(
        OrderSide.BUY, new BigDecimal("0.1000"), new BigDecimal("101"), secondSnapshot);
    verify(orderHoldCalculator, never()).limit(
        OrderSide.BUY, new BigDecimal("0.1000"), new BigDecimal("101"), firstSnapshot);
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(tradeRepository, never()).save(any());
    verify(walletService, org.mockito.Mockito.times(1)).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("1.10055000")),
        eq("ORDER_MODIFICATION"), any(UUID.class),
        eq("Pending spot order hold increased"), eq("SPOT_ORDER_LOCK"));
  }

  @Test
  void modernP0SpotStopModifyTreatsPriceAsLastPriceTriggerAndRetriesStaleOnce() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.STOP_MARKET);
    order.setContingencyGroupId(null);
    order.setHoldAmount(new BigDecimal("10.00600000"));
    order.setHoldCurrency("USDT");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    SpotMarketBundle first = bundle();
    SpotMarketBundle second = first;
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(first, second);
    when(fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null,
        ExecutableMarketSnapshot.from(first)))
        .thenReturn(pricing("100.0100", LiquidityRole.TAKER));
    when(orderHoldCalculator.stopMarket(
        OrderSide.BUY,
        new BigDecimal("0.1"),
        new BigDecimal("110"),
        ExecutableMarketSnapshot.from(first)))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("11.00600000"), "USDT"));
    org.mockito.Mockito.doThrow(new com.fxplatform.common.exception.BusinessException(
            com.fxplatform.common.exception.ErrorCode.MARKET_DATA_STALE,
            "snapshot expired while waiting for account lock"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(any(ExecutableMarketSnapshot.class));

    OrderResponse response = service().modifyOrder(
        principal,
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("110"), null, null));

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.price()).isNull();
    assertThat(order.getRequestedPrice()).isNull();
    assertThat(response.triggerPrice()).isEqualByComparingTo("110");
    assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(fullFillCoordinator, org.mockito.Mockito.times(2)).project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.TRIGGERED_STOP_MARKET,
        null,
        ExecutableMarketSnapshot.from(first));
    verify(instrumentRulesEngine, org.mockito.Mockito.times(2)).validateCanonicalOrder(
        any(CreateOrderRequest.class),
        eq(symbol),
        eq(new BigDecimal("0.1")),
        eq(new BigDecimal("100.0100")));
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("1.00000000")),
        eq("ORDER_MODIFICATION"), any(UUID.class),
        eq("Pending spot order hold increased"), eq("SPOT_ORDER_LOCK"));
    verify(riskCheckService, never()).checkOrder(any(), any());
  }

  @Test
  void modernP0SpotModifyRejectsProtectionFieldsBeforeProviderOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = ocoLeg(userId, accountId, UUID.randomUUID(), OrderType.LIMIT);
    order.setContingencyGroupId(null);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().modifyOrder(
            principal,
            order.getId(),
            new UpdateOrderRequest(null, null, new BigDecimal("80"), null)))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_ALLOWED"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(orderRepository, never()).save(any());
  }

  private void stubNewOrder(
      UUID userId,
      UUID accountId,
      String key,
      TradingAccountEntity account,
      SymbolEntity symbol,
      SpotMarketBundle bundle
  ) {
    stubNewOrderPlanning(userId, accountId, key, account, symbol, bundle);
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(UUID.randomUUID());
      }
      return order;
    });
  }

  private void stubNewOrderPlanning(
      UUID userId,
      UUID accountId,
      String key,
      TradingAccountEntity account,
      SymbolEntity symbol,
      SpotMarketBundle bundle
  ) {
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
  }

  private void stubSecondOrderIdentity(UUID userId, UUID accountId, String key) {
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
  }

  private OrderService service() {
    return service((DemoExecutionPolicyProvider) null, Clock.systemUTC());
  }

  private OrderService service(DemoExecutionPolicy depthPolicy) {
    return service(() -> depthPolicy, Clock.systemUTC());
  }

  private OrderService service(
      DemoExecutionPolicyProvider policyProvider,
      Clock clock
  ) {
    SpotSettlementService settlementService =
        new SpotSettlementService(walletService, spotPositionService);
    OrderFillService orderFillService = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        settlementService,
        walletService);
    OrderService service = new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        orderFillService,
        ledgerService,
        walletService,
        orderEventService,
        new OrderCommandFactory(),
        new OrderEntityFactory(),
        new OrderResponseMapper(),
        new OrderStatusPolicy(),
        demoExecutionGuard,
        walletBalanceRepository,
        positionRepository,
        spotPositionService,
        marketBundleResolver,
        fullFillCoordinator,
        transactionExecutor,
        symbolRepository,
        instrumentRulesEngine,
        new QuantityConversionService(),
        orderHoldCalculator);
    if (policyProvider != null) {
      service.setDepthOrderExecutionService(new DepthOrderExecutionService(
          policyProvider,
          tradeRepository,
          orderFillService,
          orderEventService,
          clock));
    }
    return service;
  }

  private static CreateOrderRequest request(
      UUID accountId,
      OrderSide side,
      OrderType type,
      QuantityUnit unit,
      String quantity,
      String price,
      String trigger,
      String key
  ) {
    return new CreateOrderRequest(
        accountId, "BTCUSDT", side, type,
        null, null, null, null, key, key,
        new BigDecimal(quantity), price == null ? null : new BigDecimal(price), 1,
        PositionSide.BOTH, unit, MarginMode.CASH,
        trigger == null ? null : new BigDecimal(trigger),
        null, false, List.of());
  }

  private void stubBeforeMutation(
      UUID userId,
      UUID accountId,
      String key,
      TradingAccountEntity account,
      SymbolEntity symbol,
      SpotMarketBundle bundle
  ) {
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
  }

  private static CreateOrderRequest advancedRequest(
      UUID accountId,
      OrderSide side,
      OrderType type,
      QuantityUnit unit,
      String quantity,
      String price,
      String trigger,
      String key,
      TimeInForce timeInForce,
      boolean postOnly
  ) {
    return new CreateOrderRequest(
        accountId, "BTCUSDT", side, type,
        null, null, null, null, key, key,
        new BigDecimal(quantity), price == null ? null : new BigDecimal(price), 1,
        PositionSide.BOTH, unit, MarginMode.CASH,
        trigger == null ? null : new BigDecimal(trigger), null, false, List.of(),
        timeInForce, postOnly, null, null, null);
  }

  private static CreateOrderRequest contractRequest(
      String key,
      OrderSide side,
      OrderType type,
      QuantityUnit unit,
      MarginMode marginMode,
      PositionSide positionSide,
      boolean reduceOnly,
      BigDecimal price,
      BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      List<CreateOrderRequest.AttachedProtectionRequest> protections
  ) {
    return new CreateOrderRequest(
        UUID.randomUUID(), "BTCUSDT", side, type,
        null, null, stopLoss, takeProfit, key, key,
        new BigDecimal("1"), price, 1, positionSide, unit, marginMode,
        triggerPrice, triggerPriceType, reduceOnly, protections);
  }

  private static CreateOrderRequest contractRequestWithLeverage(
      String key,
      int leverage
  ) {
    return new CreateOrderRequest(
        UUID.randomUUID(), "BTCUSDT", OrderSide.BUY, OrderType.MARKET,
        null, null, null, null, key, key,
        new BigDecimal("1"), null, leverage,
        PositionSide.BOTH, QuantityUnit.QUOTE, MarginMode.CASH,
        null, null, false, List.of());
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setLeverage(1);
    account.setUsedMargin(BigDecimal.ZERO);
    return account;
  }

  private static OrderEntity ocoLeg(
      UUID userId,
      UUID accountId,
      UUID groupId,
      OrderType type
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(type);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.1"));
    order.setQuantity(new BigDecimal("0.1"));
    order.setOriginalQuantity(new BigDecimal("0.1"));
    order.setBaseQuantity(new BigDecimal("0.1"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setMarginMode(MarginMode.CASH);
    order.setPositionSide(PositionSide.BOTH);
    order.setReduceOnly(false);
    order.setContingencyGroupId(groupId);
    order.setHoldAmount(BigDecimal.ZERO);
    return order;
  }

  private static OrderEntity spotStopLimit(
      UUID userId,
      UUID accountId,
      OrderStatus status
  ) {
    OrderEntity order = ocoLeg(userId, accountId, null, OrderType.STOP_LIMIT);
    order.setStatus(status);
    order.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.USER);
    order.setPrice(new BigDecimal("90"));
    order.setRequestedPrice(new BigDecimal("90"));
    order.setTriggerPrice(new BigDecimal("110"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setHoldAmount(new BigDecimal("9.00450000"));
    order.setHoldCurrency("USDT");
    return order;
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol("BTCUSDT");
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setAssetClass("CRYPTO");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMinLot(new BigDecimal("0.0001"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setLeverage(1);
    return symbol;
  }

  private static InstrumentRules rules() {
    return rulesWithStep("0.0001");
  }

  private static InstrumentRules rulesWithStep(String stepSize) {
    return new InstrumentRules(
        "BTCUSDT", true, true, true, true, true, true, true,
        ProductType.CRYPTO_SPOT,
        new BigDecimal("0.1"), new BigDecimal(stepSize),
        new BigDecimal("0.0001"), new BigDecimal("100"),
        new BigDecimal("5"), null,
        new BigDecimal("0.0001"), new BigDecimal("100"),
        1, 1, "USDT", "USDT", BigDecimal.ONE,
        "DEFAULT", "ALWAYS", "NONE", "NORMAL");
  }

  private static SpotMarketBundle bundle() {
    return bundle("99", "100", "99.5");
  }

  private static SpotMarketBundle bundle(String bid, String ask, String last) {
    Instant now = Instant.now();
    return new SpotMarketBundle(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(last),
        null, List.of(), List.of(), now.minusSeconds(1), now.plusSeconds(30));
  }

  private static FullFillPricingProjection pricing(String price, LiquidityRole role) {
    return new FullFillPricingProjection(
        new BigDecimal(price), new BigDecimal("0.01"), new BigDecimal("0.0001"),
        new BigDecimal("0.0005"), new BigDecimal("0.0005"), role);
  }

  private static FullFillResult fill(String quantity, String price, LiquidityRole role) {
    Instant now = Instant.now();
    BigDecimal baseQuantity = new BigDecimal(quantity);
    BigDecimal filledPrice = new BigDecimal(price);
    BigDecimal fee = baseQuantity.multiply(filledPrice)
        .multiply(new BigDecimal("0.0005"))
        .setScale(8, RoundingMode.HALF_UP);
    return new FullFillResult(
        filledPrice, now, baseQuantity, BigDecimal.ZERO,
        new BigDecimal("0.0005"), fee,
        "USDT", role, new BigDecimal("0.01"), MarketSourceMode.PUBLIC_EXTERNAL,
        "binance", "BTCUSDT", now.minusSeconds(1), now.plusSeconds(30));
  }

  private static FullFillResult fillForSide(
      OrderSide side,
      String quantity,
      String price,
      LiquidityRole role
  ) {
    Instant now = Instant.now();
    BigDecimal baseQuantity = new BigDecimal(quantity);
    BigDecimal feeRate = role == LiquidityRole.MAKER
        ? new BigDecimal("0.0002") : new BigDecimal("0.0005");
    BigDecimal fee = baseQuantity.multiply(new BigDecimal(price)).multiply(feeRate)
        .setScale(8, RoundingMode.HALF_UP);
    return new FullFillResult(
        new BigDecimal(price), now, baseQuantity, BigDecimal.ZERO,
        feeRate, fee, "USDT", role,
        BigDecimal.ZERO, MarketSourceMode.PUBLIC_EXTERNAL,
        "binance", "BTCUSDT", now.minusSeconds(1), now.plusSeconds(30));
  }

  private static final class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    private void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
