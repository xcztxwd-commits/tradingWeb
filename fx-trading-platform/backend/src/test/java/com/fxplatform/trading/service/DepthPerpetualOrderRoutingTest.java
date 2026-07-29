package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
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
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepthPerpetualOrderRoutingTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private TradeRepository tradeRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
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

  @Test
  void depthBookKeepsSnapshotMarketablePerpetualLimitPendingWithSealedHold() {
    String key = "depth-perp-resting-limit";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("110"), decimal("5")))),
        bundle("99", "100", "100", "100", "100"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "1.0000",
        "105",
        null,
        TimeInForce.GTC,
        false));

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name()),
        () -> assertThat(response.holdAmount()).isEqualByComparingTo("10.71000000"),
        () -> assertThat(response.holdCurrency()).isEqualTo("USDT"),
        () -> assertThat(response.baseQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(response.leverage()).isEqualTo(10),
        () -> assertThat(response.marginMode()).isEqualTo(MarginMode.CROSS));
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthMultiLevelImmediateFillUsesEveryBookLevelInsteadOfSnapshotTop() {
    String key = "depth-perp-multi-level";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(
                new DemoBookLevel(decimal("100"), decimal("1")),
                new DemoBookLevel(decimal("102"), decimal("1")))),
        bundle("109", "110", "109.5", "100", "100"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "1.5000",
        "105",
        null,
        TimeInForce.GTC,
        false));

    ArgumentCaptor<TradeEntity> trades = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository, times(2)).save(trades.capture());
    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(response.filledQuantity()).isEqualByComparingTo("1.50000000"),
        () -> assertThat(response.remainingQuantity()).isZero(),
        () -> assertThat(response.avgFillPrice()).isEqualByComparingTo("100.66666667"),
        () -> assertThat(response.fee()).isEqualByComparingTo("0.30200000"),
        () -> assertThat(response.holdAmount()).isZero(),
        () -> assertThat(trades.getAllValues())
            .extracting(TradeEntity::getPrice)
            .containsExactly(decimal("100"), decimal("102")),
        () -> assertThat(trades.getAllValues())
            .extracting(TradeEntity::getLots)
            .containsExactly(decimal("1.00000000"), decimal("0.50000000")),
        () -> assertThat(fixture.openPosition().get().getLots())
            .isEqualByComparingTo("1.50000000"),
        () -> assertThat(fixture.openPosition().get().getOpenPrice())
            .isEqualByComparingTo("100.66666667"));
    verify(ledgerService).recordOrderHold(
        eq(fixture.account()),
        eq(decimal("15.60600000")),
        eq(response.id()),
        eq("Perpetual DEPTH order margin reserved"));
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthIocPartialFillCancelsTailAndReleasesItsExactHold() {
    String key = "depth-perp-ioc-partial";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("100"), decimal("1")))),
        bundle("89", "90", "89.5", "100", "100"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "2.0000",
        "105",
        null,
        TimeInForce.IOC,
        false));

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name()),
        () -> assertThat(response.filledQuantity()).isEqualByComparingTo("1.00000000"),
        () -> assertThat(response.remainingQuantity()).isZero(),
        () -> assertThat(response.avgFillPrice()).isEqualByComparingTo("100.00000000"),
        () -> assertThat(response.fee()).isEqualByComparingTo("0.20000000"),
        () -> assertThat(response.holdAmount()).isZero(),
        () -> assertThat(fixture.account().getBalance()).isEqualByComparingTo("49999.80000000"),
        () -> assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(fixture.openPosition().get().getLots()).isEqualByComparingTo("1.00000000"));
    verify(ledgerService).recordOrderHold(
        eq(fixture.account()),
        eq(decimal("20.40000000")),
        eq(response.id()),
        eq("Perpetual DEPTH order margin reserved"));
    verify(ledgerService).recordOrderRelease(
        eq(fixture.account()),
        eq(decimal("10.20000000")),
        eq(response.id()),
        eq("DEPTH IOC Perpetual remainder released"));
    verify(tradeRepository).save(any(TradeEntity.class));
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthIocZeroFillCancelsWithoutAccountPositionOrLedgerMutation() {
    String key = "depth-perp-ioc-zero";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("101"), decimal("2")))),
        bundle("89", "90", "89.5", "100", "100"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "1.0000",
        "100",
        null,
        TimeInForce.IOC,
        false));

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name()),
        () -> assertThat(response.filledQuantity()).isZero(),
        () -> assertThat(response.remainingQuantity()).isZero(),
        () -> assertThat(response.holdAmount()).isZero(),
        () -> assertThat(fixture.account().getBalance()).isEqualByComparingTo("50000"),
        () -> assertThat(fixture.account().getUsedMargin()).isZero(),
        () -> assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("50000"),
        () -> assertThat(fixture.openPosition()).hasNullValue());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthIsolatedOpeningFillMovesReservedH0IntoPositionMarginWithoutGrowingCrossFreeMargin() {
    String key = "depth-perp-isolated-open";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("100"), decimal("1")))),
        bundle("99", "110", "99.5", "100", "100"),
        MarginMode.ISOLATED,
        null,
        List.of());

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderSide.BUY,
        OrderType.LIMIT,
        "1.0000",
        "105",
        null,
        TimeInForce.GTC,
        false,
        MarginMode.ISOLATED,
        false));

    PositionEntity position = fixture.openPosition().get();
    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(response.marginMode()).isEqualTo(MarginMode.ISOLATED),
        () -> assertThat(response.holdAmount()).isZero(),
        () -> assertThat(response.parentPositionId()).isNull(),
        () -> assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("49989.80000000"),
        () -> assertThat(fixture.account().getBalance()).isEqualByComparingTo("49999.80000000"),
        () -> assertThat(position.getMarginMode()).isEqualTo(MarginMode.ISOLATED),
        () -> assertThat(position.getLots()).isEqualByComparingTo("1.00000000"),
        () -> assertThat(position.getInitialMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("10.00000000"));
    verify(ledgerService).recordOrderHold(
        fixture.account(),
        decimal("10.20000000"),
        response.id(),
        "Perpetual DEPTH order margin reserved");
    verify(ledgerService).recordOrderRelease(
        fixture.account(),
        decimal("10.20000000"),
        response.id(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordMarginHold(
        fixture.account(),
        decimal("10.00000000"),
        position.getId(),
        "Position margin held from order hold");
    verify(accountRepository, never()).reserveMarginIfAvailable(any(), any());
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthIsolatedPartialIocCloseBindsParentAndReleasesTheExactPlannedTail() {
    String key = "depth-perp-isolated-close-ioc";
    UUID accountId = namedId("account-" + key);
    PositionEntity existing = isolatedLong(accountId, "2.0000", "30.00000000");
    OrderEntity activeClose = activeIsolatedClose(
        accountId,
        existing.getId(),
        "0.2500",
        "1.00000000");
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("1"))),
            List.of(new DemoBookLevel(decimal("101"), decimal("5")))),
        bundle("90", "91", "90.5", "100", "100"),
        MarginMode.ISOLATED,
        existing,
        List.of(activeClose));

    OrderResponse response = fixture.create(request(
        accountId,
        key,
        OrderSide.SELL,
        OrderType.LIMIT,
        "1.5000",
        "95",
        null,
        TimeInForce.IOC,
        false,
        MarginMode.ISOLATED,
        true));

    PositionEntity remaining = fixture.openPosition().get();
    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name()),
        () -> assertThat(response.marginMode()).isEqualTo(MarginMode.ISOLATED),
        () -> assertThat(response.parentPositionId()).isEqualTo(existing.getId()),
        () -> assertThat(response.filledQuantity()).isEqualByComparingTo("1.00000000"),
        () -> assertThat(response.remainingQuantity()).isZero(),
        () -> assertThat(response.holdAmount()).isZero(),
        () -> assertThat(remaining.getId()).isEqualTo(existing.getId()),
        () -> assertThat(remaining.getLots()).isEqualByComparingTo("1.00000000"),
        () -> assertThat(remaining.getInitialMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(remaining.getMarginHeld()).isEqualByComparingTo("15.00000000"),
        () -> assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("16.00000000"),
        () -> assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("49983.80200000"),
        () -> assertThat(fixture.account().getBalance()).isEqualByComparingTo("49998.80200000"));
    verify(ledgerService).recordOrderHold(
        fixture.account(),
        decimal("1.79700000"),
        response.id(),
        "Perpetual DEPTH order margin reserved");
    verify(ledgerService).recordOrderRelease(
        fixture.account(),
        decimal("0.59900000"),
        response.id(),
        "DEPTH IOC Perpetual remainder released");
    verify(ledgerService).recordMarginRelease(
        fixture.account(),
        decimal("15.00000000"),
        existing.getId(),
        "Position margin released");
    verify(tradeRepository).save(any(TradeEntity.class));
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthIsolatedCloseIncludesPlannedH0InTheAggregatePositionBufferGuard() {
    String key = "depth-perp-isolated-close-aggregate";
    UUID accountId = namedId("account-" + key);
    PositionEntity existing = isolatedLong(accountId, "2.0000", "30.00000000");
    OrderEntity activeClose = activeIsolatedClose(
        accountId,
        existing.getId(),
        "0.2500",
        "27.10300000");
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("1"))),
            List.of(new DemoBookLevel(decimal("101"), decimal("5")))),
        bundle("90", "91", "90.5", "100", "100"),
        MarginMode.ISOLATED,
        existing,
        List.of(activeClose));
    CreateOrderRequest request = request(
        accountId,
        key,
        OrderSide.SELL,
        OrderType.LIMIT,
        "1.5000",
        "95",
        null,
        TimeInForce.IOC,
        false,
        MarginMode.ISOLATED,
        true);

    assertThatThrownBy(() -> fixture.create(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARGIN_REDUCTION_UNSAFE"));

    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthFokRejectsWhenBookCannotFillTheWholePerpetualOrder() {
    String key = "depth-perp-fok-reject";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("100"), decimal("1")))),
        bundle("99", "100", "100", "100", "100"));
    CreateOrderRequest request = request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "2.0000",
        "105",
        null,
        TimeInForce.FOK,
        false);

    assertThatThrownBy(() -> fixture.create(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoMoreInteractions(fixture.fullFillCoordinator());
  }

  @Test
  void depthPostOnlyAcceptsWhenSnapshotWouldTakeButBookWouldRest() {
    String key = "depth-perp-post-only-rest";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("110"), decimal("2")))),
        bundle("89", "90", "89.5", "100", "100"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "1.0000",
        "100",
        null,
        TimeInForce.GTC,
        true));

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name()),
        () -> assertThat(response.postOnly()).isTrue(),
        () -> assertThat(response.holdAmount()).isEqualByComparingTo("10.20000000"));
    verify(tradeRepository, never()).save(any());
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthPostOnlyRejectsWhenSnapshotWouldRestButBookWouldTake() {
    String key = "depth-perp-post-only-take";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("98"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("99"), decimal("2")))),
        bundle("109", "110", "109.5", "100", "100"));
    CreateOrderRequest request = request(
        fixture.account().getId(),
        key,
        OrderType.LIMIT,
        "1.0000",
        "100",
        null,
        TimeInForce.GTC,
        true);

    assertThatThrownBy(() -> fixture.create(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verifyNoMoreInteractions(fixture.fullFillCoordinator());
  }

  @Test
  void depthStopMarketUsesMarkTriggerAndPersistsItsSourceType() {
    String key = "depth-perp-stop-market-mark";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("102"), decimal("1")))),
        bundle("99", "100", "90", "101", "101"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.STOP_MARKET,
        "1.0000",
        null,
        "100.5",
        TimeInForce.GTC,
        false));

    ArgumentCaptor<TradeEntity> trade = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(trade.capture());
    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name()),
        () -> assertThat(response.orderType()).isEqualTo(OrderType.STOP_MARKET.name()),
        () -> assertThat(response.triggerPrice()).isEqualByComparingTo("100.5"),
        () -> assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE),
        () -> assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET),
        () -> assertThat(response.executionPrice()).isEqualByComparingTo("102"),
        () -> assertThat(response.fee()).isEqualByComparingTo("0.20400000"),
        () -> assertThat(trade.getValue().getPrice()).isEqualByComparingTo("102"));
    assertDepthOnlyCoordinator(fixture);
  }

  @Test
  void depthStopLimitRemainsPendingActivationWithItsSourceContract() {
    String key = "depth-perp-stop-limit-pending";
    Fixture fixture = fixture(
        key,
        policy(
            List.of(new DemoBookLevel(decimal("99"), decimal("5"))),
            List.of(new DemoBookLevel(decimal("110"), decimal("2")))),
        bundle("99", "100", "100", "100", "100"));

    OrderResponse response = fixture.create(request(
        fixture.account().getId(),
        key,
        OrderType.STOP_LIMIT,
        "1.0000",
        "105",
        "120",
        TimeInForce.GTC,
        false));

    assertAll(
        () -> assertThat(response.status()).isEqualTo(OrderStatus.PENDING_ACTIVATION.name()),
        () -> assertThat(response.orderType()).isEqualTo(OrderType.STOP_LIMIT.name()),
        () -> assertThat(response.triggerPrice()).isEqualByComparingTo("120"),
        () -> assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE),
        () -> assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.LIMIT),
        () -> assertThat(response.holdAmount()).isEqualByComparingTo("10.71000000"));
    verify(tradeRepository, never()).save(any());
    assertDepthOnlyCoordinator(fixture);
  }

  private Fixture fixture(
      String key,
      DemoExecutionPolicy policy,
      PerpetualMarketBundle marketBundle
  ) {
    return fixture(
        key,
        policy,
        marketBundle,
        MarginMode.CROSS,
        null,
        List.of());
  }

  private Fixture fixture(
      String key,
      DemoExecutionPolicy policy,
      PerpetualMarketBundle marketBundle,
      MarginMode settingMarginMode,
      PositionEntity existingPosition,
      List<OrderEntity> activeOrders
  ) {
    UUID userId = namedId("user-" + key);
    UUID accountId = namedId("account-" + key);
    TradingAccountEntity account = account(userId, accountId);
    if (existingPosition != null) {
      BigDecimal activeHolds = activeOrders.stream()
          .map(OrderEntity::getHoldAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      account.setUsedMargin(existingPosition.getMarginHeld().add(activeHolds));
    }
    AccountSymbolSettingEntity setting = setting(accountId, settingMarginMode);
    SymbolEntity symbol = symbol();
    AtomicReference<PositionEntity> openPosition = new AtomicReference<>(existingPosition);
    List<PositionEntity> initialPositions = existingPosition == null
        ? List.of()
        : List.of(existingPosition);

    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    lenient().when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    lenient().when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenReturn(marketBundle);
    lenient().when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(
        accountId, SYMBOL)).thenReturn(Optional.of(setting));
    lenient().when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(initialPositions);
    lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(initialPositions);
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenAnswer(invocation -> Optional.ofNullable(openPosition.get()));
    lenient().when(positionRepository.save(any(PositionEntity.class)))
        .thenAnswer(invocation -> {
          PositionEntity position = invocation.getArgument(0);
          if (position.getId() == null) {
            position.setId(namedId("position-" + key));
          }
          openPosition.set(position);
          return position;
        });
    lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(activeOrders);
    lenient().when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(tradeRepository.findByOrderIdAndFillIdentity(any(UUID.class), anyString()))
        .thenReturn(Optional.empty());
    lenient().when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());

    FullFillCoordinator fullFillCoordinator = spy(new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC)));
    SpotSettlementService settlementService =
        new SpotSettlementService(walletService, spotPositionService);
    OrderFillService sharedOrderFillService = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        settlementService,
        walletService);
    DemoExecutionPolicyProvider stablePolicyProvider = () -> policy;
    DepthOrderExecutionService depthExecution = new DepthOrderExecutionService(
        stablePolicyProvider,
        tradeRepository,
        sharedOrderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));
    OrderService service = new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        sharedOrderFillService,
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
        new OrderHoldCalculator(fullFillCoordinator),
        accountSymbolSettingRepository,
        new PerpetualOrderRiskService(fullFillCoordinator),
        new PerpetualAccountRiskSnapshotService(
            positionRepository,
            symbolRepository,
            marketBundleResolver,
            fullFillCoordinator,
            new PerpetualRiskService(new PerpMarginCalculator())));
    service.setProtectionOrderService(protectionOrderService);
    service.setDepthOrderExecutionService(depthExecution);
    return new Fixture(
        service,
        new UserPrincipal(userId, "depth-perp@example.com", "TRADER"),
        account,
        fullFillCoordinator,
        openPosition);
  }

  private static void assertDepthOnlyCoordinator(Fixture fixture) {
    verify(fixture.fullFillCoordinator(), times(2))
        .requireFresh(any(ExecutableMarketSnapshot.class));
    verifyNoMoreInteractions(fixture.fullFillCoordinator());
  }

  private static CreateOrderRequest request(
      UUID accountId,
      String key,
      OrderType orderType,
      String quantity,
      String price,
      String triggerPrice,
      TimeInForce timeInForce,
      boolean postOnly
  ) {
    return request(
        accountId,
        key,
        OrderSide.BUY,
        orderType,
        quantity,
        price,
        triggerPrice,
        timeInForce,
        postOnly,
        MarginMode.CROSS,
        false);
  }

  private static CreateOrderRequest request(
      UUID accountId,
      String key,
      OrderSide side,
      OrderType orderType,
      String quantity,
      String price,
      String triggerPrice,
      TimeInForce timeInForce,
      boolean postOnly,
      MarginMode marginMode,
      boolean reduceOnly
  ) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        side,
        orderType,
        null,
        null,
        null,
        null,
        key,
        key,
        decimal(quantity),
        price == null ? null : decimal(price),
        10,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        marginMode,
        triggerPrice == null ? null : decimal(triggerPrice),
        orderType == OrderType.STOP_MARKET || orderType == OrderType.STOP_LIMIT
            ? TriggerPriceType.MARK_PRICE
            : null,
        reduceOnly,
        List.of(),
        timeInForce,
        postOnly,
        null,
        null,
        null);
  }

  private static DemoExecutionPolicy policy(
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.001"),
        decimal("0.002"),
        decimal("0.001"),
        decimal("0.0001"),
        bids,
        asks,
        null);
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(decimal("50000"));
    account.setEquity(decimal("50000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(decimal("50000"));
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }

  private static AccountSymbolSettingEntity setting(UUID accountId) {
    return setting(accountId, MarginMode.CROSS);
  }

  private static AccountSymbolSettingEntity setting(
      UUID accountId,
      MarginMode marginMode
  ) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(SYMBOL);
    setting.setLeverage(10);
    setting.setMarginMode(marginMode);
    setting.setQuantityUnit(QuantityUnit.CONTRACTS);
    setting.setVersion(1L);
    return setting;
  }

  private static PositionEntity isolatedLong(
      UUID accountId,
      String quantity,
      String marginHeld
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(namedId("isolated-position-" + accountId));
    position.setAccountId(accountId);
    position.setSymbol(SYMBOL);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.ISOLATED);
    position.setSide(OrderSide.BUY);
    position.setLots(decimal(quantity));
    position.setOpenPrice(decimal("100"));
    position.setCurrentPrice(decimal("100"));
    position.setMarkPrice(decimal("100"));
    position.setNotional(decimal(quantity).multiply(decimal("100")));
    position.setInitialMargin(decimal(quantity).multiply(decimal("10")));
    position.setMaintenanceMargin(decimal(quantity).multiply(decimal("0.5")));
    position.setMarginHeld(decimal(marginHeld));
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(0L);
    return position;
  }

  private static OrderEntity activeIsolatedClose(
      UUID accountId,
      UUID parentPositionId,
      String remainingQuantity,
      String holdAmount
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(namedId("active-close-" + accountId + "-" + holdAmount));
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.ISOLATED);
    order.setSide(OrderSide.SELL);
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING);
    order.setBaseQuantity(decimal(remainingQuantity));
    order.setRemainingQuantity(decimal(remainingQuantity));
    order.setHoldAmount(decimal(holdAmount));
    order.setParentPositionId(parentPositionId);
    return order;
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
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private static PerpetualMarketBundle bundle(
      String bid,
      String ask,
      String last,
      String mark,
      String index
  ) {
    return new PerpetualMarketBundle(
        SYMBOL,
        SYMBOL,
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid),
        decimal(ask),
        decimal(last),
        decimal(mark),
        decimal(index),
        null,
        List.of(),
        List.of(),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private static UUID namedId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(
      OrderService service,
      UserPrincipal principal,
      TradingAccountEntity account,
      FullFillCoordinator fullFillCoordinator,
      AtomicReference<PositionEntity> openPosition
  ) {

    private OrderResponse create(CreateOrderRequest request) {
      return service.createOrder(principal, request);
    }
  }
}
