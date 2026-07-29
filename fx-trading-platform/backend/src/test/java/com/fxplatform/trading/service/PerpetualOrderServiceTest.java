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
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
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
import com.fxplatform.trading.enums.TimeInForce;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
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
  void stopLimitPerpetualCreationAlwaysPersistsPendingActivationWithLimitHold() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.STOP_LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        "100",
        "perp-stop-limit",
        TimeInForce.GTC,
        false);

    OrderResponse response = service.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());
    assertThat(response.orderType()).isEqualTo(OrderType.STOP_LIMIT.name());
    assertThat(response.price()).isEqualByComparingTo("98");
    assertThat(response.triggerPrice()).isEqualByComparingTo("100");
    assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.LIMIT);
    assertThat(response.holdAmount()).isEqualByComparingTo("9.84900000");
    assertThat(response.holdCurrency()).isEqualTo("USDT");
    verify(executionAdapter, never()).execute(any(CreateOrderRequest.class));
  }

  @Test
  void stopLimitPerpetualRejectsNonGtcContractBeforeProviderOrMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.STOP_LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        "100",
        "perp-stop-limit-ioc",
        TimeInForce.IOC,
        false);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("STOP_LIMIT_CONTRACT_INVALID"));

    verify(marketBundleResolver, never()).resolvePerp(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void stopMarketPerpetualRejectsNonGtcBeforeProviderOrMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.STOP_MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        "110",
        "perp-stop-market-ioc",
        TimeInForce.IOC,
        false);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolvePerp(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void stopMarketPerpetualRejectsPostOnlyBeforeProviderOrMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.STOP_MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        "110",
        "perp-stop-market-post-only",
        TimeInForce.GTC,
        true);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolvePerp(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void marketPerpetualRejectsPostOnlyBeforeProviderOrMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        "perp-market-post-only",
        TimeInForce.GTC,
        true);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolvePerp(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void limitPerpetualRejectsPostOnlyWithIocBeforeProviderOrMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        "perp-limit-post-only-ioc",
        TimeInForce.IOC,
        true);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));

    verify(marketBundleResolver, never()).resolvePerp(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
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
  void perpetualStopLimitCanCancelBeforeActivationAndReleaseMarginExactlyOnce() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    order.setOrderType(OrderType.STOP_LIMIT);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.USER);
    order.setTriggerPrice(new BigDecimal("110"));
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPendingActivation(order)).thenReturn(1);

    OrderResponse canceled = service.cancelOrder(principal(), order.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("50000.00000000"));
    verify(orderRepository).cancelPendingActivation(order);
    verify(orderRepository, never()).cancelPending(any());
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("10.00000000"), order.getId(),
        "Pending Perpetual order canceled");
    verify(orderEventService).record(
        order.getId(), "ORDER_CANCELED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.CANCELED, null, "Pending order canceled");
  }

  @Test
  void userIsolatedCloseStopLimitCanCancelBeforeActivationWithoutCreditingFreeMargin() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING_ACTIVATION);
    order.setMarginMode(MarginMode.ISOLATED);
    order.setReduceOnly(true);
    order.setParentPositionId(UUID.randomUUID());
    setting.setMarginMode(MarginMode.ISOLATED);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPendingActivation(order)).thenReturn(1);

    OrderResponse canceled = service.cancelOrder(principal(), order.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.00000000"));
    verify(orderRepository).cancelPendingActivation(order);
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("10.00000000"), order.getId(),
        "Pending Perpetual order canceled");
    verify(orderEventService).record(
        order.getId(), "ORDER_CANCELED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.CANCELED, null, "Pending order canceled");
  }

  @Test
  void activatedPerpetualStopLimitCancelReplayReleasesMarginOnlyOnce() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPending(order)).thenReturn(1);

    OrderResponse canceled = service.cancelOrder(principal(), order.getId());
    Object replay = capture(() -> service.cancelOrder(principal(), order.getId()));

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(businessCode(replay)).isEqualTo("ORDER_NOT_CANCELABLE"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("50000.00000000"));
    verify(orderRepository, times(1)).cancelPending(order);
    verify(ledgerService, times(1)).recordOrderRelease(
        account, new BigDecimal("10.00000000"), order.getId(),
        "Pending Perpetual order canceled");
  }

  @Test
  void perpetualCancelLosesToActivationCasWithoutReleasingMarginOrChangingCandidate() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING_ACTIVATION);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.cancelPendingActivation(order)).thenReturn(0);

    Object result = capture(() -> service.cancelOrder(principal(), order.getId()));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo("ORDER_NOT_CANCELABLE"),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getCanceledAt()).isNull(),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.00000000"));
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString());
    verify(orderEventService, never()).record(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void restingPostOnlyPerpetualModifyToMarketableRejectsBeforeFinancialMutation() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(true);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    clearInvocations(accountRepository, positionRepository, ledgerService, orderRepository);

    Object result = capture(() -> service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("101"), null, null)));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo("POST_ONLY_WOULD_TAKE"),
        () -> verify(transactionExecutor, never()).execute(any()),
        () -> verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any()),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(positionRepository, never()).save(any()),
        () -> verify(orderRepository, never()).save(any()),
        () -> verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString()),
        () -> verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString()));
  }

  @Test
  void perpetualStopLimitModifyBeforeActivationChangesLimitAndTriggerWithoutActivating() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING_ACTIVATION);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(order));

    OrderResponse result = service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(
            null, new BigDecimal("80"), new BigDecimal("90"), null, null));

    assertAll(
        () -> assertThat(result.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name()),
        () -> assertThat(result.orderType()).isEqualTo(OrderType.STOP_LIMIT.name()),
        () -> assertThat(result.price()).isEqualByComparingTo("80"),
        () -> assertThat(result.triggerPrice()).isEqualByComparingTo("90"),
        () -> assertThat(result.timeInForce()).isEqualTo(TimeInForce.GTC));
    verify(orderEventService).record(
        order.getId(), "ORDER_MODIFIED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION, null, "Pending Perpetual order modified");
  }

  @Test
  void activatedPerpetualStopLimitRejectsAnyExplicitTriggerChangeBeforeProvider() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING);
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    clearInvocations(marketBundleResolver, transactionExecutor, orderRepository);

    Object result = capture(() -> service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("80"), new BigDecimal("110"), null, null)));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo("ORDER_NOT_MODIFIABLE"),
        () -> verify(marketBundleResolver, never()).resolvePerp(anyString(), any()),
        () -> verify(transactionExecutor, never()).execute(any()),
        () -> verify(orderRepository, never()).save(any()));
  }

  @Test
  void activatedPerpetualStopLimitCanModifyQuantityAndLimitWhileKeepingTrigger() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(order));

    OrderResponse result = service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("80"), null, null));

    assertAll(
        () -> assertThat(result.status()).isEqualTo(OrderStatus.PENDING.name()),
        () -> assertThat(result.orderType()).isEqualTo(OrderType.STOP_LIMIT.name()),
        () -> assertThat(result.price()).isEqualByComparingTo("80"),
        () -> assertThat(result.triggerPrice()).isEqualByComparingTo("110"));
  }

  @Test
  void activatedPerpetualStopLimitModifyToMarketableFillsAsImmediateCanonicalLimit() {
    OrderEntity order = perpetualStopLimit(OrderStatus.PENDING);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(order));

    OrderResponse result = service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("101"), null, null));

    assertAll(
        () -> assertThat(result.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(result.orderType()).isEqualTo(OrderType.STOP_LIMIT.name()),
        () -> assertThat(result.price()).isEqualByComparingTo("101"),
        () -> assertThat(result.triggerPrice()).isEqualByComparingTo("110"),
        () -> assertThat(result.liquidityRole()).isEqualTo(LiquidityRole.TAKER));
    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(request.capture(), eq(ExecutableMarketSnapshot.from(marketBundle)));
    assertAll(
        () -> assertThat(request.getValue().executionPath())
            .isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT),
        () -> assertThat(request.getValue().executionIntent().orderType())
            .isEqualTo(OrderType.LIMIT),
        () -> assertThat(request.getValue().executionIntent().triggerPrice()).isNull(),
        () -> assertThat(request.getValue().executionIntent().triggerPriceType()).isNull(),
        () -> assertThat(request.getValue().limitPrice()).isEqualByComparingTo("101"));
    verify(orderFillService).fillPerpetual(
        eq(order),
        eq(account),
        any(FullFillResult.class),
        eq(new BigDecimal("100")),
        eq(10),
        eq("Perpetual order fill after modification"));
    verify(orderEventService).record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.PENDING,
        OrderStatus.FILLED,
        null,
        "Modified Perpetual order filled");
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
  void marketablePostOnlyPerpetualRejectsBeforePersistenceOrFinancialMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "101",
        null,
        "perp-post-only-take",
        TimeInForce.GTC,
        true);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));

    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void restingPostOnlyPerpetualPersistsPendingWithNormalMarginHold() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        "perp-post-only-rest",
        TimeInForce.GTC,
        true);

    OrderResponse response = service.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.postOnly()).isTrue();
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(response.holdAmount()).isEqualByComparingTo("9.84900000");
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void trailingStopRoutesToOnePositionBoundZeroHoldProtectionCarrier() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));
    CreateOrderRequest request = trailingRequest(
        "perp-trailing-create", PositionSide.BOTH, "110", "5", null);

    OrderResponse response = service.createOrder(principal(), request);

    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(saved.capture());
    OrderEntity carrier = saved.getValue();
    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name()),
        () -> assertThat(carrier.getOrderType()).isEqualTo(OrderType.TRAILING_STOP_MARKET),
        () -> assertThat(carrier.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION),
        () -> assertThat(carrier.getOrderOrigin())
            .isEqualTo(com.fxplatform.trading.enums.OrderOrigin.PROTECTIVE),
        () -> assertThat(carrier.getProtectionType()).isEqualTo(ProtectionType.STOP_LOSS),
        () -> assertThat(carrier.getTriggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE),
        () -> assertThat(carrier.getTriggerExecutionType())
            .isEqualTo(TriggerExecutionType.MARKET),
        () -> assertThat(carrier.getParentPositionId()).isEqualTo(target.getId()),
        () -> assertThat(carrier.getPositionMode()).isEqualTo(PositionMode.ONE_WAY),
        () -> assertThat(carrier.getPositionSide()).isEqualTo(PositionSide.BOTH),
        () -> assertThat(carrier.getSide()).isEqualTo(OrderSide.SELL),
        () -> assertThat(carrier.getReduceOnly()).isTrue(),
        () -> assertThat(carrier.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(carrier.getTrailingExtreme()).isNull(),
        () -> assertThat(carrier.getTriggerPrice()).isNull(),
        () -> assertThat(carrier.getClientOrderId()).isEqualTo("perp-trailing-create"),
        () -> assertThat(carrier.getIdempotencyKey()).isEqualTo("perp-trailing-create"),
        () -> assertThat(carrier.getRequestFingerprint()).isEqualTo(
            OrderRequestFingerprint.calculate(request)));
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void hedgeTrailingStopBindsTheExactShortSlotAndRejectsTheWrongSlot() {
    account.setPositionMode(PositionMode.HEDGE);
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    target.setPositionMode(PositionMode.HEDGE);
    target.setPositionSide(PositionSide.SHORT);
    target.setSide(OrderSide.SELL);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));
    CreateOrderRequest valid = trailingRequest(
        "perp-trailing-hedge", SYMBOL, OrderSide.BUY, PositionSide.SHORT,
        null, "5", null, true, TimeInForce.GTC, false, null, null, List.of());
    CreateOrderRequest wrongSlot = trailingRequest(
        "perp-trailing-hedge-wrong", SYMBOL, OrderSide.BUY, PositionSide.LONG,
        null, "5", null, true, TimeInForce.GTC, false, null, null, List.of());

    OrderResponse response = service.createOrder(principal(), valid);

    assertAll(
        () -> assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE),
        () -> assertThat(response.positionSide()).isEqualTo(PositionSide.SHORT),
        () -> assertThat(response.side()).isEqualTo(OrderSide.BUY.name()));
    assertThatThrownBy(() -> service.createOrder(principal(), wrongSlot))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.INVALID_POSITION_SIDE));
    verify(orderRepository, times(1)).save(any(OrderEntity.class));
  }

  @Test
  void trailingStopInvalidPublicFieldMatrixFailsBeforeMutation() {
    List<CreateOrderRequest> invalid = List.of(
        trailingRequest(
            "trailing-opening", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", null, false, TimeInForce.GTC, false, null, null, List.of()),
        trailingRequest(
            "trailing-ioc", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", null, true, TimeInForce.IOC, false, null, null, List.of()),
        trailingRequest(
            "trailing-post-only", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", null, true, TimeInForce.GTC, true, null, null, List.of()),
        trailingRequest(
            "trailing-price", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", null, true, TimeInForce.GTC, false, "99", null, List.of()),
        trailingRequest(
            "trailing-trigger", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", null, true, TimeInForce.GTC, false, null, "95", List.of()),
        trailingRequest(
            "trailing-attached", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", null, true, TimeInForce.GTC, false, null, null,
            List.of(new CreateOrderRequest.AttachedProtectionRequest(
                ProtectionType.TAKE_PROFIT,
                new BigDecimal("120"),
                TriggerPriceType.MARK_PRICE,
                TriggerExecutionType.MARKET,
                null))),
        trailingRequest(
            "trailing-both-callbacks", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5", "0.05", true, TimeInForce.GTC, false, null, null, List.of()),
        trailingRequest(
            "trailing-invalid-rate", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, null, "1", true, TimeInForce.GTC, false, null, null, List.of()));

    for (CreateOrderRequest request : invalid) {
      assertThatThrownBy(() -> service.createOrder(principal(), request))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));
    }

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
  }

  @Test
  void trailingStopRejectsSpotBeforeProviderOrMutation() {
    CreateOrderRequest spot = trailingRequest(
        "spot-trailing", "BTCUSDT", OrderSide.SELL, PositionSide.BOTH,
        null, "5", null, true, TimeInForce.GTC, false, null, null, List.of());

    assertThatThrownBy(() -> service.createOrder(principal(), spot))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_ALLOWED));

    verify(marketBundleResolver, never()).resolveSpot(anyString(), any());
    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void trailingStopActivationAndAbsoluteDeltaMustMatchLockedTickSize() {
    List<CreateOrderRequest> mismatches = List.of(
        trailingRequest(
            "trailing-activation-tick", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            "110.05", "5.0", null, true, TimeInForce.GTC, false,
            null, null, List.of()),
        trailingRequest(
            "trailing-delta-tick", SYMBOL, OrderSide.SELL, PositionSide.BOTH,
            null, "5.05", null, true, TimeInForce.GTC, false,
            null, null, List.of()));

    for (CreateOrderRequest request : mismatches) {
      assertThatThrownBy(() -> service.createOrder(principal(), request))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo("PRICE_TICK_MISMATCH"));
    }

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void trailingStopAcceptsDerivedThresholdBeyondLegacyNumeric24Range() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));
    CreateOrderRequest request = trailingRequest(
        "trailing-derived-threshold-expanded",
        PositionSide.BOTH,
        null,
        "100000000000050.0",
        null);

    OrderResponse response = service.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name());
    verify(transactionExecutor).execute(any());
    verify(orderRepository).save(any(OrderEntity.class));
  }

  @Test
  void trailingStopFailsClosedWhenPositionTargetIsAmbiguous() {
    PositionEntity first = crossPosition(SYMBOL, "100", "10", 0L);
    PositionEntity duplicate = crossPosition(SYMBOL, "101", "10", 0L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(first, duplicate));

    assertThatThrownBy(() -> service.createOrder(
        principal(),
        trailingRequest("perp-trailing-ambiguous", PositionSide.BOTH, null, "5", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.INVALID_POSITION_SIDE));

    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
  }

  @Test
  void trailingStopLocksActiveProtectionsAndRejectsAggregateStopLossOverClose() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    OrderEntity existing = activeInternalClose(target.getId(), "0.7500", "0");
    existing.setStatus(OrderStatus.PARTIALLY_FILLED);
    existing.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.PROTECTIVE);
    existing.setProtectionType(ProtectionType.STOP_LOSS);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(existing));

    assertThatThrownBy(() -> service.createOrder(
        principal(),
        trailingRequest("perp-trailing-over-protected", PositionSide.BOTH, null, "5", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PROTECTION_QUANTITY_EXCEEDED));

    verify(orderRepository).findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL);
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
  }

  @Test
  void trailingStopRejectsTheEleventhActiveBoundProtection() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    List<OrderEntity> active = java.util.stream.IntStream.range(0, 10)
        .mapToObj(index -> {
          OrderEntity protection = activeInternalClose(target.getId(), "0.0100", "0");
          protection.setStatus(OrderStatus.PENDING_ACTIVATION);
          protection.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.PROTECTIVE);
          protection.setProtectionType(ProtectionType.TAKE_PROFIT);
          return protection;
        })
        .toList();
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(active);

    assertThatThrownBy(() -> service.createOrder(
        principal(),
        trailingRequest("perp-trailing-protection-limit", PositionSide.BOTH, null, "5", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PROTECTION_LIMIT_EXCEEDED));

    verify(orderRepository, never()).save(any());
  }

  @Test
  void trailingStopReplayIsStableAndChangedCallbackConflicts() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));
    CreateOrderRequest original = trailingRequest(
        "perp-trailing-replay", PositionSide.BOTH, null, null, "0.05");
    CreateOrderRequest changed = trailingRequest(
        "perp-trailing-replay", PositionSide.BOTH, null, null, "0.06");

    OrderResponse created = service.createOrder(principal(), original);
    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(saved.capture());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "perp-trailing-replay"))
        .thenReturn(Optional.of(saved.getValue()));

    OrderResponse replay = service.createOrder(principal(), original);

    assertThat(replay.id()).isEqualTo(created.id());
    assertThatThrownBy(() -> service.createOrder(principal(), changed))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));
    verify(orderRepository, times(1)).save(any(OrderEntity.class));
    verify(transactionExecutor, times(1)).execute(any());
    verify(marketBundleResolver, times(1)).resolvePerp(eq(SYMBOL), any());
  }

  @Test
  void trailingStopRechecksReplayAfterAccountLockBeforeProtectionBudget() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    CreateOrderRequest request = trailingRequest(
        "perp-trailing-locked-replay", PositionSide.BOTH, null, "5", null);
    OrderEntity existing = activeInternalClose(target.getId(), "0.7500", "0");
    existing.setMarginMode(MarginMode.CROSS);
    existing.setOrderType(OrderType.TRAILING_STOP_MARKET);
    existing.setStatus(OrderStatus.PENDING_ACTIVATION);
    existing.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.PROTECTIVE);
    existing.setProtectionType(ProtectionType.STOP_LOSS);
    existing.setPositionMode(PositionMode.ONE_WAY);
    existing.setPositionSide(PositionSide.BOTH);
    existing.setLots(new BigDecimal("0.5000"));
    existing.setQuantity(new BigDecimal("0.5000"));
    existing.setOriginalQuantity(new BigDecimal("0.5000"));
    existing.setBaseQuantity(new BigDecimal("0.5000"));
    existing.setQuantityUnit(QuantityUnit.BASE);
    existing.setLeverage(10);
    existing.setReduceOnly(true);
    existing.setTimeInForce(TimeInForce.GTC);
    existing.setPostOnly(false);
    existing.setFilledQuantity(BigDecimal.ZERO);
    existing.setFee(BigDecimal.ZERO);
    existing.setSlippage(BigDecimal.ZERO);
    existing.setClientOrderId("perp-trailing-locked-replay");
    existing.setIdempotencyKey("perp-trailing-locked-replay");
    existing.setRequestFingerprint(OrderRequestFingerprint.calculate(request));
    when(orderRepository.findByUserIdAndIdempotencyKey(
        userId, "perp-trailing-locked-replay"))
        .thenReturn(Optional.empty(), Optional.of(existing));

    OrderResponse replay = service.createOrder(principal(), request);

    assertThat(replay.id()).isEqualTo(existing.getId());
    verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    verify(accountSymbolSettingRepository, never())
        .findByAccountIdAndSymbolForUpdate(accountId, SYMBOL);
    verify(positionRepository, never()).findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL);
    verify(orderRepository, never()).findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL);
    verify(orderRepository, never()).save(any(OrderEntity.class));
  }

  @Test
  void trailingStopRejectsPositionWithNullSideInsteadOfTreatingItAsOpposite() {
    PositionEntity target = crossPosition(SYMBOL, "100", "10", 0L);
    target.setSide(null);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(target));

    assertThatThrownBy(() -> service.createOrder(
        principal(),
        trailingRequest("perp-trailing-null-position-side", PositionSide.BOTH, null, "5", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.INVALID_POSITION_SIDE));

    verify(orderRepository, never()).save(any(OrderEntity.class));
  }

  @Test
  void iocNonMarketablePerpetualPersistsOnlyTerminalCancelledLifecycle() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        "perp-ioc-cancel",
        TimeInForce.IOC,
        false);
    CreateOrderRequest conflict = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "97",
        null,
        "perp-ioc-cancel",
        TimeInForce.IOC,
        false);

    OrderResponse response = service.createOrder(principal(), request);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name());
    assertThat(response.filledQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.IOC);
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("50000");
    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(saved.capture());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "perp-ioc-cancel"))
        .thenReturn(Optional.of(saved.getValue()));
    OrderResponse replay = service.createOrder(principal(), request);
    assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(replay.id()).isEqualTo(response.id());
    assertThat(replay.status()).isEqualTo(OrderStatus.CANCELLED.name());
    assertThatThrownBy(() -> service.createOrder(principal(), conflict))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));
    verify(orderEventService).record(
        eq(saved.getValue().getId()), eq("ORDER_CANCELED"), eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.CANCELLED), eq(null), anyString());
    verify(orderRepository, times(1)).save(any(OrderEntity.class));
    verify(transactionExecutor, times(1)).execute(any());
    verify(marketBundleResolver, times(1)).resolvePerp(eq(SYMBOL), any());
    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void disabledExecutionGuardRejectsBeforePerpetualProviderTransactionOrFinancialMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        "perp-disabled",
        TimeInForce.IOC,
        false);
    doThrow(new BusinessException(ErrorCode.EXECUTION_DISABLED, "Execution disabled"))
        .when(demoExecutionGuard)
        .requireDemo(account, ProductType.LINEAR_PERP, SYMBOL);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.EXECUTION_DISABLED));

    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(symbolRepository, never()).findBySymbol(anyString());
    verify(accountSymbolSettingRepository, never())
        .findByAccountIdAndSymbolForUpdate(any(), anyString());
    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void fokNonMarketablePerpetualRejectsBeforePersistenceOrFinancialMutation() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        "perp-fok-reject",
        TimeInForce.FOK,
        false);

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));

    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void fokPerpetualMarketabilityRetriesOneStaleSnapshotBeforeZeroMutationRejection() {
    CreateOrderRequest request = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "98",
        null,
        "perp-fok-stale",
        TimeInForce.FOK,
        false);
    PerpetualMarketBundle stale = bundle();
    PerpetualMarketBundle fresh = bundle();
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenReturn(stale, fresh);
    doThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "first FOK snapshot expired"))
        .doNothing()
        .when(fullFillCoordinator)
        .requireFresh(any(ExecutableMarketSnapshot.class));

    assertThatThrownBy(() -> service.createOrder(principal(), request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));

    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
  }

  @Test
  void iocAndFokMarketOrMarketablePerpetualLimitFillFullyAsTaker() {
    CreateOrderRequest iocMarket = advancedRequest(
        OrderSide.BUY,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        "perp-ioc-market",
        TimeInForce.IOC,
        false);
    CreateOrderRequest iocLimit = advancedRequest(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "101",
        null,
        "perp-ioc-limit",
        TimeInForce.IOC,
        false);
    CreateOrderRequest fokMarket = advancedRequest(
        OrderSide.SELL,
        OrderType.MARKET,
        QuantityUnit.BASE,
        "1.0000",
        null,
        null,
        "perp-fok-market",
        TimeInForce.FOK,
        false);
    CreateOrderRequest fokLimit = advancedRequest(
        OrderSide.SELL,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "99",
        null,
        "perp-fok-limit",
        TimeInForce.FOK,
        false);

    OrderResponse iocResponse = service.createOrder(principal(), iocMarket);
    OrderResponse iocLimitResponse = service.createOrder(principal(), iocLimit);
    OrderResponse fokMarketResponse = service.createOrder(principal(), fokMarket);
    OrderResponse fokResponse = service.createOrder(principal(), fokLimit);

    assertThat(List.of(
        iocResponse.status(),
        iocLimitResponse.status(),
        fokMarketResponse.status(),
        fokResponse.status()))
        .containsOnly(OrderStatus.FILLED.name());
    assertThat(List.of(
        iocResponse.timeInForce(),
        iocLimitResponse.timeInForce(),
        fokMarketResponse.timeInForce(),
        fokResponse.timeInForce()))
        .containsExactly(
            TimeInForce.IOC,
            TimeInForce.IOC,
            TimeInForce.FOK,
            TimeInForce.FOK);
    assertThat(List.of(
        iocResponse.liquidityRole(),
        iocLimitResponse.liquidityRole(),
        fokMarketResponse.liquidityRole(),
        fokResponse.liquidityRole()))
        .containsOnly(LiquidityRole.TAKER);
    ArgumentCaptor<CreateOrderRequest> intents =
        ArgumentCaptor.forClass(CreateOrderRequest.class);
    verify(executionAdapter, times(4)).execute(intents.capture());
    assertThat(intents.getAllValues()).extracting(CreateOrderRequest::timeInForce)
        .containsExactly(
            TimeInForce.IOC,
            TimeInForce.IOC,
            TimeInForce.FOK,
            TimeInForce.FOK);
  }

  @Test
  void perpetualLimitModifyMaterializesCurrentQuantityAndAdjustsHoldOnSameOrder() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId()))
        .thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(order));

    OrderResponse result = service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("80"), null, null));
    org.mockito.ArgumentCaptor<CreateOrderRequest> validation =
        org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);

    assertAll(
        () -> assertThat(result.id()).isEqualTo(order.getId()),
        () -> assertThat(order.getClientOrderId()).isEqualTo("perp-limit-modify"),
        () -> assertThat(order.getIdempotencyKey()).isEqualTo("perp-limit-modify"),
        () -> assertThat(result.status()).isEqualTo(OrderStatus.PENDING.name()),
        () -> assertThat(result.originalQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(result.baseQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(result.price()).isEqualByComparingTo("80"),
        () -> assertThat(result.holdAmount()).isEqualByComparingTo("8.04000000"),
        () -> assertThat(order.getUpdatedAt()).isNotNull(),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("8.04000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49991.96000000"));
    verify(instrumentRulesEngine).validateCanonicalOrder(
        validation.capture(),
        any(SymbolEntity.class),
        eq(new BigDecimal("1.0000")),
        eq(new BigDecimal("80")));
    assertAll(
        () -> assertThat(validation.getValue().quantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(validation.getValue().price()).isEqualByComparingTo("80"));
    verify(ledgerService).recordOrderRelease(
        account,
        new BigDecimal("1.96000000"),
        order.getId(),
        "Pending Perpetual order margin decreased");
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(orderEventService).record(
        order.getId(),
        "ORDER_MODIFIED",
        OrderStatus.PENDING,
        OrderStatus.PENDING,
        null,
        "Pending Perpetual LIMIT order modified");
  }

  @Test
  void invalidPerpetualLimitModifyLeavesOriginalOrderHoldAndLedgerUnchanged() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId()))
        .thenReturn(Optional.of(order));
    doThrow(new BusinessException(ErrorCode.PRICE_TICK_MISMATCH, "bad tick"))
        .when(instrumentRulesEngine)
        .validateCanonicalOrder(
            any(CreateOrderRequest.class),
            any(SymbolEntity.class),
            eq(new BigDecimal("1.0000")),
            eq(new BigDecimal("80.05")));

    Object result = capture(() -> service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("80.05"), null, null)));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo(ErrorCode.PRICE_TICK_MISMATCH),
        () -> assertThat(order.getPrice()).isEqualByComparingTo("90"),
        () -> assertThat(order.getOriginalQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getBaseQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.00000000"),
        () -> verify(transactionExecutor, never()).execute(any()),
        () -> verify(orderRepository, never()).findByIdForUpdate(any()),
        () -> verify(orderRepository, never()).save(any()),
        () -> verify(accountRepository, never()).save(any()),
        () -> verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString()),
        () -> verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString()),
        () -> verify(orderEventService, never()).record(any(), anyString(), any(), any(), any(), anyString()));
  }

  @Test
  void perpetualLimitModifyReservesOnlyTheAdditionalHold() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("49990.00000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId()))
        .thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(order));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenReturn(bundle(SYMBOL, "119", "121", "120"));

    OrderResponse result = service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("110"), null, null));

    assertAll(
        () -> assertThat(result.id()).isEqualTo(order.getId()),
        () -> assertThat(result.holdAmount()).isEqualByComparingTo("11.05500000"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("11.05500000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49988.94500000"));
    verify(ledgerService).recordOrderHold(
        account,
        new BigDecimal("1.05500000"),
        order.getId(),
        "Pending Perpetual order margin increased");
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString());
  }

  @Test
  void insufficientMarginDuringPerpetualModifyLeavesOriginalOrderAndLedgerUnchanged() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    account.setBalance(new BigDecimal("10.50000000"));
    account.setEquity(new BigDecimal("10.50000000"));
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("0.50000000"));
    when(orderRepository.findByUserIdAndId(userId, order.getId()))
        .thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(order));

    Object result = capture(() -> service.modifyOrder(
        principal(),
        order.getId(),
        new UpdateOrderRequest(null, new BigDecimal("110"), null, null)));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo(ErrorCode.INSUFFICIENT_MARGIN),
        () -> assertThat(order.getPrice()).isEqualByComparingTo("90"),
        () -> assertThat(order.getOriginalQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getBaseQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("0.50000000"));
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString());
    verify(orderEventService, never()).record(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void perpetualModifyRejectsNonLimitOrderBeforeLocksProviderOrWrites() {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    order.setOrderType(OrderType.MARKET);
    order.setRequestedPrice(null);
    order.setPrice(null);
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
        new UpdateOrderRequest(null, new BigDecimal("99"), null, null)));

    assertAll(
        () -> assertThat(businessCode(result)).isEqualTo("ORDER_NOT_MODIFIABLE"),
        () -> verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any()),
        () -> verify(accountSymbolSettingRepository, never())
            .findByAccountIdAndSymbolForUpdate(any(), anyString()),
        () -> verify(positionRepository, never())
            .findOpenLinearPerpByAccountIdForUpdate(any()),
        () -> verify(marketBundleResolver, never()).resolvePerp(anyString(), any()),
        () -> verify(transactionExecutor, never()).execute(any()),
        () -> verify(orderRepository, never()).save(any()));
  }

  @Test
  void perpetualReplayUsesOriginalPublicMarginModeFingerprint() {
    setting.setMarginMode(MarginMode.ISOLATED);
    String clientOrderId = "perp-fingerprint";
    CreateOrderRequest original = request(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "90",
        null,
        PositionSide.BOTH,
        10,
        MarginMode.CROSS,
        clientOrderId);
    CreateOrderRequest conflict = request(
        OrderSide.BUY,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "1.0000",
        "90",
        null,
        PositionSide.BOTH,
        10,
        MarginMode.ISOLATED,
        clientOrderId);
    AtomicReference<OrderEntity> stored = new AtomicReference<>();
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, clientOrderId))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity value = invocation.getArgument(0);
      stored.set(value);
      return value;
    });

    OrderResponse first = service.createOrder(principal(), original);
    OrderResponse replay = service.createOrder(principal(), original);

    assertAll(
        () -> assertThat(replay.id()).isEqualTo(first.id()),
        () -> assertThat(stored.get().getMarginMode()).isEqualTo(MarginMode.ISOLATED),
        () -> assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
            stored.get(), "requestFingerprint"))
            .as("the public request survives authority-field materialization")
            .isNotNull(),
        () -> assertThatThrownBy(() -> service.createOrder(principal(), conflict))
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getCode())
                    .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID)));
    verify(orderRepository, times(1)).save(any(OrderEntity.class));
    verify(ledgerService, times(1)).recordOrderHold(
        any(TradingAccountEntity.class),
        any(BigDecimal.class),
        eq(first.id()),
        eq("Perpetual order margin reserved"));
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

  private CreateOrderRequest advancedRequest(
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
        10,
        PositionSide.BOTH,
        unit,
        MarginMode.CROSS,
        trigger == null ? null : new BigDecimal(trigger),
        null,
        false,
        List.of(),
        timeInForce,
        postOnly,
        null,
        null,
        null);
  }

  private CreateOrderRequest trailingRequest(
      String key,
      PositionSide positionSide,
      String activationPrice,
      String trailingDelta,
      String trailingRate
  ) {
    return trailingRequest(
        key,
        SYMBOL,
        OrderSide.SELL,
        positionSide,
        activationPrice,
        trailingDelta,
        trailingRate,
        true,
        TimeInForce.GTC,
        false,
        null,
        null,
        List.of());
  }

  private CreateOrderRequest trailingRequest(
      String key,
      String symbol,
      OrderSide side,
      PositionSide positionSide,
      String activationPrice,
      String trailingDelta,
      String trailingRate,
      boolean reduceOnly,
      TimeInForce timeInForce,
      boolean postOnly,
      String price,
      String triggerPrice,
      List<CreateOrderRequest.AttachedProtectionRequest> attachedProtections
  ) {
    return new CreateOrderRequest(
        accountId,
        symbol,
        side,
        OrderType.TRAILING_STOP_MARKET,
        null,
        price == null ? null : new BigDecimal(price),
        null,
        null,
        key,
        key,
        new BigDecimal("0.5000"),
        null,
        10,
        positionSide,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        triggerPrice == null ? null : new BigDecimal(triggerPrice),
        triggerPrice == null ? null : TriggerPriceType.MARK_PRICE,
        reduceOnly,
        attachedProtections,
        timeInForce,
        postOnly,
        activationPrice == null ? null : new BigDecimal(activationPrice),
        trailingDelta == null ? null : new BigDecimal(trailingDelta),
        trailingRate == null ? null : new BigDecimal(trailingRate));
  }

  private OrderEntity pendingPerpetualLimit(
      String price,
      String quantity,
      String hold
  ) {
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
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal(quantity));
    order.setQuantity(new BigDecimal(quantity));
    order.setOriginalQuantity(new BigDecimal(quantity));
    order.setBaseQuantity(new BigDecimal(quantity));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setRequestedPrice(new BigDecimal(price));
    order.setPrice(new BigDecimal(price));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal(quantity));
    order.setHoldAmount(new BigDecimal(hold));
    order.setHoldCurrency("USDT");
    order.setReduceOnly(false);
    order.setLeverage(10);
    order.setClientOrderId("perp-limit-modify");
    order.setIdempotencyKey("perp-limit-modify");
    order.setVersion(0L);
    return order;
  }

  private OrderEntity perpetualStopLimit(OrderStatus status) {
    OrderEntity order = pendingPerpetualLimit("90", "1.0000", "10.00000000");
    order.setOrderType(OrderType.STOP_LIMIT);
    order.setStatus(status);
    order.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.USER);
    order.setTriggerPrice(new BigDecimal("110"));
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
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
