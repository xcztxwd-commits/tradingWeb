package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A pending Perpetual SIMPLE plan may not survive a policy transition to DEPTH. */
@ExtendWith(MockitoExtension.class)
class DepthPerpetualPendingSimplePolicyTransitionTest {

  private static final Instant NOW = Instant.parse("2026-07-18T06:00:00Z");
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
  @Mock private PerpetualOrderRiskService perpetualOrderRiskService;
  @Mock private PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private SymbolRepository symbolRepository;
  @Mock private LedgerService ledgerService;

  @Test
  void simpleToDepthFlipBeforeTransactionSupplierFailsClosedBeforePricingOrMutation() {
    DemoExecutionPolicy simple = DemoExecutionPolicy.defaults();
    DemoExecutionPolicy depth = depthPolicy();
    AtomicReference<DemoExecutionPolicy> currentPolicy = new AtomicReference<>(simple);
    OrderEntity order = pendingLimit();
    TradingAccountEntity account = account(order);
    AccountSymbolSettingEntity setting = setting(order.getAccountId());
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

    OrderFillService orderFillService = orderFillService();
    DepthOrderExecutionService depthExecutionService = new DepthOrderExecutionService(
        currentPolicy::get,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));
    PendingOrderExecutionService service = service(orderFillService);
    service.setDepthOrderExecutionService(depthExecutionService);

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(orderRepository.findByStatus(OrderStatus.PARTIALLY_FILLED)).thenReturn(List.of());
    when(orderRepository.findUserStopLimitsAwaitingActivation()).thenReturn(List.of());
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenReturn(bundle);
    when(accountRiskSnapshotService.prepare(eq(order.getAccountId()), any()))
        .thenReturn(preparedRisk);
    when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
      currentPolicy.set(depth);
      return ((Supplier<?>) invocation.getArgument(0)).get();
    });

    lenient().when(accountRepository.findByIdForUpdate(order.getAccountId()))
        .thenReturn(Optional.of(account));
    lenient().when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(
        order.getAccountId(), SYMBOL)).thenReturn(Optional.of(setting));
    lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        order.getAccountId())).thenReturn(List.of());
    lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        order.getAccountId())).thenReturn(List.of(order));
    lenient().when(orderRepository.findByIdForUpdate(order.getId()))
        .thenReturn(Optional.of(order));
    lenient().when(accountRiskSnapshotService.project(any(), any(), any(), eq(preparedRisk)))
        .thenReturn(accountRiskProjection());
    lenient().doThrow(new AssertionError(
            "stale SIMPLE policy reached the Perpetual pricing boundary"))
        .when(perpetualOrderRiskService)
        .evaluate(
            any(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), any(), any(), any());

    int executed = service.executePendingOrders();

    assertAll(
        () -> assertThat(executed).isZero(),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(currentPolicy.get()).isSameAs(depth));
    verify(orderEventService).recordWorkerFailure(
        eq(order.getId()),
        eq("ORDER_EXECUTION_FAILED"),
        eq(OrderStatus.PENDING),
        eq(ErrorCode.MARKET_DATA_STALE),
        eq("Pending order execution deferred"),
        any(PendingOrderExecutionFingerprint.class));
    verify(perpetualOrderRiskService, never()).evaluate(
        any(), any(), any(), any(), any(), anyBoolean(),
        any(), any(), any(), any(), any());
    verify(fullFillCoordinator, never()).project(any(), any(), any(), any(), any());
    verify(fullFillCoordinator, never()).execute(any(FullFillRequest.class), any());
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(tradeRepository, never()).save(any(TradeEntity.class));
    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    verifyNoInteractions(orderFillService, ledgerService);
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
    verifyNoMoreInteractions(orderEventService);
  }

  private PendingOrderExecutionService service(OrderFillService orderFillService) {
    return new PendingOrderExecutionService(
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
  }

  private OrderFillService orderFillService() {
    PositionEngine positionEngine = new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new MarginCalculator(),
        new PnLCalculator(),
        new PerpMarginCalculator());
    return spy(new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        new SpotSettlementService(walletService, spotPositionService),
        positionEngine,
        walletService));
  }

  private static DemoExecutionPolicy depthPolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.001"),
        decimal("0.001"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(new DemoBookLevel(decimal("99"), BigDecimal.ONE)),
        List.of(new DemoBookLevel(decimal("100"), BigDecimal.ONE)),
        null);
  }

  private static OrderEntity pendingLimit() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
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
    order.setRemainingQuantity(decimal("1.0000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setPrice(decimal("101"));
    order.setRequestedPrice(decimal("101"));
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setLeverage(10);
    order.setReduceOnly(false);
    order.setHoldAmount(decimal("10.10000000"));
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

  private static PerpetualAccountRiskSnapshotService.AccountRiskProjection
      accountRiskProjection() {
    return new PerpetualAccountRiskSnapshotService.AccountRiskProjection(
        decimal("50000.00000000"),
        decimal("10.10000000"),
        decimal("10.10000000"),
        decimal("10.10000000"),
        decimal("50000.00000000"),
        decimal("49989.90000000"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        false,
        List.of());
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
