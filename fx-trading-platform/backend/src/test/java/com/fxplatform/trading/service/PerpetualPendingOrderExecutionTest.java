package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerpetualPendingOrderExecutionTest {

  private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
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
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private SymbolRepository symbolRepository;

  @Test
  void stopMarketTriggersOnMarkEvenWhenLastHasNotCrossed() {
    Fixture fixture = stubCandidate(bundle("99", "101", "90", "110"), "10.15151505");

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isEqualTo(1),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(fixture.order().getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO));
    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        request.capture(),
        eq(ExecutableMarketSnapshot.from(fixture.bundle())));
    assertThat(request.getValue().executionPath())
        .isEqualTo(FullFillExecutionPath.TRIGGERED_STOP_MARKET);
    assertThat(request.getValue().executionIntent().triggerPriceType())
        .isEqualTo(TriggerPriceType.MARK_PRICE);
    verify(orderFillService).fillPerpetual(
        fixture.order(),
        fixture.account(),
        fixture.fill(),
        new BigDecimal("110"),
        10,
        "Pending Perpetual order hold");
  }

  @Test
  void crossProtectionParentRemainsAnExternalHoldAndCanFill() {
    Fixture fixture = stubCandidate(bundle("99", "101", "90", "110"), "10.15151505");
    fixture.order().setParentPositionId(UUID.randomUUID());
    fixture.order().setProtectionType(ProtectionType.TAKE_PROFIT);
    fixture.order().setMarginMode(MarginMode.CROSS);

    int filled = service().executePendingOrders();

    assertThat(filled).isEqualTo(1);
    verify(orderFillService).fillPerpetual(
        fixture.order(),
        fixture.account(),
        fixture.fill(),
        new BigDecimal("110"),
        10,
        "Pending Perpetual order hold");
  }

  @Test
  void pendingOrderStaysPendingWhenCurrentLockedLeverageExceedsInstrumentMaximum() {
    Fixture fixture = stubCandidate(bundle("99", "101", "90", "110"), "10.15151505");
    fixture.setting().setLeverage(101);

    int filled = service().executePendingOrders();

    assertThat(filled).isZero();
    assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(orderRepository, never()).claimPending(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void pendingOrderRevalidatesAdminMaximumReductionInsideMutation() {
    Fixture fixture = stubCandidate(bundle("99", "101", "90", "110"), "10.15151505");
    fixture.setting().setLeverage(80);
    fixture.order().setLeverage(80);
    SymbolEntity prepared = symbol();
    prepared.setLeverage(100);
    SymbolEntity current = symbol();
    current.setLeverage(50);
    AtomicBoolean insideTransaction = new AtomicBoolean();
    when(symbolRepository.findBySymbol(SYMBOL))
        .thenAnswer(invocation -> Optional.of(insideTransaction.get() ? current : prepared));
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());

    int filled = service().executePendingOrders();

    assertThat(filled).isZero();
    assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(positionRepository, never()).findOpenLinearPerpByAccountIdForUpdate(fixture.account().getId());
    verify(orderRepository, never()).claimPending(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void stopMarketIgnoresLastCrossWhenAuthorityMarkHasNotCrossed() {
    Fixture fixture = stubCandidate(bundle("99", "101", "110", "90"), "10.15151505");

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isZero(),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(fixture.order().getHoldAmount()).isEqualByComparingTo("10.15151505"));
    verify(transactionExecutor, never()).execute(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
  }

  @Test
  void staleLockedAttemptReresolvesOutsideTransactionAndOnlyFreshAttemptClaims() {
    Fixture fixture = stubCandidate(bundle("99", "101", "110", "110"), "10.15151505");
    AtomicBoolean insideTransaction = new AtomicBoolean();
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isFalse();
      return fixture.bundle();
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "locked bundle expired"))
        .thenReturn(fixture.fill());

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isEqualTo(1),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.FILLED));
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(transactionExecutor, times(2)).execute(any());
    verify(orderRepository).claimPending(fixture.order().getId());
    verify(orderFillService).fillPerpetual(
        fixture.order(),
        fixture.account(),
        fixture.fill(),
        new BigDecimal("110"),
        10,
        "Pending Perpetual order hold");
  }

  @Test
  void resolverStaleFirstIsRetriedBeforeOpeningATransaction() {
    Fixture fixture = stubCandidate(bundle("99", "101", "110", "110"), "10.15151505");
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "provider refresh"))
        .thenReturn(fixture.bundle());

    int filled = service().executePendingOrders();

    assertThat(filled).isEqualTo(1);
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(transactionExecutor).execute(any());
    verify(orderRepository).claimPending(fixture.order().getId());
  }

  @Test
  void priceGapBeyondStoredHoldTopsUpMarginAndFills() {
    Fixture fixture = stubCandidate(bundle("99", "150", "110", "110"), "10.15151505");
    AtomicReference<BigDecimal> holdAtFill = new AtomicReference<>();
    org.mockito.Mockito.doReturn(risk("15.07500000"))
        .when(perpetualOrderRiskService)
        .evaluate(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any());
    org.mockito.Mockito.doAnswer(invocation -> {
      holdAtFill.set(fixture.order().getHoldAmount());
      return filled(fixture.order());
    }).when(orderFillService).fillPerpetual(
        eq(fixture.order()),
        eq(fixture.account()),
        eq(fixture.fill()),
        eq(new BigDecimal("110")),
        eq(10),
        eq("Pending Perpetual order hold"));

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isEqualTo(1),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(holdAtFill.get()).isEqualByComparingTo("15.07500000"),
        () -> assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("15.07500000"),
        () -> assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("49984.92500000"));
    verify(orderRepository).claimPending(fixture.order().getId());
    verify(orderFillService).recordPerpetualOrderHoldIncrease(
        fixture.account(),
        new BigDecimal("4.92348495"),
        fixture.order().getId());
  }

  @Test
  void pendingFillUsesCurrentLockedLeverageWithoutRewritingOrderAuditSnapshot() {
    Fixture fixture = stubCandidate(bundle("99", "101", "110", "110"), "10.15151505");
    fixture.setting().setLeverage(20);

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isEqualTo(1),
        () -> assertThat(fixture.order().getLeverage()).isEqualTo(10));
    ArgumentCaptor<AccountSymbolSettingEntity> authority =
        ArgumentCaptor.forClass(AccountSymbolSettingEntity.class);
    verify(perpetualOrderRiskService).evaluate(
        eq(PositionMode.ONE_WAY),
        authority.capture(),
        any(),
        eq(OrderSide.BUY),
        eq(PositionSide.BOTH),
        eq(false),
        eq(OrderType.STOP_MARKET),
        eq(new BigDecimal("1.0000")),
        eq(null),
        any(),
        eq(new BigDecimal("0.005")));
    assertThat(authority.getValue().getLeverage()).isEqualTo(20);
    verify(orderFillService).fillPerpetual(
        fixture.order(),
        fixture.account(),
        fixture.fill(),
        new BigDecimal("110"),
        20,
        "Pending Perpetual order hold");
  }

  @Test
  void internalCloseThatFreshlyReclassifiesToReversalStaysPendingBeforeClaim() {
    Fixture fixture = stubCandidate(bundle("99", "101", "90", "90"), "1.05500000");
    fixture.order().setSide(OrderSide.SELL);
    fixture.order().setMarginMode(MarginMode.ISOLATED);
    fixture.order().setReduceOnly(false);
    fixture.setting().setMarginMode(MarginMode.ISOLATED);
    com.fxplatform.trading.entity.PositionEntity parent = new com.fxplatform.trading.entity.PositionEntity();
    parent.setId(UUID.randomUUID());
    parent.setAccountId(fixture.account().getId());
    parent.setSymbol(SYMBOL);
    parent.setProductType(ProductType.LINEAR_PERP);
    parent.setPositionMode(PositionMode.ONE_WAY);
    parent.setPositionSide(PositionSide.BOTH);
    parent.setMarginMode(MarginMode.ISOLATED);
    parent.setSide(OrderSide.BUY);
    parent.setLots(new BigDecimal("0.5"));
    parent.setOpenPrice(new BigDecimal("100"));
    parent.setMarginHeld(new BigDecimal("5"));
    parent.setFundingPnl(BigDecimal.ZERO);
    parent.setLeverage(10);
    parent.setStatus(com.fxplatform.trading.enums.PositionStatus.OPEN);
    fixture.order().setParentPositionId(parent.getId());
    when(positionRepository.findOpenLinearPerpByAccountId(
        fixture.account().getId())).thenReturn(List.of(parent));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(parent));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(fixture.order()));
    org.mockito.Mockito.doReturn(risk(
            "1.05500000",
            10,
            MarginMode.ISOLATED,
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("4")))
        .when(perpetualOrderRiskService)
        .evaluate(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any());

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isZero(),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(fixture.order().getHoldAmount()).isEqualByComparingTo("1.05500000"));
    verify(orderRepository, never()).claimPending(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
    verify(orderEventService, never()).record(
        any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void internalCloseRechecksAggregateHoldsBeforeClaim() {
    Fixture fixture = stubCandidate(bundle("99", "101", "90", "90"), "6.00000000");
    fixture.order().setSide(OrderSide.SELL);
    fixture.order().setMarginMode(MarginMode.ISOLATED);
    fixture.order().setReduceOnly(true);
    fixture.order().setBaseQuantity(new BigDecimal("0.4"));
    fixture.order().setRemainingQuantity(new BigDecimal("0.4"));
    fixture.setting().setMarginMode(MarginMode.ISOLATED);
    com.fxplatform.trading.entity.PositionEntity parent = new com.fxplatform.trading.entity.PositionEntity();
    parent.setId(UUID.randomUUID());
    parent.setAccountId(fixture.account().getId());
    parent.setSymbol(SYMBOL);
    parent.setProductType(ProductType.LINEAR_PERP);
    parent.setPositionMode(PositionMode.ONE_WAY);
    parent.setPositionSide(PositionSide.BOTH);
    parent.setMarginMode(MarginMode.ISOLATED);
    parent.setSide(OrderSide.BUY);
    parent.setLots(BigDecimal.ONE);
    parent.setOpenPrice(new BigDecimal("100"));
    parent.setMarginHeld(new BigDecimal("10"));
    parent.setFundingPnl(BigDecimal.ZERO);
    parent.setLeverage(10);
    parent.setStatus(com.fxplatform.trading.enums.PositionStatus.OPEN);
    fixture.order().setParentPositionId(parent.getId());
    OrderEntity sibling = pendingStop(
        fixture.account().getId(), fixture.account().getUserId(), "4.00000000");
    sibling.setSide(OrderSide.SELL);
    sibling.setMarginMode(MarginMode.ISOLATED);
    sibling.setReduceOnly(true);
    sibling.setBaseQuantity(new BigDecimal("0.4"));
    sibling.setRemainingQuantity(new BigDecimal("0.4"));
    sibling.setParentPositionId(parent.getId());
    when(positionRepository.findOpenLinearPerpByAccountId(
        fixture.account().getId())).thenReturn(List.of(parent));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(parent));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(fixture.order(), sibling));
    org.mockito.Mockito.doReturn(risk(
            "0.42380000",
            10,
            MarginMode.ISOLATED,
            new BigDecimal("0.4"),
            BigDecimal.ZERO,
            new BigDecimal("9.45")))
        .when(perpetualOrderRiskService)
        .evaluate(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any());

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isZero(),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(fixture.order().getHoldAmount()).isEqualByComparingTo("6.00000000"));
    verify(orderRepository, never()).claimPending(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void freshAccountWideCrossLossKeepsOpeningOrderPendingBeforeClaim() {
    Fixture fixture = stubCandidate(bundle("99", "101", "100", "100"), "10.15151505");
    fixture.account().setBalance(new BigDecimal("20"));
    fixture.account().setEquity(new BigDecimal("20"));
    fixture.account().setUsedMargin(new BigDecimal("20.15151505"));
    fixture.account().setFreeMargin(new BigDecimal("100"));
    com.fxplatform.trading.entity.PositionEntity ethLoss = crossPosition(
        fixture.account().getId(), "ETHUSDT-PERP", "100", "10", 0L);
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountId(
        fixture.account().getId())).thenReturn(List.of(ethLoss));
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(ethLoss));
    org.mockito.Mockito.lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(fixture.order()));
    org.mockito.Mockito.lenient().when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(symbol("ETHUSDT-PERP")));
    org.mockito.Mockito.lenient().when(marketBundleResolver.resolvePerp(
        eq("ETHUSDT-PERP"), any())).thenReturn(bundle(
            "ETHUSDT-PERP", "49", "51", "50", "50"));

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isZero(),
        () -> assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(fixture.order().getHoldAmount()).isEqualByComparingTo("10.15151505"));
    verify(orderRepository, never()).claimPending(any());
    verify(accountRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void freshAccountWideCrossProfitIsAppliedAndSavedBeforePendingFill() {
    Fixture fixture = stubCandidate(bundle("99", "101", "100", "100"), "10.15151505");
    fixture.account().setBalance(new BigDecimal("20"));
    fixture.account().setEquity(new BigDecimal("20"));
    fixture.account().setUsedMargin(new BigDecimal("20.15151505"));
    fixture.account().setFreeMargin(BigDecimal.ZERO);
    com.fxplatform.trading.entity.PositionEntity ethProfit = crossPosition(
        fixture.account().getId(), "ETHUSDT-PERP", "100", "10", 3L);
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountId(
        fixture.account().getId())).thenReturn(List.of(ethProfit));
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(ethProfit));
    org.mockito.Mockito.lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(fixture.order()));
    org.mockito.Mockito.lenient().when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(symbol("ETHUSDT-PERP")));
    AtomicBoolean insideTransaction = new AtomicBoolean();
    org.mockito.Mockito.lenient().when(marketBundleResolver.resolvePerp(
        eq("ETHUSDT-PERP"), any())).thenAnswer(invocation -> {
          assertThat(insideTransaction.get()).isFalse();
          return bundle("ETHUSDT-PERP", "199", "201", "200", "200");
        });
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());

    int filled = service().executePendingOrders();

    assertAll(
        () -> assertThat(filled).isEqualTo(1),
        () -> assertThat(ethProfit.getMarkPrice()).isEqualByComparingTo("200.00000000"),
        () -> assertThat(ethProfit.getFloatingPnl()).isEqualByComparingTo("100.00000000"),
        () -> assertThat(ethProfit.getVersion()).isEqualTo(4L));
    verify(positionRepository).save(ethProfit);
    verify(accountRepository).save(fixture.account());
    verify(orderFillService).fillPerpetual(
        fixture.order(),
        fixture.account(),
        fixture.fill(),
        new BigDecimal("100"),
        10,
        "Pending Perpetual order hold");
  }

  @Test
  void accountRiskFingerprintDriftRetriesWholePendingAttemptBeforeClaim() {
    Fixture fixture = stubCandidate(bundle("99", "101", "100", "100"), "10.15151505");
    com.fxplatform.trading.entity.PositionEntity preparedV0 = crossPosition(
        fixture.account().getId(), "ETHUSDT-PERP", "100", "10", 0L);
    com.fxplatform.trading.entity.PositionEntity lockedV1 = crossPosition(
        fixture.account().getId(), "ETHUSDT-PERP", "100", "10", 1L);
    lockedV1.setId(preparedV0.getId());
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountId(
        fixture.account().getId())).thenReturn(List.of(preparedV0), List.of(lockedV1));
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(lockedV1), List.of(lockedV1));
    org.mockito.Mockito.lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        fixture.account().getId())).thenReturn(List.of(fixture.order()));
    org.mockito.Mockito.lenient().when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(symbol("ETHUSDT-PERP")));
    org.mockito.Mockito.lenient().when(marketBundleResolver.resolvePerp(
        eq("ETHUSDT-PERP"), any())).thenReturn(bundle(
            "ETHUSDT-PERP", "109", "111", "110", "110"));

    int filled = service().executePendingOrders();

    assertThat(filled).isEqualTo(1);
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(marketBundleResolver, times(2)).resolvePerp(eq("ETHUSDT-PERP"), any());
    verify(transactionExecutor, times(2)).execute(any());
    verify(orderRepository).claimPending(fixture.order().getId());
    verify(positionRepository).save(lockedV1);
  }

  private Fixture stubCandidate(PerpetualMarketBundle bundle, String hold) {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrderEntity order = pendingStop(accountId, userId, hold);
    TradingAccountEntity account = account(accountId, userId, hold);
    AccountSymbolSettingEntity setting = setting(accountId);
    FullFillResult fill = fill(bundle.ask(), order.getBaseQuantity());

    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    org.mockito.Mockito.lenient().when(accountRepository.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(account));
    org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(order.getId()))
        .thenReturn(Optional.of(order));
    org.mockito.Mockito.lenient().when(marketBundleResolver.resolvePerp(eq(SYMBOL), any()))
        .thenReturn(bundle);
    org.mockito.Mockito.lenient().when(symbolRepository.findBySymbol(SYMBOL))
        .thenReturn(Optional.of(symbol()));
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    org.mockito.Mockito.lenient().when(fullFillCoordinator.execute(
        any(FullFillRequest.class),
        any(ExecutableMarketSnapshot.class))).thenReturn(fill);
    org.mockito.Mockito.lenient().when(orderRepository.claimPending(order.getId())).thenReturn(1);
    org.mockito.Mockito.lenient().when(accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(accountId, SYMBOL))
        .thenReturn(Optional.of(setting));
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of());
    org.mockito.Mockito.lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(
        accountId)).thenReturn(List.of());
    org.mockito.Mockito.lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(
        accountId)).thenReturn(List.of(order));
    org.mockito.Mockito.lenient().when(perpetualOrderRiskService.evaluate(
        any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> risk(
            hold,
            ((AccountSymbolSettingEntity) invocation.getArgument(1)).getLeverage()));
    org.mockito.Mockito.lenient().when(orderFillService.fill(
        eq(order),
        eq(account),
        eq(fill),
        any(BigDecimal.class),
        anyString())).thenAnswer(invocation -> filled(order));
    org.mockito.Mockito.lenient().when(orderFillService.fillPerpetual(
        eq(order),
        eq(account),
        eq(fill),
        any(BigDecimal.class),
        anyInt(),
        anyString())).thenAnswer(invocation -> filled(order));
    return new Fixture(order, account, setting, bundle, fill);
  }

  private PendingOrderExecutionService service() {
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
        new PerpetualAccountRiskSnapshotService(
            positionRepository,
            symbolRepository,
            marketBundleResolver,
            fullFillCoordinator,
            new PerpetualRiskService(new PerpMarginCalculator())));
  }

  private static OrderEntity filled(OrderEntity order) {
    order.setStatus(OrderStatus.FILLED);
    order.setFilledQuantity(order.getBaseQuantity());
    order.setRemainingQuantity(BigDecimal.ZERO);
    order.setHoldAmount(BigDecimal.ZERO);
    return order;
  }

  private static OrderEntity pendingStop(UUID accountId, UUID userId, String hold) {
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
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("1.0000"));
    order.setQuantity(new BigDecimal("1.0000"));
    order.setOriginalQuantity(new BigDecimal("1.0000"));
    order.setBaseQuantity(new BigDecimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setLeverage(10);
    order.setReduceOnly(false);
    order.setTriggerPrice(new BigDecimal("100"));
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("1.0000"));
    order.setHoldAmount(new BigDecimal(hold));
    order.setHoldCurrency("USDT");
    order.setClientOrderId(UUID.randomUUID().toString());
    order.setIdempotencyKey(order.getClientOrderId());
    return order;
  }

  private static TradingAccountEntity account(UUID accountId, UUID userId, String hold) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000"));
    account.setEquity(new BigDecimal("50000"));
    account.setUsedMargin(new BigDecimal(hold));
    account.setFreeMargin(new BigDecimal("50000").subtract(new BigDecimal(hold)));
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
    return symbol(SYMBOL);
  }

  private static SymbolEntity symbol(String code) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(code);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    return symbol;
  }

  private static com.fxplatform.trading.entity.PositionEntity crossPosition(
      UUID accountId,
      String symbol,
      String entry,
      String marginHeld,
      long version
  ) {
    com.fxplatform.trading.entity.PositionEntity position =
        new com.fxplatform.trading.entity.PositionEntity();
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

  private static PerpetualMarketBundle bundle(
      String bid,
      String ask,
      String last,
      String mark
  ) {
    return bundle(SYMBOL, bid, ask, last, mark);
  }

  private static PerpetualMarketBundle bundle(
      String symbol,
      String bid,
      String ask,
      String last,
      String mark
  ) {
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal(bid),
        new BigDecimal(ask),
        new BigDecimal(last),
        new BigDecimal(mark),
        new BigDecimal(mark),
        null,
        List.of(),
        List.of(),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static FullFillResult fill(BigDecimal price, BigDecimal quantity) {
    BigDecimal fee = price.multiply(quantity).multiply(new BigDecimal("0.0005"));
    return new FullFillResult(
        price,
        NOW,
        quantity,
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        fee,
        "USDT",
        LiquidityRole.TAKER,
        BigDecimal.ZERO,
        MarketSourceMode.PUBLIC_EXTERNAL,
        "binance-usdm",
        "BTCUSDT",
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static PerpetualOrderRiskService.OrderRisk risk(String hold) {
    return risk(hold, 10);
  }

  private static PerpetualOrderRiskService.OrderRisk risk(String hold, int leverage) {
    return risk(
        hold,
        leverage,
        MarginMode.CROSS,
        BigDecimal.ZERO,
        BigDecimal.ONE,
        BigDecimal.ZERO);
  }

  private static PerpetualOrderRiskService.OrderRisk risk(
      String hold,
      int leverage,
      MarginMode marginMode,
      BigDecimal closing,
      BigDecimal opening,
      BigDecimal isolatedCapacity
  ) {
    BigDecimal holdAmount = new BigDecimal(hold);
    return new PerpetualOrderRiskService.OrderRisk(
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        marginMode,
        leverage,
        closing,
        opening,
        new BigDecimal("101.01010000"),
        new BigDecimal("101.01010000"),
        new BigDecimal("10.10101000"),
        new BigDecimal("0.05050505"),
        BigDecimal.ZERO.setScale(8),
        holdAmount,
        isolatedCapacity,
        "USDT");
  }

  private record Fixture(
      OrderEntity order,
      TradingAccountEntity account,
      AccountSymbolSettingEntity setting,
      PerpetualMarketBundle bundle,
      FullFillResult fill
  ) {
  }
}
