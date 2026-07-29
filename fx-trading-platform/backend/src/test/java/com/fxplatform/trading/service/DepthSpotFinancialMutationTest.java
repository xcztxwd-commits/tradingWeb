package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.FullFillCoordinator;
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
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Audits DEPTH Spot finance mutations through the real wallet, settlement, and position services.
 * Repository doubles retain every row so tests assert state, not merely collaborator calls.
 */
class DepthSpotFinancialMutationTest {

  private static final String SYMBOL = "BTCUSDT";

  private final OrderRepository orderRepository = mock(OrderRepository.class);
  private final TradingAccountRepository accountRepository = mock(TradingAccountRepository.class);
  private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
  private final ExecutionAdapter executionAdapter = mock(ExecutionAdapter.class);
  private final TradeRepository tradeRepository = mock(TradeRepository.class);
  private final PositionRepository positionRepository = mock(PositionRepository.class);
  private final LedgerService ledgerService = mock(LedgerService.class);
  private final OrderEventService orderEventService = mock(OrderEventService.class);
  private final DemoExecutionGuard demoExecutionGuard = mock(DemoExecutionGuard.class);
  private final MarketBundleResolver marketBundleResolver = mock(MarketBundleResolver.class);
  private final FullFillCoordinator fullFillCoordinator = mock(FullFillCoordinator.class);
  private final TradingTransactionExecutor transactionExecutor = mock(TradingTransactionExecutor.class);
  private final SymbolRepository symbolRepository = mock(SymbolRepository.class);
  private final InstrumentRulesEngine instrumentRulesEngine = mock(InstrumentRulesEngine.class);
  private final OrderHoldCalculator orderHoldCalculator = mock(OrderHoldCalculator.class);
  private final WalletBalanceRepository walletBalanceRepository = mock(WalletBalanceRepository.class);
  private final AssetLedgerEntryRepository assetLedgerEntryRepository =
      mock(AssetLedgerEntryRepository.class);
  private final SpotPositionRepository spotPositionRepository = mock(SpotPositionRepository.class);

  private final Map<String, WalletBalanceEntity> balances = new HashMap<>();
  private final Map<String, SpotPositionEntity> positions = new HashMap<>();
  private final List<AssetLedgerEntryEntity> assetLedger = new ArrayList<>();
  private final List<TradeEntity> trades = new ArrayList<>();
  private final List<OrderEntity> orders = new ArrayList<>();

  private WalletService walletService;
  private SpotPositionService spotPositionService;

