package com.fxplatform.trading.scenario.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.scenario.ExpectedScenarioResult;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.PositionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.TradeState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.WalletState;
import com.fxplatform.trading.scenario.ScenarioAction;
import com.fxplatform.trading.scenario.ScenarioCatalog;
import com.fxplatform.trading.scenario.ScenarioDefinition;
import com.fxplatform.trading.scenario.ScenarioPriceStep;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PerpetualScenarioOracleTest {

  private static final BigDecimal MMR = decimal("0.005");
  private static final BigDecimal CLOSE_FEE_RATE = decimal("0.0005");

  private final PerpetualScenarioOracle oracle = new PerpetualScenarioOracle();

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("perpetualScenarios")
  void everyCatalogRowProducesACompleteCheckpointForEveryAction(
      ScenarioDefinition scenario
  ) {
    ExpectedScenarioResult result = oracle.calculate(scenario);

    assertThat(result.caseId()).isEqualTo(scenario.caseId());
    assertThat(result.checkpoints()).hasSize(scenario.actions().size());
    for (int index = 0; index < scenario.actions().size(); index++) {
      assertThat(result.checkpoints().get(index).actionIndex()).isEqualTo(index + 1);
      assertThat(result.checkpoints().get(index).actionId())
          .isEqualTo(scenario.actions().get(index).parameters().actionId());
      assertThat(result.checkpoints().get(index).snapshot()).satisfies(snapshot -> {
        assertThat(snapshot.orders()).doesNotContainNull();
        assertThat(snapshot.trades()).doesNotContainNull();
        assertThat(snapshot.positions()).doesNotContainNull();
        assertThat(snapshot.wallets()).doesNotContainNull();
        assertThat(snapshot.account()).isNotNull();
        assertThat(snapshot.ledger()).doesNotContainNull();
        assertThat(snapshot.protections()).doesNotContainNull();
        assertThat(snapshot.events()).doesNotContainNull();
        assertSnapshotAccounting(snapshot);
      });
      assertHandledAction(scenario, result, index);
    }
    assertThat(result.calculationTrace())
        .noneMatch(line -> line.contains("no Perpetual state transition"));
    if (!scenario.expectedError().isBlank()) {
      assertThat(result.failure()).isPresent();
      assertThat(result.failure()).get()
          .extracting(ExpectedScenarioResult.FailureState::code)
          .isEqualTo(scenario.expectedError());
    }
    if ("NONE".equals(scenario.expectedTrades())) {
      assertThat(result.finalCheckpoint().snapshot().trades()).isEmpty();
    }
  }

  @Test
  void longAndShortPnlUseOppositeDirectionalFormulas() {
    PositionState longPosition = position(
        oracle.calculate(scenario("PERP_CLOSE_PROFIT")).checkpoints().getFirst().snapshot(),
        PositionSide.BOTH);
    PositionState shortPosition = position(
        oracle.calculate(scenario("PERP_FUNDING_POSITIVE_SHORT"))
            .checkpoints().getFirst().snapshot(),
        PositionSide.SHORT);

    assertThat(longPosition.unrealizedPnl()).isEqualByComparingTo(s8(
        longPosition.markPrice().subtract(longPosition.averageEntry())
            .multiply(longPosition.quantity())));
    assertThat(shortPosition.unrealizedPnl()).isEqualByComparingTo(s8(
        shortPosition.averageEntry().subtract(shortPosition.markPrice())
            .multiply(shortPosition.quantity())));

    Snapshot profitableClose = finalSnapshot("PERP_CLOSE_PROFIT");
    Snapshot losingClose = finalSnapshot("PERP_CLOSE_LOSS");
    Snapshot shortClose = finalSnapshot("PERP_SHORT_TO_LONG_REVERSAL");

    assertThat(profitableClose.trades().getLast().realizedPnl()).isPositive();
    assertThat(losingClose.trades().getLast().realizedPnl()).isNegative();
    assertThat(shortClose.trades().getLast().realizedPnl()).isEqualByComparingTo(s8(
        shortClose.trades().getFirst().price()
            .subtract(shortClose.trades().getLast().price())));
  }

  @Test
  void profitableCloseHoldUsesMarkToEstimatedFillOrderLossPlusClosingFee() {
    ScenarioDefinition definition = scenario("PERP_CLOSE_PROFIT");
    ExpectedScenarioResult result = oracle.calculate(definition);
    Snapshot beforeClose = result.checkpoints().getFirst().snapshot();
    Snapshot afterClose = result.finalCheckpoint().snapshot();
    TradeState close = afterClose.trades().getLast();
    BigDecimal mark = definition.priceSteps().get(2).mark();
    BigDecimal expectedHold = s8(mark.subtract(close.price()).max(BigDecimal.ZERO)
        .multiply(close.quantity())
        .add(close.fee()));

    assertThat(singleEntry(ledgerDelta(beforeClose, afterClose), "ORDER_HOLD").amount())
        .isEqualByComparingTo(expectedHold)
        .isEqualByComparingTo("0.67169403");
  }

  @Test
  void adminCloseUsesTheSameMarkBasedOrderLossReserveAsAUserClose() {
    ScenarioDefinition definition = scenario("PERP_ADMIN_FORCE_CLOSE");
    ExpectedScenarioResult result = oracle.calculate(definition);
    Snapshot beforeClose = result.checkpoints().getFirst().snapshot();
    Snapshot afterClose = result.finalCheckpoint().snapshot();
    TradeState close = afterClose.trades().getLast();
    BigDecimal mark = definition.priceSteps().get(2).mark();
    BigDecimal expectedHold = s8(mark.subtract(close.price()).max(BigDecimal.ZERO)
        .multiply(close.quantity())
        .add(close.fee()));

    assertThat(singleEntry(ledgerDelta(beforeClose, afterClose), "ORDER_HOLD").amount())
        .isEqualByComparingTo(expectedHold)
        .isEqualByComparingTo("0.65969503");
  }

  @Test
  void isolatedUnrealizedPnlStaysInsideTheSlotInsteadOfBecomingCrossFreeMargin() {
    ExpectedScenarioResult result =
        oracle.calculate(scenario("PERP_ISOLATED_MARGIN_REDUCE_SAFE"));

    for (Checkpoint checkpoint : result.checkpoints()) {
      Snapshot snapshot = checkpoint.snapshot();
      PositionState isolated = position(snapshot, PositionSide.BOTH);
      BigDecimal expectedFree = s8(
          wallet(snapshot).total().subtract(isolated.marginHeld()));

      assertThat(snapshot.account().freeMargin()).isEqualByComparingTo(expectedFree);
      assertThat(snapshot.account().freeMargin())
          .isEqualByComparingTo(wallet(snapshot).available());
    }
  }

  @Test
  void sameDirectionAddUsesWeightedEntryAndPartialAndFullCloseKeepTheCostCycle() {
    Snapshot added = finalSnapshot("PERP_SAME_SIDE_ADD");
    BigDecimal expectedEntry = s8(
        added.trades().get(0).quantity().multiply(added.trades().get(0).price())
            .add(added.trades().get(1).quantity().multiply(added.trades().get(1).price()))
            .divide(decimal("3"), 18, RoundingMode.HALF_UP));

    assertThat(position(added, PositionSide.BOTH).quantity()).isEqualByComparingTo("3");
    assertThat(position(added, PositionSide.BOTH).averageEntry())
        .isEqualByComparingTo(expectedEntry);

    ExpectedScenarioResult partialResult =
        oracle.calculate(scenario("PERP_SAME_SIDE_PARTIAL_CLOSE"));
    PositionState beforePartial = position(
        partialResult.checkpoints().getFirst().snapshot(), PositionSide.BOTH);
    PositionState afterPartial = position(
        partialResult.finalCheckpoint().snapshot(), PositionSide.BOTH);
    assertThat(afterPartial.quantity()).isEqualByComparingTo("1");
    assertThat(afterPartial.averageEntry()).isEqualByComparingTo(beforePartial.averageEntry());

    PositionState fullyClosed = position(
        finalSnapshot("PERP_SAME_SIDE_FULL_CLOSE"), PositionSide.BOTH);
    assertThat(fullyClosed.status()).isEqualTo("CLOSED");
    assertThat(fullyClosed.quantity()).isEqualByComparingTo("0");
    assertThat(fullyClosed.averageEntry()).isEqualByComparingTo("0");
    assertThat(fullyClosed.unrealizedPnl()).isEqualByComparingTo("0");
  }

  @Test
  void everyFullFillUsesTheFirstTradeReferenceWithinItsOwnOrder() {
    Snapshot result = finalSnapshot("PERP_SAME_SIDE_ADD");

    assertThat(result.trades()).hasSizeGreaterThan(1);
    assertThat(result.trades()).allSatisfy(trade ->
        assertThat(trade.ref()).isEqualTo(trade.orderRef() + "-trade-1"));
  }

  @Test
  void ordinaryCloseFillLedgersUseThePostTradeFeeBalance() {
    ExpectedScenarioResult result = oracle.calculate(scenario("PERP_CLOSE_PROFIT"));
    Snapshot before = result.checkpoints().getFirst().snapshot();
    Snapshot after = result.finalCheckpoint().snapshot();
    List<LedgerState> fillEntries = ledgerDelta(before, after).stream()
        .filter(entry -> List.of(
            "ORDER_RELEASE",
            "MARGIN_RELEASE",
            "TRADE_PNL",
            "FUNDING_FEE",
            "TRADE_FEE").contains(entry.type()))
        .toList();

    assertThat(fillEntries).extracting(LedgerState::type)
        .containsExactly("ORDER_RELEASE", "MARGIN_RELEASE", "TRADE_PNL", "TRADE_FEE");
    assertThat(fillEntries)
        .allSatisfy(entry -> assertThat(entry.balanceAfter())
            .isEqualByComparingTo(wallet(after).total()));
  }

  @Test
  void isolatedLiquidationFillLedgersUseThePostTradeFeeBalance() {
    ExpectedScenarioResult result =
        oracle.calculate(scenario("PERP_FUNDING_ISOLATED_LIQUIDATION"));
    Snapshot before = result.checkpoints().get(1).snapshot();
    Snapshot after = result.finalCheckpoint().snapshot();
    PositionState positionBefore = position(before, PositionSide.BOTH);
    TradeState trade = after.trades().getLast();
    BigDecimal postTradeFeeBalance = s8(wallet(before).total()
        .add(trade.realizedPnl())
        .add(positionBefore.fundingPnl())
        .subtract(trade.fee()));
    List<LedgerState> fillEntries = ledgerDelta(before, after).stream()
        .filter(entry -> List.of(
            "ORDER_RELEASE",
            "MARGIN_RELEASE",
            "TRADE_PNL",
            "FUNDING_FEE",
            "TRADE_FEE").contains(entry.type()))
        .toList();

    assertThat(fillEntries).extracting(LedgerState::type)
        .containsExactly("MARGIN_RELEASE", "TRADE_PNL", "FUNDING_FEE", "TRADE_FEE");
    assertThat(fillEntries)
        .allSatisfy(entry -> assertThat(entry.balanceAfter())
            .isEqualByComparingTo(postTradeFeeBalance));
  }

  @Test
  void oneWayOppositeFillSettlesTheOldSlotBeforeOpeningTheRemainder() {
    Snapshot snapshot = finalSnapshot("PERP_LONG_TO_SHORT_REVERSAL");
    PositionState position = position(snapshot, PositionSide.BOTH);

    assertThat(snapshot.positions()).hasSize(1);
    assertThat(position.side()).isEqualTo(OrderSide.SELL);
    assertThat(position.quantity()).isEqualByComparingTo("1");
    assertThat(position.averageEntry())
        .isEqualByComparingTo(snapshot.trades().getLast().price());
    assertThat(position.realizedPnl()).isZero();
    assertThat(snapshot.trades().getLast().realizedPnl()).isEqualByComparingTo(s8(
        snapshot.trades().getLast().price()
            .subtract(snapshot.trades().getFirst().price())));
  }

  @Test
  void hedgeModeKeepsLongAndShortSlotsIndependent() {
    Snapshot snapshot = finalSnapshot("PERP_HEDGE_INDEPENDENT");

    assertThat(snapshot.positions()).hasSize(2);
    assertThat(position(snapshot, PositionSide.LONG).quantity()).isEqualByComparingTo("1");
    assertThat(position(snapshot, PositionSide.LONG).side()).isEqualTo(OrderSide.BUY);
    assertThat(position(snapshot, PositionSide.SHORT).quantity()).isEqualByComparingTo("1");
    assertThat(position(snapshot, PositionSide.SHORT).side()).isEqualTo(OrderSide.SELL);
  }

  @Test
  void baseQuoteAndContractsNormalizeOnceToCanonicalBaseQuantity() {
    Snapshot base = finalSnapshot("PERP_QUANTITY_BASE");
    Snapshot quote = finalSnapshot("PERP_QUANTITY_QUOTE");
    Snapshot contracts = finalSnapshot("PERP_QUANTITY_CONTRACTS");

    assertCanonicalQuantity(base, "0.01000000");
    assertCanonicalQuantity(quote, "0.99900000");
    assertCanonicalQuantity(contracts, "1.00000000");
  }

  @Test
  void initialAndMaintenanceMarginUseEntryAndMarkNotional() {
    Snapshot snapshot = finalSnapshot("PERP_CROSS_MARGIN");
    PositionState position = position(snapshot, PositionSide.BOTH);
    BigDecimal entryNotional = s8(position.quantity().multiply(position.averageEntry()));
    BigDecimal markNotional = s8(position.quantity().multiply(position.markPrice()));

    assertThat(position.initialMargin())
        .isEqualByComparingTo(s8(entryNotional.divide(decimal("10"), 18, RoundingMode.HALF_UP)));
    assertThat(position.maintenanceMargin())
        .isEqualByComparingTo(s8(markNotional.multiply(MMR)));
    assertThat(position.notional()).isEqualByComparingTo(markNotional);
    assertThat(position.marginHeld()).isEqualByComparingTo(position.initialMargin());
    assertThat(snapshot.account().usedMargin()).isEqualByComparingTo(position.marginHeld());
    assertThat(snapshot.account().maintenanceMargin())
        .isEqualByComparingTo(position.maintenanceMargin());
    assertThat(wallet(snapshot).locked()).isEqualByComparingTo(position.marginHeld());
  }

  @Test
  void leverageIncreaseReleasesMarginAndDecreaseAddsMargin() {
    Snapshot increaseSnapshot = finalSnapshot("PERP_LEVERAGE_UP");
    PositionState afterIncrease = position(increaseSnapshot, PositionSide.BOTH);
    Snapshot decreaseSnapshot = finalSnapshot("PERP_LEVERAGE_DOWN_SAFE");
    PositionState afterDecrease = position(
        decreaseSnapshot, PositionSide.BOTH);

    assertThat(afterIncrease.leverage()).isEqualTo(20);
    assertThat(afterIncrease.initialMargin()).isEqualByComparingTo("5");
    assertThat(afterIncrease.marginHeld()).isEqualByComparingTo("5");
    assertThat(increaseSnapshot.ledger())
        .filteredOn(entry -> entry.type().equals("MARGIN_RELEASE"))
        .singleElement()
        .satisfies(entry -> assertThat(entry.amount()).isEqualByComparingTo("5"));
    assertThat(increaseSnapshot.events()).isEmpty();
    assertThat(afterDecrease.leverage()).isEqualTo(5);
    assertThat(afterDecrease.initialMargin()).isEqualByComparingTo("20");
    assertThat(afterDecrease.marginHeld()).isEqualByComparingTo("20");
    assertThat(decreaseSnapshot.ledger())
        .filteredOn(entry -> entry.type().equals("MARGIN_HOLD"))
        .singleElement()
        .satisfies(entry -> assertThat(entry.amount()).isEqualByComparingTo("10"));
  }

  @Test
  void isolatedMarginAdjustmentsRespectTheMaintenanceAndCloseFeeBoundary() {
    PositionState added = position(
        finalSnapshot("PERP_ISOLATED_MARGIN_ADD"), PositionSide.BOTH);
    assertThat(added.marginHeld().subtract(added.initialMargin()))
        .isEqualByComparingTo("100");

    ExpectedScenarioResult safeResult =
        oracle.calculate(scenario("PERP_ISOLATED_MARGIN_REDUCE_SAFE"));
    PositionState safe = position(safeResult.finalCheckpoint().snapshot(), PositionSide.BOTH);
    BigDecimal isolatedEquity = s8(
        safe.marginHeld().add(safe.fundingPnl()).add(safe.unrealizedPnl()));
    BigDecimal threshold = s8(safe.maintenanceMargin()
        .add(s8(safe.notional().multiply(CLOSE_FEE_RATE))));
    assertThat(isolatedEquity).isGreaterThan(threshold);
    assertThat(safeResult.failure()).isEmpty();

    ExpectedScenarioResult unsafe =
        oracle.calculate(scenario("PERP_ISOLATED_MARGIN_REDUCE_UNSAFE_REJECT"));
    assertThat(unsafe.failure()).get().satisfies(failure -> {
      assertThat(failure.code()).isEqualTo("MARGIN_REDUCTION_UNSAFE");
      assertThat(failure.zeroMutation()).isTrue();
    });
    assertDomainStateUnchanged(
        unsafe.checkpoints().getFirst().snapshot(),
        unsafe.finalCheckpoint().snapshot());
  }

  @Test
  void reduceOnlyBelowEqualAndAboveUseStrictBoundaries() {
    PositionState below = position(
        finalSnapshot("PERP_REDUCE_ONLY_BELOW"), PositionSide.BOTH);
    PositionState equal = position(
        finalSnapshot("PERP_REDUCE_ONLY_EQUAL"), PositionSide.BOTH);
    ExpectedScenarioResult above =
        oracle.calculate(scenario("PERP_REDUCE_ONLY_ABOVE_REJECT"));

    assertThat(below.quantity()).isEqualByComparingTo("1");
    assertThat(equal.status()).isEqualTo("CLOSED");
    assertThat(equal.quantity()).isEqualByComparingTo("0");
    assertThat(above.failure()).get().satisfies(failure -> {
      assertThat(failure.code()).isEqualTo("REDUCE_ONLY_EXCEEDS_POSITION");
      assertThat(failure.zeroMutation()).isTrue();
    });
    assertDomainStateUnchanged(
        above.checkpoints().getFirst().snapshot(),
        above.finalCheckpoint().snapshot());
  }

  @Test
  void fundingSignsFollowLongPaysShortAndZeroTransfersNothing() {
    assertFunding("PERP_FUNDING_POSITIVE_LONG", PositionSide.LONG, "-0.10010000");
    assertFunding("PERP_FUNDING_POSITIVE_SHORT", PositionSide.SHORT, "0.10010000");
    assertFunding("PERP_FUNDING_NEGATIVE_LONG", PositionSide.LONG, "0.10010000");
    assertFunding("PERP_FUNDING_NEGATIVE_SHORT", PositionSide.SHORT, "-0.10010000");
    assertFunding("PERP_FUNDING_ZERO_LONG", PositionSide.LONG, "0.00000000");
    assertFunding("PERP_FUNDING_ZERO_SHORT", PositionSide.SHORT, "0.00000000");
  }

  @Test
  void protectionBudgetsResizeNewestFirstAndExpireAtZero() {
    ExpectedScenarioResult resize = oracle.calculate(scenario("PERP_PROTECTION_RESIZE"));
    assertThat(resize.checkpoints().get(1).snapshot().protections()).singleElement()
        .satisfies(protection -> assertThat(protection.quantity()).isEqualByComparingTo("2"));
    assertThat(resize.checkpoints().get(2).snapshot().protections()).singleElement()
        .satisfies(protection -> assertThat(protection.quantity()).isEqualByComparingTo("1"));
    assertThat(resize.finalCheckpoint().snapshot().protections())
        .filteredOn(protection -> protection.status().equals("ACTIVE"))
        .singleElement()
        .satisfies(protection -> assertThat(protection.quantity()).isEqualByComparingTo("1"));
    assertThat(resize.finalCheckpoint().snapshot().protections()).hasSize(1);

    Snapshot expired = finalSnapshot("PERP_PROTECTION_EXPIRE");
    assertThat(expired.protections()).singleElement().satisfies(protection -> {
      assertThat(protection.status()).isEqualTo("EXPIRED");
      assertThat(protection.quantity()).isEqualByComparingTo("0");
    });
  }

  @Test
  void pendingModifyCancelAndTriggerMaterializeOrderHoldsAndTerminalStates() {
    Snapshot waiting = finalSnapshot("PERP_LIMIT_WAIT");
    assertThat(waiting.orders()).singleElement().satisfies(order -> {
      assertThat(order.status()).isEqualTo("PENDING");
      assertThat(order.holdAmount()).isPositive();
    });
    assertThat(waiting.trades()).isEmpty();
    assertThat(waiting.account().usedMargin())
        .isEqualByComparingTo(waiting.orders().getFirst().holdAmount());
    assertThat(waiting.ledger()).extracting(entry -> entry.type())
        .containsExactly("ORDER_HOLD");

    Snapshot modified = finalSnapshot("PERP_LIMIT_MODIFY");
    assertThat(modified.orders()).singleElement().satisfies(order -> {
      assertThat(order.ref()).isEqualTo("PERP_LIMIT_MODIFY-order-1");
      assertThat(order.status()).isEqualTo("PENDING");
      assertThat(order.quantity()).isEqualByComparingTo("1");
      assertThat(order.holdAmount()).isPositive();
    });
    assertThat(modified.trades()).isEmpty();
    assertThat(modified.events()).extracting(event -> event.type())
        .contains("ORDER_MODIFIED");
    assertThat(modified.ledger()).extracting(entry -> entry.type())
        .containsExactly("ORDER_HOLD", "ORDER_HOLD");

    Snapshot canceled = finalSnapshot("PERP_LIMIT_CANCEL");
    assertThat(canceled.orders()).singleElement().satisfies(order -> {
      assertThat(order.status()).isEqualTo("CANCELED");
      assertThat(order.holdAmount()).isZero();
    });
    assertThat(canceled.account().usedMargin()).isZero();
    assertThat(canceled.ledger()).extracting(entry -> entry.type())
        .containsExactly("ORDER_HOLD", "ORDER_RELEASE");

    for (String caseId : new String[] {
        "PERP_LIMIT_TRIGGER",
        "PERP_STOP_MARKET_EXACT",
        "PERP_STOP_MARKET_CROSS",
        "PERP_STOP_MARKET_GAP"
    }) {
      Snapshot triggered = finalSnapshot(caseId);
      assertThat(triggered.orders()).singleElement()
          .satisfies(order -> assertThat(order.status()).isEqualTo("FILLED"));
      assertThat(triggered.trades()).singleElement();
      assertThat(triggered.positions()).singleElement()
          .satisfies(position -> assertThat(position.status()).isEqualTo("OPEN"));
    }
  }

  @Test
  void attachedProtectionIsCreatedAtomicallyAndBoundToItsEntryOrder() {
    assertAttachedProtection(
        "PERP_ATTACHED_TP",
        ProtectionType.TAKE_PROFIT,
        "120",
        OrderType.STOP_MARKET);
    assertAttachedProtection(
        "PERP_ATTACHED_SL",
        ProtectionType.STOP_LOSS,
        "80",
        OrderType.STOP_MARKET);
  }

  @Test
  void protectionTriggerPreservesMetadataAndConsumesEachCarrierOnce() {
    assertTriggeredProtection(
        "PERP_TP_MARKET",
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET,
        null);
    assertTriggeredProtection(
        "PERP_SL_MARKET",
        ProtectionType.STOP_LOSS,
        TriggerExecutionType.MARKET,
        null);
    assertTriggeredProtection(
        "PERP_TP_LIMIT",
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.LIMIT,
        "129");
    assertTriggeredProtection(
        "PERP_SL_LIMIT",
        ProtectionType.STOP_LOSS,
        TriggerExecutionType.LIMIT,
        "69");

    Snapshot multi = finalSnapshot("PERP_MULTI_PROTECTION_TRIGGER");
    assertThat(multi.protections())
        .filteredOn(protection -> protection.status().equals("FILLED"))
        .singleElement();
    assertThat(multi.protections())
        .filteredOn(protection -> protection.status().equals("ACTIVE"))
        .singleElement();
  }

  @Test
  void protectionTriggerHoldUsesExecutablePriceAndLimitFeeBuffer() {
    assertThat(lastOrderHold("PERP_MULTI_PROTECTION_TRIGGER"))
        .isEqualByComparingTo("0.66569453");
    assertThat(lastOrderHold("PERP_SL_MARKET"))
        .isEqualByComparingTo("0.64169653");
    assertThat(lastOrderHold("PERP_TP_MARKET"))
        .isEqualByComparingTo("0.67769353");
    assertThat(lastOrderHold("PERP_SL_LIMIT"))
        .isEqualByComparingTo("1.13475000");
    assertThat(lastOrderHold("PERP_TP_LIMIT"))
        .isEqualByComparingTo("1.16475000");
  }

  @Test
  void executionLedgerKeepsRawHoldMarginPnlAndFeeSources() {
    Snapshot opened = finalSnapshot("PERP_ORDER_MARKET");
    assertThat(opened.ledger()).extracting(entry -> entry.type())
        .containsExactly("ORDER_HOLD", "ORDER_RELEASE", "MARGIN_HOLD", "TRADE_FEE");

    Snapshot closed = finalSnapshot("PERP_CLOSE_PROFIT");
    assertThat(closed.ledger()).extracting(entry -> entry.type())
        .contains(
            "ORDER_HOLD",
            "ORDER_RELEASE",
            "MARGIN_HOLD",
            "MARGIN_RELEASE",
            "TRADE_PNL",
            "TRADE_FEE")
        .doesNotContain("TRADE_SETTLEMENT");
    for (int index = 1; index < closed.ledger().size(); index++) {
      assertThat(closed.ledger().get(index).sequence())
          .isEqualTo(closed.ledger().get(index - 1).sequence() + 1);
    }
    assertThat(closed.ledger().getLast().balanceAfter())
        .isEqualByComparingTo(wallet(closed).total());
  }

  @Test
  void crossFundingMovesWalletWhileIsolatedFundingStaysInTheSlotUntilLiquidation() {
    ExpectedScenarioResult cross =
        oracle.calculate(scenario("PERP_FUNDING_CROSS_LIQUIDATION"));
    Snapshot crossBeforeFunding = cross.checkpoints().getFirst().snapshot();
    Snapshot crossAfterFunding = cross.checkpoints().get(1).snapshot();
    assertThat(position(crossAfterFunding, PositionSide.BOTH).fundingPnl()).isNegative();
    assertThat(wallet(crossAfterFunding).total())
        .isLessThan(wallet(crossBeforeFunding).total());

    ExpectedScenarioResult isolated =
        oracle.calculate(scenario("PERP_FUNDING_ISOLATED_LIQUIDATION"));
    Snapshot isolatedBeforeFunding = isolated.checkpoints().getFirst().snapshot();
    Snapshot isolatedAfterFunding = isolated.checkpoints().get(1).snapshot();
    assertThat(position(isolatedAfterFunding, PositionSide.BOTH).marginMode())
        .isEqualTo(MarginMode.ISOLATED);
    assertThat(position(isolatedAfterFunding, PositionSide.BOTH).fundingPnl()).isNegative();
    assertThat(wallet(isolatedAfterFunding).total())
        .isEqualByComparingTo(wallet(isolatedBeforeFunding).total());

    assertThat(position(cross.finalCheckpoint().snapshot(), PositionSide.BOTH).status())
        .isEqualTo("CLOSED");
    assertThat(position(isolated.finalCheckpoint().snapshot(), PositionSide.BOTH).status())
        .isEqualTo("CLOSED");
  }

  @Test
  void fundingShortfallKeepsTheContractualFeeAndAddsOneSettlementShortfall() {
    for (String caseId : List.of(
        "PERP_FUNDING_CROSS_LIQUIDATION",
        "PERP_FUNDING_ISOLATED_LIQUIDATION")) {
      ScenarioDefinition definition = scenario(caseId);
      ExpectedScenarioResult result = oracle.calculate(definition);
      Snapshot before = result.checkpoints().getFirst().snapshot();
      Snapshot after = result.checkpoints().get(1).snapshot();
      PositionState beforePosition = position(before, PositionSide.BOTH);
      ScenarioAction funding = definition.actions().get(1);
      BigDecimal mark = definition.priceSteps().get(2).mark();
      BigDecimal signed = s8(beforePosition.quantity().abs()
          .multiply(mark)
          .multiply(funding.parameters().fundingRate()));
      BigDecimal cashflow = beforePosition.side() == OrderSide.SELL
          ? signed
          : signed.negate();
      BigDecimal pool = beforePosition.marginMode() == MarginMode.ISOLATED
          ? s8(beforePosition.marginHeld().add(beforePosition.fundingPnl()))
          : wallet(before).total();
      BigDecimal shortfall = s8(pool.add(cashflow).negate().max(BigDecimal.ZERO));
      BigDecimal applied = s8(cashflow.add(shortfall));

      assertThat(shortfall).isPositive();
      assertThat(ledgerDelta(before, after)).satisfiesExactly(
          fee -> {
            assertThat(fee.type()).isEqualTo("FUNDING_FEE");
            assertThat(fee.amount()).isEqualByComparingTo(cashflow);
            assertThat(fee.referenceType()).isEqualTo("FUNDING_SETTLEMENT");
            assertThat(fee.reference()).isEqualTo(funding.parameters().actionId());
          },
          deficit -> {
            assertThat(deficit.type()).isEqualTo("BANKRUPTCY_SHORTFALL");
            assertThat(deficit.amount()).isEqualByComparingTo(shortfall);
            assertThat(deficit.referenceType()).isEqualTo("FUNDING_SETTLEMENT");
            assertThat(deficit.reference()).isEqualTo(funding.parameters().actionId());
          });
      assertThat(position(after, PositionSide.BOTH).fundingPnl()
          .subtract(beforePosition.fundingPnl()))
          .isEqualByComparingTo(applied);
      BigDecimal expectedWalletDelta = beforePosition.marginMode() == MarginMode.CROSS
          ? applied
          : BigDecimal.ZERO;
      assertThat(wallet(after).total().subtract(wallet(before).total()))
          .isEqualByComparingTo(expectedWalletDelta);
    }
  }

  @Test
  void isolatedSystemCloseRealizesAppliedFundingBeforeItsTradeFee() {
    ScenarioDefinition liquidationDefinition =
        scenario("PERP_FUNDING_ISOLATED_LIQUIDATION");
    ExpectedScenarioResult liquidation = oracle.calculate(liquidationDefinition);
    assertIsolatedFundingRealization(
        liquidation.checkpoints().get(1).snapshot(),
        liquidation.finalCheckpoint().snapshot());

    ScenarioAction admin = scenario("PERP_ADMIN_FORCE_CLOSE").actions().getLast();
    ScenarioDefinition adminDefinition = withActions(
        liquidationDefinition,
        List.of(
            liquidationDefinition.actions().get(0),
            liquidationDefinition.actions().get(1),
            admin));
    ExpectedScenarioResult adminResult = oracle.calculate(adminDefinition);
    assertIsolatedFundingRealization(
        adminResult.checkpoints().get(1).snapshot(),
        adminResult.finalCheckpoint().snapshot());
  }

  @Test
  void isolatedLiquidationChargesOnlySlotCapacityAndRecordsShortfallBeforeForcedClose() {
    ScenarioDefinition definition = scenario("PERP_FUNDING_ISOLATED_LIQUIDATION");
    ExpectedScenarioResult result = oracle.calculate(definition);
    Snapshot before = result.checkpoints().get(1).snapshot();
    Snapshot after = result.finalCheckpoint().snapshot();
    PositionState beforePosition = position(before, PositionSide.BOTH);
    TradeState closeTrade = after.trades().getLast();
    ScenarioAction liquidation = definition.actions().getLast();

    BigDecimal balanceBeforeFill = wallet(before).total();
    BigDecimal balanceAfterFill = s8(balanceBeforeFill
        .add(closeTrade.realizedPnl())
        .add(beforePosition.fundingPnl())
        .subtract(closeTrade.fee()));
    BigDecimal coreDebit = s8(
        balanceBeforeFill.subtract(balanceAfterFill).max(BigDecimal.ZERO));
    BigDecimal coreCapacity = beforePosition.marginHeld();
    BigDecimal coreShortfall = s8(
        coreDebit.subtract(coreCapacity).max(BigDecimal.ZERO));
    BigDecimal balanceAfterCoreCredit = s8(balanceAfterFill.add(coreShortfall));
    BigDecimal balanceFloorShortfall = s8(
        balanceAfterCoreCredit.negate().max(BigDecimal.ZERO));
    coreShortfall = s8(coreShortfall.add(balanceFloorShortfall));
    balanceAfterCoreCredit = s8(balanceAfterCoreCredit.add(balanceFloorShortfall));
    BigDecimal contractualFee = s8(closeTrade.quantity().multiply(closeTrade.price())
        .multiply(liquidation.parameters().liquidationFeeRate()));
    BigDecimal remainingCapacity = s8(
        coreCapacity.subtract(coreDebit).max(BigDecimal.ZERO));
    BigDecimal chargedFee = s8(contractualFee
        .min(remainingCapacity)
        .min(balanceAfterCoreCredit.max(BigDecimal.ZERO)));
    BigDecimal totalShortfall = s8(coreShortfall
        .add(contractualFee.subtract(chargedFee).max(BigDecimal.ZERO)));
    List<LedgerState> delta = ledgerDelta(before, after);
    List<LedgerState> liquidationFees = delta.stream()
        .filter(entry -> entry.type().equals("LIQUIDATION_FEE"))
        .toList();

    if (chargedFee.signum() == 0) {
      assertThat(liquidationFees).isEmpty();
    } else {
      assertThat(liquidationFees).singleElement().satisfies(entry -> {
        assertThat(entry.amount()).isEqualByComparingTo(chargedFee.negate());
        assertThat(entry.referenceType()).isEqualTo("POSITION");
        assertThat(entry.reference()).isEqualTo("BTCUSDT-PERP:BOTH");
      });
    }
    LedgerState shortfall = delta.stream()
        .filter(entry -> entry.type().equals("BANKRUPTCY_SHORTFALL"))
        .filter(entry -> entry.referenceType().equals("LIQUIDATION_ORDER"))
        .findFirst()
        .orElseThrow();
    assertThat(shortfall.amount()).isEqualByComparingTo(totalShortfall);
    assertThat(shortfall.reference()).isEqualTo(liquidation.parameters().orderId());
    assertThat(shortfall.sequence())
        .isLessThan(singleEntry(delta, "FORCED_CLOSE").sequence());
    if (!liquidationFees.isEmpty()) {
      assertThat(liquidationFees.getFirst().sequence()).isLessThan(shortfall.sequence());
    }
  }

  @Test
  void crossLiquidationDefersChargedFeesAndUsesTheCrossSettlementReference() {
    ScenarioDefinition exactDefinition = scenario("PERP_LIQUIDATION_EXACT_BOUNDARY");
    ExpectedScenarioResult exactResult = oracle.calculate(exactDefinition);
    Snapshot exactBefore = exactResult.checkpoints().getFirst().snapshot();
    Snapshot exactAfter = exactResult.finalCheckpoint().snapshot();
    TradeState exactClose = exactAfter.trades().getLast();
    BigDecimal exactContractualFee = s8(exactClose.quantity().multiply(exactClose.price())
        .multiply(exactDefinition.actions().getLast().parameters().liquidationFeeRate()));
    BigDecimal exactRawBalance = s8(wallet(exactBefore).total()
        .add(exactClose.realizedPnl())
        .subtract(exactClose.fee()));
    BigDecimal exactChargedFee = s8(
        exactContractualFee.min(exactRawBalance.max(BigDecimal.ZERO)));
    List<LedgerState> exactDelta = ledgerDelta(exactBefore, exactAfter);
    LedgerState exactFee = singleEntry(exactDelta, "LIQUIDATION_FEE");

    assertThat(exactChargedFee).isPositive();
    assertThat(exactFee.amount()).isEqualByComparingTo(exactChargedFee.negate());
    assertThat(exactFee.referenceType()).isEqualTo("POSITION");
    assertThat(exactFee.reference()).isEqualTo("BTCUSDT-PERP:BOTH");
    assertThat(singleEntry(exactDelta, "FORCED_CLOSE").sequence())
        .isLessThan(exactFee.sequence());

    ScenarioDefinition shortfallDefinition =
        scenario("PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL");
    ExpectedScenarioResult shortfallResult = oracle.calculate(shortfallDefinition);
    Snapshot shortfallBefore = shortfallResult.checkpoints().getFirst().snapshot();
    Snapshot shortfallAfter = shortfallResult.finalCheckpoint().snapshot();
    TradeState shortfallClose = shortfallAfter.trades().getLast();
    ScenarioAction shortfallAction = shortfallDefinition.actions().getLast();
    BigDecimal shortfallContractualFee = s8(shortfallClose.quantity().multiply(shortfallClose.price())
        .multiply(shortfallAction.parameters().liquidationFeeRate()));
    BigDecimal rawBalance = s8(wallet(shortfallBefore).total()
        .add(shortfallClose.realizedPnl())
        .subtract(shortfallClose.fee()));
    BigDecimal coreShortfall = s8(rawBalance.negate().max(BigDecimal.ZERO));
    BigDecimal chargedFee = s8(
        shortfallContractualFee.min(rawBalance.max(BigDecimal.ZERO)));
    BigDecimal expectedShortfall = s8(coreShortfall
        .add(shortfallContractualFee.subtract(chargedFee).max(BigDecimal.ZERO)));
    List<LedgerState> shortfallDelta = ledgerDelta(shortfallBefore, shortfallAfter);
    List<LedgerState> chargedEntries = shortfallDelta.stream()
        .filter(entry -> entry.type().equals("LIQUIDATION_FEE"))
        .toList();

    if (chargedFee.signum() == 0) {
      assertThat(chargedEntries).isEmpty();
    } else {
      assertThat(chargedEntries).singleElement()
          .satisfies(entry -> assertThat(entry.amount())
              .isEqualByComparingTo(chargedFee.negate()));
    }
    LedgerState shortfall = singleEntry(shortfallDelta, "BANKRUPTCY_SHORTFALL");
    assertThat(shortfall.amount()).isEqualByComparingTo(expectedShortfall);
    assertThat(shortfall.referenceType()).isEqualTo("CROSS_LIQUIDATION_SETTLEMENT");
    assertThat(shortfall.reference()).isEqualTo(shortfallAction.parameters().actionId());
    assertThat(singleEntry(shortfallDelta, "FORCED_CLOSE").sequence())
        .isLessThan(shortfall.sequence());
  }

  @Test
  void liquidationOmitsZeroTradePnlLedgerEntries() {
    ScenarioDefinition source = scenario("PERP_FUNDING_CROSS_LIQUIDATION");
    BigDecimal entryPrice = oracle.calculate(source)
        .checkpoints().getFirst().snapshot().trades().getFirst().price();
    ScenarioDefinition breakevenLiquidation =
        withLiquidationTrigger(source, entryPrice);
    ExpectedScenarioResult result = oracle.calculate(breakevenLiquidation);
    Snapshot before = result.checkpoints().get(1).snapshot();
    Snapshot after = result.finalCheckpoint().snapshot();

    assertThat(after.trades().getLast().realizedPnl()).isZero();
    assertThat(ledgerDelta(before, after))
        .noneMatch(entry -> entry.type().equals("TRADE_PNL"));
  }

  @Test
  void liquidationUsesEquityMaintenanceAndCloseFeesForSafeExactAndMultiSlotChecks() {
    Snapshot safe = finalSnapshot("PERP_LIQUIDATION_SAFE");
    assertThat(position(safe, PositionSide.BOTH).status()).isEqualTo("OPEN");
    assertThat(safe.orders()).hasSize(1);

    Snapshot exact = finalSnapshot("PERP_LIQUIDATION_EXACT_BOUNDARY");
    assertThat(position(exact, PositionSide.BOTH).status()).isEqualTo("CLOSED");
    assertThat(exact.orders())
        .filteredOn(order -> order.origin().equals("LIQUIDATION"))
        .singleElement();

    Snapshot recovery = finalSnapshot("PERP_MULTI_POSITION_RECOVERY");
    assertThat(position(recovery, PositionSide.LONG).status()).isEqualTo("OPEN");
    assertThat(position(recovery, PositionSide.SHORT).status()).isEqualTo("CLOSED");

    Snapshot cascade = finalSnapshot("PERP_CASCADING_LIQUIDATION");
    assertThat(cascade.positions())
        .allSatisfy(position -> assertThat(position.status()).isEqualTo("CLOSED"));
    assertThat(cascade.orders())
        .filteredOn(order -> order.origin().equals("LIQUIDATION"))
        .hasSize(2);
  }

  @Test
  void batchAdminAndRaceActionsExposeTheirPerItemOutcomes() {
    Snapshot cancelPartial = finalSnapshot("PERP_CANCEL_ALL_PARTIAL_FAILURE");
    assertThat(cancelPartial.orders())
        .filteredOn(order -> order.status().equals("FILLED"))
        .singleElement();
    assertThat(cancelPartial.orders())
        .filteredOn(order -> order.status().equals("CANCELED"))
        .singleElement();

    Snapshot closeSuccess = finalSnapshot("PERP_CLOSE_ALL_SUCCESS");
    assertThat(closeSuccess.positions())
        .allSatisfy(position -> assertThat(position.status()).isEqualTo("CLOSED"));

    Snapshot closePartial = finalSnapshot("PERP_CLOSE_ALL_PARTIAL_FAILURE");
    assertThat(closePartial.positions())
        .filteredOn(position -> position.status().equals("CLOSED"))
        .singleElement();
    assertThat(closePartial.positions())
        .filteredOn(position -> position.status().equals("OPEN"))
        .singleElement();
    assertThat(closePartial.events()).extracting(event -> event.type())
        .containsOnly("ORDER_FILLED");

    Snapshot admin = finalSnapshot("PERP_ADMIN_FORCE_CLOSE");
    assertThat(admin.orders())
        .filteredOn(order -> order.origin().equals("ADMIN_FORCE_CLOSE"))
        .singleElement()
        .satisfies(order -> assertThat(order.ref())
            .isEqualTo("PERP_ADMIN_FORCE_CLOSE-action-2-order"));
    assertThat(admin.ledger()).extracting(entry -> entry.type())
        .contains("FORCED_CLOSE");

    for (String caseId : new String[] {
        "PERP_CLOSE_VS_PROTECTION_RACE",
        "PERP_CLOSE_VS_LIQUIDATION_RACE",
        "PERP_CLOSE_VS_BATCH_RACE"
    }) {
      Snapshot race = finalSnapshot(caseId);
      assertThat(race.positions())
          .allSatisfy(position -> assertThat(position.status()).isEqualTo("CLOSED"));
      assertThat(race.orders())
          .filteredOn(order -> order.ref().equals(
              scenario(caseId).actions().getLast().parameters().orderId()))
          .singleElement();
      assertThat(wallet(race).total()).isNotNegative();
    }
    Snapshot liquidationRace = finalSnapshot("PERP_CLOSE_VS_LIQUIDATION_RACE");
    assertThat(liquidationRace.account().bankruptcyShortfall()).isPositive();
    assertThat(liquidationRace.ledger())
        .filteredOn(entry -> entry.type().equals("BANKRUPTCY_SHORTFALL"))
        .singleElement()
        .satisfies(entry -> {
          assertThat(entry.referenceType()).isEqualTo("CROSS_LIQUIDATION_SETTLEMENT");
          assertThat(entry.reference())
              .isEqualTo("PERP_CLOSE_VS_LIQUIDATION_RACE-action-2");
        });
  }

  @Test
  void economicRacesCalculateBothCompleteWinnerOutcomes() {
    for (var expected : List.of(
        Map.entry("PERP_CLOSE_VS_BATCH_RACE", "BATCH_CLOSE"),
        Map.entry("PERP_CLOSE_VS_LIQUIDATION_RACE", "LIQUIDATION"),
        Map.entry("PERP_CLOSE_VS_PROTECTION_RACE", "PROTECTIVE"))) {
      ScenarioDefinition scenario = scenario(expected.getKey());
      ScenarioAction race = scenario.actions().getLast();
      List<ExpectedScenarioResult> alternatives = oracle.calculateAll(scenario);

      assertThat(alternatives).hasSize(2).allSatisfy(result -> {
        assertThat(result.caseId()).isEqualTo(scenario.caseId());
        assertThat(result.checkpoints()).hasSize(scenario.actions().size());
      });
      ExpectedScenarioResult primary = alternatives.getFirst();
      ExpectedScenarioResult competitor = alternatives.getLast();
      assertThat(competitor.checkpoints().subList(0, scenario.actions().size() - 1))
          .usingRecursiveComparison()
          .isEqualTo(primary.checkpoints().subList(0, scenario.actions().size() - 1));

      assertRaceAlternative(primary, race.parameters().orderId(), "USER");
      assertRaceAlternative(
          competitor,
          race.parameters().competingOrderId(),
          expected.getValue());
    }

    ExpectedScenarioResult liquidationAlternative = oracle.calculateAll(
            scenario("PERP_CLOSE_VS_LIQUIDATION_RACE"))
        .getLast();
    Snapshot liquidation = liquidationAlternative.finalCheckpoint().snapshot();
    assertThat(liquidation.ledger()).extracting(LedgerState::type)
        .contains("FORCED_CLOSE", "BANKRUPTCY_SHORTFALL");
    assertThat(liquidation.account().bankruptcyShortfall()).isPositive();
    assertThat(liquidationAlternative.calculationTrace())
        .anyMatch(line -> line.contains("liquidationFee="));

    Snapshot protection = oracle.calculateAll(
            scenario("PERP_CLOSE_VS_PROTECTION_RACE"))
        .getLast()
        .finalCheckpoint()
        .snapshot();
    assertThat(protection.protections()).singleElement()
        .satisfies(value -> assertThat(value.status()).isEqualTo("FILLED"));
  }

  @Test
  void liquidationCapsTheWalletAtZeroAndRecordsTheWholeShortfall() {
    ScenarioDefinition lowCollateral =
        scenario("PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL");
    assertThat(lowCollateral.initialBalances().get("USDT")).isEqualTo("2000.00000000");
    Snapshot snapshot = oracle.calculate(lowCollateral).finalCheckpoint().snapshot();

    assertThat(position(snapshot, PositionSide.BOTH).status()).isEqualTo("CLOSED");
    assertThat(wallet(snapshot).total()).isEqualByComparingTo("0");
    assertThat(snapshot.account().balance()).isEqualByComparingTo("0");
    assertThat(snapshot.account().bankruptcyShortfall())
        .isEqualByComparingTo("969.12066450");
    assertThat(snapshot.ledger())
        .filteredOn(entry -> entry.type().equals("BANKRUPTCY_SHORTFALL"))
        .singleElement()
        .satisfies(entry -> assertThat(entry.amount())
            .isEqualByComparingTo(snapshot.account().bankruptcyShortfall()));
  }

  @Test
  void providerSwitchWithGapIsObservedBeforeTheSecondFill() {
    ExpectedScenarioResult result =
        oracle.calculate(scenario("PERP_PROVIDER_SWITCH_WITH_GAP"));

    assertThat(result.checkpoints()).hasSize(2);
    assertThat(result.finalCheckpoint().snapshot().events())
        .filteredOn(event -> event.type().equals("MARKET_SOURCE_CHANGED"))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.fromStatus()).isEqualTo("binance");
          assertThat(event.toStatus()).isEqualTo("okx");
        });
    assertThat(result.finalCheckpoint().snapshot().trades()).hasSize(2);
  }

  @Test
  void coreTerminalRevalueConsumesTheFinalMarkWithoutCreatingExecutionRows() {
    var core = ScenarioCatalog.perpetual()
        .filter(candidate -> candidate.caseId().startsWith("PERP_CORE_"))
        .toList();
    assertThat(core).hasSize(64);

    for (ScenarioDefinition scenario : core) {
      ExpectedScenarioResult result = oracle.calculate(scenario);
      Snapshot beforeRevalue = result.checkpoints().get(1).snapshot();
      Snapshot afterRevalue = result.finalCheckpoint().snapshot();

      assertThat(result.checkpoints()).hasSize(3);
      assertThat(afterRevalue.positions()).allSatisfy(position -> {
        if ("CLOSED".equals(position.status())) {
          assertThat(position.markPrice()).isZero();
        } else {
          assertThat(position.markPrice())
              .isEqualByComparingTo(scenario.priceSteps().getLast().mark());
        }
      });
      assertThat(afterRevalue.orders()).isEqualTo(beforeRevalue.orders());
      assertThat(afterRevalue.trades()).isEqualTo(beforeRevalue.trades());
      assertThat(afterRevalue.ledger()).isEqualTo(beforeRevalue.ledger());
    }

    assertThat(finalMark("PERP_CORE_UP_UP_UP_ADD_ADD_LONG"))
        .isNotEqualByComparingTo(finalMark("PERP_CORE_UP_UP_DOWN_ADD_ADD_LONG"));
    assertThat(finalMark("PERP_CORE_UP_DOWN_UP_ADD_ADD_LONG"))
        .isNotEqualByComparingTo(finalMark("PERP_CORE_UP_DOWN_DOWN_ADD_ADD_LONG"));
    assertThat(finalMark("PERP_CORE_DOWN_UP_UP_ADD_ADD_LONG"))
        .isNotEqualByComparingTo(finalMark("PERP_CORE_DOWN_UP_DOWN_ADD_ADD_LONG"));
    assertThat(finalMark("PERP_CORE_DOWN_DOWN_UP_ADD_ADD_LONG"))
        .isNotEqualByComparingTo(finalMark("PERP_CORE_DOWN_DOWN_DOWN_ADD_ADD_LONG"));
  }

  @Test
  void oracleSourceHasNoProductionCalculatorEngineOrSettlementDependency() throws IOException {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/oracle/PerpetualScenarioOracle.java"));

    assertThat(source)
        .doesNotContain("com.fxplatform.risk")
        .doesNotContain("com.fxplatform.trading.service")
        .doesNotContain("Calculator")
        .doesNotContain("Engine")
        .doesNotContain("SettlementService");
  }

  @Test
  void rejectionCodeIsDerivedWithoutReadingTheCatalogExpectedError() {
    ScenarioDefinition tampered = withExpectedError(
        scenario("PERP_REDUCE_ONLY_ABOVE_REJECT"),
        "WRONG_CATALOG_ANSWER");

    assertThat(oracle.calculate(tampered).failure()).get()
        .extracting(ExpectedScenarioResult.FailureState::code)
        .isEqualTo("REDUCE_ONLY_EXCEEDS_POSITION");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("perpetualInputDerivedFailures")
  void deterministicRejectionIsDerivedFromInputAndStateInsteadOfFailureCondition(
      String caseId,
      String expectedCode
  ) {
    ScenarioDefinition source = scenario(caseId);
    ScenarioDefinition mislabeled = withLastFailureCondition(
        source,
        "TARGET_LEVERAGE_EXCEEDS_INSTRUMENT_MAX".equals(
            source.actions().getLast().parameters().failureCondition())
            ? "PRICE_SCALE_EXCEEDS_INSTRUMENT"
            : "TARGET_LEVERAGE_EXCEEDS_INSTRUMENT_MAX");

    assertThat(oracle.calculate(mislabeled).failure()).get()
        .extracting(ExpectedScenarioResult.FailureState::code)
        .isEqualTo(expectedCode);
  }

  @Test
  void partialFillCompatibilityRejectionUsesTheInputStatusInsteadOfFailureCondition() {
    ScenarioDefinition source = scenario("PERP_PARTIAL_FILL_COMPAT_REJECT");
    ScenarioDefinition mislabeled = withLastConditionAndFailureCondition(
        source,
        "PARTIAL_FILL_INPUT",
        "ADMIN_PERMISSION_REQUIRED");

    assertThat(oracle.calculate(mislabeled).failure()).get()
        .extracting(ExpectedScenarioResult.FailureState::code)
        .isEqualTo("PARTIAL_FILL_NOT_SUPPORTED");
  }

  private Snapshot finalSnapshot(String caseId) {
    return oracle.calculate(scenario(caseId)).finalCheckpoint().snapshot();
  }

  private static void assertRaceAlternative(
      ExpectedScenarioResult result,
      String winnerRef,
      String winnerOrigin
  ) {
    Snapshot before = result.checkpoints().get(result.checkpoints().size() - 2).snapshot();
    Snapshot after = result.finalCheckpoint().snapshot();
    assertThat(after.orders())
        .filteredOn(order -> order.ref().equals(winnerRef))
        .singleElement()
        .satisfies(order -> {
          assertThat(order.status()).isEqualTo("FILLED");
          assertThat(order.type()).isEqualTo(OrderType.MARKET);
          assertThat(order.reduceOnly()).isTrue();
          assertThat(order.origin()).isEqualTo(winnerOrigin);
        });
    assertThat(after.trades().subList(before.trades().size(), after.trades().size()))
        .singleElement()
        .satisfies(trade -> assertThat(trade.orderRef()).isEqualTo(winnerRef));
    assertThat(ledgerDelta(before, after))
        .filteredOn(entry -> entry.type().equals("TRADE_FEE"))
        .singleElement();
    assertThat(after.events().subList(before.events().size(), after.events().size()))
        .filteredOn(event -> event.type().equals("ORDER_FILLED"))
        .singleElement()
        .satisfies(event -> assertThat(event.subjectRef()).isEqualTo(winnerRef));
    assertThat(after.positions())
        .noneMatch(position -> position.status().equals("OPEN"));
  }

  private BigDecimal lastOrderHold(String caseId) {
    return finalSnapshot(caseId).ledger().stream()
        .filter(entry -> entry.type().equals("ORDER_HOLD"))
        .toList()
        .getLast()
        .amount();
  }

  private static void assertIsolatedFundingRealization(
      Snapshot before,
      Snapshot after
  ) {
    BigDecimal funding = position(before, PositionSide.BOTH).fundingPnl();
    List<LedgerState> delta = ledgerDelta(before, after);
    LedgerState realization = delta.stream()
        .filter(entry -> entry.type().equals("FUNDING_FEE"))
        .filter(entry -> entry.referenceType().equals("POSITION"))
        .findFirst()
        .orElseThrow();

    assertThat(funding).isNotZero();
    assertThat(realization.amount()).isEqualByComparingTo(funding);
    assertThat(realization.reference()).isEqualTo("BTCUSDT-PERP:BOTH");
    assertThat(realization.sequence())
        .isLessThan(singleEntry(delta, "TRADE_FEE").sequence());
    assertThat(position(after, PositionSide.BOTH).fundingPnl()).isZero();
  }

  private static List<LedgerState> ledgerDelta(Snapshot before, Snapshot after) {
    return after.ledger().subList(before.ledger().size(), after.ledger().size());
  }

  private static LedgerState singleEntry(List<LedgerState> entries, String type) {
    return entries.stream()
        .filter(entry -> entry.type().equals(type))
        .findFirst()
        .orElseThrow();
  }

  private static ScenarioDefinition withLiquidationTrigger(
      ScenarioDefinition source,
      BigDecimal triggerPrice
  ) {
    List<ScenarioAction> actions = new ArrayList<>(source.actions());
    ScenarioAction liquidation = actions.getLast();
    ScenarioAction.Parameters parameters = liquidation.parameters();
    actions.set(actions.size() - 1, new ScenarioAction(
        liquidation.type(),
        liquidation.direction(),
        liquidation.quantity(),
        new ScenarioAction.Parameters(
            parameters.actionId(),
            parameters.clientOrderId(),
            parameters.orderId(),
            parameters.competingOrderId(),
            parameters.protectionId(),
            parameters.price(),
            triggerPrice,
            parameters.leverage(),
            parameters.marginDelta(),
            parameters.fundingRate(),
            parameters.feeRate(),
            parameters.makerFeeRate(),
            parameters.takerFeeRate(),
            parameters.worstFeeRate(),
            BigDecimal.ZERO,
            parameters.maintenanceMarginRate(),
            parameters.liquidationFeeRate(),
            parameters.quantityUnit(),
            parameters.reduceOnly(),
            parameters.side(),
            parameters.orderType(),
            parameters.protectionType(),
            parameters.triggerExecutionType(),
            parameters.triggerPriceType(),
            parameters.condition(),
            parameters.failureCondition()),
        liquidation.detail()));
    List<ScenarioPriceStep> priceSteps = new ArrayList<>(source.priceSteps());
    ScenarioPriceStep last = priceSteps.getLast();
    priceSteps.set(priceSteps.size() - 1, new ScenarioPriceStep(
        last.path(),
        last.sequence(),
        triggerPrice,
        triggerPrice,
        triggerPrice,
        triggerPrice,
        triggerPrice,
        last.source(),
        last.asOf(),
        last.expiresAt(),
        last.missingFields()));
    return new ScenarioDefinition(
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
        priceSteps,
        actions,
        source.exitReason(),
        source.expectedOrder(),
        source.expectedTrades(),
        source.expectedPosition(),
        source.expectedWallet(),
        source.expectedAccount(),
        source.expectedLedger(),
        source.expectedProtections(),
        source.expectedEvents(),
        source.expectedError(),
        source.testClass(),
        source.testMethod(),
        source.executionStatus());
  }

  private static ScenarioDefinition withActions(
      ScenarioDefinition source,
      List<ScenarioAction> actions
  ) {
    return new ScenarioDefinition(
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
        actions,
        source.exitReason(),
        source.expectedOrder(),
        source.expectedTrades(),
        source.expectedPosition(),
        source.expectedWallet(),
        source.expectedAccount(),
        source.expectedLedger(),
        source.expectedProtections(),
        source.expectedEvents(),
        source.expectedError(),
        source.testClass(),
        source.testMethod(),
        source.executionStatus());
  }

  private static ScenarioDefinition withLastFailureCondition(
      ScenarioDefinition source,
      String failureCondition
  ) {
    return withLastConditionAndFailureCondition(
        source,
        source.actions().getLast().parameters().condition(),
        failureCondition);
  }

  private static ScenarioDefinition withLastConditionAndFailureCondition(
      ScenarioDefinition source,
      String condition,
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
            condition,
            failureCondition),
        action.detail()));
    return withActions(source, actions);
  }

  private BigDecimal finalMark(String caseId) {
    return position(finalSnapshot(caseId), PositionSide.BOTH).markPrice();
  }

  private static void assertHandledAction(
      ScenarioDefinition scenario,
      ExpectedScenarioResult result,
      int index
  ) {
    ScenarioAction action = scenario.actions().get(index);
    Checkpoint checkpoint = result.checkpoints().get(index);
    Snapshot after = checkpoint.snapshot();
    if (checkpoint.failure() != null) {
      return;
    }

    switch (action.type()) {
      case ADD, PARTIAL_CLOSE, FULL_CLOSE, PLACE_ORDER, REVERSE ->
          assertThat(after.orders())
              .anySatisfy(order -> assertThat(order.ref())
                  .isEqualTo(action.parameters().orderId()));
      case MODIFY -> assertThat(after.events())
          .anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("ORDER_MODIFIED");
            assertThat(event.subjectRef()).isEqualTo(action.parameters().orderId());
          });
      case CANCEL -> assertThat(after.orders())
          .filteredOn(order -> order.ref().equals(action.parameters().orderId()))
          .singleElement()
          .satisfies(order -> assertThat(order.status()).isEqualTo("CANCELED"));
      case TRIGGER -> assertThat(after.events())
          .anyMatch(event -> event.type().equals("ORDER_FILLED")
              || event.type().equals("PROTECTION_TRIGGERED"));
      case REPLAY, ROLLBACK -> {
        assertThat(index).isPositive();
        assertDomainStateUnchanged(
            result.checkpoints().get(index - 1).snapshot(),
            after);
      }
      case CHANGE_LEVERAGE -> assertThat(after.positions())
          .filteredOn(position -> position.status().equals("OPEN"))
          .allSatisfy(position -> assertThat(position.leverage())
              .isEqualTo(action.parameters().leverage()));
      case ADJUST_MARGIN -> assertThat(after.ledger())
          .anyMatch(entry -> entry.type().equals("MARGIN_HOLD")
              || entry.type().equals("MARGIN_RELEASE"));
      case SET_PROTECTION -> assertThat(after.protections())
          .anySatisfy(protection -> assertThat(protection.ref())
              .isEqualTo(action.parameters().protectionId()));
      case SETTLE_FUNDING, LIQUIDATE, CLOSE_ALL -> {
        // These actions may legitimately produce no durable order event on a safe/no-op branch.
      }
      case CANCEL_ALL -> assertThat(after.orders())
          .noneMatch(order -> order.status().equals("PENDING"));
      case ADMIN_FORCE_CLOSE -> assertThat(after.orders())
          .anyMatch(order -> order.origin().equals("ADMIN_FORCE_CLOSE"));
      case RACE -> assertThat(after.orders())
          .anyMatch(order -> order.ref().equals(action.parameters().orderId()));
      case REVALUE -> {
        assertThat(index).isPositive();
        Snapshot before = result.checkpoints().get(index - 1).snapshot();
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.trades()).isEqualTo(before.trades());
        assertThat(after.ledger()).isEqualTo(before.ledger());
        assertThat(after.protections()).isEqualTo(before.protections());
      }
      case BUY, SELL, PARTIAL_SELL, CREATE_OCO ->
          throw new AssertionError("Unsupported Perpetual Catalog action " + action.type());
    }
  }

  private void assertAttachedProtection(
      String caseId,
      ProtectionType expectedType,
      String expectedTrigger,
      OrderType expectedCarrierType
  ) {
    ScenarioDefinition scenario = scenario(caseId);
    ScenarioAction entry = scenario.actions().getFirst();
    Snapshot snapshot = oracle.calculate(scenario).finalCheckpoint().snapshot();

    assertThat(snapshot.orders()).hasSize(2);
    assertThat(snapshot.orders())
        .filteredOn(order -> order.ref().equals(entry.parameters().orderId()))
        .singleElement()
        .satisfies(order -> assertThat(order.status()).isEqualTo("FILLED"));
    assertThat(snapshot.orders())
        .filteredOn(order -> order.origin().equals("PROTECTIVE"))
        .singleElement()
        .satisfies(order -> {
          assertThat(order.ref()).isEqualTo(entry.parameters().protectionId());
          assertThat(order.parentRef()).isEqualTo(entry.parameters().orderId());
          assertThat(order.type()).isEqualTo(expectedCarrierType);
          assertThat(order.status()).isEqualTo("PENDING_ACTIVATION");
        });
    assertThat(snapshot.protections()).singleElement().satisfies(protection -> {
      assertThat(protection.ref()).isEqualTo(entry.parameters().protectionId());
      assertThat(protection.type()).isEqualTo(expectedType);
      assertThat(protection.status()).isEqualTo("ACTIVE");
      assertThat(protection.quantity()).isEqualByComparingTo("1");
      assertThat(protection.triggerPrice()).isEqualByComparingTo(expectedTrigger);
      assertThat(protection.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
      assertThat(protection.executionType()).isEqualTo(TriggerExecutionType.MARKET);
      assertThat(protection.limitPrice()).isNull();
    });
  }

  private void assertTriggeredProtection(
      String caseId,
      ProtectionType expectedType,
      TriggerExecutionType expectedExecution,
      String expectedLimit
  ) {
    Snapshot snapshot = finalSnapshot(caseId);
    assertThat(snapshot.protections()).singleElement().satisfies(protection -> {
      assertThat(protection.type()).isEqualTo(expectedType);
      assertThat(protection.status()).isEqualTo("FILLED");
      assertThat(protection.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
      assertThat(protection.executionType()).isEqualTo(expectedExecution);
      if (expectedLimit == null) {
        assertThat(protection.limitPrice()).isNull();
      } else {
        assertThat(protection.limitPrice()).isEqualByComparingTo(expectedLimit);
      }
    });
    assertThat(snapshot.orders())
        .filteredOn(order -> order.origin().equals("PROTECTIVE"))
        .singleElement()
        .satisfies(order -> {
          assertThat(order.status()).isEqualTo("FILLED");
          assertThat(order.holdAmount()).isZero();
        });
    assertThat(snapshot.trades()).hasSize(2);
    assertThat(snapshot.positions()).singleElement()
        .satisfies(position -> assertThat(position.status()).isEqualTo("CLOSED"));
  }

  private void assertFunding(String caseId, PositionSide side, String expected) {
    ExpectedScenarioResult result = oracle.calculate(scenario(caseId));
    Snapshot before = result.checkpoints().getFirst().snapshot();
    Snapshot after = result.finalCheckpoint().snapshot();

    assertThat(position(after, side).fundingPnl()).isEqualByComparingTo(expected);
    assertThat(wallet(after).total().subtract(wallet(before).total()))
        .isEqualByComparingTo(expected);
  }

  private static void assertCanonicalQuantity(Snapshot snapshot, String expected) {
    assertThat(snapshot.orders()).singleElement()
        .satisfies(order -> assertThat(order.quantity()).isEqualByComparingTo(expected));
    assertThat(snapshot.trades()).singleElement()
        .satisfies(trade -> assertThat(trade.quantity()).isEqualByComparingTo(expected));
    assertThat(position(snapshot, PositionSide.BOTH).quantity())
        .isEqualByComparingTo(expected);
  }

  private static ScenarioDefinition scenario(String caseId) {
    return ScenarioCatalog.perpetual()
        .filter(candidate -> candidate.caseId().equals(caseId))
        .findFirst()
        .orElseThrow();
  }

  private static ScenarioDefinition withExpectedError(
      ScenarioDefinition source,
      String expectedError
  ) {
    return new ScenarioDefinition(
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
        expectedError,
        source.testClass(),
        source.testMethod(),
        source.executionStatus());
  }

  private static void assertDomainStateUnchanged(Snapshot before, Snapshot after) {
    assertThat(after.orders()).isEqualTo(before.orders());
    assertThat(after.trades()).isEqualTo(before.trades());
    assertThat(after.positions()).isEqualTo(before.positions());
    assertThat(after.wallets()).isEqualTo(before.wallets());
    assertThat(after.account()).isEqualTo(before.account());
    assertThat(after.ledger()).isEqualTo(before.ledger());
    assertThat(after.protections()).isEqualTo(before.protections());
  }

  private static void assertSnapshotAccounting(Snapshot snapshot) {
    assertThat(snapshot.orders()).extracting(order -> order.ref())
        .doesNotHaveDuplicates();
    assertThat(snapshot.trades()).extracting(trade -> trade.ref())
        .doesNotHaveDuplicates();
    assertThat(snapshot.trades()).extracting(trade -> trade.uniqueKey())
        .doesNotHaveDuplicates();
    assertThat(snapshot.protections()).extracting(protection -> protection.ref())
        .doesNotHaveDuplicates();
    for (var trade : snapshot.trades()) {
      assertThat(snapshot.orders())
          .anyMatch(order -> order.ref().equals(trade.orderRef()));
    }

    BigDecimal positionMargin = snapshot.positions().stream()
        .map(PositionState::marginHeld)
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal pendingHolds = snapshot.orders().stream()
        .filter(order -> order.status().equals("PENDING"))
        .map(order -> order.holdAmount())
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal expectedUsedMargin = s8(positionMargin.add(pendingHolds));
    BigDecimal isolatedPrincipal = snapshot.positions().stream()
        .filter(position -> position.marginMode() == MarginMode.ISOLATED)
        .map(PositionState::marginHeld)
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal crossPositionMargin = snapshot.positions().stream()
        .filter(position -> position.marginMode() == MarginMode.CROSS)
        .map(PositionState::marginHeld)
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal crossOrderHolds = snapshot.orders().stream()
        .filter(order -> order.status().equals("PENDING"))
        .filter(order -> snapshot.positions().stream().noneMatch(position ->
            position.marginMode() == MarginMode.ISOLATED
                && position.status().equals("OPEN")
                && order.reduceOnly()
                && position.slot().equals(order.parentRef())))
        .map(order -> order.holdAmount())
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal crossUnrealized = snapshot.positions().stream()
        .filter(position -> position.marginMode() == MarginMode.CROSS)
        .map(PositionState::unrealizedPnl)
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal isolatedFunding = snapshot.positions().stream()
        .filter(position -> position.marginMode() == MarginMode.ISOLATED)
        .map(PositionState::fundingPnl)
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    BigDecimal unrealized = snapshot.positions().stream()
        .map(PositionState::unrealizedPnl)
        .reduce(s8(BigDecimal.ZERO), BigDecimal::add);
    WalletState wallet = wallet(snapshot);

    assertThat(snapshot.account().balance()).isEqualByComparingTo(wallet.total());
    assertThat(snapshot.account().usedMargin()).isEqualByComparingTo(expectedUsedMargin);
    assertThat(snapshot.account().equity()).isEqualByComparingTo(s8(
        wallet.total().add(unrealized).add(isolatedFunding)));
    assertThat(snapshot.account().freeMargin()).isEqualByComparingTo(s8(
        wallet.total()
            .subtract(isolatedPrincipal)
            .add(crossUnrealized)
            .subtract(crossPositionMargin)
            .subtract(crossOrderHolds)));
    assertThat(wallet.locked()).isEqualByComparingTo(expectedUsedMargin);
    assertThat(wallet.available()).isEqualByComparingTo(s8(
        wallet.total().subtract(expectedUsedMargin).max(BigDecimal.ZERO)));

    for (int index = 0; index < snapshot.ledger().size(); index++) {
      assertThat(snapshot.ledger().get(index).sequence()).isEqualTo(index + 1);
    }
    for (int index = 0; index < snapshot.events().size(); index++) {
      assertThat(snapshot.events().get(index).sequence()).isEqualTo(index + 1);
    }
    if (!snapshot.ledger().isEmpty()) {
      assertThat(snapshot.ledger().getLast().balanceAfter())
          .isEqualByComparingTo(wallet.total());
    }
  }

  private static Stream<ScenarioDefinition> perpetualScenarios() {
    return ScenarioCatalog.perpetual();
  }

  private static Stream<Arguments> perpetualInputDerivedFailures() {
    return Stream.of(
        Arguments.of("PERP_MIN_SIZE_REJECT", "QUANTITY_CONVERTS_TO_ZERO"),
        Arguments.of("PERP_QUANTITY_STEP_REJECT", "CONTRACT_QUANTITY_NOT_INTEGRAL"),
        Arguments.of("PERP_PRICE_PRECISION_REJECT", "PRICE_TICK_MISMATCH"),
        Arguments.of("PERP_LEVERAGE_OVER_MAX_REJECT", "LEVERAGE_OUT_OF_RANGE"),
        Arguments.of("PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT", "INSUFFICIENT_MARGIN"),
        Arguments.of("PERP_REDUCE_ONLY_ABOVE_REJECT", "REDUCE_ONLY_EXCEEDS_POSITION"),
        Arguments.of("PERP_ISOLATED_MARGIN_REDUCE_UNSAFE_REJECT", "MARGIN_REDUCTION_UNSAFE"),
        Arguments.of("PERP_PROTECTION_LIMIT_11_REJECT", "PROTECTION_LIMIT_EXCEEDED"),
        Arguments.of("PERP_INCOMPLETE_MARKET", "MARKET_BUNDLE_INCOMPLETE"),
        Arguments.of("PERP_STALE_MARKET", "MARKET_DATA_STALE"),
        Arguments.of("PERP_CLIENT_ORDER_CONFLICT", "DUPLICATE_CLIENT_ORDER_ID"),
        Arguments.of("PERP_SAME_POSITION_DOUBLE_CLOSE", "POSITION_NOT_FOUND"));
  }

  private static PositionState position(Snapshot snapshot, PositionSide side) {
    return snapshot.positions().stream()
        .filter(position -> position.positionSide() == side)
        .findFirst()
        .orElseThrow();
  }

  private static WalletState wallet(Snapshot snapshot) {
    return snapshot.wallets().stream()
        .filter(wallet -> wallet.asset().equals("USDT"))
        .findFirst()
        .orElseThrow();
  }

  private static BigDecimal s8(BigDecimal value) {
    return value.setScale(8, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
