package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.DemoMatchingResult;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.TradeRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DepthPerpetualExecutionPlanningTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
  private static final BigDecimal ZERO_MONEY = decimal("0.00000000");
  private static final UUID ACCOUNT_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000401");
  private static final Method SYNTHETIC_PLAN_FACTORY = syntheticPlanFactory();
  private static final Method SYNTHETIC_RISK_FACTORY = syntheticRiskFactory();

  private final TradeRepository tradeRepository = mock(TradeRepository.class);
  private final OrderFillService orderFillService = mock(OrderFillService.class);
  private final OrderEventService orderEventService = mock(OrderEventService.class);

  @Test
  void pureOpenUsesEachLevelPriceAndBuildsTheHoldScheduleBackwards() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");
    DemoMatchingResult matching = matching(
        OrderStatus.FILLED,
        "0",
        fill("1", "100", "0.001"),
        fill("1", "102", "0.001"));
    DepthOrderExecutionService.DepthHoldPlan plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.BUY, 10, "0", "2", "105", "95", "21.10500000"),
        ZERO_MONEY,
        executionPlan(OrderSide.BUY, policy, matching, snapshot("99", "101", "100")));

    assertThat(plan.initialHold()).isEqualByComparingTo("20.40200000");
    assertThat(plan.holdAfterEachFill())
        .containsExactly(decimal("10.30200000"), ZERO_MONEY);

    BigDecimal proportionalHalf = plan.initialHold().divide(decimal("2"));
    assertThat(plan.holdAfterEachFill().get(0)).isNotEqualByComparingTo(proportionalHalf);
  }

  @Test
  void pureReductionAddsDirectionalAdverseLossToTheActualFillFee() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");
    var service = service(policy);

    var closeLong = service.planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.SELL, 10, "1", "0", "100", "90", "10.09000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.SELL,
            policy,
            matching(OrderStatus.FILLED, "0", fill("1", "90", "0.001")),
            snapshot("99", "101", "100")));
    var closeShort = service.planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.BUY, 10, "1", "0", "110", "110", "10.11000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            policy,
            matching(OrderStatus.FILLED, "0", fill("1", "110", "0.001")),
            snapshot("99", "101", "100")));

    assertThat(closeLong.initialHold()).isEqualByComparingTo("10.09000000");
    assertThat(closeLong.holdAfterEachFill()).containsExactly(ZERO_MONEY);
    assertThat(closeShort.initialHold()).isEqualByComparingTo("10.11000000");
    assertThat(closeShort.holdAfterEachFill()).containsExactly(ZERO_MONEY);
  }

  @Test
  void oneWayReversalSplitsCloseAndOpenInsideOneFillLevel() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.SELL, 10, "0.4", "0.6", "100", "90", "9.49000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.SELL,
            policy,
            matching(OrderStatus.FILLED, "0", fill("1", "90", "0.001")),
            snapshot("99", "101", "100")));

    // close: 0.4 * (100 - 90) = 4; open: 0.6 * 90 / 10 = 5.4; fee = 0.09
    assertThat(plan.initialHold()).isEqualByComparingTo("9.49000000");
    assertThat(plan.holdAfterEachFill()).containsExactly(ZERO_MONEY);
  }

  @Test
  void multipleFillsCarryTheCloseToOpenBoundaryAcrossLevels() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.SELL, 10, "1.5", "0.5", "100", "98", "7.09700000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.SELL,
            policy,
            matching(
                OrderStatus.FILLED,
                "0",
                fill("1", "99", "0.001"),
                fill("1", "98", "0.001")),
            snapshot("99", "101", "100")));

    // Fill 1 is all close: 1 adverse loss + 0.099 fee.
    // Fill 2 closes 0.5 and opens 0.5: 1 loss + 4.9 margin + 0.098 fee.
    assertThat(plan.initialHold()).isEqualByComparingTo("7.09700000");
    assertThat(plan.holdAfterEachFill())
        .containsExactly(decimal("5.99800000"), ZERO_MONEY);
  }

  @Test
  void gtcRemainderUsesConservativeTailPriceAndWorstPolicyFee() {
    DemoExecutionPolicy policy = policy(
        "0.002",
        "0.003",
        List.of(),
        List.of(level("110", "3")));
    DemoMatchingResult matching = matching(
        OrderStatus.PARTIALLY_FILLED,
        "2",
        fill("1", "100", "0.003"));

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.BUY, 10, "0", "3", "1", "999", "32.96000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            policy,
            matching,
            snapshot("90", "110", "100")));

    // Exact fill = 10 margin + 0.3 fee. Tail = 2 * 110 / 10 + 2 * 110 * 0.003.
    assertThat(plan.initialHold()).isEqualByComparingTo("32.96000000");
    assertThat(plan.holdAfterEachFill()).containsExactly(decimal("22.66000000"));
  }

  @Test
  void remainingTailPreservesCloseRiskBeforeCrossingIntoOpeningExposure() {
    DemoExecutionPolicy policy = policy(
        "0.0002",
        "0.001",
        List.of(level("120", "1"), level("80", "3")),
        List.of());

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.SELL, 10, "2", "1", "1", "999", "52.36000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.SELL,
            policy,
            matching(
                OrderStatus.PARTIALLY_FILLED,
                "2.5",
                fill("0.5", "90", "0.001")),
            snapshot("80", "120", "100")));

    // Exact fill closes .5: adverse 5 + fee .045. Tail closes 1.5 (30 loss),
    // opens 1 (12 margin), and buffers fee for all 2.5 remaining (.3).
    assertThat(plan.initialHold()).isEqualByComparingTo("47.34500000");
    assertThat(plan.holdAfterEachFill()).containsExactly(decimal("42.30000000"));
  }

  @Test
  void iocPartialPlanAlsoRetainsItsPositiveRiskTailUntilCancellation() {
    DemoExecutionPolicy policy = policy(
        "0.0002",
        "0.001",
        List.of(),
        List.of(level("110", "2")));

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.BUY, 10, "0", "2", "1", "999", "22.22000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            policy,
            matching(
                OrderStatus.CANCELLED,
                "1",
                fill("1", "100", "0.001")),
            snapshot("90", "110", "100")));

    assertThat(plan.holdAfterEachFill()).containsExactly(decimal("11.11000000"));
    assertThat(plan.holdAfterEachFill().getLast()).isPositive();
  }

  @Test
  void iocWithNoFillsProducesZeroInitialHold() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(),
        List.of(level("100", "1")));

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.BUY, 10, "0", "1", "90", "90", "9"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            OrderType.LIMIT,
            TimeInForce.IOC,
            decimal("90"),
            policy,
            matching(OrderStatus.CANCELLED, "1"),
            snapshot("99", "100", "100")));

    assertThat(plan.initialHold()).isEqualByComparingTo(ZERO_MONEY);
    assertThat(plan.holdAfterEachFill()).isEmpty();
  }

  @Test
  void existingExcessIsReleasedByTheFirstFillAndAnUnderheldOrderIsToppedUpOnce() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");
    DemoMatchingResult matching = matching(
        OrderStatus.FILLED,
        "0",
        fill("1", "100", "0.001"));
    PerpetualOrderRiskService.OrderRisk risk =
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10.10000000");
    var service = service(policy);
    DepthOrderExecutionService.DepthMatchPlan matchPlan = executionPlan(
        OrderSide.BUY,
        policy,
        matching,
        snapshot("99", "101", "100"));

    var excess = service.planPerpetual(
        ACCOUNT_ID, 10, risk, decimal("15"), matchPlan);
    var deficient = service.planPerpetual(
        ACCOUNT_ID, 10, risk, decimal("5"), matchPlan);

    assertThat(excess.initialHold()).isEqualByComparingTo("15.00000000");
    assertThat(excess.holdAfterEachFill()).containsExactly(ZERO_MONEY);
    assertThat(deficient.initialHold()).isEqualByComparingTo("10.10000000");
    assertThat(deficient.holdAfterEachFill()).containsExactly(ZERO_MONEY);
  }

  @Test
  void zeroFeeStillUsesRealRiskWhileZeroRiskFillAndTailEachUseOneMoneyUnit() {
    DemoExecutionPolicy openPolicy = policy("0", "0");
    DemoExecutionPolicy closePolicy = policy(
        "0", "0", List.of(level("110", "2")), List.of());

    var riskyOpen = service(openPolicy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            openPolicy,
            matching(OrderStatus.FILLED, "0", fill("1", "100", "0")),
            snapshot("99", "101", "100")));
    var zeroRiskCloseAndTail = service(closePolicy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.SELL, 10, "2", "0", "1", "1", "0"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.SELL,
            closePolicy,
            matching(OrderStatus.PARTIALLY_FILLED, "1", fill("1", "110", "0")),
            snapshot("110", "111", "100")));

    assertThat(riskyOpen.initialHold()).isEqualByComparingTo("10.00000000");
    assertThat(riskyOpen.holdAfterEachFill()).containsExactly(ZERO_MONEY);
    assertThat(zeroRiskCloseAndTail.initialHold()).isEqualByComparingTo("0.00000002");
    assertThat(zeroRiskCloseAndTail.holdAfterEachFill())
        .containsExactly(decimal("0.00000001"));
  }

  @Test
  void zeroFeeTailReservesOneMoneyUnitForEveryFutureCappedFill() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(level("110", "3")),
        List.of(),
        decimal("1"));

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        10,
        risk(OrderSide.SELL, 10, "3", "0", "110", "110", "0.00000003"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.SELL,
            policy,
            matching(
                OrderStatus.PARTIALLY_FILLED,
                "2",
                fill("1", "110", "0")),
            snapshot("110", "111", "100")));

    assertThat(plan.initialHold()).isEqualByComparingTo("0.00000003");
    assertThat(plan.holdAfterEachFill())
        .containsExactly(decimal("0.00000002"));
  }

  @Test
  void marginAndFeeAreRoundedPerFillBeforeTheBackwardSum() {
    DemoExecutionPolicy policy = policy("0.0002", "0.0005");

    var plan = service(policy).planPerpetual(
        ACCOUNT_ID,
        2,
        risk(OrderSide.BUY, 2, "0", "0.001", "50000.30003", "50000", "25.02510013"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            policy,
            matching(
                OrderStatus.FILLED,
                "0",
                fill("0.0005", "50000.10001", "0.0005"),
                fill("0.0005", "50000.30003", "0.0005")),
            snapshot("49999", "50001", "50000")));

    assertThat(plan.initialHold()).isEqualByComparingTo("25.02510013");
    assertThat(plan.holdAfterEachFill())
        .containsExactly(decimal("12.51257509"), ZERO_MONEY);
  }

  @Test
  void rejectsAProjectedHoldThatExceedsNumeric24_8() {
    String fillQuantity = "100";
    String fillPrice = "90000000000000";
    DemoExecutionPolicy policy = policy("0", "0");

    assertThatThrownBy(() -> service(policy).planPerpetual(
        ACCOUNT_ID,
        1,
        risk(OrderSide.BUY, 1, "0", "200", fillPrice, fillPrice, "0"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            policy,
            matching(
                OrderStatus.FILLED,
                "0",
                fill(fillQuantity, fillPrice, "0"),
                fill(fillQuantity, fillPrice, "0")),
            snapshot("99", "100", "100"))))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.ORDER_HOLD_INVALID));
  }

  @Test
  void marketTailPricingStopsWhenNearDepthCoversTheTailQuantity() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(),
        List.of(level("100", "100"), level("1000", "1")));
    var matchPlan = executionPlan(
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        null,
        policy,
        matching(OrderStatus.PENDING, "1"),
        snapshot("99", "100", "100"));

    PerpetualRiskPricing pricing = service(policy).perpetualRiskPricing(matchPlan);

    assertThat(pricing.marginAndFeePrice()).isEqualByComparingTo("100");
    assertThat(pricing.adverseClosePrice()).isEqualByComparingTo("100");
  }

  @Test
  void fullFillRiskPricingComesFromTheExactActualFills() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(level("80", "1")),
        List.of());
    var matchPlan = executionPlan(
        OrderSide.SELL,
        OrderType.MARKET,
        TimeInForce.GTC,
        null,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "80", "0")),
        snapshot("80", "100", "100"));

    PerpetualRiskPricing pricing = service(policy).perpetualRiskPricing(matchPlan);

    assertThat(pricing.marginAndFeePrice()).isEqualByComparingTo("80");
    assertThat(pricing.adverseClosePrice()).isEqualByComparingTo("80");
    assertThat(pricing.feeRate()).isEqualByComparingTo("0");
  }

  @Test
  void planPerpetualRejectsRiskPricedWithoutTheExactSealedPlanAuthority() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(level("80", "1")),
        List.of());
    var matchPlan = executionPlan(
        OrderSide.SELL,
        OrderType.MARKET,
        TimeInForce.GTC,
        null,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "80", "0")),
        snapshot("80", "100", "100"));
    var favorablyPricedRisk = boundRisk(
        risk(OrderSide.SELL, 10, "1", "0", "100", "100", "0"),
        matchPlan,
        ACCOUNT_ID,
        new PerpetualRiskPricing(decimal("100"), decimal("100"), BigDecimal.ZERO));

    assertThatThrownBy(() -> service(policy).planPerpetual(
        authority(ACCOUNT_ID, 10),
        favorablyPricedRisk,
        ZERO_MONEY,
        matchPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  void planPerpetualRejectsRiskBoundToAnEquivalentButDifferentPlanInstance() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(),
        List.of(level("100", "1")));
    DemoMatchingResult matching = matching(
        OrderStatus.FILLED, "0", fill("1", "100", "0"));
    ExecutableMarketSnapshot snapshot = snapshot("99", "101", "100");
    var firstPlan = executionPlan(OrderSide.BUY, policy, matching, snapshot);
    var secondPlan = executionPlan(OrderSide.BUY, policy, matching, snapshot);
    var firstPlanRisk = boundRisk(
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10"),
        firstPlan,
        ACCOUNT_ID,
        service(policy).perpetualRiskPricing(firstPlan));

    assertThatThrownBy(() -> service(policy).planPerpetual(
        authority(ACCOUNT_ID, 10),
        firstPlanRisk,
        ZERO_MONEY,
        secondPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  void oneBoundRiskCanBeConsumedByItsExactPlanOnlyOnce() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(),
        List.of(level("100", "1")));
    var matchPlan = executionPlan(
        OrderSide.BUY,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "100", "0")),
        snapshot("99", "101", "100"));
    var boundRisk = boundRisk(
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10"),
        matchPlan,
        ACCOUNT_ID,
        service(policy).perpetualRiskPricing(matchPlan));
    var service = service(policy);

    service.planPerpetual(authority(ACCOUNT_ID, 10), boundRisk, ZERO_MONEY, matchPlan);

    assertThatThrownBy(() -> service.planPerpetual(
        authority(ACCOUNT_ID, 10), boundRisk, ZERO_MONEY, matchPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  void zeroFillIocReturnsZeroWithoutEvaluatingPerpetualRisk() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(level("80", "1")),
        List.of());
    var matchPlan = executionPlan(
        OrderSide.SELL,
        OrderType.LIMIT,
        TimeInForce.IOC,
        decimal("90"),
        policy,
        matching(OrderStatus.CANCELLED, "1"),
        snapshot("80", "100", "100"));

    var holdPlan = service(policy).planPerpetual(
        null, null, ZERO_MONEY, matchPlan);

    assertThat(holdPlan.initialHold()).isEqualByComparingTo(ZERO_MONEY);
    assertThat(holdPlan.holdAfterEachFill()).isEmpty();
  }

  @Test
  void buyLimitWithoutEligibleDepthFallsBackToTheLimitNotTheSnapshotAsk() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(),
        List.of(level("100", "1")));
    var matchPlan = executionPlan(
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("90"),
        policy,
        matching(OrderStatus.PENDING, "1"),
        snapshot("99", "101", "100"));

    PerpetualRiskPricing pricing = service(policy).perpetualRiskPricing(matchPlan);

    assertThat(pricing.marginAndFeePrice()).isEqualByComparingTo("90");
    assertThat(pricing.adverseClosePrice()).isEqualByComparingTo("90");
  }

  @Test
  void sellLimitCoveredByNearDepthDoesNotAddLimitOrSnapshotFallback() {
    DemoExecutionPolicy policy = policy(
        "0",
        "0",
        List.of(level("100", "100")),
        List.of());
    var matchPlan = executionPlan(
        OrderSide.SELL,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("90"),
        policy,
        matching(OrderStatus.PENDING, "1"),
        snapshot("150", "151", "150"));

    PerpetualRiskPricing pricing = service(policy).perpetualRiskPricing(matchPlan);

    assertThat(pricing.marginAndFeePrice()).isEqualByComparingTo("100");
    assertThat(pricing.adverseClosePrice()).isEqualByComparingTo("100");
  }

  @Test
  void buyRiskPricingUsesOnlyLevelsTouchedByTheTailUnlessFallbackIsNeeded() {
    DemoMatchingResult matching = matching(
        OrderStatus.PARTIALLY_FILLED,
        "2",
        fill("1", "100", "0.003"));
    DemoExecutionPolicy limitPolicy = policy(
        "0.002",
        "0.003",
        List.of(),
        List.of(
            level("100", "1"),
            level("103", "1"),
            level("110", "5")));
    var limitPlan = executionPlan(
        OrderSide.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("105"),
        limitPolicy,
        matching,
        snapshot("95", "107", "100"));

    PerpetualRiskPricing limitPricing = service(limitPolicy).perpetualRiskPricing(limitPlan);

    assertThat(limitPricing.marginAndFeePrice()).isEqualByComparingTo("103");
    assertThat(limitPricing.adverseClosePrice()).isEqualByComparingTo("103");
    assertThat(limitPricing.feeRate()).isEqualByComparingTo("0.003");

    DemoExecutionPolicy marketPolicy = policy(
        "0.002",
        "0.003",
        List.of(),
        List.of(level("100", "1"), level("103", "1")));
    var marketPlan = executionPlan(
        OrderSide.BUY,
        OrderType.MARKET,
        TimeInForce.GTC,
        null,
        marketPolicy,
        matching,
        snapshot("95", "110", "100"));

    PerpetualRiskPricing marketPricing = service(marketPolicy).perpetualRiskPricing(marketPlan);

    assertThat(marketPricing.marginAndFeePrice()).isEqualByComparingTo("103");
    assertThat(marketPricing.adverseClosePrice()).isEqualByComparingTo("103");
    assertThat(marketPricing.feeRate()).isEqualByComparingTo("0.003");
  }

  @Test
  void sellRiskPricingUsesOnlyLevelsTouchedByTheTailUnlessFallbackIsNeeded() {
    DemoMatchingResult matching = matching(
        OrderStatus.PARTIALLY_FILLED,
        "2",
        fill("1", "120", "0.003"));
    DemoExecutionPolicy limitPolicy = policy(
        "0.002",
        "0.003",
        List.of(
            level("120", "1"),
            level("90", "1"),
            level("70", "5")),
        List.of());
    var limitPlan = executionPlan(
        OrderSide.SELL,
        OrderType.LIMIT,
        TimeInForce.GTC,
        decimal("80"),
        limitPolicy,
        matching,
        snapshot("75", "125", "100"));

    PerpetualRiskPricing limitPricing = service(limitPolicy).perpetualRiskPricing(limitPlan);

    assertThat(limitPricing.marginAndFeePrice()).isEqualByComparingTo("120");
    assertThat(limitPricing.adverseClosePrice()).isEqualByComparingTo("90");
    assertThat(limitPricing.feeRate()).isEqualByComparingTo("0.003");

    DemoExecutionPolicy marketPolicy = policy(
        "0.002",
        "0.003",
        List.of(level("120", "1"), level("90", "1")),
        List.of());
    var marketPlan = executionPlan(
        OrderSide.SELL,
        OrderType.MARKET,
        TimeInForce.GTC,
        null,
        marketPolicy,
        matching,
        snapshot("75", "125", "100"));

    PerpetualRiskPricing marketPricing = service(marketPolicy).perpetualRiskPricing(marketPlan);

    assertThat(marketPricing.marginAndFeePrice()).isEqualByComparingTo("120");
    assertThat(marketPricing.adverseClosePrice()).isEqualByComparingTo("90");
    assertThat(marketPricing.feeRate()).isEqualByComparingTo("0.003");
  }

  @Test
  void rejectsLeverageThatDiffersFromTheLockedRiskAuthority() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(policy).planPerpetual(
        ACCOUNT_ID,
        5,
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10.10000000"),
        ZERO_MONEY,
        executionPlan(
            OrderSide.BUY,
            policy,
            matching(OrderStatus.FILLED, "0", fill("1", "100", "0.001")),
            snapshot("99", "101", "100"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leverage");
  }

  @Test
  void rejectsDepthRiskWhenTheLockedPositionAuthorityDiffers() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");
    var matchPlan = executionPlan(
        OrderSide.BUY,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "100", "0.001")),
        snapshot("99", "101", "100"));
    var wrongAuthority = new PerpetualOrderRiskService.DepthPlanningAuthority(
        ACCOUNT_ID,
        PositionMode.HEDGE,
        PositionSide.SHORT,
        MarginMode.CROSS,
        10,
        false,
        decimal("0.005"));
    var coherentRisk = boundRisk(
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10.10000000"),
        matchPlan,
        ACCOUNT_ID,
        service(policy).perpetualRiskPricing(matchPlan));

    assertThatThrownBy(() -> service(policy).planPerpetual(
        wrongAuthority,
        coherentRisk,
        ZERO_MONEY,
        matchPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  void rejectsRiskThatIsNotBoundToTheExactDepthPlanAuthority() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");
    var matchPlan = executionPlan(
        OrderSide.BUY,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "100", "0.001")),
        snapshot("99", "101", "100"));
    var coherent = boundRisk(
        risk(OrderSide.BUY, 10, "0", "1", "100", "100", "10.10000000"),
        matchPlan,
        ACCOUNT_ID,
        service(policy).perpetualRiskPricing(matchPlan));
    var coherentAuthority = authority(ACCOUNT_ID, 10);
    var differentSnapshotPlan = executionPlan(
        OrderSide.BUY,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "100", "0.001")),
        snapshot("98", "102", "100"));
    var differentSidePlan = executionPlan(
        OrderSide.SELL,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "100", "0.001")),
        snapshot("99", "101", "100"));

    assertThatThrownBy(() -> service(policy).planPerpetual(
        authority(
            UUID.fromString("00000000-0000-0000-0000-000000000402"), 10),
        coherent,
        ZERO_MONEY,
        matchPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
    assertThatThrownBy(() -> service(policy).planPerpetual(
        coherentAuthority, coherent, ZERO_MONEY, differentSnapshotPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
    assertThatThrownBy(() -> service(policy).planPerpetual(
        coherentAuthority, coherent, ZERO_MONEY, differentSidePlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  void rejectsLegacyUnboundRiskAtTheDepthPlanCompositionBoundary() {
    DemoExecutionPolicy policy = policy("0.0002", "0.001");
    var matchPlan = executionPlan(
        OrderSide.BUY,
        policy,
        matching(OrderStatus.FILLED, "0", fill("1", "100", "0.001")),
        snapshot("99", "101", "100"));
    PerpetualOrderRiskService.OrderRisk unbound = risk(
        OrderSide.BUY, 10, "0", "1", "100", "100", "10.10000000");

    assertThatThrownBy(() -> service(policy).planPerpetual(
        authority(ACCOUNT_ID, 10), unbound, ZERO_MONEY, matchPlan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bound DEPTH plan");
  }

  private TestDepthOrderExecutionService service(DemoExecutionPolicy policy) {
    DemoExecutionPolicyProvider provider = () -> policy;
    return new TestDepthOrderExecutionService(
        provider,
        tradeRepository,
        orderFillService,
        orderEventService);
  }

  private static final class TestDepthOrderExecutionService
      extends DepthOrderExecutionService {

    private TestDepthOrderExecutionService(
        DemoExecutionPolicyProvider policyProvider,
        TradeRepository tradeRepository,
        OrderFillService orderFillService,
        OrderEventService orderEventService
    ) {
      super(
          policyProvider,
          tradeRepository,
          orderFillService,
          orderEventService,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DepthHoldPlan planPerpetual(
        UUID accountId,
        int leverage,
        PerpetualOrderRiskService.OrderRisk risk,
        BigDecimal currentHold,
        DepthMatchPlan plan
    ) {
      return super.planPerpetual(
          authority(accountId, leverage),
          boundRisk(risk, plan, accountId, perpetualRiskPricing(plan)),
          currentHold,
          plan);
    }
  }

  private static DepthOrderExecutionService.DepthMatchPlan executionPlan(
      OrderSide side,
      DemoExecutionPolicy policy,
      DemoMatchingResult matching,
      ExecutableMarketSnapshot snapshot
  ) {
    return executionPlan(
        side,
        OrderType.MARKET,
        TimeInForce.GTC,
        null,
        policy,
        matching,
        snapshot);
  }

  private static DepthOrderExecutionService.DepthMatchPlan executionPlan(
      OrderSide side,
      OrderType executableType,
      TimeInForce timeInForce,
      BigDecimal limitPrice,
      DemoExecutionPolicy policy,
      DemoMatchingResult matching,
      ExecutableMarketSnapshot snapshot
  ) {
    BigDecimal totalRemainingBeforeTick = matching.filledQuantity()
        .add(matching.remainingQuantity());
    try {
      return (DepthOrderExecutionService.DepthMatchPlan) SYNTHETIC_PLAN_FACTORY.invoke(
          null,
          policy,
          snapshot,
          "BTCUSDT",
          ProductType.LINEAR_PERP,
          side,
          executableType,
          timeInForce,
          totalRemainingBeforeTick,
          limitPrice,
          false,
          LiquidityRole.TAKER,
          matching);
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new AssertionError("Synthetic DEPTH plan construction failed", exception.getCause());
    } catch (IllegalAccessException exception) {
      throw new AssertionError("Synthetic DEPTH plan factory is inaccessible", exception);
    }
  }

  private static Method syntheticPlanFactory() {
    try {
      Method factory = DepthOrderExecutionService.class.getDeclaredMethod(
          "syntheticPlanForTests",
          DemoExecutionPolicy.class,
          ExecutableMarketSnapshot.class,
          String.class,
          ProductType.class,
          OrderSide.class,
          OrderType.class,
          TimeInForce.class,
          BigDecimal.class,
          BigDecimal.class,
          boolean.class,
          LiquidityRole.class,
          DemoMatchingResult.class);
      factory.setAccessible(true);
      return factory;
    } catch (NoSuchMethodException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static Method syntheticRiskFactory() {
    try {
      Method factory = PerpetualOrderRiskService.class.getDeclaredMethod(
          "syntheticDepthRiskForTests",
          PerpetualOrderRiskService.OrderRisk.class,
          UUID.class,
          String.class,
          OrderSide.class,
          BigDecimal.class,
          PositionMode.class,
          PositionSide.class,
          MarginMode.class,
          int.class,
          boolean.class,
          OrderType.class,
          BigDecimal.class,
          ExecutableMarketSnapshot.class,
          BigDecimal.class,
          PerpetualRiskPricing.class);
      factory.setAccessible(true);
      return factory;
    } catch (NoSuchMethodException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static PerpetualOrderRiskService.DepthPlanningAuthority authority(
      UUID accountId,
      int leverage
  ) {
    return new PerpetualOrderRiskService.DepthPlanningAuthority(
        accountId,
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        MarginMode.CROSS,
        leverage,
        false,
        decimal("0.005"));
  }

  private static ExecutableMarketSnapshot snapshot(String bid, String ask, String mark) {
    Instant asOf = NOW;
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.LINEAR_PERP,
        "PUBLIC",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid),
        decimal(ask),
        decimal(mark),
        decimal(mark),
        decimal(mark),
        asOf,
        asOf.plusSeconds(60));
  }

  private static PerpetualOrderRiskService.OrderRisk risk(
      OrderSide side,
      int leverage,
      String closingBase,
      String openingBase,
      String worstPrice,
      String closeWorstPrice,
      String holdAmount
  ) {
    return new PerpetualOrderRiskService.OrderRisk(
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        MarginMode.CROSS,
        leverage,
        decimal(closingBase),
        decimal(openingBase),
        decimal(worstPrice),
        decimal(closeWorstPrice),
        ZERO_MONEY,
        ZERO_MONEY,
        ZERO_MONEY,
        decimal(holdAmount),
        ZERO_MONEY,
        "USDT");
  }

  private static PerpetualOrderRiskService.OrderRisk boundRisk(
      PerpetualOrderRiskService.OrderRisk risk,
      DepthOrderExecutionService.DepthMatchPlan plan,
      UUID accountId,
      PerpetualRiskPricing pricing
  ) {
    try {
      return (PerpetualOrderRiskService.OrderRisk) SYNTHETIC_RISK_FACTORY.invoke(
          null,
          risk,
          accountId,
          plan.symbol(),
          plan.side(),
          plan.remainingBaseQuantity(),
          risk.positionMode(),
          risk.positionSide(),
          risk.marginMode(),
          risk.leverage(),
          false,
          plan.executableType(),
          plan.limitPrice(),
          plan.snapshot(),
          decimal("0.005"),
          pricing);
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new AssertionError("Synthetic DEPTH risk construction failed", exception.getCause());
    } catch (IllegalAccessException exception) {
      throw new AssertionError("Synthetic DEPTH risk factory is inaccessible", exception);
    }
  }

  private static DemoMatchingResult matching(
      OrderStatus status,
      String remainingQuantity,
      DemoMatchFill... fills
  ) {
    List<DemoMatchFill> fillList = List.of(fills);
    BigDecimal filled = fillList.stream()
        .map(DemoMatchFill::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new DemoMatchingResult(
        fillList,
        filled,
        decimal(remainingQuantity),
        status);
  }

  private static DemoMatchFill fill(String quantity, String price, String feeRate) {
    return new DemoMatchFill(
        decimal(quantity),
        decimal(price),
        LiquidityRole.TAKER,
        decimal(feeRate));
  }

  private static DemoExecutionPolicy policy(String makerFeeRate, String takerFeeRate) {
    return policy(makerFeeRate, takerFeeRate, List.of(), List.of());
  }

  private static DemoExecutionPolicy policy(
      String makerFeeRate,
      String takerFeeRate,
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks
  ) {
    return policy(makerFeeRate, takerFeeRate, bids, asks, null);
  }

  private static DemoExecutionPolicy policy(
      String makerFeeRate,
      String takerFeeRate,
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks,
      BigDecimal maxFillQuantityPerTick
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal(makerFeeRate),
        decimal(takerFeeRate),
        decimal("0.001"),
        decimal("0.0001"),
        bids,
        asks,
        maxFillQuantityPerTick);
  }

  private static DemoBookLevel level(String price, String quantity) {
    return new DemoBookLevel(decimal(price), decimal(quantity));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
