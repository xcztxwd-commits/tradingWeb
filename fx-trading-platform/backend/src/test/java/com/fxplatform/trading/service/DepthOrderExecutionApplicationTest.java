package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.TradeRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class DepthOrderExecutionApplicationTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

  private final TradeRepository tradeRepository = mock(TradeRepository.class);
  private final OrderFillService orderFillService = mock(OrderFillService.class);
  private final OrderEventService orderEventService = mock(OrderEventService.class);

  @Test
  void depthApplyLockedRequiresExistingTransaction() throws Exception {
    Method applyLocked = DepthOrderExecutionService.class.getMethod(
        "applyLocked",
        OrderEntity.class,
        OrderEntity.class,
        TradingAccountEntity.class,
        DepthOrderExecutionService.DepthMatchPlan.class,
        DepthOrderExecutionService.DepthHoldPlan.class,
        boolean.class);

    assertThat(applyLocked.getAnnotation(Transactional.class))
        .isNotNull()
        .extracting(Transactional::propagation)
        .isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void applyLockedUsesSnapshotOrdinalsAndExactH1InFillOrder() {
    DemoExecutionPolicy policy = depthPolicy(List.of(
        level("100", "1"),
        level("101", "1")));
    DepthOrderExecutionService service = service(policy);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("2"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot);
    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(
        decimal("201.10050000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, holds.initialHold());
    applyFillMutationAnswer();
    when(tradeRepository.findByOrderIdAndFillIdentity(eq(order.getId()), anyString()))
        .thenReturn(Optional.empty());

    DepthOrderExecutionService.DepthExecutionOutcome outcome = service.applyLocked(
        order, order, account, match, holds, false);

    assertThat(outcome.replayed()).isFalse();
    assertThat(outcome.newlyAppliedFillCount()).isEqualTo(2);
    assertThat(outcome.remainingQuantity()).isEqualByComparingTo("0");
    assertThat(outcome.remainingHold()).isEqualByComparingTo("0");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);

    InOrder mutationOrder = inOrder(orderFillService, orderEventService);
    mutationOrder.verify(orderFillService).applyFill(
        eq(order), eq(order), eq(account),
        eq(match.matchingResult().fills().get(0)),
        eq(DemoFillIdentity.forSnapshot(order.getId(), snapshot, 0)),
        eq(snapshot), eq(policy), eq(decimal("101.05050000")), eq(false));
    mutationOrder.verify(orderEventService).record(
        eq(order.getId()), eq("ORDER_PARTIALLY_FILLED"),
        eq(OrderStatus.PENDING), eq(OrderStatus.PARTIALLY_FILLED),
        isNull(), anyString());
    mutationOrder.verify(orderFillService).applyFill(
        eq(order), eq(order), eq(account),
        eq(match.matchingResult().fills().get(1)),
        eq(DemoFillIdentity.forSnapshot(order.getId(), snapshot, 1)),
        eq(snapshot), eq(policy),
        argThat(hold -> hold.compareTo(BigDecimal.ZERO) == 0), eq(false));
    mutationOrder.verify(orderEventService).record(
        eq(order.getId()), eq("ORDER_FILLED"),
        eq(OrderStatus.PARTIALLY_FILLED), eq(OrderStatus.FILLED),
        isNull(), anyString());

    ArgumentCaptor<String> identities = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<BigDecimal> holdAfter = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<Boolean> terminal = ArgumentCaptor.forClass(Boolean.class);
    verify(orderFillService, times(2)).applyFill(
        eq(order),
        eq(order),
        eq(account),
        any(DemoMatchFill.class),
        identities.capture(),
        eq(snapshot),
        eq(policy),
        holdAfter.capture(),
        terminal.capture());
    assertThat(identities.getAllValues()).containsExactly(
        DemoFillIdentity.forSnapshot(order.getId(), snapshot, 0),
        DemoFillIdentity.forSnapshot(order.getId(), snapshot, 1));
    assertThat(holdAfter.getAllValues())
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(decimal("101.05050000"), decimal("0"));
    assertThat(terminal.getAllValues()).containsExactly(false, false);

    ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<OrderStatus> fromStatuses = ArgumentCaptor.forClass(OrderStatus.class);
    ArgumentCaptor<OrderStatus> toStatuses = ArgumentCaptor.forClass(OrderStatus.class);
    verify(orderEventService, times(2)).record(
        eq(order.getId()),
        eventTypes.capture(),
        fromStatuses.capture(),
        toStatuses.capture(),
        isNull(),
        anyString());
    assertThat(eventTypes.getAllValues())
        .containsExactly("ORDER_PARTIALLY_FILLED", "ORDER_FILLED");
    assertThat(fromStatuses.getAllValues())
        .containsExactly(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
    assertThat(toStatuses.getAllValues())
        .containsExactly(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED);
  }

  @Test
  void applyLockedMarksOnlyTheLastIocFillTerminalAndPreservesPositiveTailH1() {
    DemoExecutionPolicy policy = depthPolicy(List.of(
        level("100", "1"),
        level("101", "1")));
    DepthOrderExecutionService service = service(policy);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.IOC,
        decimal("2.5"),
        decimal("101"),
        false,
        LiquidityRole.TAKER,
        snapshot);
    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(
        decimal("251.62575000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, holds.initialHold());
    applyFillMutationAnswer();
    when(tradeRepository.findByOrderIdAndFillIdentity(eq(order.getId()), anyString()))
        .thenReturn(Optional.empty());

    DepthOrderExecutionService.DepthExecutionOutcome outcome = service.applyLocked(
        order, order, account, match, holds, true);

    assertThat(outcome.newlyAppliedFillCount()).isEqualTo(2);
    assertThat(outcome.remainingQuantity()).isEqualByComparingTo("0.5");
    assertThat(outcome.remainingHold()).isEqualByComparingTo("50.52525000");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    ArgumentCaptor<BigDecimal> holdAfter = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<Boolean> terminal = ArgumentCaptor.forClass(Boolean.class);
    verify(orderFillService, times(2)).applyFill(
        eq(order), eq(order), eq(account), any(DemoMatchFill.class), anyString(),
        eq(snapshot), eq(policy), holdAfter.capture(), terminal.capture());
    assertThat(holdAfter.getAllValues())
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(decimal("151.57575000"), decimal("50.52525000"));
    assertThat(terminal.getAllValues()).containsExactly(false, true);
  }

  @Test
  void applyLockedReplaySkipsEveryFillAndLifecycleEvent() {
    DemoExecutionPolicy policy = depthPolicy(List.of(level("100", "1")));
    DepthOrderExecutionService service = service(policy);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot);
    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(
        decimal("100.05000000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, holds.initialHold());
    String ordinalZero = DemoFillIdentity.forSnapshot(order.getId(), snapshot, 0);
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), ordinalZero))
        .thenReturn(Optional.of(new TradeEntity()));

    DepthOrderExecutionService.DepthExecutionOutcome outcome = service.applyLocked(
        order, order, account, match, holds, false);

    assertThat(outcome.replayed()).isTrue();
    assertThat(outcome.newlyAppliedFillCount()).isZero();
    assertThat(outcome.remainingQuantity()).isEqualByComparingTo("1");
    assertThat(outcome.remainingHold()).isEqualByComparingTo("100.05");
    verify(tradeRepository).findByOrderIdAndFillIdentity(order.getId(), ordinalZero);
    verifyNoInteractions(orderFillService, orderEventService);
  }

  @Test
  void applyLockedRealReplayWinsOverExpiredPlanChangedPolicyAndMutatedOrderState() {
    DemoExecutionPolicy originalPolicy = depthPolicy(List.of(level("100", "1")));
    DepthOrderExecutionService planningService = service(originalPolicy);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService.DepthMatchPlan match = planningService.prepare(
        originalPolicy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot);
    DepthOrderExecutionService.DepthHoldPlan originalHolds = planningService.planSpot(
        decimal("100.05000000"), match);
    TradingAccountEntity account = account();
    OrderEntity alreadyApplied = order(account.getId(), match, originalHolds.initialHold());
    alreadyApplied.setStatus(OrderStatus.PARTIALLY_FILLED);
    alreadyApplied.setFilledQuantity(decimal("0.75"));
    alreadyApplied.setRemainingQuantity(decimal("0.25"));
    alreadyApplied.setHoldAmount(decimal("25.01250000"));
    String ordinalZero = DemoFillIdentity.forSnapshot(
        alreadyApplied.getId(), snapshot, 0);
    when(tradeRepository.findByOrderIdAndFillIdentity(alreadyApplied.getId(), ordinalZero))
        .thenReturn(Optional.of(new TradeEntity()));
    DemoExecutionPolicy replacementPolicy = depthPolicy(List.of(level("110", "1")));
    DepthOrderExecutionService replayService = new DepthOrderExecutionService(
        () -> replacementPolicy,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC));

    DepthOrderExecutionService.DepthExecutionOutcome outcome = replayService.applyLocked(
        alreadyApplied, alreadyApplied, account, match, originalHolds, false);

    assertThat(outcome.replayed()).isTrue();
    assertThat(outcome.newlyAppliedFillCount()).isZero();
    assertThat(outcome.remainingQuantity()).isEqualByComparingTo("0.25");
    assertThat(outcome.remainingHold()).isEqualByComparingTo("25.0125");
    verifyNoInteractions(orderFillService, orderEventService);
  }

  @Test
  void applyLockedTreatsPolicyRotationAsRetryableStaleBeforeMutation() {
    DemoExecutionPolicy originalPolicy = depthPolicy(List.of(level("100", "1")));
    AtomicReference<DemoExecutionPolicy> current = new AtomicReference<>(originalPolicy);
    DepthOrderExecutionService service = new DepthOrderExecutionService(
        current::get,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        originalPolicy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot());
    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(
        decimal("100.05000000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, holds.initialHold());
    current.set(depthPolicy(List.of(level("101", "1"))));

    assertThatThrownBy(() -> service.applyLocked(
        order, order, account, match, holds, false))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.MARKET_DATA_STALE));
    verifyNoInteractions(orderFillService, orderEventService);
  }

  @Test
  void applyLockedRejectsUnsafeIntermediateAndTerminalH1BeforeFirstMutation() {
    DemoExecutionPolicy policy = depthPolicy(List.of(
        level("100", "1"),
        level("101", "1")));
    DepthOrderExecutionService service = service(policy);
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("2"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot());
    DepthOrderExecutionService.DepthHoldPlan valid = service.planSpot(
        decimal("201.10050000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, valid.initialHold());
    DepthOrderExecutionService.DepthHoldPlan zeroIntermediate =
        new DepthOrderExecutionService.DepthHoldPlan(
            valid.initialHold(), List.of(BigDecimal.ZERO, BigDecimal.ZERO));

    assertThatThrownBy(() -> service.applyLocked(
        order, order, account, match, zeroIntermediate, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("H1");
    verifyNoInteractions(orderFillService, orderEventService);

    DepthOrderExecutionService.DepthHoldPlan positiveTerminal =
        new DepthOrderExecutionService.DepthHoldPlan(
            valid.initialHold(), List.of(decimal("101.05050000"), BigDecimal.ONE));
    assertThatThrownBy(() -> service.applyLocked(
        order, order, account, match, positiveTerminal, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("H1");
    verifyNoInteractions(orderFillService, orderEventService);
  }

  @Test
  void applyLockedRejectsZeroTailH1ForIocRemainderBeforeFirstMutation() {
    DemoExecutionPolicy policy = depthPolicy(List.of(level("100", "1")));
    DepthOrderExecutionService service = service(policy);
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.IOC,
        decimal("1.5"),
        decimal("100"),
        false,
        LiquidityRole.TAKER,
        snapshot());
    DepthOrderExecutionService.DepthHoldPlan valid = service.planSpot(
        decimal("150.07500000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, valid.initialHold());
    DepthOrderExecutionService.DepthHoldPlan zeroTail =
        new DepthOrderExecutionService.DepthHoldPlan(
            valid.initialHold(), List.of(BigDecimal.ZERO));

    assertThatThrownBy(() -> service.applyLocked(
        order, order, account, match, zeroTail, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("H1");
    verifyNoInteractions(orderFillService, orderEventService);
  }

  @Test
  void applyLockedRejectsLaterOrdinalWithoutOrdinalZeroBeforeMutation() {
    DemoExecutionPolicy policy = depthPolicy(List.of(
        level("100", "1"),
        level("101", "1")));
    DepthOrderExecutionService service = service(policy);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("2"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot);
    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(
        decimal("201.10050000"), match);
    TradingAccountEntity account = account();
    OrderEntity order = order(account.getId(), match, holds.initialHold());
    String ordinalZero = DemoFillIdentity.forSnapshot(order.getId(), snapshot, 0);
    String ordinalOne = DemoFillIdentity.forSnapshot(order.getId(), snapshot, 1);
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), ordinalZero))
        .thenReturn(Optional.empty());
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), ordinalOne))
        .thenReturn(Optional.of(new TradeEntity()));

    assertThatThrownBy(() -> service.applyLocked(
        order, order, account, match, holds, false))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.FILL_IDENTITY_CONFLICT));
    verifyNoInteractions(orderFillService, orderEventService);
  }

  @Test
  void applyLockedRejectsOrderAndHoldScheduleMismatchBeforeFirstMutation() {
    DemoExecutionPolicy policy = depthPolicy(List.of(level("100", "1")));
    DepthOrderExecutionService service = service(policy);
    DepthOrderExecutionService.DepthMatchPlan match = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot());
    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(
        decimal("100.05000000"), match);
    TradingAccountEntity account = account();
    OrderEntity wrongRemaining = order(account.getId(), match, holds.initialHold());
    wrongRemaining.setRemainingQuantity(decimal("2"));

    assertThatThrownBy(() -> service.applyLocked(
        wrongRemaining, wrongRemaining, account, match, holds, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remaining");
    verifyNoInteractions(orderFillService, orderEventService);

    OrderEntity correctOrder = order(account.getId(), match, holds.initialHold());
    correctOrder.setHoldAmount(decimal("100"));
    assertThatThrownBy(() -> service.applyLocked(
        correctOrder, correctOrder, account, match, holds, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initial hold");
    verifyNoInteractions(orderFillService, orderEventService);

    correctOrder.setHoldAmount(holds.initialHold());
    DepthOrderExecutionService.DepthHoldPlan wrongSchedule =
        new DepthOrderExecutionService.DepthHoldPlan(holds.initialHold(), List.of());
    assertThatThrownBy(() -> service.applyLocked(
        correctOrder, correctOrder, account, match, wrongSchedule, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schedule");
    verifyNoInteractions(orderFillService, orderEventService);
  }

  private DepthOrderExecutionService service(DemoExecutionPolicy policy) {
    return new DepthOrderExecutionService(
        () -> policy,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void applyFillMutationAnswer() {
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
          BigDecimal nextHold = invocation.getArgument(7);
          BigDecimal oldFilled = order.getFilledQuantity() == null
              ? BigDecimal.ZERO
              : order.getFilledQuantity();
          BigDecimal nextFilled = oldFilled.add(fill.quantity());
          BigDecimal nextRemaining = order.getRemainingQuantity().subtract(fill.quantity());
          order.setFilledQuantity(nextFilled);
          order.setRemainingQuantity(nextRemaining);
          order.setStatus(nextRemaining.signum() == 0
              ? OrderStatus.FILLED
              : OrderStatus.PARTIALLY_FILLED);
          holdOwner.setHoldAmount(nextHold);
          return order;
        });
  }

  private static TradingAccountEntity account() {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    return account;
  }

  private static OrderEntity order(
      UUID accountId,
      DepthOrderExecutionService.DepthMatchPlan match,
      BigDecimal hold
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol(match.symbol());
    order.setProductType(match.productType());
    order.setSide(match.side());
    order.setOrderType(match.executableType());
    order.setStatus(OrderStatus.PENDING);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(match.remainingBaseQuantity());
    order.setHoldAmount(hold);
    return order;
  }

  private static DemoExecutionPolicy depthPolicy(List<DemoBookLevel> asks) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.0002"),
        decimal("0.0005"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(),
        asks,
        null);
  }

  private static DemoBookLevel level(String price, String quantity) {
    return new DemoBookLevel(decimal(price), decimal(quantity));
  }

  private static ExecutableMarketSnapshot snapshot() {
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "PUBLIC",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"),
        decimal("100"),
        decimal("99.5"),
        null,
        null,
        NOW,
        NOW.plusSeconds(60));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
