package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.DemoMatchingResult;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class DepthSpotExecutionPlanningTest {

  @Test
  void marketBuyUsesPerLevelRoundingAndChoosesTheLargestStorageStepWithinBudget() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1", "101@2"));
    DepthOrderExecutionService service = service(policy);

    var conversion = service.convertSpotMarketBuy(
        decimal("250.00000000"), decimal("0.0001"), policy);

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("2.4839");
    assertThat(conversion.baseQuantity().remainder(decimal("0.0001"))).isZero();
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("249.99883695");
    assertThat(conversion.projectedQuoteSpend()).isLessThanOrEqualTo(decimal("250.00000000"));
    assertThat(projectedBuySpend(decimal("2.4840"), policy.asks(), policy.takerFeeRate()))
        .isEqualByComparingTo("250.00894200")
        .isGreaterThan(decimal("250.00000000"));
  }

  @Test
  void marketBuyDoesNotMergeSeparateLevelRoundingAtAnExactQuoteBoundary() {
    DemoExecutionPolicy policy = depthPolicy(levels(
        "100.00005@0.0001",
        "100.00005@0.0001"));
    DepthOrderExecutionService service = service(policy);

    var conversion = service.convertSpotMarketBuy(
        decimal("0.02001001"), decimal("0.0001"), policy);

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0001");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("0.01000501");
    assertThat(projectedBuySpend(decimal("0.0002"), policy.asks(), policy.takerFeeRate()))
        .isEqualByComparingTo("0.02001002")
        .isGreaterThan(decimal("0.02001001"));
  }

  @Test
  void marketBuyConversionUsesFullBookAndLeavesThePerTickCapForMatching() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@5"), decimal("1"));
    DepthOrderExecutionService service = service(policy);

    var conversion = service.convertSpotMarketBuy(
        decimal("300.15000000"), decimal("0.0001"), policy);

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("3.0000");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("300.15000000");
  }

  @Test
  void marketBuyRejectsPerTickCapThatIsNotStorageStepAligned() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"), decimal("0.0003"));

    assertThatThrownBy(() -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.0002"), policy))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_INSTRUMENT_RULES"))
        .hasMessageContaining("step");
  }

  @Test
  void marketBuyBudgetAccountsForPerTickCapRoundingOfTheSameLevel() {
    DemoExecutionPolicy policy = depthPolicy(
        levels("100.00005@0.0002"), decimal("0.0001"));
    DepthOrderExecutionService service = service(policy);

    var conversion = service.convertSpotMarketBuy(
        decimal("0.02001001"), decimal("0.0001"), policy);

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0001");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo("0.01000501");
  }

  @Test
  void marketBuyRejectsAnExtremeQuoteBudgetBeforeConversionArithmetic() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));

    assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        assertBusinessCode("ORDER_HOLD_INVALID", () -> service(policy).convertSpotMarketBuy(
            decimal("1E+100000000"), decimal("0.0001"), policy)));
  }

  @Test
  void marketBuyRejectsAStorageStepOutsideNumeric12Scale4AtItsBoundary() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));

    assertThatThrownBy(() -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.00001"), policy))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("INVALID_INSTRUMENT_RULES");
              assertThat(exception.getMessage()).containsIgnoringCase("storage step");
            });
  }

  @Test
  void marketBuyRejectsAnAskPriceOutsideNumeric24Scale10BeforeMultiplication() {
    DemoExecutionPolicy policy = depthPolicy(levels("100000000000000@1"));

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).convertSpotMarketBuy(
        decimal("1000000000000000"), decimal("0.0001"), policy));
  }

  @Test
  void marketBuyRejectsAnAskQuantityOutsideNumeric12Scale4BeforeSumming() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@100000000"));

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.0001"), policy));
  }

  @Test
  void marketBuyRejectsAPerTickCapOutsideNumeric12Scale4BeforeRemainder() {
    DemoExecutionPolicy policy = depthPolicy(
        levels("100@1"), decimal("100000000"));

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.0001"), policy));
  }

  @Test
  void marketBuyRejectsAFeeRateOutsideNumeric18Scale8BeforeMultiplication() {
    DemoExecutionPolicy policy = new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.0002"),
        decimal("0.000000001"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(),
        levels("100@1"),
        null);

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.0001"), policy));
  }

  @Test
  void buyLimitHoldPlanDerivesTailFromTheBoundLimitAndWorstPolicyFee() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1.5", "120");

    var holdPlan = service.planSpot(decimal("160.08000000"), depthPlan);

    assertThat(holdPlan.holdAfterEachFill()).containsExactly(decimal("60.03000000"));
  }

  @Test
  void buyLimitTailRoundsEveryFutureCappedFillBeforeSumming() {
    DemoExecutionPolicy policy = depthPolicy(
        levels("1.000000006@4"), decimal("1"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service,
        policy,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        "4",
        "1.000000006");

    var holdPlan = service.planSpot(decimal("0"), depthPlan);

    assertThat(depthPlan.matchingResult().remainingQuantity()).isEqualByComparingTo("3");
    assertThat(holdPlan.holdAfterEachFill())
        .containsExactly(decimal("3.00150003"));
  }

  @Test
  void buyHoldPlanBuildsAnExactBackwardScheduleAndTopsUpAnUnderheldOrder() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1", "101@2"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "3.5", "120");
    DemoMatchingResult matching = depthPlan.matchingResult();
    BigDecimal currentHold = decimal("10.00000000");
    BigDecimal retainedTail = decimal("60.03000000");

    var plan = service.planSpot(currentHold, depthPlan);

    assertThat(plan.initialHold()).isEqualByComparingTo("362.18100000");
    assertThat(plan.initialHold()).isGreaterThan(currentHold);
    assertThat(plan.holdAfterEachFill())
        .containsExactly(decimal("262.13100000"), retainedTail);
    assertThat(plan.holdAfterEachFill().getLast()).isPositive();
    assertScheduleCoversEveryFill(
        plan.initialHold(),
        plan.holdAfterEachFill(),
        matching.fills().stream().map(DepthSpotExecutionPlanningTest::buySpend).toList());
  }

  @Test
  void sellHoldPlanKeepsExistingExcessAndDerivesTheBaseRemainderTail() {
    DemoExecutionPolicy policy = depthPolicy(
        levels("101@1.25", "100@0.75"), List.of(), null);
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service, policy, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, "2.5", "90");
    DemoMatchingResult matching = depthPlan.matchingResult();
    BigDecimal existingHold = decimal("3.00000000");

    var plan = service.planSpot(existingHold, depthPlan);

    assertThat(plan.initialHold()).isEqualByComparingTo(existingHold);
    assertThat(plan.holdAfterEachFill())
        .containsExactly(decimal("1.25000000"), decimal("0.50000000"));
    BigDecimal firstRelease = plan.initialHold()
        .subtract(money(matching.fills().getFirst().quantity()))
        .subtract(plan.holdAfterEachFill().getFirst());
    assertThat(firstRelease).isEqualByComparingTo("0.50000000");
    assertScheduleCoversEveryFill(
        plan.initialHold(),
        plan.holdAfterEachFill(),
        matching.fills().stream().map(fill -> money(fill.quantity())).toList());
  }

  @Test
  void iocPartialPlanKeepsThePositiveTailUntilTheCallerCancelsIt() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC, "1.5", "100");
    BigDecimal retainedTail = decimal("50.02500000");

    var plan = service.planSpot(decimal("150.07500000"), depthPlan);

    assertThat(plan.holdAfterEachFill()).containsExactly(retainedTail);
    assertThat(plan.holdAfterEachFill().getLast()).isPositive();
  }

  @Test
  void iocZeroFillProducesNoInitialHoldAndNoFillSchedule() {
    DemoExecutionPolicy policy = depthPolicy(levels("101@1"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC, "1", "100");

    var holdPlan = service.planSpot(decimal("100.05000000"), depthPlan);

    assertThat(depthPlan.matchingResult().fills()).isEmpty();
    assertThat(holdPlan.initialHold()).isZero();
    assertThat(holdPlan.holdAfterEachFill()).isEmpty();
  }

  @Test
  void marketBuyRejectsQuoteBudgetThatCannotFundItsRemainingBaseQuantity() {
    DemoExecutionPolicy policy = depthPolicy(
        levels("100@1", "200@1"), decimal("1"));
    DepthOrderExecutionService service = service(policy);
    BigDecimal quoteBudget = decimal("150.00000000");
    var depthPlan = spotPlan(
        service,
        policy,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        "2",
        null);

    assertBusinessCode("ORDER_HOLD_INVALID", () -> service.planSpot(quoteBudget, depthPlan));
  }

  @Test
  void marketBuyFullFillCannotTopUpBeyondThePublicQuoteBudget() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@2"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service,
        policy,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        "2",
        null);

    assertBusinessCode(
        "ORDER_HOLD_INVALID",
        () -> service.planSpot(decimal("100.00000000"), depthPlan));
  }

  @Test
  void marketBuyTailProjectsEveryFutureCappedTickWithPerFillRounding() {
    DemoExecutionPolicy policy = depthPolicy(
        levels(
            "0.000051@0.0001",
            "0.000051@0.0001",
            "0.000051@0.0001",
            "0.000051@0.0001"),
        decimal("0.0001"));
    DepthOrderExecutionService service = service(policy);
    BigDecimal quoteBudget = decimal("0.00000004");
    var conversion = service.convertSpotMarketBuy(quoteBudget, decimal("0.0001"), policy);
    var depthPlan = spotPlan(
        service,
        policy,
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        conversion.baseQuantity().toPlainString(),
        null,
        spotSnapshot("0.000050", "0.000051", "0.0000505"));

    var holdPlan = service.planSpot(quoteBudget, depthPlan);

    assertThat(conversion.baseQuantity()).isEqualByComparingTo("0.0004");
    assertThat(conversion.projectedQuoteSpend()).isEqualByComparingTo(quoteBudget);
    assertThat(depthPlan.matchingResult().remainingQuantity()).isEqualByComparingTo("0.0003");
    assertThat(holdPlan.holdAfterEachFill()).containsExactly(decimal("0.00000003"));
  }

  @Test
  void buyLimitRejectsAHoldThatExceedsNumeric24Scale8() {
    DemoExecutionPolicy policy = depthPolicy(levels(
        "90000000000000@100",
        "90000000000000@100"));
    DepthOrderExecutionService service = service(policy);
    var depthPlan = spotPlan(
        service,
        policy,
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        "200",
        "90000000000000");

    assertBusinessCode("ORDER_HOLD_INVALID", () -> service.planSpot(decimal("0"), depthPlan));
  }

  @Test
  void marketSellRejectsEmptyBidBookBeforePlanningAHold() {
    DemoExecutionPolicy policy = depthPolicy(List.of(), List.of(), null);
    DepthOrderExecutionService service = service(policy);

    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE", () -> spotPlan(
        service, policy, OrderSide.SELL, OrderType.MARKET, TimeInForce.GTC, "1", null));
  }

  @Test
  void spotHoldPlanRejectsFilledQuantityThatDoesNotEqualTheFillSum() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));
    DepthOrderExecutionService service = service(policy);
    var coherent = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1.5", "100");
    DemoMatchingResult inconsistent = new DemoMatchingResult(
        List.of(fill("1", "100", "0.0005")),
        decimal("0.5"),
        decimal("0.5"),
        OrderStatus.PARTIALLY_FILLED);
    var inconsistentPlan = spy(coherent);
    when(inconsistentPlan.matchingResult()).thenReturn(inconsistent);

    assertThatThrownBy(() -> service.planSpot(decimal("150.07500000"), inconsistentPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("filled");
  }

  @Test
  void spotHoldPlanRejectsAStatusThatContradictsTheRemainder() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));
    DepthOrderExecutionService service = service(policy);
    var coherent = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1.5", "100");
    DemoMatchingResult inconsistent = new DemoMatchingResult(
        List.of(fill("1", "100", "0.0005")),
        decimal("1"),
        decimal("0.5"),
        OrderStatus.FILLED);
    var inconsistentPlan = spy(coherent);
    when(inconsistentPlan.matchingResult()).thenReturn(inconsistent);

    assertThatThrownBy(() -> service.planSpot(decimal("150.07500000"), inconsistentPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status");
  }

  @Test
  void spotHoldPlanRejectsAFillWhoseRoleAndFeeDoNotMatchTheBoundPlan() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));
    DepthOrderExecutionService service = service(policy);
    var coherent = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1", "100");
    DemoMatchingResult tamperedMatching = new DemoMatchingResult(
        List.of(new DemoMatchFill(
            decimal("1"),
            decimal("100"),
            LiquidityRole.MAKER,
            policy.makerFeeRate())),
        decimal("1"),
        decimal("0"),
        OrderStatus.FILLED);
    var tamperedPlan = spy(coherent);
    when(tamperedPlan.matchingResult()).thenReturn(tamperedMatching);

    assertThatThrownBy(() -> service.planSpot(decimal("100.05000000"), tamperedPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageMatching("(?i).*(fee|role).*");
  }

  @Test
  void spotHoldPlanDerivesPositiveTailExactlyWhenTheRemainderExists() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@1"));
    DepthOrderExecutionService service = service(policy);
    var partial = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1.5", "100");
    var filled = spotPlan(
        service, policy, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1", "100");

    var partialHold = service.planSpot(decimal("150.07500000"), partial);
    var filledHold = service.planSpot(decimal("100.05000000"), filled);

    assertThat(partialHold.holdAfterEachFill().getLast())
        .isEqualByComparingTo("50.02500000")
        .isPositive();
    assertThat(filledHold.holdAfterEachFill().getLast()).isZero();
  }

  @Test
  void marketBuyRejectsEmptyDepth() {
    DemoExecutionPolicy policy = depthPolicy(List.of());

    assertBusinessCode("MARKET_BUNDLE_INCOMPLETE", () ->
        service(policy).convertSpotMarketBuy(
            decimal("100"), decimal("0.0001"), policy));
  }

  @Test
  void marketBuyRejectsDepthQuantityBelowStoragePrecision() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@0.00001"));

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.0001"), policy));
  }

  @Test
  void marketBuyRejectsAStorageStepWhoseRoundedGrossIsZero() {
    DemoExecutionPolicy policy = depthPolicy(levels("0.00001@0.0001"));

    assertThatThrownBy(() -> service(policy).convertSpotMarketBuy(
        decimal("1"), decimal("0.0001"), policy))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("INVALID_INSTRUMENT_RULES");
              assertThat(exception.getMessage()).matches("(?i).*(gross|positive).*");
            });
  }

  @Test
  void marketBuyRejectsMalformedLevelQuantityAtTheServiceBoundary() {
    DemoBookLevel malformed = mock(DemoBookLevel.class);
    when(malformed.price()).thenReturn(decimal("100"));
    when(malformed.quantity()).thenReturn(BigDecimal.ZERO);
    DemoExecutionPolicy policy = depthPolicy(List.of(malformed));

    assertThatThrownBy(() -> service(policy).convertSpotMarketBuy(
        decimal("100"), decimal("0.0001"), policy))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void marketBuyRejectsAPositiveNonStepLevelBeforeAValidLaterLevel() {
    DemoExecutionPolicy policy = depthPolicy(levels("100@0.0003", "101@1"));

    assertBusinessCode("INVALID_INSTRUMENT_RULES", () ->
        service(policy).convertSpotMarketBuy(
            decimal("100"), decimal("0.0002"), policy));
  }

  @Test
  void marketBuyConversionRejectsADepthPolicyThatIsNotTheCurrentProviderInstance() {
    DemoExecutionPolicy current = depthPolicy(levels("100@1"));
    DemoExecutionPolicy callerSupplied = depthPolicy(levels("50@2"));

    assertThatThrownBy(() -> service(current).convertSpotMarketBuy(
        decimal("100"), decimal("0.0001"), callerSupplied))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current policy");
  }

  private static DepthOrderExecutionService service(DemoExecutionPolicy policy) {
    return new DepthOrderExecutionService(
        () -> policy,
        mock(TradeRepository.class),
        mock(OrderFillService.class),
        mock(OrderEventService.class));
  }

  private static DemoExecutionPolicy depthPolicy(List<DemoBookLevel> asks) {
    return depthPolicy(asks, null);
  }

  private static DemoExecutionPolicy depthPolicy(
      List<DemoBookLevel> asks,
      BigDecimal maxFillQuantityPerTick
  ) {
    return depthPolicy(List.of(), asks, maxFillQuantityPerTick);
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

  private static DepthOrderExecutionService.DepthMatchPlan spotPlan(
      DepthOrderExecutionService service,
      DemoExecutionPolicy policy,
      OrderSide side,
      OrderType orderType,
      TimeInForce timeInForce,
      String quantity,
      String limitPrice
  ) {
    return spotPlan(
        service,
        policy,
        side,
        orderType,
        timeInForce,
        quantity,
        limitPrice,
        spotSnapshot());
  }

  private static DepthOrderExecutionService.DepthMatchPlan spotPlan(
      DepthOrderExecutionService service,
      DemoExecutionPolicy policy,
      OrderSide side,
      OrderType orderType,
      TimeInForce timeInForce,
      String quantity,
      String limitPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    return service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        side,
        orderType,
        timeInForce,
        decimal(quantity),
        limitPrice == null ? null : decimal(limitPrice),
        false,
        LiquidityRole.TAKER,
        snapshot);
  }

  private static ExecutableMarketSnapshot spotSnapshot() {
    return spotSnapshot("99", "100", "99.5");
  }

  private static ExecutableMarketSnapshot spotSnapshot(
      String bid,
      String ask,
      String last
  ) {
    Instant asOf = Instant.now();
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

  private static void assertBusinessCode(String expectedCode, ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
  }

  private static DemoMatchFill fill(String quantity, String price, String feeRate) {
    return new DemoMatchFill(
        decimal(quantity), decimal(price), LiquidityRole.TAKER, decimal(feeRate));
  }

  private static void assertScheduleCoversEveryFill(
      BigDecimal initialHold,
      List<BigDecimal> holdAfterEachFill,
      List<BigDecimal> spends
  ) {
    assertThat(holdAfterEachFill).hasSameSizeAs(spends);
    BigDecimal holdBefore = initialHold;
    for (int i = 0; i < spends.size(); i++) {
      BigDecimal holdAfter = holdAfterEachFill.get(i);
      assertThat(holdBefore)
          .as("hold node %s covers its rounded spend and exact successor", i)
          .isGreaterThanOrEqualTo(spends.get(i).add(holdAfter));
      holdBefore = holdAfter;
    }
  }

  private static BigDecimal projectedBuySpend(
      BigDecimal baseQuantity,
      List<DemoBookLevel> asks,
      BigDecimal feeRate
  ) {
    BigDecimal remaining = baseQuantity;
    BigDecimal spend = money(BigDecimal.ZERO);
    for (DemoBookLevel level : asks) {
      if (remaining.signum() == 0) {
        break;
      }
      BigDecimal quantity = remaining.min(level.quantity());
      BigDecimal gross = money(quantity.multiply(level.price()));
      BigDecimal fee = money(quantity.multiply(level.price()).multiply(feeRate));
      spend = money(spend.add(gross).add(fee));
      remaining = remaining.subtract(quantity);
    }
    return spend;
  }

  private static BigDecimal buySpend(DemoMatchFill fill) {
    BigDecimal gross = money(fill.quantity().multiply(fill.price()));
    BigDecimal fee = money(fill.quantity().multiply(fill.price()).multiply(fill.feeRate()));
    return money(gross.add(fee));
  }

  private static List<DemoBookLevel> levels(String... values) {
    return java.util.Arrays.stream(values)
        .map(value -> value.split("@"))
        .map(parts -> new DemoBookLevel(decimal(parts[0]), decimal(parts[1])))
        .toList();
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(8, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
