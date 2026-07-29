package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RED contract binding every pending Spot DEPTH fill to current instrument authority. */
@ExtendWith(MockitoExtension.class)
class DepthPendingInstrumentAuthorityTest {

  private static final String SYMBOL = "BTCUSDT";
  private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");

  @Mock private OrderRepository orderRepository;
  @Mock private TradeRepository tradeRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private WalletService walletService;
  @Mock private SpotPositionService spotPositionService;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private OrderFillService orderFillService;
  @Mock private OrderEventService orderEventService;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private SymbolRepository symbolRepository;

  @Test
  void offTickCurrentFillFailsClosedBeforeAnyOrderWalletOrFillWrite() {
    RunResult run = execute(
        policy("100.05", "0.0004"),
        rules("0.1", "0.0001"),
        "0.0008");

    assertAll(
        () -> assertInstrumentAuthoritiesInstalled(run),
        () -> assertThat(run.failure())
            .isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getCode()).isEqualTo(ErrorCode.PRICE_TICK_MISMATCH)),
        () -> verify(symbolRepository).findBySymbol(SYMBOL),
        () -> assertSingleRulesSnapshot(run),
        () -> assertThat(mockingDetails(run.instrumentRulesEngine()).getInvocations())
            .filteredOn(invocation ->
                invocation.getMethod().getName().equals("validateCanonicalDepthFills"))
            .hasSize(1),
        () -> verify(run.depthExecutionService(), never()).planSpot(any(), any()),
        () -> verify(run.depthExecutionService(), never()).applyLocked(
            any(), any(), any(), any(), any(), eq(false)),
        () -> assertNoFinancialWrites(run.order()));
  }

  @Test
  void offStepCurrentFillUsesEffectivePersistedStepAndFailsClosedBeforeWrites() {
    RunResult run = execute(
        policy("100.0", "0.0004"),
        rules("0.1", "0.0003"),
        "0.0009");

    assertAll(
        () -> assertInstrumentAuthoritiesInstalled(run),
        () -> assertThat(run.failure())
            .isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getCode()).isEqualTo("INVALID_INSTRUMENT_RULES")),
        () -> verify(symbolRepository).findBySymbol(SYMBOL),
        () -> assertSingleRulesSnapshot(run),
        () -> verify(run.depthExecutionService()).requireQuantityStep(
            any(), eq(QuantityConversionService.storageCompatibleStep(new BigDecimal("0.0003")))),
        () -> verify(run.depthExecutionService(), never()).planSpot(any(), any()),
        () -> verify(run.depthExecutionService(), never()).applyLocked(
            any(), any(), any(), any(), any(), eq(false)),
        () -> assertNoFinancialWrites(run.order()));
  }

  @Test
  void disabledCurrentInstrumentFailsClosedBeforeMatchingOrAnyFinancialWrite() {
    RunResult run = execute(
        policy("100.0", "0.0004"),
        rules("0.1", "0.0001", false, false, false),
        "0.0008");

    assertAll(
        () -> assertInstrumentAuthoritiesInstalled(run),
        () -> assertThat(run.failure())
            .isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getCode()).isEqualTo(ErrorCode.SYMBOL_NOT_TRADABLE)),
        () -> verify(symbolRepository).findBySymbol(SYMBOL),
        () -> assertSingleRulesSnapshot(run),
        () -> verify(run.depthExecutionService(), never()).prepare(
            any(), anyString(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any(), any()),
        () -> verify(run.depthExecutionService(), never()).planSpot(any(), any()),
        () -> verify(run.depthExecutionService(), never()).applyLocked(
            any(), any(), any(), any(), any(), eq(false)),
        () -> assertNoFinancialWrites(run.order()));
  }

  private RunResult execute(
      DemoExecutionPolicy policy,
      InstrumentRules rules,
      String orderQuantity
  ) {
    OrderEntity order = pendingBuy(orderQuantity, "101", "1000");
    TradingAccountEntity account = account(order.getAccountId(), order.getUserId());
    SymbolEntity symbol = symbol();
    ExecutableMarketSnapshot snapshot = snapshot(policy.asks().getFirst().price());

    InstrumentRulesEngine instrumentRulesEngine = spy(new InstrumentRulesEngine(
        symbolRepository,
        null,
        null,
        null,
        null,
        new ObjectMapper(),
        new TradingInstrumentClassifier()));
    lenient().doReturn(rules).when(instrumentRulesEngine).rules(symbol);
    lenient().when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));

    DepthOrderExecutionService depthExecutionService = spy(new DepthOrderExecutionService(
        () -> policy,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC)));
    lenient().doReturn(new DepthOrderExecutionService.DepthHoldPlan(
        order.getHoldAmount(), List.of(order.getHoldAmount())))
        .when(depthExecutionService).planSpot(any(), any());
    lenient().doThrow(new AssertionError(
        "Pending DEPTH execution reached the financial mutation boundary before instrument validation"))
        .when(depthExecutionService).applyLocked(
            any(), any(), any(), any(), any(), eq(false));

    when(transactionExecutor.execute(any())).thenAnswer(invocation ->
        ((Supplier<?>) invocation.getArgument(0)).get());
    when(accountRepository.findByIdForUpdate(order.getAccountId()))
        .thenReturn(Optional.of(account));
    when(walletBalanceRepository.findByAccountIdForUpdate(order.getAccountId()))
        .thenReturn(List.of());
    when(spotPositionService.lockExisting(order.getAccountId())).thenReturn(List.of());
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    when(tradeRepository.findByOrderIdAndFillIdentity(any(), any()))
        .thenReturn(Optional.empty());

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
    processor.setDepthOrderExecutionService(depthExecutionService);
    boolean symbolRepositoryInstalled = installAuthority(
        processor, SymbolRepository.class, symbolRepository);
    boolean instrumentRulesEngineInstalled = installAuthority(
        processor, InstrumentRulesEngine.class, instrumentRulesEngine);

    Throwable failure = catchThrowable(() -> processor.process(order, snapshot));
    return new RunResult(
        failure,
        order,
        symbol,
        instrumentRulesEngine,
        depthExecutionService,
        symbolRepositoryInstalled,
        instrumentRulesEngineInstalled);
  }

  private void assertInstrumentAuthoritiesInstalled(RunResult run) {
    assertAll(
        () -> assertThat(run.symbolRepositoryInstalled())
            .as("PendingOrderExecutionProcessor SymbolRepository injection point")
            .isTrue(),
        () -> assertThat(run.instrumentRulesEngineInstalled())
            .as("PendingOrderExecutionProcessor InstrumentRulesEngine injection point")
            .isTrue());
  }

  private void assertSingleRulesSnapshot(RunResult run) {
    assertThat(mockingDetails(run.instrumentRulesEngine()).getInvocations())
        .filteredOn(invocation -> invocation.getMethod().getName().equals("rules"))
        .hasSize(1);
  }

  private void assertNoFinancialWrites(OrderEntity order) {
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("0");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo(order.getBaseQuantity());
    assertThat(order.getHoldAmount()).isEqualByComparingTo("1000");
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(tradeRepository, never()).save(any());
    verify(walletBalanceRepository, never()).save(any());
    verifyNoInteractions(walletService, orderFillService, orderEventService);
  }

  private static boolean installAuthority(
      Object target,
      Class<?> authorityType,
      Object authority
  ) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (field.getType() != authorityType) {
          continue;
        }
        try {
          field.setAccessible(true);
          field.set(target, authority);
          return true;
        } catch (ReflectiveOperationException exception) {
          throw new AssertionError("Cannot inject pending authority " + field.getName(), exception);
        }
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.getParameterCount() != 1
            || method.getParameterTypes()[0] != authorityType) {
          continue;
        }
        try {
          method.setAccessible(true);
          method.invoke(target, authority);
          return true;
        } catch (ReflectiveOperationException exception) {
          throw new AssertionError("Cannot invoke pending authority setter " + method.getName(), exception);
        }
      }
    }
    return false;
  }

  private static OrderEntity pendingBuy(String quantity, String price, String hold) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    BigDecimal baseQuantity = new BigDecimal(quantity);
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setLots(baseQuantity);
    order.setQuantity(baseQuantity);
    order.setOriginalQuantity(baseQuantity);
    order.setBaseQuantity(baseQuantity);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(baseQuantity);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setPrice(new BigDecimal(price));
    order.setRequestedPrice(new BigDecimal(price));
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CASH);
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setReduceOnly(false);
    order.setHoldAmount(new BigDecimal(hold));
    order.setHoldCurrency("USDT");
    order.setFee(BigDecimal.ZERO);
    order.setCreatedAt(NOW.minusSeconds(60));
    return order;
  }

  private static TradingAccountEntity account(UUID accountId, UUID userId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setLeverage(1);
    return account;
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
    symbol.setMinLot(new BigDecimal("0.0001"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setEnabled(true);
    symbol.setTradable(true);
    return symbol;
  }

  private static InstrumentRules rules(String tickSize, String stepSize) {
    return rules(tickSize, stepSize, true, true, true);
  }

  private static InstrumentRules rules(
      String tickSize,
      String stepSize,
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
        ProductType.CRYPTO_SPOT,
        new BigDecimal(tickSize),
        new BigDecimal(stepSize),
        new BigDecimal("0.0001"),
        new BigDecimal("100"),
        new BigDecimal("0.0001"),
        new BigDecimal("1000000"),
        new BigDecimal("0.0001"),
        new BigDecimal("100"),
        1,
        1,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private static DemoExecutionPolicy policy(String askPrice, String tickQuantity) {
    BigDecimal quantity = new BigDecimal(tickQuantity);
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new DemoBookLevel(new BigDecimal(askPrice), quantity)),
        null);
  }

  private static ExecutableMarketSnapshot snapshot(BigDecimal ask) {
    return new ExecutableMarketSnapshot(
        SYMBOL,
        ProductType.CRYPTO_SPOT,
        "binance",
        SYMBOL,
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        ask,
        new BigDecimal("100"),
        null,
        null,
        NOW.minusSeconds(1),
        NOW.plusSeconds(60));
  }

  private record RunResult(
      Throwable failure,
      OrderEntity order,
      SymbolEntity symbol,
      InstrumentRulesEngine instrumentRulesEngine,
      DepthOrderExecutionService depthExecutionService,
      boolean symbolRepositoryInstalled,
      boolean instrumentRulesEngineInstalled
  ) {
  }
}
