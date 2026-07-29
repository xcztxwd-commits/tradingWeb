package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketTimeAuthority;
import com.fxplatform.execution.WallClockExecutableMarketTimeAuthority;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DepthOrderExecutionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

  private final TradeRepository tradeRepository = mock(TradeRepository.class);
  private final OrderFillService orderFillService = mock(OrderFillService.class);
  private final OrderEventService orderEventService = mock(OrderEventService.class);

  @Test
  void springCanSelectTheProductionConstructorWhenTheClockTestConstructorAlsoExists() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext()) {
      context.registerBean(
          DemoExecutionPolicyProvider.class,
          () -> mock(DemoExecutionPolicyProvider.class));
      context.registerBean(TradeRepository.class, () -> tradeRepository);
      context.registerBean(OrderFillService.class, () -> orderFillService);
      context.registerBean(OrderEventService.class, () -> orderEventService);
      context.registerBean(
          ExecutableMarketTimeAuthority.class,
          () -> new WallClockExecutableMarketTimeAuthority(
              Clock.fixed(NOW, ZoneOffset.UTC)));
      context.register(DepthOrderExecutionService.class);

      context.refresh();

      assertThat(context.getBean(DepthOrderExecutionService.class)).isNotNull();
    }
  }

  @Test
  void simplePolicyBypassesDepthAndCurrentPolicyPreservesProviderInstance() {
    DemoExecutionPolicy policy = simplePolicy();
    DepthOrderExecutionService service = service(policy);

    assertThat(service.currentPolicy()).isSameAs(policy);
    assertThat(service.isDepth(policy)).isFalse();
  }

  @Test
  void prepareRetainsExactPolicyAndSnapshotAndMatchesMultipleLevels() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("101", "2"), level("100", "1"), level("102", "5")),
        null);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan plan = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("2.5"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot);

    assertThat(service.isDepth(policy)).isTrue();
    assertThat(plan.policy()).isSameAs(policy);
    assertThat(plan.snapshot()).isSameAs(snapshot);
    assertThat(plan.matchingResult().fills())
        .extracting(DemoMatchFill::price)
        .containsExactly(decimal("100"), decimal("101"));
    assertThat(plan.matchingResult().fills())
        .extracting(DemoMatchFill::quantity)
        .containsExactly(decimal("1"), decimal("1.5"));
    assertThat(plan.matchingResult().terminalOrWorkingStatus()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void prepareRejectsMarketOrderThatClaimsMakerLiquidity() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);

    assertThatThrownBy(() -> service(policy).prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.MAKER,
        snapshot()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TAKER");
  }

  @Test
  void prepareRejectsADepthPolicyThatIsNotTheCurrentProviderInstance() {
    DemoExecutionPolicy current = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);
    DemoExecutionPolicy callerSupplied = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);

    assertThatThrownBy(() -> service(current).prepare(
        callerSupplied,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current policy");
  }

  @Test
  void prepareBindsTheCompleteMatchingRequestToThePlan() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "2")),
        null);
    ExecutableMarketSnapshot snapshot = snapshot();
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan plan = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.IOC,
        decimal("1.25"),
        decimal("101"),
        false,
        LiquidityRole.TAKER,
        snapshot);

    assertThat(plan.policy()).isSameAs(policy);
    assertThat(plan.snapshot()).isSameAs(snapshot);
    assertThat(plan.symbol()).isEqualTo("BTCUSDT");
    assertThat(plan.productType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(plan.side()).isEqualTo(OrderSide.BUY);
    assertThat(plan.executableType()).isEqualTo(OrderType.LIMIT);
    assertThat(plan.timeInForce()).isEqualTo(TimeInForce.IOC);
    assertThat(plan.remainingBaseQuantity()).isEqualByComparingTo("1.25");
    assertThat(plan.limitPrice()).isEqualByComparingTo("101");
    assertThat(plan.placementPostOnly()).isFalse();
    assertThat(plan.role()).isEqualTo(LiquidityRole.TAKER);
  }

  @Test
  void prepareRejectsSnapshotWhoseSymbolDoesNotMatchTheRequest() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    ExecutableMarketSnapshot valid = snapshot();
    ExecutableMarketSnapshot wrongSymbol = new ExecutableMarketSnapshot(
        "ETHUSDT",
        ProductType.CRYPTO_SPOT,
        valid.providerCode(),
        "ETHUSDT",
        valid.sourceMode(),
        valid.bid(),
        valid.ask(),
        valid.last(),
        null,
        null,
        valid.asOf(),
        valid.expiresAt());

    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE", () -> service(policy).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, wrongSymbol));
  }

  @Test
  void prepareRejectsSnapshotWhoseProductDoesNotMatchTheCanonicalSymbol() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    ExecutableMarketSnapshot valid = snapshot();
    ExecutableMarketSnapshot wrongProduct = new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.LINEAR_PERP,
        valid.providerCode(),
        valid.providerSymbol(),
        valid.sourceMode(),
        valid.bid(),
        valid.ask(),
        valid.last(),
        decimal("100"),
        decimal("100"),
        valid.asOf(),
        valid.expiresAt());

    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE", () -> service(policy).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, wrongProduct));
  }

  @Test
  void prepareRejectsIncompleteProviderMetadataBeforeMatching() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    ExecutableMarketSnapshot valid = snapshot();
    ExecutableMarketSnapshot missingProvider = new ExecutableMarketSnapshot(
        valid.platformSymbol(),
        valid.productType(),
        " ",
        valid.providerSymbol(),
        valid.sourceMode(),
        valid.bid(),
        valid.ask(),
        valid.last(),
        valid.mark(),
        valid.index(),
        valid.asOf(),
        valid.expiresAt());

    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE", () -> service(policy).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, missingProvider));
  }

  @Test
  void prepareRejectsExpiredAndInvalidSnapshotWindowsBeforeMatching() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ExecutableMarketSnapshot valid = snapshot();
    ExecutableMarketSnapshot expired = snapshotWithWindow(
        valid, NOW.minusSeconds(120), NOW.minusSeconds(60));
    ExecutableMarketSnapshot emptyWindow = snapshotWithWindow(valid, NOW, NOW);

    assertBusinessCode("MARKET_DATA_STALE", () -> service(policy, clock).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, expired));
    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE", () -> service(policy, clock).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, emptyWindow));
  }

  @Test
  void requireFreshRejectsPlanThatExpiresAfterPrepareBeforeAuthorityLock() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    MutableClock clock = new MutableClock(NOW);
    ExecutableMarketSnapshot expiringSnapshot = snapshotWithWindow(
        snapshot(), NOW.minusSeconds(1), NOW.plusSeconds(1));
    DepthOrderExecutionService service = service(policy, clock);

    DepthOrderExecutionService.DepthMatchPlan plan = service.prepare(
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
        expiringSnapshot);
    clock.advance(Duration.ofSeconds(1));

    assertBusinessCode("MARKET_DATA_STALE", () -> service.requireFresh(plan));
    assertBusinessCode("MARKET_DATA_STALE", () -> service.planSpot(decimal("200"), plan));
  }

  @Test
  void wallClockDepthAuthorityRejectsValidationMarkedFutureSnapshots() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    Instant virtualTime = Instant.parse("2030-01-01T00:00:01Z");
    ExecutableMarketSnapshot validation = snapshotWithAuthority(
        snapshot(),
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        virtualTime.plusSeconds(60));

    assertBusinessCode("MARKET_DATA_STALE", () -> service(policy).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, validation));
  }

  @Test
  void injectedAuthorityOwnsHistoricalDepthPrepareAndFinalFreshness() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    Instant virtualTime = Instant.parse("2020-01-01T00:00:01Z");
    ExecutableMarketSnapshot validation = snapshotWithAuthority(
        snapshot(),
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        virtualTime.plusSeconds(60));
    ExecutableMarketTimeAuthority authority = mock(ExecutableMarketTimeAuthority.class);
    when(authority.currentTime(validation)).thenReturn(virtualTime);
    DepthOrderExecutionService service = new DepthOrderExecutionService(
        () -> policy,
        tradeRepository,
        orderFillService,
        orderEventService,
        authority);

    DepthOrderExecutionService.DepthMatchPlan executable = service.prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, validation);
    DepthOrderExecutionService.DepthMatchPlan pending = service.preparePendingStop(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.STOP_MARKET, TimeInForce.GTC,
        decimal("1"), null, validation);

    service.requireFresh(executable);
    service.requireFresh(pending);
  }

  @Test
  void prepareRejectsFillQuantityThatCannotBeRepresentedByNumeric12Scale4() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        decimal("0.00015"));

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
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
        snapshot()));
  }

  @Test
  void prepareRejectsFillPriceThatCannotBeRepresentedByNumeric24Scale10() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100.00000000001", "1")),
        null);

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
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
        snapshot()));
  }

  @Test
  void prepareRejectsUnfilledBoundQuantityThatCannotBeRepresentedByNumeric12Scale4() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("1.00001"),
        decimal("99"),
        false,
        LiquidityRole.TAKER,
        snapshot()));
  }

  @Test
  void prepareRejectsExtremePositiveExponentWithoutMaterializingItsZeros() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);

    assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
            policy,
            "BTCUSDT",
            ProductType.CRYPTO_SPOT,
            OrderSide.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            decimal("1E+100000000"),
            decimal("99"),
            false,
            LiquidityRole.TAKER,
            snapshot())));
  }

  @Test
  void prepareRejectsMinimumScaleEvenWhenTrailingZeroNormalizationWouldOverflow() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);
    BigDecimal minimumScale = new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE);

    assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
            policy,
            "BTCUSDT",
            ProductType.CRYPTO_SPOT,
            OrderSide.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            minimumScale,
            decimal("99"),
            false,
            LiquidityRole.TAKER,
            snapshot())));
  }

  @Test
  void prepareRejectsUnfilledBoundLimitPriceThatCannotBeRepresentedByNumeric24Scale10() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("1"),
        decimal("99.00000000001"),
        false,
        LiquidityRole.TAKER,
        snapshot()));
  }

  @Test
  void prepareRejectsSnapshotPriceOutsideNumeric24Scale10() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);
    ExecutableMarketSnapshot source = snapshot();
    ExecutableMarketSnapshot invalidSnapshot = new ExecutableMarketSnapshot(
        source.platformSymbol(),
        source.productType(),
        source.providerCode(),
        source.providerSymbol(),
        source.sourceMode(),
        decimal("100000000000000"),
        source.ask(),
        source.last(),
        source.mark(),
        source.index(),
        source.asOf(),
        source.expiresAt());

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
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
        invalidSnapshot));
  }

  @Test
  void prepareRejectsAProjectedFillWhoseGrossExceedsNumeric24Scale8() {
    String maximumQuantity = "99999999.9999";
    String maximumPrice = "99999999999999.9999999999";
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level(maximumPrice, maximumQuantity)),
        null);

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).prepare(
        policy,
        "BTCUSDT",
        ProductType.LINEAR_PERP,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal(maximumQuantity),
        null,
        false,
        LiquidityRole.TAKER,
        perpetualSnapshot()));
  }

  @Test
  void prepareRejectsSymbolThatDoesNotMatchItsSnapshot() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);

    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE",
        () -> service(policy).prepare(
            policy, "ETHUSDT", ProductType.CRYPTO_SPOT,
            OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
            decimal("1"), null, false, LiquidityRole.TAKER, snapshot()));
  }

  @Test
  void depthMatchPlanRejectsMatchingWhoseFeeDoesNotMatchItsBoundPolicy() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(), List.of(level("100", "1")), null);
    DepthOrderExecutionService.DepthMatchPlan coherent = service(policy).prepare(
        policy, "BTCUSDT", ProductType.CRYPTO_SPOT,
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC,
        decimal("1"), null, false, LiquidityRole.TAKER, snapshot());
    DemoExecutionPolicy mismatchedFeePolicy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        policy.makerFeeRate(),
        decimal("0.0009"),
        policy.liquidationFeeRate(),
        policy.slippageRate(),
        policy.bids(),
        policy.asks(),
        policy.maxFillQuantityPerTick());

    DepthOrderExecutionService.DepthMatchPlan tampered = spy(coherent);
    when(tampered.policy()).thenReturn(mismatchedFeePolicy);

    assertThatThrownBy(() -> service(policy).planSpot(decimal("100.05000000"), tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageMatching("(?i).*(fee|role).*");
  }

  @Test
  void depthMatchPlanHasNoPublicConstructorThatCanForgeMatchingResults() {
    assertThat(DepthOrderExecutionService.DepthMatchPlan.class.getConstructors()).isEmpty();
  }

  @Test
  void fokRejectsBeforeMutationWhenEligibleDepthIsInsufficient() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1"), level("101", "2")),
        null);
    DepthOrderExecutionService service = service(policy);

    assertThatThrownBy(() -> service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.FOK,
        decimal("4"),
        decimal("101"),
        false,
        LiquidityRole.TAKER,
        snapshot()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));
  }

  @Test
  void fokRejectsWhenPerTickCapPreventsAFullFill() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "5")),
        decimal("3"));
    DepthOrderExecutionService service = service(policy);

    assertThatThrownBy(() -> service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.FOK,
        decimal("4"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FOK_NOT_FILLABLE"));
  }

  @Test
  void postOnlyPlacementRejectsWhenDepthWouldTakeLiquidity() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);
    DepthOrderExecutionService service = service(policy);

    assertThatThrownBy(() -> service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("1"),
        decimal("101"),
        true,
        LiquidityRole.TAKER,
        snapshot()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("POST_ONLY_WOULD_TAKE"));
  }

  @Test
  void nonMarketablePostOnlyPlacementProducesPendingPlanWithoutFills() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("102", "1")),
        null);
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan plan = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("1"),
        decimal("101"),
        true,
        LiquidityRole.TAKER,
        snapshot());

    assertThat(plan.matchingResult().fills()).isEmpty();
    assertThat(plan.matchingResult().terminalOrWorkingStatus()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void restingPostOnlyRemainderPassesPlacementFlagFalseAndMatchesAsMaker() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(),
        List.of(level("100", "1")),
        null);
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan plan = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("1"),
        decimal("101"),
        false,
        LiquidityRole.MAKER,
        snapshot());

    assertThat(plan.matchingResult().fills()).singleElement().satisfies(fill -> {
      assertThat(fill.liquidityRole()).isEqualTo(LiquidityRole.MAKER);
      assertThat(fill.feeRate()).isEqualByComparingTo(policy.makerFeeRate());
    });
    assertThat(plan.matchingResult().terminalOrWorkingStatus()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void tickAlreadyAppliedUsesSnapshotOrdinalZeroAsWholeTickGate() {
    DemoExecutionPolicy policy = depthPolicy(List.of(), List.of(level("100", "1")), null);
    DepthOrderExecutionService service = service(policy);
    OrderEntity order = new OrderEntity();
    UUID orderId = UUID.randomUUID();
    order.setId(orderId);
    ExecutableMarketSnapshot snapshot = snapshot();
    String ordinalZero = DemoFillIdentity.forSnapshot(orderId, snapshot, 0);
    when(tradeRepository.findByOrderIdAndFillIdentity(orderId, ordinalZero))
        .thenReturn(Optional.empty(), Optional.of(new TradeEntity()));

    assertThat(service.tickAlreadyApplied(order, snapshot)).isFalse();
    assertThat(service.tickAlreadyApplied(order, snapshot)).isTrue();
    verify(tradeRepository, org.mockito.Mockito.times(2))
        .findByOrderIdAndFillIdentity(orderId, ordinalZero);
  }

  private DepthOrderExecutionService service(DemoExecutionPolicy policy) {
    return service(policy, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private DepthOrderExecutionService service(DemoExecutionPolicy policy, Clock clock) {
    DemoExecutionPolicyProvider provider = () -> policy;
    return new DepthOrderExecutionService(
        provider,
        tradeRepository,
        orderFillService,
        orderEventService,
        clock);
  }

  private static DemoExecutionPolicy simplePolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        decimal("0.0002"),
        decimal("0.0005"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(),
        List.of(),
        null);
  }

  private static DemoExecutionPolicy depthPolicy(
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks,
      BigDecimal maxFillQuantityPerTick
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.0002"),
        decimal("0.0005"),
        decimal("0.001"),
        decimal("0.0001"),
        bids,
        asks,
        maxFillQuantityPerTick);
  }

  private static DemoBookLevel level(String price, String quantity) {
    return new DemoBookLevel(decimal(price), decimal(quantity));
  }

  private static ExecutableMarketSnapshot snapshot() {
    Instant asOf = NOW;
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
        asOf,
        asOf.plusSeconds(60));
  }

  private static ExecutableMarketSnapshot perpetualSnapshot() {
    Instant asOf = NOW;
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.LINEAR_PERP,
        "PUBLIC",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"),
        decimal("100"),
        decimal("99.5"),
        decimal("99.5"),
        decimal("99.5"),
        asOf,
        asOf.plusSeconds(60));
  }

  private static ExecutableMarketSnapshot snapshotWithWindow(
      ExecutableMarketSnapshot source,
      Instant asOf,
      Instant expiresAt
  ) {
    return new ExecutableMarketSnapshot(
        source.platformSymbol(),
        source.productType(),
        source.providerCode(),
        source.providerSymbol(),
        source.sourceMode(),
        source.bid(),
        source.ask(),
        source.last(),
        source.mark(),
        source.index(),
        asOf,
        expiresAt);
  }

  private static ExecutableMarketSnapshot snapshotWithAuthority(
      ExecutableMarketSnapshot source,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return new ExecutableMarketSnapshot(
        source.platformSymbol(),
        source.productType(),
        providerCode,
        source.providerSymbol(),
        sourceMode,
        source.bid(),
        source.ask(),
        source.last(),
        source.mark(),
        source.index(),
        asOf,
        expiresAt);
  }

  private static void assertBusinessCode(
      String code,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action
  ) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private static final class MutableClock extends Clock {

    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new UnsupportedOperationException("Test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
