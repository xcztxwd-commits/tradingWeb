package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepthPerpetualPendingLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private TradeRepository tradeRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private QuoteService quoteService;
  @Mock private RiskCheckService riskCheckService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private WalletService walletService;
  @Mock private SpotPositionService spotPositionService;
  @Mock private PositionRepository positionRepository;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private SymbolRepository symbolRepository;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private LedgerService ledgerService;

  @Test
  void restingCrossLimitFillsAcrossThreeTicksWithExactH1AndMakerAccounting() {
    Fixture fixture = fixture("three-ticks");

    fixture.moveToTick(NOW.minusSeconds(3));
    int first = fixture.service().executePendingOrders();
    assertTick(
        fixture,
        first,
        OrderStatus.PARTIALLY_FILLED,
        "0.40000000",
        "0.60000000",
        "0.04000000",
        "6.06000000",
        "0.40000000",
        "4.00000000");

    fixture.moveToTick(NOW.minusSeconds(2));
    int second = fixture.service().executePendingOrders();
    assertTick(
        fixture,
        second,
        OrderStatus.PARTIALLY_FILLED,
        "0.80000000",
        "0.20000000",
        "0.08000000",
        "2.02000000",
        "0.80000000",
        "8.00000000");

    fixture.moveToTick(NOW.minusSeconds(1));
    int third = fixture.service().executePendingOrders();
    assertTick(
        fixture,
        third,
        OrderStatus.FILLED,
        "1.00000000",
        "0.00000000",
        "0.10000000",
        "0.00000000",
        "1.00000000",
        "10.00000000");

    assertAll(
        () -> assertThat(fixture.trades())
            .extracting(TradeEntity::getLots)
            .containsExactly(decimal("0.40000000"), decimal("0.40000000"), decimal("0.20000000")),
        () -> assertThat(fixture.trades())
            .extracting(TradeEntity::getPrice)
            .containsOnly(decimal("100")),
        () -> assertThat(fixture.trades())
            .extracting(TradeEntity::getFee)
            .containsExactly(decimal("0.04000000"), decimal("0.04000000"), decimal("0.02000000")),
        () -> assertThat(fixture.trades())
            .extracting(TradeEntity::getLiquidityRole)
            .containsOnly(LiquidityRole.MAKER),
        () -> assertThat(fixture.trades())
            .extracting(TradeEntity::getFillIdentity)
            .doesNotContainNull()
            .doesNotHaveDuplicates(),
        () -> assertThat(fixture.order().getAvgFillPrice()).isEqualByComparingTo("100.00000000"),
        () -> assertThat(fixture.order().getLiquidityRole()).isEqualTo(LiquidityRole.MAKER),
        () -> assertThat(fixture.account().getBalance()).isEqualByComparingTo("49999.90000000"),
        () -> assertThat(fixture.account().getEquity()).isEqualByComparingTo("49999.90000000"),
        () -> assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("49989.90000000"),
        () -> verify(fullFillCoordinator, never()).execute(
            any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)));
  }

  @Test
  void sameTickReplayStopsBeforeMatchingHoldFillLedgerAndLifecycleMutation() {
    Fixture fixture = fixture("same-tick-replay");
    fixture.moveToTick(NOW.minusSeconds(3));

    assertThat(fixture.service().executePendingOrders()).isEqualTo(1);
    assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    OrderSnapshot afterFirst = OrderSnapshot.capture(fixture.order());
    int tradeCount = fixture.trades().size();

    clearInvocations(
        fixture.depthExecution(),
        fixture.orderFillService(),
        orderRepository,
        tradeRepository,
        accountRepository,
        positionRepository,
        ledgerService,
        orderEventService,
        fullFillCoordinator);

    int replayed = fixture.service().executePendingOrders();

    List<String> depthCalls = mockingDetails(fixture.depthExecution()).getInvocations().stream()
        .map(invocation -> invocation.getMethod().getName())
        .toList();
    List<String> fillCalls = mockingDetails(fixture.orderFillService()).getInvocations().stream()
        .map(invocation -> invocation.getMethod().getName())
        .toList();
    assertAll(
        () -> assertThat(replayed).isZero(),
        () -> assertThat(OrderSnapshot.capture(fixture.order())).isEqualTo(afterFirst),
        () -> assertThat(fixture.trades()).hasSize(tradeCount),
        () -> assertThat(depthCalls).doesNotContain("prepare", "planPerpetual", "applyLocked"),
        () -> assertThat(fillCalls).doesNotContain("applyFill", "fillPerpetual"),
        () -> verify(orderRepository, never()).save(any(OrderEntity.class)),
        () -> verify(tradeRepository, never()).save(any(TradeEntity.class)),
        () -> verify(accountRepository, never()).save(any(TradingAccountEntity.class)),
        () -> verify(positionRepository, never()).save(any(PositionEntity.class)),
        () -> verifyNoInteractions(ledgerService, orderEventService),
        () -> verify(fullFillCoordinator, never()).execute(
            any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)));
  }

  private Fixture fixture(String key) {
    UUID userId = namedId("user-" + key);
    UUID accountId = namedId("account-" + key);
    OrderEntity order = restingLimit(userId, accountId, key);
    TradingAccountEntity account = account(userId, accountId);
    AccountSymbolSettingEntity setting = setting(accountId);
    SymbolEntity symbol = symbol();
    DemoExecutionPolicy policy = policy();
    AtomicReference<PerpetualMarketBundle> market = new AtomicReference<>(
        bundle(NOW.minusSeconds(3)));
    AtomicReference<PositionEntity> position = new AtomicReference<>();
    List<TradeEntity> trades = new ArrayList<>();

    when(orderRepository.findByStatus(any(OrderStatus.class))).thenAnswer(invocation -> {
      OrderStatus requested = invocation.getArgument(0);
      return order.getStatus() == requested ? List.of(order) : List.of();
    });
    when(orderRepository.findUserStopLimitsAwaitingActivation()).thenReturn(List.of());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    lenient().when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    lenient().when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(orderRepository.findByIdForUpdate(order.getId()))
        .thenReturn(Optional.of(order));
    lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenAnswer(invocation -> isActive(order.getStatus()) ? List.of(order) : List.of());
    lenient().when(orderRepository.claimPending(order.getId())).thenAnswer(invocation -> {
      if (order.getStatus() != OrderStatus.PENDING) {
        return 0;
      }
      order.setStatus(OrderStatus.WORKING);
      return 1;
    });
    lenient().when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    lenient().when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    lenient().when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    lenient().when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(
        accountId, SYMBOL)).thenReturn(Optional.of(setting));
    lenient().when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenAnswer(invocation -> market.get());
    lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());

    lenient().when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenAnswer(invocation -> openPositions(position));
    lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenAnswer(invocation -> openPositions(position));
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenAnswer(invocation -> Optional.ofNullable(position.get()));
    lenient().when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity saved = invocation.getArgument(0);
      if (saved.getId() == null) {
        saved.setId(namedId("position-" + key));
      }
      position.set(saved);
      return saved;
    });

    lenient().when(tradeRepository.findByOrderIdAndFillIdentity(eq(order.getId()), anyString()))
        .thenAnswer(invocation -> trades.stream()
            .filter(trade -> invocation.getArgument(1).equals(trade.getFillIdentity()))
            .findFirst());
    lenient().when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity saved = invocation.getArgument(0);
      if (saved.getId() == null) {
        saved.setId(namedId("trade-" + key + "-" + trades.size()));
      }
      if (!trades.contains(saved)) {
        trades.add(saved);
      }
      return saved;
    });

    lenient().when(fullFillCoordinator.project(any(), any(), any(), any(), any()))
        .thenReturn(new FullFillPricingProjection(
            decimal("100"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            decimal("0.001"),
            decimal("0.001"),
            LiquidityRole.MAKER));
    lenient().when(fullFillCoordinator.execute(
        any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenAnswer(invocation -> fullFill(order.getRemainingQuantity(), market.get()));

    PositionEngine positionEngine = new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new MarginCalculator(),
        new PnLCalculator(),
        new PerpMarginCalculator());
    OrderFillService orderFillService = spy(new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        new SpotSettlementService(walletService, spotPositionService),
        positionEngine,
        walletService));
    DemoExecutionPolicyProvider policyProvider = () -> policy;
    DepthOrderExecutionService depthExecution = spy(new DepthOrderExecutionService(
        policyProvider,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC)));
    PerpetualOrderRiskService perpetualRisk = new PerpetualOrderRiskService(
        fullFillCoordinator,
        new PerpMarginCalculator());
    PerpetualAccountRiskSnapshotService accountRisk =
        new PerpetualAccountRiskSnapshotService(
            positionRepository,
            symbolRepository,
            marketBundleResolver,
            fullFillCoordinator,
            new PerpetualRiskService(new PerpMarginCalculator()),
            instrumentRulesEngine);
    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        orderFillService,
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        marketBundleResolver,
        fullFillCoordinator,
        perpetualRisk,
        accountSymbolSettingRepository,
        symbolRepository,
        accountRisk);
    injectIfPresent(service, DepthOrderExecutionService.class, depthExecution);
    injectIfPresent(service, InstrumentRulesEngine.class, instrumentRulesEngine);
    return new Fixture(
        service,
        order,
        account,
        market,
        position,
        trades,
        depthExecution,
        orderFillService);
  }

  private static void assertTick(
      Fixture fixture,
      int executed,
      OrderStatus status,
      String filled,
      String remaining,
      String fee,
      String hold,
      String positionQuantity,
      String positionMargin
  ) {
    assertAll(
        () -> assertThat(executed).isEqualTo(1),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(status),
        () -> assertThat(fixture.order().getFilledQuantity()).isEqualByComparingTo(filled),
        () -> assertThat(fixture.order().getRemainingQuantity()).isEqualByComparingTo(remaining),
        () -> assertThat(fixture.order().getFee()).isEqualByComparingTo(fee),
        () -> assertThat(fixture.order().getHoldAmount()).isEqualByComparingTo(hold),
        () -> assertThat(fixture.order().getLiquidityRole()).isEqualTo(LiquidityRole.MAKER),
        () -> assertThat(fixture.position().get()).isNotNull(),
        () -> assertThat(fixture.position().get().getLots())
            .isEqualByComparingTo(positionQuantity),
        () -> assertThat(fixture.position().get().getMarginHeld())
            .isEqualByComparingTo(positionMargin));
  }

  private static void injectIfPresent(
      Object target,
      Class<?> dependencyType,
      Object dependency
  ) {
    for (Method method : target.getClass().getDeclaredMethods()) {
      if (method.getParameterCount() == 1
          && method.getParameterTypes()[0].isAssignableFrom(dependencyType)) {
        try {
          method.setAccessible(true);
          method.invoke(target, dependency);
          return;
        } catch (ReflectiveOperationException exception) {
          throw new AssertionError("Could not inject " + dependencyType.getSimpleName(), exception);
        }
      }
    }
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (field.getType().isAssignableFrom(dependencyType)) {
          try {
            field.setAccessible(true);
            field.set(target, dependency);
            return;
          } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inject " + dependencyType.getSimpleName(), exception);
          }
        }
      }
    }
  }

  private static List<PositionEntity> openPositions(
      AtomicReference<PositionEntity> position
  ) {
    PositionEntity current = position.get();
    return current == null || current.getStatus() != PositionStatus.OPEN
        ? List.of()
        : List.of(current);
  }

  private static boolean isActive(OrderStatus status) {
    return status == OrderStatus.PENDING
        || status == OrderStatus.WORKING
        || status == OrderStatus.PARTIALLY_FILLED;
  }

  private static OrderEntity restingLimit(UUID userId, UUID accountId, String key) {
    OrderEntity order = new OrderEntity();
    order.setId(namedId("order-" + key));
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(decimal("1.0000"));
    order.setQuantity(decimal("1.0000"));
    order.setOriginalQuantity(decimal("1.0000"));
    order.setBaseQuantity(decimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setPrice(decimal("100"));
    order.setRequestedPrice(decimal("100"));
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setLeverage(10);
    order.setReduceOnly(false);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(decimal("1.0000"));
    order.setFee(BigDecimal.ZERO);
    order.setHoldAmount(decimal("10.10000000"));
    order.setHoldCurrency("USDT");
    order.setClientOrderId(key);
    order.setIdempotencyKey(key);
    order.setCreatedAt(NOW.minusSeconds(60));
    return order;
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(decimal("50000.00000000"));
    account.setEquity(decimal("50000.00000000"));
    account.setUsedMargin(decimal("10.10000000"));
    account.setFreeMargin(decimal("49989.90000000"));
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }

  private static AccountSymbolSettingEntity setting(UUID accountId) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(SYMBOL);
    setting.setLeverage(10);
    setting.setMarginMode(MarginMode.CROSS);
    setting.setQuantityUnit(QuantityUnit.BASE);
    setting.setVersion(1L);
    return setting;
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(namedId("symbol-" + SYMBOL));
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(decimal("0.005"));
    symbol.setLeverage(100);
    symbol.setEnabled(true);
    symbol.setTradable(true);
    return symbol;
  }

  private static InstrumentRules rules() {
    return new InstrumentRules(
        SYMBOL,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        ProductType.LINEAR_PERP,
        decimal("0.1"),
        decimal("0.0001"),
        decimal("0.0001"),
        decimal("1000"),
        decimal("5"),
        null,
        decimal("0.0001"),
        decimal("1000"),
        100,
        10,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private static DemoExecutionPolicy policy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.001"),
        decimal("0.001"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
        List.of(new DemoBookLevel(decimal("100"), decimal("5"))),
        decimal("0.4000"));
  }

  private static PerpetualMarketBundle bundle(Instant asOf) {
    return new PerpetualMarketBundle(
        SYMBOL,
        SYMBOL,
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"),
        decimal("100"),
        decimal("100"),
        decimal("100"),
        decimal("100"),
        null,
        List.of(),
        List.of(),
        asOf,
        NOW.plusSeconds(30));
  }

  private static FullFillResult fullFill(
      BigDecimal quantity,
      PerpetualMarketBundle bundle
  ) {
    BigDecimal fee = quantity.multiply(decimal("100")).multiply(decimal("0.001"));
    return new FullFillResult(
        decimal("100"),
        bundle.asOf(),
        quantity,
        BigDecimal.ZERO,
        decimal("0.001"),
        fee,
        "USDT",
        LiquidityRole.MAKER,
        BigDecimal.ZERO,
        bundle.sourceMode(),
        bundle.providerCode(),
        bundle.providerSymbol(),
        bundle.asOf(),
        bundle.expiresAt());
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private static UUID namedId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(
      PendingOrderExecutionService service,
      OrderEntity order,
      TradingAccountEntity account,
      AtomicReference<PerpetualMarketBundle> market,
      AtomicReference<PositionEntity> position,
      List<TradeEntity> trades,
      DepthOrderExecutionService depthExecution,
      OrderFillService orderFillService
  ) {

    private void moveToTick(Instant asOf) {
      market.set(bundle(asOf));
    }
  }

  private record OrderSnapshot(
      OrderStatus status,
      BigDecimal filled,
      BigDecimal remaining,
      BigDecimal average,
      BigDecimal fee,
      BigDecimal hold,
      LiquidityRole liquidityRole
  ) {

    private static OrderSnapshot capture(OrderEntity order) {
      return new OrderSnapshot(
          order.getStatus(),
          order.getFilledQuantity(),
          order.getRemainingQuantity(),
          order.getAvgFillPrice(),
          order.getFee(),
          order.getHoldAmount(),
          order.getLiquidityRole());
    }
  }
}