  @BeforeEach
  void setUpStatefulRepositories() {
    balances.clear();
    positions.clear();
    assetLedger.clear();
    trades.clear();
    orders.clear();

    when(transactionExecutor.execute(any())).thenAnswer(invocation ->
        ((Supplier<?>) invocation.getArgument(0)).get());

    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        any(UUID.class), anyString(), anyString())).thenAnswer(invocation -> Optional.ofNullable(
            balances.get(walletKey(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
    when(walletBalanceRepository.findByAccountIdForUpdate(any(UUID.class))).thenAnswer(invocation ->
        balances.values().stream()
            .filter(balance -> balance.getAccountId().equals(invocation.getArgument(0)))
            .toList());
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> {
      WalletBalanceEntity balance = invocation.getArgument(0);
      if (balance.getId() == null) {
        balance.setId(UUID.randomUUID());
      }
      balances.put(walletKey(balance.getAccountId(), balance.getWalletType(), balance.getAsset()), balance);
      return balance;
    });
    when(assetLedgerEntryRepository.findByBusinessOperation(
        any(UUID.class), anyString(), anyString(), anyString(), any(UUID.class), anyString()))
        .thenAnswer(invocation -> assetLedger.stream()
            .filter(entry -> entry.getAccountId().equals(invocation.getArgument(0)))
            .filter(entry -> entry.getWalletType().equals(invocation.getArgument(1)))
            .filter(entry -> entry.getAsset().equals(invocation.getArgument(2)))
            .filter(entry -> entry.getReferenceType().equals(invocation.getArgument(3)))
            .filter(entry -> entry.getReferenceId().equals(invocation.getArgument(4)))
            .filter(entry -> entry.getOperationType().equals(invocation.getArgument(5)))
            .findFirst().orElse(null));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class))).thenAnswer(invocation -> {
      AssetLedgerEntryEntity entry = invocation.getArgument(0);
      if (entry.getId() == null) {
        entry.setId(UUID.randomUUID());
      }
      assetLedger.add(entry);
      return entry;
    });

    when(spotPositionRepository.findBySlotForUpdate(
        any(UUID.class), anyString(), anyString(), anyString())).thenAnswer(invocation ->
            Optional.ofNullable(positions.get(positionKey(
                invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3)))));
    when(spotPositionRepository.findByAccountIdForUpdate(any(UUID.class))).thenAnswer(invocation ->
        positions.values().stream()
            .filter(position -> position.getAccountId().equals(invocation.getArgument(0)))
            .toList());
    when(spotPositionRepository.save(any(SpotPositionEntity.class))).thenAnswer(invocation -> {
      SpotPositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      positions.put(positionKey(
          position.getAccountId(), position.getWalletType(),
          position.getAsset(), position.getCostAsset()), position);
      return position;
    });

    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(UUID.randomUUID());
      }
      if (orders.stream().noneMatch(existing -> existing.getId().equals(order.getId()))) {
        orders.add(order);
      }
      return order;
    });
    when(tradeRepository.findByOrderIdAndFillIdentity(any(UUID.class), anyString()))
        .thenAnswer(invocation -> trades.stream()
            .filter(trade -> trade.getOrderId().equals(invocation.getArgument(0)))
            .filter(trade -> trade.getFillIdentity().equals(invocation.getArgument(1)))
            .findFirst());
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      trades.add(trade);
      return trade;
    });

    walletService = new WalletService(walletBalanceRepository, assetLedgerEntryRepository);
    spotPositionService = new SpotPositionService(spotPositionRepository);
  }

  @Test
  void depthSellLimitAcrossTwoBidLevelsMutatesWalletLedgerPositionAndNotAccountSummary() {
    Fixture fixture = fixture("depth-sell-two-levels");
    putBalance(fixture.accountId(), "BTC", "2", "2", "0");
    putBalance(fixture.accountId(), "USDT", "10", "10", "0");
    putPosition(fixture.accountId(), "2", "80", "0", "0");
    AccountSummary before = AccountSummary.from(fixture.account());
    DemoExecutionPolicy policy = depthPolicy(
        List.of(
            new DemoBookLevel(decimal("100"), decimal("0.4")),
            new DemoBookLevel(decimal("99"), decimal("0.6"))),
        List.of(new DemoBookLevel(decimal("110"), decimal("5"))));
    stubRoute(fixture, bundle("98", "110", "99.5", Instant.now().plusSeconds(30)));

    OrderResponse response = service(() -> policy, Clock.systemUTC()).createOrder(
        fixture.principal(), request(
            fixture.accountId(), OrderSide.SELL, OrderType.LIMIT, QuantityUnit.BASE,
            "1", "95", "depth-sell-two-levels"));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo("1.00000000");
    assertThat(response.avgFillPrice()).isEqualByComparingTo("99.40000000");
    assertThat(response.fee()).isEqualByComparingTo("0.19880000");
    assertBalance(fixture.accountId(), "BTC", "1.00000000", "1.00000000", "0.00000000");
    assertBalance(fixture.accountId(), "USDT", "109.20120000", "109.20120000", "0.00000000");
    assertPosition(fixture.accountId(), "1.00000000", "80.00000000", "19.20120000", "0.19880000");
    assertThat(AccountSummary.from(fixture.account())).isEqualTo(before);
    assertThat(trades).hasSize(2);
    assertLedger(
        entry("BTC", "SPOT_ORDER_LOCK", "-1.00000000"),
        entry("BTC", "SPOT_SELL_DEBIT", "-0.40000000"),
        entry("USDT", "SPOT_SELL_CREDIT", "40.00000000"),
        entry("USDT", "TRADE_FEE", "-0.08000000"),
        entry("BTC", "SPOT_SELL_DEBIT", "-0.60000000"),
        entry("USDT", "SPOT_SELL_CREDIT", "59.40000000"),
        entry("USDT", "TRADE_FEE", "-0.11880000"));
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void depthMarketBuyAcrossTwoAskLevelsSpendsExactBudgetAndBuildsPosition() {
    Fixture fixture = fixture("depth-market-two-levels");
    putBalance(fixture.accountId(), "USDT", "1000", "1000", "0");
    AccountSummary before = AccountSummary.from(fixture.account());
    DemoExecutionPolicy policy = depthPolicy(
        List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
        List.of(
            new DemoBookLevel(decimal("100"), decimal("0.5")),
            new DemoBookLevel(decimal("101"), decimal("1"))));
    stubRoute(fixture, bundle("99", "109", "100", Instant.now().plusSeconds(30)));

    OrderResponse response = service(() -> policy, Clock.systemUTC()).createOrder(
        fixture.principal(), request(
            fixture.accountId(), OrderSide.BUY, OrderType.MARKET, QuantityUnit.QUOTE,
            "151.803", null, "depth-market-two-levels"));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.baseQuantity()).isEqualByComparingTo("1.5000");
    assertThat(response.avgFillPrice()).isEqualByComparingTo("100.66666667");
    assertThat(response.fee()).isEqualByComparingTo("0.30200000");
    assertBalance(fixture.accountId(), "USDT", "848.69800000", "848.69800000", "0.00000000");
    assertBalance(fixture.accountId(), "BTC", "1.50000000", "1.50000000", "0.00000000");
    assertPosition(fixture.accountId(), "1.50000000", "100.66666667", "0", "0.30200000");
    assertThat(AccountSummary.from(fixture.account())).isEqualTo(before);
    assertThat(trades).hasSize(2);
    assertLedger(
        entry("USDT", "SPOT_ORDER_LOCK", "-151.80300000"),
        entry("USDT", "SPOT_BUY_DEBIT", "-50.00000000"),
        entry("USDT", "TRADE_FEE", "-0.10000000"),
        entry("BTC", "SPOT_BUY_CREDIT", "0.50000000"),
        entry("USDT", "SPOT_ORDER_RELEASE", "0.50100000"),
        entry("USDT", "SPOT_BUY_DEBIT", "-101.00000000"),
        entry("USDT", "TRADE_FEE", "-0.20200000"),
        entry("BTC", "SPOT_BUY_CREDIT", "1.00000000"));
    verify(fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void staleAfterFinancialLocksRetriesFreshSnapshotAndMutatesFinanceExactlyOnce() {
    Fixture fixture = fixture("depth-stale-then-fresh");
    putBalance(fixture.accountId(), "USDT", "1000", "1000", "0");
    Instant now = Instant.parse("2026-07-18T01:00:00Z");
    MutableClock clock = new MutableClock(now, ZoneId.of("UTC"));
    DemoExecutionPolicy policy = depthPolicy(
        List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
        List.of(
            new DemoBookLevel(decimal("100"), decimal("0.4")),
            new DemoBookLevel(decimal("101"), decimal("0.6"))));
    SpotMarketBundle expiring = bundle("99", "110", "99.5", now.plusSeconds(1));
    SpotMarketBundle fresh = bundle("99", "110", "100", now.plusSeconds(30));
    stubRoute(fixture, expiring);
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any())).thenReturn(expiring, fresh);
    AtomicInteger financialLockPasses = new AtomicInteger();
    when(walletBalanceRepository.findByAccountIdForUpdate(fixture.accountId()))
        .thenAnswer(invocation -> {
          if (financialLockPasses.incrementAndGet() == 1) {
            clock.set(now.plusSeconds(2));
          }
          return balances.values().stream()
              .filter(balance -> balance.getAccountId().equals(fixture.accountId()))
              .toList();
        });
    AccountSummary before = AccountSummary.from(fixture.account());

    OrderResponse response = service(() -> policy, clock).createOrder(
        fixture.principal(), request(
            fixture.accountId(), OrderSide.BUY, OrderType.LIMIT, QuantityUnit.BASE,
            "1", "105", "depth-stale-then-fresh"));

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(financialLockPasses).hasValue(2);
    assertThat(orders).hasSize(1);
    assertThat(trades).hasSize(2);
    assertThat(assetLedger).hasSize(7);
    assertBalance(fixture.accountId(), "USDT", "899.19880000", "899.19880000", "0.00000000");
    assertBalance(fixture.accountId(), "BTC", "1.00000000", "1.00000000", "0.00000000");
    assertPosition(fixture.accountId(), "1.00000000", "100.60000000", "0", "0.20120000");
    assertThat(AccountSummary.from(fixture.account())).isEqualTo(before);
    assertLedger(
        entry("USDT", "SPOT_ORDER_LOCK", "-100.80120000"),
        entry("USDT", "SPOT_BUY_DEBIT", "-40.00000000"),
        entry("USDT", "TRADE_FEE", "-0.08000000"),
        entry("BTC", "SPOT_BUY_CREDIT", "0.40000000"),
        entry("USDT", "SPOT_BUY_DEBIT", "-60.60000000"),
        entry("USDT", "TRADE_FEE", "-0.12120000"),
        entry("BTC", "SPOT_BUY_CREDIT", "0.60000000"));
  }

  private OrderService service(DemoExecutionPolicyProvider policyProvider, Clock clock) {
    SpotSettlementService settlementService =
        new SpotSettlementService(walletService, spotPositionService);
    OrderFillService fillService = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlementService, walletService);
    OrderService service = new OrderService(
        orderRepository, accountRepository, riskCheckService, executionAdapter,
        fillService, ledgerService, walletService, orderEventService,
        new OrderCommandFactory(), new OrderEntityFactory(), new OrderResponseMapper(),
        new OrderStatusPolicy(), demoExecutionGuard, walletBalanceRepository,
        positionRepository, spotPositionService, marketBundleResolver,
        fullFillCoordinator, transactionExecutor, symbolRepository,
        instrumentRulesEngine, new QuantityConversionService(), orderHoldCalculator);
    service.setDepthOrderExecutionService(new DepthOrderExecutionService(
        policyProvider, tradeRepository, fillService, orderEventService, clock));
    return service;
  }

  private void stubRoute(Fixture fixture, SpotMarketBundle bundle) {
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        fixture.userId(), fixture.accountId(), fixture.key())).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(fixture.userId(), fixture.key()))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(fixture.accountId(), fixture.userId()))
        .thenReturn(Optional.of(fixture.account()));
    when(accountRepository.findByIdAndUserIdForUpdate(fixture.accountId(), fixture.userId()))
        .thenReturn(Optional.of(fixture.account()));
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol()));
    when(instrumentRulesEngine.rules(any(SymbolEntity.class))).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any())).thenReturn(bundle);
  }

  private Fixture fixture(String key) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setLeverage(1);
    account.setBalance(decimal("10000"));
    account.setEquity(decimal("10025"));
    account.setUsedMargin(decimal("25"));
    account.setFreeMargin(decimal("10000"));
    account.setMarginLevel(decimal("401"));
    return new Fixture(
        userId, accountId, key,
        new UserPrincipal(userId, "trader@example.com", "TRADER"), account);
  }

  private void putBalance(UUID accountId, String asset, String total, String available, String locked) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setId(UUID.randomUUID());
    balance.setAccountId(accountId);
    balance.setWalletType(WalletType.SPOT.code());
    balance.setAsset(asset);
    balance.setTotal(decimal(total));
    balance.setAvailable(decimal(available));
    balance.setLocked(decimal(locked));
    balances.put(walletKey(accountId, WalletType.SPOT.code(), asset), balance);
  }

  private void putPosition(
      UUID accountId,
      String quantity,
      String averageCost,
      String realizedPnl,
      String feeCost
  ) {
    SpotPositionEntity position = new SpotPositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setWalletType(WalletType.SPOT.code());
    position.setAsset("BTC");
    position.setCostAsset("USDT");
    position.setQuantity(decimal(quantity));
    position.setAverageCost(decimal(averageCost));
    position.setRealizedPnl(decimal(realizedPnl));
    position.setUnrealizedPnl(BigDecimal.ZERO);
    position.setFeeCost(decimal(feeCost));
    positions.put(positionKey(accountId, WalletType.SPOT.code(), "BTC", "USDT"), position);
  }

  private void assertBalance(
      UUID accountId,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity balance = balances.get(walletKey(accountId, WalletType.SPOT.code(), asset));
    assertThat(balance).isNotNull();
    assertThat(balance.getTotal()).isEqualByComparingTo(total);
    assertThat(balance.getAvailable()).isEqualByComparingTo(available);
    assertThat(balance.getLocked()).isEqualByComparingTo(locked);
    assertThat(balance.getTotal()).isEqualByComparingTo(
        balance.getAvailable().add(balance.getLocked()));
  }

  private void assertPosition(
      UUID accountId,
      String quantity,
      String averageCost,
      String realizedPnl,
      String feeCost
  ) {
    SpotPositionEntity position = positions.get(
        positionKey(accountId, WalletType.SPOT.code(), "BTC", "USDT"));
    assertThat(position).isNotNull();
    assertThat(position.getQuantity()).isEqualByComparingTo(quantity);
    assertThat(position.getAverageCost()).isEqualByComparingTo(averageCost);
    assertThat(position.getRealizedPnl()).isEqualByComparingTo(realizedPnl);
    assertThat(position.getFeeCost()).isEqualByComparingTo(feeCost);
  }

  private void assertLedger(ExpectedLedger... expected) {
    assertThat(assetLedger).hasSize(expected.length);
    for (int index = 0; index < expected.length; index++) {
      AssetLedgerEntryEntity actual = assetLedger.get(index);
      assertThat(actual.getAsset()).isEqualTo(expected[index].asset());
      assertThat(actual.getEntryType()).isEqualTo(expected[index].entryType());
      assertThat(actual.getAmount()).isEqualByComparingTo(expected[index].amount());
    }
  }

  private static ExpectedLedger entry(String asset, String entryType, String amount) {
    return new ExpectedLedger(asset, entryType, decimal(amount));
  }

  private static DemoExecutionPolicy depthPolicy(
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.001"), decimal("0.002"),
        decimal("0.001"), decimal("0.0001"),
        bids, asks, null);
  }

  private static CreateOrderRequest request(
      UUID accountId,
      OrderSide side,
      OrderType type,
      QuantityUnit unit,
      String quantity,
      String price,
      String key
  ) {
    return new CreateOrderRequest(
        accountId, SYMBOL, side, type,
        null, null, null, null, key, key,
        decimal(quantity), price == null ? null : decimal(price), 1,
        PositionSide.BOTH, unit, MarginMode.CASH,
        null, null, false, List.of());
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setAssetClass("CRYPTO");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMinLot(decimal("0.0001"));
    symbol.setMaxLot(decimal("100"));
    symbol.setLeverage(1);
    return symbol;
  }

  private static InstrumentRules rules() {
    return new InstrumentRules(
        SYMBOL, true, true, true, true, true, true, true,
        ProductType.CRYPTO_SPOT,
        decimal("0.1"), decimal("0.0001"),
        decimal("0.0001"), decimal("100"),
        decimal("5"), null,
        decimal("0.0001"), decimal("100"),
        1, 1, "USDT", "USDT", BigDecimal.ONE, BigDecimal.ONE,
        "DEFAULT", "ALWAYS", "NONE", "NORMAL");
  }

  private static SpotMarketBundle bundle(
      String bid,
      String ask,
      String last,
      Instant expiresAt
  ) {
    return new SpotMarketBundle(
        SYMBOL, SYMBOL, "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid), decimal(ask), decimal(last),
        null, List.of(), List.of(), expiresAt.minusSeconds(30), expiresAt);
  }

  private static String walletKey(UUID accountId, String walletType, String asset) {
    return accountId + "|" + walletType + "|" + asset;
  }

  private static String positionKey(
      UUID accountId,
      String walletType,
      String asset,
      String costAsset
  ) {
    return accountId + "|" + walletType + "|" + asset + "|" + costAsset;
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private record Fixture(
      UUID userId,
      UUID accountId,
      String key,
      UserPrincipal principal,
      TradingAccountEntity account
  ) {
  }

  private record ExpectedLedger(String asset, String entryType, BigDecimal amount) {
  }

  private record AccountSummary(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      BigDecimal marginLevel
  ) {
    private static AccountSummary from(TradingAccountEntity account) {
      return new AccountSummary(
          account.getBalance(), account.getEquity(), account.getUsedMargin(),
          account.getFreeMargin(), account.getMarginLevel());
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
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

    private void set(Instant instant) {
      this.instant = instant;
    }
  }
}
