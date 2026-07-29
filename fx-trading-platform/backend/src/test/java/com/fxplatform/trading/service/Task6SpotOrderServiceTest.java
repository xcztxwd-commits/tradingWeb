package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
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
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
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
import java.util.function.Supplier;
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
    FullFillResult canonicalFill = fill("0.9999", "100.0100", LiquidityRole.TAKER);

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
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.9999");
    assertThat(response.marginMode()).isEqualTo(MarginMode.CASH);
    assertThat(response.positionSide()).isEqualTo(PositionSide.BOTH);

    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    ArgumentCaptor<ExecutableMarketSnapshot> executionSnapshot =
        ArgumentCaptor.forClass(ExecutableMarketSnapshot.class);
    verify(fullFillCoordinator).execute(fillRequest.capture(), executionSnapshot.capture());
    assertThat(fillRequest.getValue().requestedBaseQuantity()).isEqualByComparingTo("0.9999");
    assertThat(fillRequest.getValue().executionIntent().quantity()).isEqualByComparingTo("0.9999");
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
            null, null, List.of()), "PRODUCT_NOT_ALLOWED"),
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
                TriggerExecutionType.MARKET, null))), "PRODUCT_NOT_ALLOWED"),
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
    assertThat(response.fee()).isEqualByComparingTo("0.00005000");
    assertThat(response.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        fillRequest.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertThat(fillRequest.getValue().executionPath())
        .isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(fillRequest.getValue().limitPrice()).isEqualByComparingTo("101");
    verify(orderHoldCalculator, never()).limit(any(), any(), any(), any());
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("0.99550000")),
        eq("ORDER_MODIFICATION"), any(UUID.class),
        eq("Pending spot order hold increased for immediate fill"), eq("SPOT_ORDER_LOCK"));
    verify(walletService).debitLockedWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("10.00000000")),
        eq("TRADE"), any(UUID.class), eq("Spot buy quote spent"), eq("SPOT_BUY_DEBIT"));
    verify(walletService, never()).debitAvailableWithEntryType(
        eq(accountId), eq("USDT"), any(BigDecimal.class), any(String.class), any(UUID.class),
        any(String.class), any(String.class));
    ArgumentCaptor<TradeEntity> trade = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(trade.capture());
    assertThat(trade.getValue().getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(trade.getValue().getFee()).isEqualByComparingTo("0.00005000");
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
        new BigDecimal("1.00500000"),
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
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(UUID.randomUUID());
      }
      return order;
    });
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
    SpotSettlementService settlementService =
        new SpotSettlementService(walletService, spotPositionService);
    return new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        new OrderFillService(
            orderRepository,
            tradeRepository,
            positionRepository,
            accountRepository,
            ledgerService,
            symbolRepository,
            settlementService,
            walletService),
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
    return new InstrumentRules(
        "BTCUSDT", true, true, true, true, true, true, true,
        ProductType.CRYPTO_SPOT,
        new BigDecimal("0.1"), new BigDecimal("0.0001"),
        new BigDecimal("0.0001"), new BigDecimal("100"),
        new BigDecimal("5"), null,
        new BigDecimal("0.0001"), new BigDecimal("100"),
        1, 1, "USDT", "USDT", BigDecimal.ONE, BigDecimal.ONE,
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
    return new FullFillResult(
        new BigDecimal(price), now, new BigDecimal(quantity), BigDecimal.ZERO,
        new BigDecimal("0.0005"), new BigDecimal(quantity).multiply(new BigDecimal("0.0005")),
        "BTC", role, new BigDecimal("0.01"), MarketSourceMode.PUBLIC_EXTERNAL,
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
    BigDecimal fee = side == OrderSide.BUY
        ? baseQuantity.multiply(feeRate)
        : baseQuantity.multiply(new BigDecimal(price)).multiply(feeRate);
    return new FullFillResult(
        new BigDecimal(price), now, baseQuantity, BigDecimal.ZERO,
        feeRate, fee, side == OrderSide.BUY ? "BTC" : "USDT", role,
        BigDecimal.ZERO, MarketSourceMode.PUBLIC_EXTERNAL,
        "binance", "BTCUSDT", now.minusSeconds(1), now.plusSeconds(30));
  }
}
