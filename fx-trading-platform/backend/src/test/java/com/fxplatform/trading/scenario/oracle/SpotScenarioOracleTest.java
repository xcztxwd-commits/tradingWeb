package com.fxplatform.trading.scenario.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.scenario.ExpectedScenarioResult;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.OrderState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.PositionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.WalletState;
import com.fxplatform.trading.scenario.ScenarioAction;
import com.fxplatform.trading.scenario.ScenarioCatalog;
import com.fxplatform.trading.scenario.ScenarioDefinition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

class SpotScenarioOracleTest {

  private final SpotScenarioOracle oracle = new SpotScenarioOracle();

  @Test
  void marketBuyKeepsQuoteSpendAndUsdtFeeInsideTheTotalBudget() {
    ExpectedScenarioResult result = oracle.calculate(scenario("SPOT_CORE_BUY_BUY"));
    Snapshot first = result.checkpoints().getFirst().snapshot();

    assertThat(first.trades()).singleElement().satisfies(trade -> {
      assertThat(trade.quantity()).isEqualByComparingTo("0.90440000");
      assertThat(trade.price()).isEqualByComparingTo("110.51105000");
      assertThat(trade.quoteNotional()).isEqualByComparingTo("99.94619362");
      assertThat(trade.fee()).isEqualByComparingTo("0.04997310");
      assertThat(trade.feeAsset()).isEqualTo("USDT");
      assertThat(trade.quoteNotional().add(trade.fee()))
          .isLessThanOrEqualTo(new BigDecimal("100"));
    });
    assertThat(wallet(first, "USDT").total()).isEqualByComparingTo("99900.00383328");
    assertThat(wallet(first, "BTC").total()).isEqualByComparingTo("10.90440000");
    assertThat(position(first).quantity()).isEqualByComparingTo("0.90440000");
    assertThat(position(first).averageEntry()).isEqualByComparingTo("110.51105000");
    assertThat(first.ledger()).extracting(LedgerState::type)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "SPOT_BUY_CREDIT");
    assertThat(first.ledger()).extracting(LedgerState::amount)
        .containsExactly(
            new BigDecimal("-99.94619362"),
            new BigDecimal("-0.04997310"),
            new BigDecimal("0.90440000"));
    assertThat(first.ledger()).extracting(LedgerState::balanceAfter)
        .containsExactly(
            new BigDecimal("99900.05380638"),
            new BigDecimal("99900.00383328"),
            new BigDecimal("10.90440000"));
    assertThat(first.ledger()).extracting(LedgerState::referenceType)
        .containsOnly("TRADE");
  }

  @Test
  void marketSellChargesQuoteFeeAndRealizesCostBasis() {
    Snapshot result = oracle.calculate(scenario("SPOT_SELL_PROFIT"))
        .checkpoints().getLast().snapshot();

    assertThat(result.trades().getLast()).satisfies(trade -> {
      assertThat(trade.quantity()).isEqualByComparingTo("1.00000000");
      assertThat(trade.price()).isEqualByComparingTo("119.48805000");
      assertThat(trade.fee()).isEqualByComparingTo("0.05974403");
      assertThat(trade.feeAsset()).isEqualTo("USDT");
      assertThat(trade.realizedPnl()).isZero();
    });
    assertThat(position(result).realizedPnl()).isPositive();
    assertThat(wallet(result, "BTC").total()).isEqualByComparingTo("10.80880000");
    assertThat(wallet(result, "USDT").total()).isGreaterThan(new BigDecimal("99900"));
  }

  @Test
  void partialSellUsesThePersistedEightDecimalAverageCostForPositionPnl() {
    Snapshot result = oracle.calculate(scenario("SPOT_CORE_BUY_PARTIAL_SELL"))
        .checkpoints().get(1).snapshot();

    assertThat(result.trades().getLast().realizedPnl()).isZero();
    assertThat(position(result).realizedPnl()).isEqualByComparingTo("4.45862799");
  }

  @Test
  void repeatedBuysUseThePersistedWeightedAverageCost() {
    Snapshot result = oracle.calculate(scenario("SPOT_ADD_UP"))
        .checkpoints().getLast().snapshot();

    assertThat(position(result).quantity()).isEqualByComparingTo("1.31900000");
    assertThat(position(result).averageEntry()).isEqualByComparingTo("113.65465470");
    assertThat(wallet(result, "USDT").total()).isEqualByComparingTo("99850.01455520");
    assertThat(wallet(result, "BTC").total()).isEqualByComparingTo("11.31900000");
  }

  @Test
  void everyFullFillUsesTheFirstTradeReferenceWithinItsOwnOrder() {
    Snapshot result = oracle.calculate(scenario("SPOT_MULTI_BUY_SELL"))
        .finalCheckpoint().snapshot();

    assertThat(result.trades()).hasSizeGreaterThan(1);
    assertThat(result.trades()).allSatisfy(trade ->
        assertThat(trade.ref()).isEqualTo(trade.orderRef() + "-trade-1"));
  }

  @Test
  void repeatedPartialSellsKeepAverageCostAndLeavePositiveQuantity() {
    Snapshot result = oracle.calculate(scenario("SPOT_CORE_PARTIAL_SELL_PARTIAL_SELL"))
        .checkpoints().getLast().snapshot();

    assertThat(position(result).quantity()).isEqualByComparingTo("0.50000000");
    assertThat(position(result).averageEntry()).isEqualByComparingTo("100.00000000");
    assertThat(position(result).realizedPnl()).isEqualByComparingTo("9.15445796");
  }

  @Test
  void fullSellThenRebuyStartsANewCostCycle() {
    ExpectedScenarioResult result = oracle.calculate(scenario("SPOT_FULL_ZERO_REBUY_RESET"));

    PositionState afterFullSell = position(result.checkpoints().getFirst().snapshot());
    assertThat(afterFullSell.quantity()).isEqualByComparingTo("0.00000000");
    assertThat(afterFullSell.averageEntry()).isEqualByComparingTo("0.00000000");
    assertThat(afterFullSell.unrealizedPnl()).isEqualByComparingTo("0.00000000");

    PositionState afterRebuy = position(result.checkpoints().getLast().snapshot());
    assertThat(afterRebuy.quantity()).isEqualByComparingTo("0.99440000");
    assertThat(afterRebuy.averageEntry()).isEqualByComparingTo("100.51005000");
    assertThat(afterRebuy.realizedPnl()).isEqualByComparingTo("194.34305475");
  }

  @Test
  void limitAndStopOrdersUseCeilingHolds() {
    Snapshot limit = oracle.calculate(scenario("SPOT_LIMIT_WAIT"))
        .checkpoints().getFirst().snapshot();
    Snapshot stop = oracle.calculate(scenario("SPOT_STOP_MARKET_PENDING"))
        .checkpoints().getFirst().snapshot();

    assertThat(limit.orders()).singleElement()
        .extracting(OrderState::holdAmount)
        .isEqualTo(new BigDecimal("95.04750000"));
    assertThat(wallet(limit, "USDT").locked()).isEqualByComparingTo("95.04750000");
    assertThat(stop.orders()).singleElement()
        .extracting(OrderState::holdAmount)
        .isEqualTo(new BigDecimal("110.06600550"));
    assertThat(wallet(stop, "USDT").locked()).isEqualByComparingTo("110.06600550");
    assertThat(limit.ledger()).singleElement().satisfies(entry -> {
      assertThat(entry.type()).isEqualTo("SPOT_ORDER_LOCK");
      assertThat(entry.asset()).isEqualTo("USDT");
      assertThat(entry.amount()).isEqualByComparingTo("-95.04750000");
      assertThat(entry.balanceAfter()).isEqualByComparingTo("99904.95250000");
      assertThat(entry.referenceType()).isEqualTo("ORDER");
      assertThat(entry.reference()).isEqualTo("SPOT_LIMIT_WAIT-order-1");
    });
  }

  @Test
  void marketableModifyKeepsTheOrderIdAndRecordsOnlyTheActualHoldTopUp() {
    Snapshot result = oracle.calculate(scenario("SPOT_LIMIT_MODIFY"))
        .finalCheckpoint().snapshot();

    assertThat(result.orders()).singleElement().satisfies(order -> {
      assertThat(order.ref()).isEqualTo("SPOT_LIMIT_MODIFY-order-1");
      assertThat(order.status()).isEqualTo("FILLED");
      assertThat(order.avgFillPrice()).isEqualByComparingTo("100.50000000");
      assertThat(order.holdAmount()).isZero();
    });
    assertThat(result.trades()).singleElement().satisfies(trade -> {
      assertThat(trade.orderRef()).isEqualTo("SPOT_LIMIT_MODIFY-order-1");
      assertThat(trade.uniqueKey()).isEqualTo("SPOT_LIMIT_MODIFY-client-1");
      assertThat(trade.liquidityRole().name()).isEqualTo("TAKER");
    });
    assertThat(result.ledger()).extracting(LedgerState::type)
        .containsExactly(
            "SPOT_ORDER_LOCK",
            "SPOT_ORDER_LOCK",
            "SPOT_BUY_DEBIT",
            "TRADE_FEE",
            "SPOT_BUY_CREDIT");
    assertThat(result.ledger()).extracting(LedgerState::amount)
        .containsExactly(
            new BigDecimal("-95.04750000"),
            new BigDecimal("-5.50275000"),
            new BigDecimal("-100.50000000"),
            new BigDecimal("-0.05025000"),
            new BigDecimal("1.00000000"));
    assertThat(result.ledger().get(1)).satisfies(entry -> {
      assertThat(entry.referenceType()).isEqualTo("ORDER_MODIFICATION");
      assertThat(entry.reference()).isEqualTo("SPOT_LIMIT_MODIFY-action-2");
      assertThat(entry.balanceAfter()).isEqualByComparingTo("99899.44975000");
    });
    assertThat(result.events()).extracting(ExpectedScenarioResult.EventState::type)
        .containsExactly("ORDER_PENDING", "ORDER_MODIFIED", "ORDER_FILLED");
  }

  @Test
  void pendingFillDebitsLockedFundsThenReleasesTheRemainderBeforeCreditsAndFee() {
    Snapshot result = oracle.calculate(scenario("SPOT_LIMIT_TRIGGER"))
        .finalCheckpoint().snapshot();

    assertThat(result.ledger()).extracting(LedgerState::type)
        .containsExactly(
            "SPOT_ORDER_LOCK",
            "SPOT_BUY_DEBIT",
            "TRADE_FEE",
            "ORDER_RELEASE",
            "SPOT_BUY_CREDIT");
    assertThat(result.ledger()).extracting(LedgerState::amount)
        .containsExactly(
            new BigDecimal("-85.04250000"),
            new BigDecimal("-80.50000000"),
            new BigDecimal("-0.01610000"),
            new BigDecimal("4.52640000"),
            new BigDecimal("1.00000000"));
    assertThat(result.ledger().get(3)).satisfies(entry -> {
      assertThat(entry.referenceType()).isEqualTo("ORDER");
      assertThat(entry.reference()).isEqualTo("SPOT_LIMIT_TRIGGER-order-1");
      assertThat(entry.balanceAfter()).isEqualByComparingTo("99919.48390000");
    });
  }

  @Test
  void triggeredStopBuyAtomicallyTopsUpItsHoldAtTheCanonicalExecutionPrice() {
    Snapshot result = oracle.calculate(scenario("SPOT_STOP_MARKET_EXACT"))
        .finalCheckpoint().snapshot();

    assertThat(result.ledger()).extracting(LedgerState::type)
        .containsExactly(
            "SPOT_ORDER_LOCK",
            "SPOT_ORDER_LOCK",
            "SPOT_BUY_DEBIT",
            "TRADE_FEE",
            "SPOT_BUY_CREDIT");
    assertThat(result.ledger().get(1)).satisfies(entry -> {
      assertThat(entry.amount()).isEqualByComparingTo("-0.50030003");
      assertThat(entry.referenceType()).isEqualTo("ORDER_TRIGGER");
      assertThat(entry.reference()).isEqualTo("SPOT_STOP_MARKET_EXACT-order-1");
    });
    assertThat(wallet(result, "USDT").locked()).isZero();
    assertThat(wallet(result, "USDT").total())
        .isEqualByComparingTo(wallet(result, "USDT").available());
  }

  @Test
  void concurrentBalanceRacePreservesTheCommittedWinnerAndExposesTheLoserFailure() {
    ExpectedScenarioResult expected = oracle.calculate(scenario("SPOT_BALANCE_RACE"));
    Snapshot result = expected.finalCheckpoint().snapshot();

    assertThat(expected.finalCheckpoint().failure()).satisfies(failure -> {
      assertThat(failure.code()).isEqualTo("INSUFFICIENT_BALANCE");
      assertThat(failure.zeroMutation()).isFalse();
    });
    assertThat(result.orders()).singleElement()
        .extracting(OrderState::ref)
        .isEqualTo("SPOT_BALANCE_RACE-order-1");
    assertThat(result.trades()).hasSize(1);
    assertThat(result.ledger()).extracting(LedgerState::type)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "SPOT_BUY_CREDIT");
    assertThat(wallet(result, "USDT").available()).isLessThan(new BigDecimal("6000"));
  }

  @Test
  void ocoLegsShareOneHoldOwnerInsteadOfSummingBothLegs() {
    Snapshot result = oracle.calculate(scenario("SPOT_OCO_GROUP_CANCEL"))
        .checkpoints().getFirst().snapshot();

    assertThat(result.orders()).hasSize(2);
    assertThat(result.orders()).filteredOn(order -> order.holdAmount().signum() > 0)
        .singleElement()
        .extracting(OrderState::holdAmount)
        .isEqualTo(new BigDecimal("1.00000000"));
    assertThat(result.orders()).extracting(OrderState::holdOwnerRef)
        .containsOnly("SPOT_OCO_GROUP_CANCEL-limit");
    assertThat(wallet(result, "BTC").locked()).isEqualByComparingTo("1.00000000");
  }

  @Test
  void ocoRaceCalculatesBothCompleteWinnerOutcomesFromIndependentStates() {
    ScenarioDefinition scenario = scenario("SPOT_OCO_DUAL_TRIGGER_RACE");
    List<ExpectedScenarioResult> alternatives = oracle.calculateAll(scenario);

    assertThat(alternatives).hasSize(2).allSatisfy(result -> {
      assertThat(result.caseId()).isEqualTo(scenario.caseId());
      assertThat(result.checkpoints()).hasSize(scenario.actions().size());
    });
    ExpectedScenarioResult primary = alternatives.getFirst();
    ExpectedScenarioResult competitor = alternatives.getLast();
    assertThat(competitor.checkpoints().getFirst())
        .usingRecursiveComparison()
        .isEqualTo(primary.checkpoints().getFirst());

    Snapshot primaryFinal = primary.finalCheckpoint().snapshot();
    Snapshot competitorFinal = competitor.finalCheckpoint().snapshot();
    String primaryRef = scenario.actions().getLast().parameters().orderId();
    String competitorRef = scenario.actions().getLast().parameters().competingOrderId();
    assertThat(primaryFinal.orders())
        .filteredOn(order -> order.ref().equals(primaryRef))
        .singleElement()
        .satisfies(order -> {
          assertThat(order.status()).isEqualTo("FILLED");
          assertThat(order.type()).isEqualTo(OrderType.LIMIT);
          assertThat(order.origin()).isEqualTo("OCO");
        });
    assertThat(competitorFinal.orders())
        .filteredOn(order -> order.ref().equals(competitorRef))
        .singleElement()
        .satisfies(order -> {
          assertThat(order.status()).isEqualTo("FILLED");
          assertThat(order.type()).isEqualTo(OrderType.STOP_MARKET);
          assertThat(order.origin()).isEqualTo("OCO");
        });
    assertThat(primaryFinal.trades()).singleElement()
        .satisfies(trade -> assertThat(trade.orderRef()).isEqualTo(primaryRef));
    assertThat(competitorFinal.trades()).singleElement()
        .satisfies(trade -> assertThat(trade.orderRef()).isEqualTo(competitorRef));
    assertThat(wallet(primaryFinal, "USDT").total())
        .isNotEqualByComparingTo(wallet(competitorFinal, "USDT").total());
  }

  @Test
  void spotWalletTradesDoNotMutateTheIndependentPerpetualAccountSummary() {
    ExpectedScenarioResult result = oracle.calculate(scenario("SPOT_ADD_UP"));

    assertThat(result.checkpoints()).allSatisfy(checkpoint -> {
      assertThat(checkpoint.snapshot().account().balance())
          .isEqualByComparingTo("100000.00000000");
      assertThat(checkpoint.snapshot().account().equity())
          .isEqualByComparingTo("100000.00000000");
      assertThat(checkpoint.snapshot().account().usedMargin()).isZero();
      assertThat(checkpoint.snapshot().account().freeMargin())
          .isEqualByComparingTo("100000.00000000");
    });
    assertThat(wallet(result.finalCheckpoint().snapshot(), "USDT").total())
        .isNotEqualByComparingTo("100000.00000000");
  }

  @Test
  void rejectionCodeIsDerivedWithoutReadingTheCatalogExpectedError() {
    ScenarioDefinition source = scenario("SPOT_PRICE_PRECISION_REJECT");
    ScenarioDefinition tampered = new ScenarioDefinition(
        source.caseId(),
        source.priority(),
        source.productType(),
        source.positionMode(),
        source.positionSide(),
        source.marginMode(),
        source.leverage(),
        source.orderType(),
        source.quantityUnit(),
        source.reduceOnly(),
        source.initialBalances(),
        source.initialPosition(),
        source.initialOrders(),
        source.priceSteps(),
        source.actions(),
        source.exitReason(),
        source.expectedOrder(),
        source.expectedTrades(),
        source.expectedPosition(),
        source.expectedWallet(),
        source.expectedAccount(),
        source.expectedLedger(),
        source.expectedProtections(),
        source.expectedEvents(),
        "WRONG_CATALOG_ANSWER",
        source.testClass(),
        source.testMethod(),
        source.executionStatus());

    assertThat(oracle.calculate(tampered).failure()).get()
        .extracting(ExpectedScenarioResult.FailureState::code)
        .isEqualTo("PRICE_TICK_MISMATCH");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("spotInputDerivedFailures")
  void deterministicRejectionIsDerivedFromInputInsteadOfFailureCondition(
      String caseId,
      String expectedCode
  ) {
    ScenarioDefinition source = scenario(caseId);
    ScenarioDefinition mislabeled = withLastFailureCondition(
        source,
        "NOTIONAL_BELOW_MINIMUM".equals(source.actions().getLast().parameters()
            .failureCondition())
            ? "PRICE_SCALE_EXCEEDS_INSTRUMENT"
            : "NOTIONAL_BELOW_MINIMUM");

    assertThat(oracle.calculate(mislabeled).failure()).get()
        .extracting(ExpectedScenarioResult.FailureState::code)
        .isEqualTo(expectedCode);
  }

  @Test
  void coreChainsConsumeTheThirdPriceSegmentWithoutCreatingAnotherTrade() {
    ScenarioDefinition scenario = scenario("SPOT_CORE_BUY_BUY");
    ExpectedScenarioResult result = oracle.calculate(scenario);

    assertThat(result.checkpoints()).hasSize(3);
    assertThat(result.finalCheckpoint().actionId()).endsWith("action-3");
    assertThat(position(result.finalCheckpoint().snapshot()).markPrice())
        .isEqualByComparingTo(scenario.priceSteps().getLast().last());
    assertThat(result.finalCheckpoint().snapshot().trades()).hasSize(2);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("spotScenarios")
  void everySpotMatrixRowProducesOneImmutableCheckpointPerAction(
      ScenarioDefinition scenario
  ) {
    ExpectedScenarioResult result = oracle.calculate(scenario);

    assertThat(result.caseId()).isEqualTo(scenario.caseId());
    assertThat(result.checkpoints()).hasSameSizeAs(scenario.actions());
    assertThat(result.checkpoints()).allSatisfy(checkpoint -> {
      assertThat(checkpoint.snapshot().wallets()).allSatisfy(wallet -> {
        assertThat(wallet.total()).isNotNegative();
        assertThat(wallet.available()).isNotNegative();
        assertThat(wallet.locked()).isNotNegative();
        assertThat(wallet.total())
            .isEqualByComparingTo(wallet.available().add(wallet.locked()));
      });
    });
    if (scenario.expectedError().isBlank()) {
      assertThat(result.failure()).isEmpty();
    } else {
      assertThat(result.failure()).isPresent()
          .get()
          .extracting(ExpectedScenarioResult.FailureState::code)
          .isEqualTo(scenario.expectedError());
    }
  }

  private static ScenarioDefinition scenario(String caseId) {
    return ScenarioCatalog.spot()
        .filter(candidate -> candidate.caseId().equals(caseId))
        .findFirst()
        .orElseThrow();
  }

  private static Stream<ScenarioDefinition> spotScenarios() {
    return ScenarioCatalog.spot();
  }

  private static Stream<Arguments> spotInputDerivedFailures() {
    return Stream.of(
        Arguments.of("SPOT_INSUFFICIENT_BALANCE", "INSUFFICIENT_BALANCE"),
        Arguments.of("SPOT_OVERSELL", "INSUFFICIENT_BALANCE"),
        Arguments.of("SPOT_QUANTITY_PRECISION_REJECT", "QUANTITY_STEP_MISMATCH"),
        Arguments.of("SPOT_PRICE_PRECISION_REJECT", "PRICE_TICK_MISMATCH"),
        Arguments.of("SPOT_MIN_NOTIONAL_REJECT", "ORDER_NOTIONAL_TOO_SMALL"),
        Arguments.of("SPOT_CLIENT_ORDER_CONFLICT", "DUPLICATE_CLIENT_ORDER_ID"),
        Arguments.of("SPOT_LEGACY_STOP_REJECT", "INVALID_SPOT_ORDER_TYPE"),
        Arguments.of("SPOT_LEVERAGE_REJECT", "INVALID_SPOT_ORDER_FIELDS"),
        Arguments.of("SPOT_REDUCE_ONLY_REJECT", "INVALID_SPOT_ORDER_FIELDS"),
        Arguments.of("SPOT_PROTECTION_REJECT", "INVALID_SPOT_ORDER_FIELDS"));
  }

  private static ScenarioDefinition withLastFailureCondition(
      ScenarioDefinition source,
      String failureCondition
  ) {
    List<ScenarioAction> actions = new ArrayList<>(source.actions());
    ScenarioAction action = actions.getLast();
    ScenarioAction.Parameters value = action.parameters();
    actions.set(actions.size() - 1, new ScenarioAction(
        action.type(),
        action.direction(),
        action.quantity(),
        new ScenarioAction.Parameters(
            value.actionId(),
            value.clientOrderId(),
            value.orderId(),
            value.competingOrderId(),
            value.protectionId(),
            value.price(),
            value.triggerPrice(),
            value.leverage(),
            value.marginDelta(),
            value.fundingRate(),
            value.feeRate(),
            value.makerFeeRate(),
            value.takerFeeRate(),
            value.worstFeeRate(),
            value.slippageRate(),
            value.maintenanceMarginRate(),
            value.liquidationFeeRate(),
            value.quantityUnit(),
            value.reduceOnly(),
            value.side(),
            value.orderType(),
            value.protectionType(),
            value.triggerExecutionType(),
            value.triggerPriceType(),
            value.condition(),
            failureCondition),
        action.detail()));
    return new ScenarioDefinition(
        source.caseId(), source.priority(), source.productType(), source.positionMode(),
        source.positionSide(), source.marginMode(), source.leverage(), source.orderType(),
        source.quantityUnit(), source.reduceOnly(), source.initialBalances(),
        source.initialPosition(), source.initialOrders(), source.priceSteps(), actions,
        source.exitReason(), source.expectedOrder(), source.expectedTrades(),
        source.expectedPosition(), source.expectedWallet(), source.expectedAccount(),
        source.expectedLedger(), source.expectedProtections(), source.expectedEvents(),
        source.expectedError(), source.testClass(), source.testMethod(),
        source.executionStatus());
  }

  private static WalletState wallet(Snapshot snapshot, String asset) {
    return snapshot.wallets().stream()
        .filter(wallet -> wallet.asset().equals(asset))
        .findFirst()
        .orElseThrow();
  }

  private static PositionState position(Snapshot snapshot) {
    return snapshot.positions().getFirst();
  }
}
