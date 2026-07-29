package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DepthPendingStopLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");

  private final OrderRepository orderRepository = mock(OrderRepository.class);
  private final TradingAccountRepository accountRepository =
      mock(TradingAccountRepository.class);
  private final DemoExecutionGuard demoExecutionGuard = mock(DemoExecutionGuard.class);
  private final WalletBalanceRepository walletBalanceRepository =
      mock(WalletBalanceRepository.class);
  private final WalletService walletService = mock(WalletService.class);
  private final SpotPositionService spotPositionService = mock(SpotPositionService.class);
  private final FullFillCoordinator fullFillCoordinator = mock(FullFillCoordinator.class);
  private final OrderFillService orderFillService = mock(OrderFillService.class);
  private final OrderEventService orderEventService = mock(OrderEventService.class);
  private final TradingTransactionExecutor transactionExecutor =
      mock(TradingTransactionExecutor.class);
  private final TradeRepository tradeRepository = mock(TradeRepository.class);
  private final SymbolRepository symbolRepository = mock(SymbolRepository.class);
  private final InstrumentRulesEngine instrumentRulesEngine = mock(InstrumentRulesEngine.class);
  private final InstrumentRules instrumentRules = mock(InstrumentRules.class);
  private final AtomicReference<DemoExecutionPolicy> currentPolicy = new AtomicReference<>();

  private DemoExecutionPolicyProvider policyProvider;
  private DepthOrderExecutionService depthOrderExecutionService;

  @BeforeEach
  void setUpDepthAuthoritiesAndStatefulFillBoundary() {
    policyProvider = currentPolicy::get;
    depthOrderExecutionService = new DepthOrderExecutionService(
        policyProvider,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));

    when(transactionExecutor.execute(any())).thenAnswer(
        invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    when(tradeRepository.findByOrderIdAndFillIdentity(any(), anyString()))
        .thenReturn(Optional.empty());
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("BTCUSDT");
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    when(symbolRepository.findBySymbol(anyString())).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(any(SymbolEntity.class))).thenReturn(instrumentRules);
    when(instrumentRules.stepSize()).thenReturn(new BigDecimal("0.0001"));
    when(instrumentRules.productType()).thenReturn(ProductType.CRYPTO_SPOT);
    when(orderRepository.activateStopLimitPending(any())).thenReturn(1);
    when(orderRepository.activateStopLimitWorking(any())).thenReturn(1);
    when(orderRepository.claimPending(any())).thenReturn(1);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any()))
        .thenAnswer(invocation -> legacyFullFill(invocation.getArgument(0)));
    when(orderFillService.applyFill(
        any(OrderEntity.class),
        any(OrderEntity.class),
        any(TradingAccountEntity.class),
        any(DemoMatchFill.class),
        anyString(),
        any(ExecutableMarketSnapshot.class),
        any(DemoExecutionPolicy.class),
        any(BigDecimal.class),
        anyBoolean())).thenAnswer(invocation -> {
          OrderEntity order = invocation.getArgument(0);
          OrderEntity holdOwner = invocation.getArgument(1);
          DemoMatchFill fill = invocation.getArgument(3);
          BigDecimal holdAfter = invocation.getArgument(7);
          BigDecimal filledBefore = order.getFilledQuantity() == null
              ? BigDecimal.ZERO
              : order.getFilledQuantity();
          BigDecimal remaining = order.getRemainingQuantity().subtract(fill.quantity());
          order.setFilledQuantity(filledBefore.add(fill.quantity()));
          order.setRemainingQuantity(remaining);
          order.setStatus(remaining.signum() == 0
              ? OrderStatus.FILLED
              : OrderStatus.PARTIALLY_FILLED);
          holdOwner.setHoldAmount(holdAfter);
          return order;
        });
  }

  @Test
  void stopLimitActivatesWithoutDepthFillThenOnlyNextTickFillsRemainderAsMaker() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = stopOrder(
        accountId,
        OrderType.STOP_LIMIT,
        "1",
        "100.05000000",
        "100",
        "100");
    stubLockedAuthorities(account, order);
    PendingOrderExecutionProcessor processor = processor();

    currentPolicy.set(policy(level("101", "1")));
    ExecutableMarketSnapshot activationTick = snapshot(0, "98", "99", "100");

    boolean activationFilled = processor.process(order, activationTick);

    assertAll(
        () -> assertThat(activationFilled).isFalse(),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("1"),
        () -> verify(orderRepository).activateStopLimitPending(order.getId()),
        () -> verify(orderEventService).record(
            eq(order.getId()),
            eq("ORDER_TRIGGERED"),
            eq(OrderStatus.PENDING_ACTIVATION),
            eq(OrderStatus.PENDING),
            eq(null),
            anyString()),
        () -> verifyNoInteractions(walletService),
        () -> verifyNoInteractions(orderFillService),
        () -> verify(fullFillCoordinator, never()).execute(any(), any()));

    currentPolicy.set(policy(level("99", "1")));
    ExecutableMarketSnapshot makerTick = snapshot(1, "98", "99", "99");

    assertThat(processor.process(order, makerTick)).isTrue();

    ArgumentCaptor<DemoMatchFill> applied = ArgumentCaptor.forClass(DemoMatchFill.class);
    verify(orderFillService).applyFill(
        eq(order),
        eq(order),
        eq(account),
        applied.capture(),
        anyString(),
        eq(makerTick),
        eq(currentPolicy.get()),
        any(BigDecimal.class),
        eq(false));
    assertAll(
        () -> assertThat(applied.getValue().quantity()).isEqualByComparingTo("1"),
        () -> assertThat(applied.getValue().liquidityRole()).isEqualTo(LiquidityRole.MAKER),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0"),
        () -> assertThat(order.getOrderType()).isEqualTo(OrderType.STOP_LIMIT),
        () -> verify(fullFillCoordinator, never()).execute(any(), any()));
  }

  @Test
  void stopLimitTriggerTickPartiallyTakesAndLaterTickMakesTheRemainder() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = stopOrder(
        accountId,
        OrderType.STOP_LIMIT,
        "2",
        "200.10000000",
        "100",
        "100");
    stubLockedAuthorities(account, order);
    PendingOrderExecutionProcessor processor = processor();

    currentPolicy.set(policy(level("99", "1")));
    ExecutableMarketSnapshot triggerTick = snapshot(0, "98", "99", "100");

    assertThat(processor.process(order, triggerTick)).isTrue();
    assertAll(
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED),
        () -> assertThat(order.getFilledQuantity()).isEqualByComparingTo("1"),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("1"),
        () -> assertThat(order.getOrderType()).isEqualTo(OrderType.STOP_LIMIT));

    currentPolicy.set(policy(level("98", "1")));
    ExecutableMarketSnapshot makerTick = snapshot(1, "97", "98", "99");

    assertThat(processor.process(order, makerTick)).isTrue();

    ArgumentCaptor<DemoMatchFill> applied = ArgumentCaptor.forClass(DemoMatchFill.class);
    verify(orderFillService, times(2)).applyFill(
        eq(order),
        eq(order),
        eq(account),
        applied.capture(),
        anyString(),
        any(ExecutableMarketSnapshot.class),
        any(DemoExecutionPolicy.class),
        any(BigDecimal.class),
        eq(false));
    assertAll(
        () -> assertThat(applied.getAllValues())
            .extracting(DemoMatchFill::liquidityRole)
            .containsExactly(LiquidityRole.TAKER, LiquidityRole.MAKER),
        () -> assertThat(applied.getAllValues())
            .extracting(DemoMatchFill::quantity)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(decimal("1"), decimal("1")),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(order.getFilledQuantity()).isEqualByComparingTo("2"),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0"),
        () -> assertThat(order.getOrderType()).isEqualTo(OrderType.STOP_LIMIT),
        () -> verify(fullFillCoordinator, never()).execute(any(), any()));
  }

  @Test
  void stopMarketPartialContinuesTakingAfterLastMovesBackAcrossTrigger() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = stopOrder(
        accountId,
        OrderType.STOP_MARKET,
        "2",
        "198.09900000",
        null,
        "100");
    order.setStatus(OrderStatus.PENDING);
    stubLockedAuthorities(account, order);
    PendingOrderExecutionProcessor processor = processor();

    currentPolicy.set(policy(level("99", "1")));
    ExecutableMarketSnapshot triggerTick = snapshot(0, "98", "99", "100");

    assertThat(processor.process(order, triggerTick)).isTrue();
    assertAll(
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("1"),
        () -> assertThat(order.getOrderType()).isEqualTo(OrderType.STOP_MARKET));

    currentPolicy.set(policy(level("98", "1")));
    ExecutableMarketSnapshot continuationTick = snapshot(1, "97", "98", "99");

    assertThat(processor.process(order, continuationTick)).isTrue();

    ArgumentCaptor<DemoMatchFill> applied = ArgumentCaptor.forClass(DemoMatchFill.class);
    verify(orderFillService, times(2)).applyFill(
        eq(order),
        eq(order),
        eq(account),
        applied.capture(),
        anyString(),
        any(ExecutableMarketSnapshot.class),
        any(DemoExecutionPolicy.class),
        any(BigDecimal.class),
        eq(false));
    assertAll(
        () -> assertThat(applied.getAllValues())
            .extracting(DemoMatchFill::liquidityRole)
            .containsExactly(LiquidityRole.TAKER, LiquidityRole.TAKER),
        () -> assertThat(applied.getAllValues())
            .extracting(DemoMatchFill::quantity)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(decimal("1"), decimal("1")),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(order.getFilledQuantity()).isEqualByComparingTo("2"),
        () -> assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0"),
        () -> assertThat(order.getOrderType()).isEqualTo(OrderType.STOP_MARKET),
        () -> verify(fullFillCoordinator, never()).execute(any(), any()));
  }

  private PendingOrderExecutionProcessor processor() {
    PendingOrderExecutionProcessor processor = new PendingOrderExecutionProcessor(
        orderRepository,
        accountRepository,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        fullFillCoordinator,
        orderFillService,
        orderEventService,
        transactionExecutor);
    injectAssignableFieldIfPresent(processor, depthOrderExecutionService);
    injectAssignableFieldIfPresent(processor, policyProvider);
    injectAssignableFieldIfPresent(processor, symbolRepository);
    injectAssignableFieldIfPresent(processor, instrumentRulesEngine);
    return processor;
  }

  private void stubLockedAuthorities(TradingAccountEntity account, OrderEntity order) {
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
  }

  private static void injectAssignableFieldIfPresent(Object target, Object authority) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!field.getType().isInstance(authority)) {
          continue;
        }
        try {
          field.setAccessible(true);
          field.set(target, authority);
        } catch (ReflectiveOperationException exception) {
          throw new AssertionError("Cannot inject pending DEPTH authority " + field.getName(), exception);
        }
      }
    }
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(accountId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    return account;
  }

  private static OrderEntity stopOrder(
      UUID accountId,
      OrderType orderType,
      String quantity,
      String hold,
      String limitPrice,
      String triggerPrice
  ) {
    OrderEntity order = new OrderEntity();
    BigDecimal baseQuantity = decimal(quantity);
    order.setId(UUID.randomUUID());
    order.setUserId(accountId);
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(orderType);
    order.setStatus(orderType == OrderType.STOP_LIMIT
        ? OrderStatus.PENDING_ACTIVATION
        : OrderStatus.PENDING);
    order.setLots(baseQuantity);
    order.setQuantity(baseQuantity);
    order.setOriginalQuantity(baseQuantity);
    order.setBaseQuantity(baseQuantity);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(baseQuantity);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setMarginMode(MarginMode.CASH);
    order.setPositionSide(PositionSide.BOTH);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setReduceOnly(false);
    if (limitPrice != null) {
      order.setPrice(decimal(limitPrice));
      order.setRequestedPrice(decimal(limitPrice));
    }
    order.setTriggerPrice(decimal(triggerPrice));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(orderType == OrderType.STOP_LIMIT
        ? TriggerExecutionType.LIMIT
        : TriggerExecutionType.MARKET);
    order.setHoldAmount(decimal(hold));
    order.setHoldCurrency("USDT");
    order.setIdempotencyKey("depth-stop-" + order.getId());
    order.setClientOrderId("depth-stop-" + order.getId());
    return order;
  }

  private static DemoExecutionPolicy policy(DemoBookLevel... asks) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.0002"),
        decimal("0.0005"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(),
        List.of(asks),
        null);
  }

  private static DemoBookLevel level(String price, String quantity) {
    return new DemoBookLevel(decimal(price), decimal(quantity));
  }

  private static ExecutableMarketSnapshot snapshot(
      long tick,
      String bid,
      String ask,
      String last
  ) {
    Instant asOf = NOW.plusSeconds(tick);
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "PUBLIC",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid),
        decimal(ask),
        decimal(last),
        null,
        null,
        asOf,
        asOf.plusSeconds(60));
  }

  private static FullFillResult legacyFullFill(FullFillRequest request) {
    BigDecimal quantity = request.requestedBaseQuantity();
    BigDecimal price = decimal("99");
    BigDecimal feeRate = decimal("0.0005");
    BigDecimal fee = quantity.multiply(price).multiply(feeRate).setScale(8);
    return new FullFillResult(
        price,
        NOW,
        quantity,
        BigDecimal.ZERO,
        feeRate,
        fee,
        "USDT",
        LiquidityRole.TAKER,
        BigDecimal.ZERO,
        MarketSourceMode.PUBLIC_EXTERNAL,
        "PUBLIC",
        "BTCUSDT",
        NOW,
        NOW.plusSeconds(60));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
