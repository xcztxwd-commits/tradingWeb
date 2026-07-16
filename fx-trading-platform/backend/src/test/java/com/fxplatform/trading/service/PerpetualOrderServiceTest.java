package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerpetualOrderServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
  @Mock private OrderFillService orderFillService;
  @Mock private LedgerService ledgerService;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private SpotPositionService spotPositionService;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private SymbolRepository symbolRepository;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private ProtectionOrderService protectionOrderService;

  private UUID userId;
  private UUID accountId;
  private TradingAccountEntity account;
  private AccountSymbolSettingEntity setting;
  private PerpetualMarketBundle marketBundle;
  private FullFillCoordinator fullFillCoordinator;
  private OrderService service;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    account = account(userId, accountId);
    setting = setting(accountId, 10, MarginMode.CROSS);
    marketBundle = bundle();
    SymbolEntity symbol = symbol();

    lenient().when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(any(), any(), anyString()))
        .thenReturn(Optional.empty());
    lenient().when(orderRepository.findByUserIdAndIdempotencyKey(any(), anyString()))
        .thenReturn(Optional.empty());
    lenient().when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(accountId, SYMBOL))
        .thenAnswer(invocation -> Optional.of(setting));
    lenient().when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of());
    lenient().when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of());
    lenient().when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    lenient().when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    lenient().when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenReturn(marketBundle);
    lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    lenient().when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    lenient().when(executionAdapter.execute(any(CreateOrderRequest.class))).thenAnswer(invocation -> {
      CreateOrderRequest intent = invocation.getArgument(0);
      return new ExecutionResult(
          null,
          NOW,
          intent.quantity(),
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          null,
          BigDecimal.ZERO,
          null,
          null);
    });
    lenient().when(orderFillService.fillPerpetual(
        any(OrderEntity.class),
        any(TradingAccountEntity.class),
        any(FullFillResult.class),
        any(BigDecimal.class),
        anyInt(),
        anyString())).thenAnswer(invocation -> {
          OrderEntity order = invocation.getArgument(0);
          FullFillResult fill = invocation.getArgument(2);
          order.setStatus(OrderStatus.FILLED);
          order.setExecutionPrice(fill.filledPrice());
          order.setAvgFillPrice(fill.filledPrice());
          order.setFilledQuantity(fill.filledQuantity());
          order.setRemainingQuantity(BigDecimal.ZERO);
          order.setFee(fill.fee());
          order.setFeeAsset(fill.feeAsset());
          order.setLiquidityRole(fill.liquidityRole());
          order.setSlippage(fill.slippage());
          order.setFilledAt(fill.filledAt());
          order.setHoldAmount(BigDecimal.ZERO);
          return order;
        });

    fullFillCoordinator = spy(new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC)));
    service = newService(fullFillCoordinator);
    service.setProtectionOrderService(protectionOrderService);
  }

  private OrderService newService(FullFillCoordinator coordinator) {
    return new OrderService(
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
        coordinator,
        transactionExecutor,
        symbolRepository,
        instrumentRulesEngine,
        new QuantityConversionService(),
        new OrderHoldCalculator(coordinator),
        accountSymbolSettingRepository,
        new PerpetualOrderRiskService(coordinator),
        new PerpetualAccountRiskSnapshotService(
            positionRepository,
            symbolRepository,
            marketBundleResolver,
            coordinator,
            new PerpetualRiskService(new PerpMarginCalculator())));
  }

  @Test
  void marketUsesLockedAuthorityAndPreservesPublicQuantityAudit() {
    account.setPositionMode(PositionMode.HEDGE);
    setting.setLeverage(7);
    setting.setMarginMode(MarginMode.ISOLATED);
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.QUOTE,
        "100",
        null,
        null,
        PositionSide.LONG,
        99,
        MarginMode.CROSS,
        "market-authority");

    OrderResponse response = service.createOrder(principal(), request);

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE),
        () -> assertThat(response.positionSide()).isEqualTo(PositionSide.LONG),
        () -> assertThat(response.leverage()).isEqualTo(7),
        () -> assertThat(response.marginMode()).isEqualTo(MarginMode.ISOLATED),
        () -> assertThat(response.quantity()).isEqualByComparingTo("100"),
        () -> assertThat(response.originalQuantity()).isEqualByComparingTo("100"),
        () -> assertThat(response.quantityUnit()).isEqualTo(QuantityUnit.QUOTE),
        () -> assertThat(response.baseQuantity()).isEqualByComparingTo("1.0000"));
    verify(riskCheckService, never()).checkOrder(any(), any());
  }

  @Test
  void nonMarketableLimitUsesExactLimitForPendingHold() {
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        PositionSide.BOTH,
        77,
        MarginMode.ISOLATED,
        "resting-limit");

    OrderResponse response = service.createOrder(principal(), request);

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name()),
        () -> assertThat(response.positionMode()).isEqualTo(PositionMode.ONE_WAY),
        () -> assertThat(response.leverage()).isEqualTo(10),
        () -> assertThat(response.marginMode()).isEqualTo(MarginMode.CROSS),
        () -> assertThat(response.holdAmount()).isEqualByComparingTo("9.84900000"),
        () -> assertThat(response.holdCurrency()).isEqualTo("USDT"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("9.84900000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.15100000"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("50000"));
    verify(marketBundleResolver).resolvePerp(eq(SYMBOL), any());
    verify(executionAdapter, never()).execute(any(CreateOrderRequest.class));
  }

  @Test
  void stopMarketAlreadyAcrossMarkStillPersistsPendingMarkTrigger() {
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.STOP_MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        "90",
        PositionSide.BOTH,
        44,
        MarginMode.ISOLATED,
        "mark-stop");

    Object result = capture(() -> service.createOrder(principal(), request));

    assertAll(
        () -> assertThat(result).isInstanceOf(OrderResponse.class),
        () -> {
          if (result instanceof OrderResponse response) {
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
            assertThat(response.triggerPrice()).isEqualByComparingTo("90");
            assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
            assertThat(response.executionPrice()).isNull();
          }
        },
        () -> verify(executionAdapter, never()).execute(any(CreateOrderRequest.class)));
  }

  @Test
  void marketableLimitUsesBidAskAndReservesBeforeItsImmediateFill() {
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "102",
        null,
        PositionSide.BOTH,
        88,
        MarginMode.ISOLATED,
        "marketable-limit");

    OrderResponse response = service.createOrder(principal(), request);

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(response.executionPrice()).isEqualByComparingTo("101"),
        () -> assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER),
        () -> assertThat(response.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO));
    InOrder mutationOrder = inOrder(
        accountRepository,
        orderRepository,
        ledgerService,
        orderFillService);
    mutationOrder.verify(accountRepository).save(account);
    mutationOrder.verify(orderRepository).save(any(OrderEntity.class));
    mutationOrder.verify(ledgerService).recordOrderHold(
        account,
        new BigDecimal("10.25100000"),
        response.id(),
        "Perpetual order margin reserved");
    mutationOrder.verify(orderFillService).fillPerpetual(
        any(OrderEntity.class),
        eq(account),
        any(FullFillResult.class),
        eq(new BigDecimal("100")),
        eq(10),
        eq("Perpetual position margin held"));
  }

  @Test
  void missingExactSettingFailsClosedBeforeAnyReserveOrOrderWrite() {
    when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(accountId, SYMBOL))
        .thenReturn(Optional.empty());
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "missing-setting");

    Object result = capture(() -> service.createOrder(principal(), request));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo("INVALID_INSTRUMENT_RULES"),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(orderRepository, never()).save(any()),
        () -> verify(orderFillService, never()).fillPerpetual(
            any(OrderEntity.class),
            any(TradingAccountEntity.class),
            any(FullFillResult.class),
            any(BigDecimal.class),
            anyInt(),
            anyString()));
  }

  @Test
  void lockedSettingLeverageAboveCurrentInstrumentMaximumFailsClosed() {
    setting.setLeverage(101);
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1",
        null,
        null,
        PositionSide.BOTH,
        1,
        MarginMode.CROSS,
        "locked-leverage-too-high");

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.LEVERAGE_OUT_OF_RANGE));

    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void adminMaximumReductionAfterMarketPreparationIsRevalidatedInsideMutation() {
    setting.setLeverage(80);
    SymbolEntity prepared = symbol();
    prepared.setLeverage(100);
    SymbolEntity current = symbol();
    current.setLeverage(50);
    AtomicBoolean insideTransaction = new AtomicBoolean();
    when(symbolRepository.findBySymbol(SYMBOL))
        .thenAnswer(invocation -> Optional.of(insideTransaction.get() ? current : prepared));
    when(instrumentRulesEngine.rules(any(SymbolEntity.class))).thenReturn(rules());
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1",
        null,
        null,
        PositionSide.BOTH,
        1,
        MarginMode.CROSS,
        "max-lowered-after-prepare");

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.LEVERAGE_OUT_OF_RANGE));

    verify(positionRepository, never()).findOpenLinearPerpByAccountIdForUpdate(accountId);
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void insufficientHoldFailsBeforeOrderSaveAndExecution() {
    account.setBalance(BigDecimal.ZERO);
    account.setEquity(BigDecimal.ZERO);
    account.setFreeMargin(BigDecimal.ZERO);
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "insufficient-hold");

    Object result = capture(() -> service.createOrder(principal(), request));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo(ErrorCode.INSUFFICIENT_MARGIN),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(orderRepository, never()).save(any()),
        () -> verify(orderFillService, never()).fillPerpetual(
            any(OrderEntity.class),
            any(TradingAccountEntity.class),
            any(FullFillResult.class),
            any(BigDecimal.class),
            anyInt(),
            anyString()));
  }

  @Test
  void staleLockedAttemptRetriesWithProviderOutsideTransactionAndOneWriteSet() {
    AtomicBoolean insideTransaction = new AtomicBoolean();
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isFalse();
      return marketBundle;
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());
    FullFillCoordinator staleOnceCoordinator = spy(new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC)));
    org.mockito.Mockito.lenient()
        .doThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "lock wait expired bundle"))
        .doNothing()
        .when(staleOnceCoordinator)
        .requireFresh(any(FullFillResult.class));
    OrderService retryingService = newService(staleOnceCoordinator);
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "stale-once");

    OrderResponse response = retryingService.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(accountRepository, times(2)).findByIdAndUserIdForUpdate(accountId, userId);
    verify(accountSymbolSettingRepository, times(2))
        .findByAccountIdAndSymbolForUpdate(accountId, SYMBOL);
    verify(accountRepository).save(account);
    verify(orderRepository).save(any(OrderEntity.class));
  }

  @Test
  void resolverStaleFirstRetriesBeforeOpeningATransaction() {
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "provider refresh"))
        .thenReturn(marketBundle);
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "resolver-stale-once");

    OrderResponse response = service.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(transactionExecutor).execute(any());
    verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    verify(orderRepository).save(any(OrderEntity.class));
  }

  @Test
  void freshAccountWideCrossLossRejectsDespiteStalePersistedFreeMargin() {
    account.setBalance(new BigDecimal("20"));
    account.setEquity(new BigDecimal("20"));
    account.setUsedMargin(new BigDecimal("10"));
    account.setFreeMargin(new BigDecimal("100"));
    PositionEntity ethLoss = crossPosition("ETHUSDT-PERP", "100", "10", 0L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(ethLoss));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(ethLoss));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(linearSymbol("ETHUSDT-PERP")));
    when(marketBundleResolver.resolvePerp(eq("ETHUSDT-PERP"), any()))
        .thenReturn(bundle("ETHUSDT-PERP", "49", "51", "50"));
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "fresh-cross-loss");

    Object result = capture(() -> service.createOrder(principal(), request));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo(ErrorCode.INSUFFICIENT_MARGIN),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(positionRepository, never()).save(any()),
        () -> verify(orderRepository, never()).save(any()));
  }

  @Test
  void freshAccountWideCrossProfitFundsTargetOrderAndProvidersStayOutsideTransaction() {
    account.setBalance(new BigDecimal("20"));
    account.setEquity(new BigDecimal("20"));
    account.setUsedMargin(new BigDecimal("10"));
    account.setFreeMargin(BigDecimal.ZERO);
    PositionEntity ethProfit = crossPosition("ETHUSDT-PERP", "100", "10", 3L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(ethProfit));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(ethProfit));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(linearSymbol("ETHUSDT-PERP")));
    AtomicBoolean insideTransaction = new AtomicBoolean();
    when(marketBundleResolver.resolvePerp(eq("ETHUSDT-PERP"), any())).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isFalse();
      return bundle("ETHUSDT-PERP", "199", "201", "200");
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "fresh-cross-profit");

    OrderResponse response = service.createOrder(principal(), request);

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(ethProfit.getMarkPrice()).isEqualByComparingTo("200.00000000"),
        () -> assertThat(ethProfit.getFloatingPnl()).isEqualByComparingTo("100.00000000"),
        () -> assertThat(ethProfit.getVersion()).isEqualTo(4L));
    verify(marketBundleResolver).resolvePerp(eq("ETHUSDT-PERP"), any());
    verify(positionRepository).save(ethProfit);
  }

  @Test
  void accountRiskFingerprintDriftRetriesOutsideTransactionAndWritesOnlyFreshAttempt() {
    PositionEntity preparedV0 = crossPosition("ETHUSDT-PERP", "100", "10", 0L);
    PositionEntity lockedV1 = crossPosition("ETHUSDT-PERP", "100", "10", 1L);
    lockedV1.setId(preparedV0.getId());
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(preparedV0), List.of(lockedV1));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(lockedV1), List.of(lockedV1));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(linearSymbol("ETHUSDT-PERP")));
    when(marketBundleResolver.resolvePerp(eq("ETHUSDT-PERP"), any()))
        .thenReturn(bundle("ETHUSDT-PERP", "109", "111", "110"));
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "fingerprint-retry");

    OrderResponse response = service.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(marketBundleResolver, times(2)).resolvePerp(eq("ETHUSDT-PERP"), any());
    verify(transactionExecutor, times(2)).execute(any());
    verify(accountRepository, times(2)).findByIdAndUserIdForUpdate(accountId, userId);
    verify(positionRepository).save(lockedV1);
    verify(orderRepository).save(any(OrderEntity.class));
  }

  @Test
  void attachedProtectionsAreCreatedBeforeImmediateParentFill() {
    CreateOrderRequest protectedRequest = protectedRequest("protected-market");

    OrderResponse response = service.createOrder(principal(), protectedRequest);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    InOrder mutationOrder = inOrder(orderRepository, protectionOrderService, orderFillService);
    mutationOrder.verify(orderRepository).save(any(OrderEntity.class));
    mutationOrder.verify(protectionOrderService).createAttachedLocked(
        any(OrderEntity.class),
        eq(protectedRequest.attachedProtections()),
        eq(new BigDecimal("100")));
    mutationOrder.verify(orderFillService).fillPerpetual(
        any(OrderEntity.class),
        eq(account),
        any(FullFillResult.class),
        eq(new BigDecimal("100")),
        eq(10),
        eq("Perpetual position margin held"));
  }

  @Test
  void stopContractIsRejectedBeforeProviderOrWrites() {
    CreateOrderRequest stopRequest = request(
        OrderSide.BUY,
        OrderType.STOP,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        "unsupported-stop");

    Object stopResult = capture(() -> service.createOrder(principal(), stopRequest));

    assertAll(
        () -> assertThat(businessCode(stopResult)).isEqualTo("INVALID_PERPETUAL_ORDER_TYPE"),
        () -> verify(marketBundleResolver, never()).resolvePerp(anyString(), any()),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(orderRepository, never()).save(any()));
  }

  @Test
  void isolatedPureCloseUsesInternalHoldAndCancelReplayReleasesUsedOnlyOnce() {
    setting.setMarginMode(MarginMode.ISOLATED);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(BigDecimal.ZERO);
    PositionEntity position = isolatedLong("1", "10");
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(position));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(position));
    CreateOrderRequest request = request(
        OrderSide.SELL,
        OrderType.STOP_MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        "110",
        PositionSide.BOTH,
        10,
        MarginMode.ISOLATED,
        "isolated-internal-close",
        true);

    OrderResponse created = service.createOrder(principal(), request);

    org.mockito.ArgumentCaptor<OrderEntity> persisted =
        org.mockito.ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(persisted.capture());
    OrderEntity order = persisted.getValue();
    assertAll(
        () -> assertThat(created.status()).isEqualTo(OrderStatus.PENDING.name()),
        () -> assertThat(order.getParentPositionId()).isEqualTo(position.getId()),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo("1.05939505"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("11.05939505"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.00000000"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("10"),
        () -> assertThat(position.getVersion()).isZero());
    InOrder createWrites = inOrder(accountRepository, orderRepository, ledgerService);
    createWrites.verify(accountRepository).save(account);
    createWrites.verify(orderRepository).save(order);
    createWrites.verify(ledgerService).recordOrderHold(
        account,
        new BigDecimal("1.05939505"),
        order.getId(),
        "Perpetual order margin reserved");

    when(orderRepository.findByUserIdAndId(userId, order.getId()))
        .thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPending(order)).thenReturn(1);

    OrderResponse canceled = service.cancelOrder(principal(), order.getId());
    Object replay = capture(() -> service.cancelOrder(principal(), order.getId()));

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(businessCode(replay)).isEqualTo("ORDER_NOT_CANCELABLE"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.00000000"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("10"));
    verify(accountRepository, times(2)).save(account);
    verify(positionRepository).save(position);
    verify(orderRepository).cancelPending(order);
    verify(ledgerService).recordOrderRelease(
        account,
        new BigDecimal("1.05939505"),
        order.getId(),
        "Pending Perpetual order canceled");
  }

  @Test
  void cancelPendingOpeningParentExpiresAttachedProtectionsInsideCancelMutation() {
    OrderEntity parent = new OrderEntity();
    parent.setId(UUID.randomUUID());
    parent.setUserId(userId);
    parent.setAccountId(accountId);
    parent.setSymbol(SYMBOL);
    parent.setProductType(ProductType.LINEAR_PERP);
    parent.setPositionMode(PositionMode.ONE_WAY);
    parent.setPositionSide(PositionSide.BOTH);
    parent.setMarginMode(MarginMode.CROSS);
    parent.setSide(OrderSide.BUY);
    parent.setOrderType(OrderType.LIMIT);
    parent.setStatus(OrderStatus.PENDING);
    parent.setQuantity(BigDecimal.ONE);
    parent.setOriginalQuantity(BigDecimal.ONE);
    parent.setBaseQuantity(BigDecimal.ONE);
    parent.setLots(BigDecimal.ONE);
    parent.setRemainingQuantity(BigDecimal.ONE);
    parent.setHoldAmount(BigDecimal.ZERO);
    parent.setVersion(0L);
    when(orderRepository.findByUserIdAndId(userId, parent.getId()))
        .thenReturn(Optional.of(parent));
    when(orderRepository.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
    when(orderRepository.cancelPending(parent)).thenReturn(1);

    OrderResponse canceled = service.cancelOrder(principal(), parent.getId());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name());
    InOrder lifecycle = inOrder(orderRepository, protectionOrderService, orderEventService);
    lifecycle.verify(orderRepository).cancelPending(parent);
    lifecycle.verify(protectionOrderService)
        .expireAttachedForCanceledParentLocked(parent, account);
    lifecycle.verify(orderEventService).record(
        parent.getId(),
        "ORDER_CANCELED",
        OrderStatus.PENDING,
        OrderStatus.CANCELED,
        null,
        "Pending order canceled");
  }

  @Test
  void aggregateIsolatedCloseQuantityAndHoldsFailBeforeAnyWrite() {
    setting.setMarginMode(MarginMode.ISOLATED);
    account.setUsedMargin(new BigDecimal("10"));
    account.setFreeMargin(BigDecimal.ZERO);
    PositionEntity position = isolatedLong("1", "10");
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(position));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(position));
    OrderEntity existing = activeInternalClose(position.getId(), "0.6", "0.2");
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(existing));
    CreateOrderRequest quantityOverflow = request(
        OrderSide.SELL,
        OrderType.STOP_MARKET,
        QuantityUnit.BASE,
        "0.5",
        null,
        "110",
        PositionSide.BOTH,
        10,
        MarginMode.ISOLATED,
        "aggregate-quantity",
        true);

    Object quantityResult = capture(() -> service.createOrder(principal(), quantityOverflow));

    assertThat(businessCode(quantityResult)).isEqualTo(ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION);
    existing.setRemainingQuantity(new BigDecimal("0.4"));
    // 9.34406049 existing + 0.10593951 new == the exact 9.45 risk buffer boundary.
    existing.setHoldAmount(new BigDecimal("9.34406049"));
    CreateOrderRequest holdOverflow = request(
        OrderSide.SELL,
        OrderType.STOP_MARKET,
        QuantityUnit.BASE,
        "0.1",
        null,
        "110",
        PositionSide.BOTH,
        10,
        MarginMode.ISOLATED,
        "aggregate-hold",
        true);

    Object holdResult = capture(() -> service.createOrder(principal(), holdOverflow));

    assertAll(
        () -> assertThat(businessCode(holdResult)).isEqualTo(ErrorCode.MARGIN_REDUCTION_UNSAFE),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(positionRepository, never()).save(any()),
        () -> verify(orderRepository, never()).save(any()),
        () -> verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString()));
  }

  @Test
  void perpetualModifyFailsClosedWithoutLocksProviderOrWrites() {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setStatus(OrderStatus.PENDING);
    when(orderRepository.findByUserIdAndId(userId, order.getId()))
        .thenReturn(Optional.of(order));
    clearInvocations(
        accountRepository,
        accountSymbolSettingRepository,
        positionRepository,
        marketBundleResolver,
        transactionExecutor,
        orderRepository);

    Object result = capture(() -> service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(new BigDecimal("2"), new BigDecimal("99"), null, null)));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo("ORDER_NOT_MODIFIABLE"),
        () -> verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any()),
        () -> verify(accountSymbolSettingRepository, never())
            .findByAccountIdAndSymbolForUpdate(any(), anyString()),
        () -> verify(positionRepository, never())
            .findOpenLinearPerpBySymbolForUpdate(any(), anyString()),
        () -> verify(marketBundleResolver, never()).resolvePerp(anyString(), any()),
        () -> verify(transactionExecutor, never()).execute(any()),
        () -> verify(orderRepository, never()).save(any()));
  }

  @Test
  void publicLeverageIsNotFedBackIntoAuthorityRuleValidation() {
    CreateOrderRequest request = request(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        PositionSide.BOTH,
        999,
        MarginMode.ISOLATED,
        "public-leverage-audit");

    service.createOrder(principal(), request);

    org.mockito.ArgumentCaptor<CreateOrderRequest> validatedRequest =
        org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);
    verify(instrumentRulesEngine).validateCanonicalOrder(
        validatedRequest.capture(),
        any(SymbolEntity.class),
        eq(new BigDecimal("1.0000")),
        eq(new BigDecimal("100")));
    assertThat(validatedRequest.getValue().leverage()).isNull();
  }

  private UserPrincipal principal() {
    return new UserPrincipal(userId, "trader@example.com", "TRADER");
  }

  private CreateOrderRequest protectedRequest(String key) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        key,
        key,
        new BigDecimal("1.0000"),
        null,
        10,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        null,
        null,
        false,
        List.of(new CreateOrderRequest.AttachedProtectionRequest(
            ProtectionType.STOP_LOSS,
            new BigDecimal("90"),
            TriggerPriceType.MARK_PRICE,
            TriggerExecutionType.MARKET,
            null)));
  }

  private CreateOrderRequest request(
      OrderSide side,
      OrderType type,
      QuantityUnit unit,
      String quantity,
      String price,
      String trigger,
      PositionSide positionSide,
      int publicLeverage,
      MarginMode publicMarginMode,
      String key
  ) {
    return request(
        side,
        type,
        unit,
        quantity,
        price,
        trigger,
        positionSide,
        publicLeverage,
        publicMarginMode,
        key,
        false);
  }

  private CreateOrderRequest request(
      OrderSide side,
      OrderType type,
      QuantityUnit unit,
      String quantity,
      String price,
      String trigger,
      PositionSide positionSide,
      int publicLeverage,
      MarginMode publicMarginMode,
      String key,
      boolean reduceOnly
  ) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        side,
        type,
        null,
        null,
        null,
        null,
        key,
        key,
        new BigDecimal(quantity),
        price == null ? null : new BigDecimal(price),
        publicLeverage,
        positionSide,
        unit,
        publicMarginMode,
        trigger == null ? null : new BigDecimal(trigger),
        null,
        reduceOnly,
        List.of());
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000"));
    account.setEquity(new BigDecimal("50000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("50000"));
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }

  private static AccountSymbolSettingEntity setting(
      UUID accountId,
      int leverage,
      MarginMode marginMode
  ) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(SYMBOL);
    setting.setLeverage(leverage);
    setting.setMarginMode(marginMode);
    setting.setQuantityUnit(QuantityUnit.CONTRACTS);
    setting.setVersion(1L);
    return setting;
  }

  private PositionEntity isolatedLong(String quantity, String marginHeld) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(SYMBOL);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.ISOLATED);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal(quantity));
    position.setOpenPrice(new BigDecimal("100"));
    position.setCurrentPrice(new BigDecimal("100"));
    position.setMarkPrice(new BigDecimal("100"));
    position.setNotional(new BigDecimal(quantity).multiply(new BigDecimal("100")));
    position.setInitialMargin(new BigDecimal(marginHeld));
    position.setMaintenanceMargin(new BigDecimal(quantity).multiply(new BigDecimal("0.5")));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(com.fxplatform.trading.enums.PositionStatus.OPEN);
    position.setVersion(0L);
    return position;
  }

  private OrderEntity activeInternalClose(UUID parentPositionId, String remaining, String hold) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.ISOLATED);
    order.setSide(OrderSide.SELL);
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING);
    order.setBaseQuantity(new BigDecimal(remaining));
    order.setRemainingQuantity(new BigDecimal(remaining));
    order.setHoldAmount(new BigDecimal(hold));
    order.setParentPositionId(parentPositionId);
    return order;
  }

  private PositionEntity crossPosition(
      String symbol,
      String entry,
      String marginHeld,
      long version
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.CROSS);
    position.setSide(OrderSide.BUY);
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal(entry));
    position.setCurrentPrice(new BigDecimal(entry));
    position.setMarkPrice(new BigDecimal(entry));
    position.setNotional(new BigDecimal(entry));
    position.setInitialMargin(new BigDecimal(marginHeld));
    position.setMaintenanceMargin(new BigDecimal("0.5"));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(com.fxplatform.trading.enums.PositionStatus.OPEN);
    position.setVersion(version);
    return position;
  }

  private static SymbolEntity symbol() {
    return linearSymbol(SYMBOL);
  }

  private static SymbolEntity linearSymbol(String code) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(code);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setBaseCurrency(code.startsWith("ETH") ? "ETH" : "BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
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
        new BigDecimal("0.1"),
        new BigDecimal("0.0001"),
        new BigDecimal("0.0001"),
        new BigDecimal("1000"),
        new BigDecimal("5"),
        null,
        new BigDecimal("0.0001"),
        new BigDecimal("1000"),
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

  private static PerpetualMarketBundle bundle() {
    return bundle(SYMBOL, "99", "101", "100");
  }

  private static PerpetualMarketBundle bundle(
      String symbol,
      String bid,
      String ask,
      String mark
  ) {
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal(bid),
        new BigDecimal(ask),
        new BigDecimal(mark),
        new BigDecimal(mark),
        new BigDecimal(mark),
        null,
        List.of(),
        List.of(),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static Object capture(Supplier<?> action) {
    try {
      return action.get();
    } catch (Throwable throwable) {
      return throwable;
    }
  }

  private static String businessCode(Object result) {
    return result instanceof BusinessException exception ? exception.getCode() : null;
  }
}
