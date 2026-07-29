package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
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
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Binds pending Perpetual DEPTH matching to one current instrument-rules snapshot. */
@ExtendWith(MockitoExtension.class)
class DepthPerpetualPendingInstrumentAuthorityTest {

  private static final Instant NOW = Instant.parse("2026-07-18T07:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private TradeRepository tradeRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private QuoteService quoteService;
  @Mock private RiskCheckService riskCheckService;
  @Mock private OrderFillService orderFillService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private WalletService walletService;
  @Mock private SpotPositionService spotPositionService;
  @Mock private PositionRepository positionRepository;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private PerpetualOrderRiskService perpetualOrderRiskService;
  @Mock private PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private SymbolRepository symbolRepository;

  @Test
  void disabledInstrumentFailsClosedBeforeMatchingRiskOrMutation() {
    InstrumentRules disabled = rules("0.1", false, false, false);
    RunResult run = execute(policy("100.0"), disabled);

    assertAll(
        () -> assertThat(run.executed()).isZero(),
        () -> assertThat(run.order().getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> verify(run.instrumentRulesEngine(), times(1)).rules(same(run.symbol())),
        () -> verify(run.instrumentRulesEngine()).validateDepthExecutionAuthority(
            same(run.symbol()), same(disabled)),
        () -> verify(run.depthExecutionService(), never()).prepare(
            any(DemoExecutionPolicy.class),
            anyString(),
            eq(ProductType.LINEAR_PERP),
            any(OrderSide.class),
            any(OrderType.class),
            any(OrderType.class),
            any(TimeInForce.class),
            any(BigDecimal.class),
            any(BigDecimal.class),
            anyBoolean(),
            any(LiquidityRole.class),
            any(ExecutableMarketSnapshot.class)),
        () -> assertWorkerFailure(run.order(), ErrorCode.SYMBOL_NOT_TRADABLE),
        () -> assertNoRiskOrMutation(run));
  }

  @Test
  void offTickFillValidationReusesTheSingleResolvedRulesInstance() {
    InstrumentRules enabled = rules("0.1", true, true, true);
    RunResult run = execute(policy("100.05"), enabled);

    assertAll(
        () -> assertThat(run.executed()).isZero(),
        () -> assertThat(run.order().getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> verify(run.instrumentRulesEngine(), times(1)).rules(same(run.symbol())),
        () -> verify(run.instrumentRulesEngine()).validateCanonicalDepthFills(
            same(run.symbol()), same(enabled), anyList()),
        () -> verify(run.instrumentRulesEngine(), never()).validateCanonicalDepthFills(
            same(run.symbol()), anyList()),
        () -> assertWorkerFailure(run.order(), ErrorCode.PRICE_TICK_MISMATCH),
        () -> assertNoRiskOrMutation(run));
  }

  private RunResult execute(DemoExecutionPolicy policy, InstrumentRules rules) {
    OrderEntity order = pendingLimit();
    TradingAccountEntity account = account(order);
    AccountSymbolSettingEntity setting = setting(order.getAccountId());
    SymbolEntity symbol = symbol();
    PerpetualMarketBundle bundle = bundle();
    ExecutableMarketSnapshot snapshot = ExecutableMarketSnapshot.from(bundle);
    PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedRisk =
        new PerpetualAccountRiskSnapshotService.PreparedAccountRisk(
            order.getAccountId(),
            Map.of(
                SYMBOL,
                new PerpetualAccountRiskSnapshotService.PreparedSymbolRisk(
                    SYMBOL,
                    snapshot,
                    decimal("0.005"),
                    100)),
            List.of());

    InstrumentRulesEngine instrumentRulesEngine = spy(new InstrumentRulesEngine(
        symbolRepository,
        null,
        null,
        null,
        null,
        new ObjectMapper(),
        new TradingInstrumentClassifier()));
    doReturn(rules).when(instrumentRulesEngine).rules(symbol);
    DepthOrderExecutionService depthExecutionService = spy(new DepthOrderExecutionService(
        () -> policy,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC)));

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(orderRepository.findByStatus(OrderStatus.PARTIALLY_FILLED)).thenReturn(List.of());
    when(orderRepository.findUserStopLimitsAwaitingActivation()).thenReturn(List.of());
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenReturn(bundle);
    when(accountRiskSnapshotService.prepare(eq(order.getAccountId()), any()))
        .thenReturn(preparedRisk);
    when(transactionExecutor.execute(any())).thenAnswer(invocation ->
        ((Supplier<?>) invocation.getArgument(0)).get());
    when(accountRepository.findByIdForUpdate(order.getAccountId()))
        .thenReturn(Optional.of(account));
    when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(
        order.getAccountId(), SYMBOL)).thenReturn(Optional.of(setting));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(order.getAccountId()))
        .thenReturn(List.of());
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(order.getAccountId()))
        .thenReturn(List.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(tradeRepository.findByOrderIdAndFillIdentity(eq(order.getId()), anyString()))
        .thenReturn(Optional.empty());
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));

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
        perpetualOrderRiskService,
        accountSymbolSettingRepository,
        symbolRepository,
        accountRiskSnapshotService);
    service.setDepthOrderExecutionService(depthExecutionService);
    service.setInstrumentRulesEngine(instrumentRulesEngine);

    int executed = service.executePendingOrders();
    return new RunResult(
        executed,
        order,
        symbol,
        instrumentRulesEngine,
        depthExecutionService);
  }

  private void assertWorkerFailure(OrderEntity order, String errorCode) {
    verify(orderEventService).recordWorkerFailure(
        eq(order.getId()),
        eq("ORDER_EXECUTION_FAILED"),
        eq(OrderStatus.PENDING),
        eq(errorCode),
        eq("Pending order execution deferred"),
        any(PendingOrderExecutionFingerprint.class));
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
    verifyNoMoreInteractions(orderEventService);
  }

  private void assertNoRiskOrMutation(RunResult run) {
    verify(accountRiskSnapshotService, never()).project(any(), any(), any(), any());
    verify(accountRiskSnapshotService, never()).applyRevaluation(any(), any(), any());
    verify(perpetualOrderRiskService, never()).evaluateDepth(
        any(), any(), any(), any(), any(), anyBoolean(),
        any(), any(), any(), any(), any(), any());
    verify(run.depthExecutionService(), never()).perpetualRiskPricing(any());
    verify(run.depthExecutionService(), never()).planPerpetual(any(), any(), any(), any());
    verify(run.depthExecutionService(), never()).applyLocked(
        any(), any(), any(), any(), any(), eq(false));
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(tradeRepository, never()).save(any(TradeEntity.class));
    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    verifyNoInteractions(orderFillService);
    assertAll(
        () -> assertThat(run.order().getFilledQuantity()).isEqualByComparingTo("0"),
        () -> assertThat(run.order().getRemainingQuantity()).isEqualByComparingTo("0.8000"),
        () -> assertThat(run.order().getHoldAmount()).isEqualByComparingTo("8.08000000"));
  }

  private static DemoExecutionPolicy policy(String askPrice) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.001"),
        decimal("0.001"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
        List.of(new DemoBookLevel(decimal(askPrice), decimal("0.4000"))),
        null);
  }

  private static InstrumentRules rules(
      String tickSize,
      boolean enabled,
      boolean tradable,
      boolean orderEnabled
  ) {
    return new InstrumentRules(
        SYMBOL,
        true,
        enabled,
        tradable,
        true,
        true,
        true,
        orderEnabled,
        ProductType.LINEAR_PERP,
        decimal(tickSize),
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
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private static OrderEntity pendingLimit() {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(decimal("0.8000"));
    order.setQuantity(decimal("0.8000"));
    order.setOriginalQuantity(decimal("0.8000"));
    order.setBaseQuantity(decimal("0.8000"));
    order.setRemainingQuantity(decimal("0.8000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setPrice(decimal("101"));
    order.setRequestedPrice(decimal("101"));
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setLeverage(10);
    order.setReduceOnly(false);
    order.setHoldAmount(decimal("8.08000000"));
    order.setHoldCurrency("USDT");
    order.setCreatedAt(NOW.minusSeconds(60));
    return order;
  }

  private static TradingAccountEntity account(OrderEntity order) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    account.setUserId(order.getUserId());
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(decimal("50000.00000000"));
    account.setEquity(decimal("50000.00000000"));
    account.setUsedMargin(decimal("8.08000000"));
    account.setFreeMargin(decimal("49991.92000000"));
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
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("CRYPTO");
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

  private static PerpetualMarketBundle bundle() {
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
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private record RunResult(
      int executed,
      OrderEntity order,
      SymbolEntity symbol,
      InstrumentRulesEngine instrumentRulesEngine,
      DepthOrderExecutionService depthExecutionService
  ) {
  }
}
