package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.scenario.ExpectedScenarioResult.EventState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.OrderState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.TradeState;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ScenarioAssertions {

  private static final Set<String> NONDETERMINISTIC_ECONOMIC_RACES = Set.of(
      "OCO_DUAL_TRIGGER",
      "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL",
      "USER_CLOSE_COMPETES_WITH_LIQUIDATION",
      "USER_CLOSE_COMPETES_WITH_STOP_LOSS");

  private ScenarioAssertions() {
  }

  public static void assertScenarioEquals(
      String caseId,
      ExpectedScenarioResult expected,
      ActualScenarioResult actual
  ) {
    Objects.requireNonNull(caseId, "caseId");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");

    assertThat(expected.caseId())
        .as("[%s] expected caseId", caseId)
        .isEqualTo(caseId);
    assertThat(actual.caseId())
        .as("[%s] actual caseId", caseId)
        .isEqualTo(caseId);
    assertThat(actual.checkpoints())
        .as("[%s] checkpoints", caseId)
        .usingRecursiveComparison()
        .withComparatorForType(
            Comparator.nullsFirst(BigDecimal::compareTo),
            BigDecimal.class)
        .isEqualTo(expected.checkpoints());
  }

  public static void assertScenarioEquals(
      ScenarioDefinition scenario,
      ExpectedScenarioResult expected,
      ActualScenarioResult actual
  ) {
    assertScenarioEquals(scenario, List.of(expected), actual);
  }

  public static void assertScenarioEquals(
      ScenarioDefinition scenario,
      List<ExpectedScenarioResult> expectations,
      ActualScenarioResult actual
  ) {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(expectations, "expectations");
    Objects.requireNonNull(actual, "actual");
    assertThat(expectations)
        .as("[%s] expected alternatives", scenario.caseId())
        .isNotEmpty()
        .doesNotContainNull();
    int economicRaceIndex = economicRaceIndex(scenario);
    if (economicRaceIndex >= 0) {
      assertThat(expectations)
          .as("[%s] complete economic-race alternatives", scenario.caseId())
          .hasSize(2);
      assertConcurrentEconomicRace(
          scenario,
          economicRaceIndex,
          actual);
      assertScenarioMatchesAny(scenario.caseId(), expectations, actual);
      return;
    }
    ScenarioAction.Parameters balanceRace = scenario.actions().stream()
        .filter(action -> action.type() == ScenarioAction.Type.RACE)
        .map(ScenarioAction::parameters)
        .filter(parameters ->
            "TWO_6000_USDT_ORDERS_COMPETE_FOR_10000".equals(parameters.condition()))
        .findFirst()
        .orElse(null);
    if (balanceRace == null) {
      assertScenarioMatchesAny(scenario.caseId(), expectations, actual);
      return;
    }

    String caseId = scenario.caseId();
    assertThat(expectations)
        .as("[%s] balance race expected alternatives", caseId)
        .singleElement();
    ExpectedScenarioResult expected = expectations.getFirst();
    assertThat(expected.caseId())
        .as("[%s] expected caseId", caseId)
        .isEqualTo(caseId);
    assertThat(actual.caseId())
        .as("[%s] actual caseId", caseId)
        .isEqualTo(caseId);
    assertThat(actual.checkpoints())
        .as("[%s] checkpoints", caseId)
        .usingRecursiveComparison()
        .withComparatorForType(
            Comparator.nullsFirst(BigDecimal::compareTo),
            BigDecimal.class)
        .ignoringFieldsMatchingRegexes(".*uniqueKey")
        .isEqualTo(expected.checkpoints());
    assertThat(actual.finalCheckpoint().snapshot().trades())
        .as("[%s] balance-race winner identity", caseId)
        .extracting(TradeState::uniqueKey)
        .singleElement()
        .isIn(balanceRace.clientOrderId(), balanceRace.competingOrderId());
  }

  private static int economicRaceIndex(ScenarioDefinition scenario) {
    for (int index = 0; index < scenario.actions().size(); index++) {
      ScenarioAction action = scenario.actions().get(index);
      if (action.type() == ScenarioAction.Type.RACE
          && NONDETERMINISTIC_ECONOMIC_RACES.contains(
              action.parameters().condition())) {
        return index;
      }
    }
    return -1;
  }

  private static void assertConcurrentEconomicRace(
      ScenarioDefinition scenario,
      int raceIndex,
      ActualScenarioResult actual
  ) {
    String caseId = scenario.caseId();
    assertThat(actual.caseId())
        .as("[%s] actual caseId", caseId)
        .isEqualTo(caseId);
    assertThat(raceIndex)
        .as("[%s] economic race must have a pre-race checkpoint", caseId)
        .isPositive();
    assertThat(raceIndex)
        .as("[%s] economic race must be the terminal catalog action", caseId)
        .isEqualTo(scenario.actions().size() - 1);
    assertThat(actual.checkpoints())
        .as("[%s] checkpoint count", caseId)
        .hasSize(scenario.actions().size());

    ScenarioAction race = scenario.actions().get(raceIndex);
    ExpectedScenarioResult.Checkpoint finalCheckpoint = actual.checkpoints().get(raceIndex);
    assertThat(finalCheckpoint.actionIndex())
        .as("[%s] race action index", caseId)
        .isEqualTo(raceIndex + 1);
    assertThat(finalCheckpoint.actionId())
        .as("[%s] race action id", caseId)
        .isEqualTo(race.parameters().actionId());
    assertThat(finalCheckpoint.failure())
        .as("[%s] a legal race winner must commit", caseId)
        .isNull();

    Snapshot before = actual.checkpoints().get(raceIndex - 1).snapshot();
    Snapshot after = finalCheckpoint.snapshot();
    List<TradeState> newTrades = after.trades().stream()
        .filter(trade -> before.trades().stream()
            .noneMatch(existing -> existing.ref().equals(trade.ref())))
        .toList();
    assertThat(newTrades)
        .as("[%s] exactly one new trade may settle the race", caseId)
        .singleElement();
    TradeState settlement = newTrades.getFirst();
    Set<String> declaredWinners = Set.of(
        race.parameters().orderId(),
        race.parameters().competingOrderId());
    assertThat(settlement.orderRef())
        .as("[%s] declared race winner", caseId)
        .isIn(declaredWinners);
    assertThat(settlement.quantity())
        .as("[%s] settlement quantity", caseId)
        .isPositive();
    assertThat(settlement.price())
        .as("[%s] settlement price", caseId)
        .isPositive();
    assertThat(settlement.quoteNotional())
        .as("[%s] settlement notional", caseId)
        .isEqualByComparingTo(settlement.quantity().multiply(settlement.price()));
    assertThat(settlement.fee())
        .as("[%s] settlement fee", caseId)
        .isPositive();
    assertThat(settlement.productType())
        .as("[%s] settlement product", caseId)
        .isEqualTo(scenario.productType());

    OrderState winner = after.orders().stream()
        .filter(order -> order.ref().equals(settlement.orderRef()))
        .findFirst()
        .orElse(null);
    assertThat(winner)
        .as("[%s] persisted winner order", caseId)
        .isNotNull();
    assertThat(winner.status())
        .as("[%s] winner terminal status", caseId)
        .isEqualTo("FILLED");
    assertThat(winner.origin())
        .as("[%s] winner origin", caseId)
        .isEqualTo(expectedWinnerOrigin(race, settlement.orderRef()));

    List<LedgerState> appendedLedger = appended(
        caseId,
        "ledger",
        before.ledger(),
        after.ledger());
    List<LedgerState> tradeFees = appendedLedger.stream()
        .filter(entry -> "TRADE_FEE".equals(entry.type()))
        .toList();
    assertThat(tradeFees)
        .as("[%s] exactly one trade fee may settle the race", caseId)
        .singleElement();
    LedgerState tradeFee = tradeFees.getFirst();
    assertThat(tradeFee.amount())
        .as("[%s] trade fee debit", caseId)
        .isNegative();
    assertThat(tradeFee.reference())
        .as("[%s] trade fee settlement reference", caseId)
        .isIn(settlement.ref(), settlement.orderRef());

    List<EventState> appendedEvents = appended(
        caseId,
        "events",
        before.events(),
        after.events());
    List<EventState> filledEvents = appendedEvents.stream()
        .filter(event -> "ORDER_FILLED".equals(event.type()))
        .toList();
    assertThat(filledEvents)
        .as("[%s] exactly one fill event may settle the race", caseId)
        .singleElement()
        .extracting(EventState::subjectRef)
        .isEqualTo(settlement.orderRef());

    assertRaceTerminalState(caseId, race, after, appendedEvents);
  }

  private static void assertScenarioMatchesAny(
      String caseId,
      List<ExpectedScenarioResult> expectations,
      ActualScenarioResult actual
  ) {
    assertThat(actual.caseId())
        .as("[%s] actual caseId", caseId)
        .isEqualTo(caseId);
    assertThat(expectations).allSatisfy(expected -> assertThat(expected.caseId())
        .as("[%s] expected alternative caseId", caseId)
        .isEqualTo(caseId));
    List<String> mismatches = new java.util.ArrayList<>();
    for (int index = 0; index < expectations.size(); index++) {
      ExpectedScenarioResult expected = expectations.get(index);
      try {
        assertThat(actual.checkpoints())
            .as("[%s] checkpoints, expected alternative %s", caseId, index + 1)
            .usingRecursiveComparison()
            .withComparatorForType(
                Comparator.nullsFirst(BigDecimal::compareTo),
                BigDecimal.class)
            .isEqualTo(expected.checkpoints());
        return;
      } catch (AssertionError mismatch) {
        mismatches.add(mismatch.getMessage());
      }
    }
    throw new AssertionError(
        "[%s] actual checkpoints did not match any complete expected "
            .formatted(caseId)
            + "alternative:\n"
            + String.join("\n", mismatches));
  }

  private static void assertRaceTerminalState(
      String caseId,
      ScenarioAction race,
      Snapshot after,
      List<EventState> appendedEvents
  ) {
    String condition = race.parameters().condition();
    if ("OCO_DUAL_TRIGGER".equals(condition)) {
      assertThat(after.orders().stream()
          .filter(order -> order.ref().equals(race.parameters().orderId())
              || order.ref().equals(race.parameters().competingOrderId()))
          .map(OrderState::status)
          .toList())
          .as("[%s] OCO winner and canceled peer", caseId)
          .containsExactlyInAnyOrder("FILLED", "CANCELED");
      assertThat(appendedEvents)
          .as("[%s] OCO peer cancellation", caseId)
          .filteredOn(event -> "ORDER_CANCELED".equals(event.type()))
          .singleElement();
      return;
    }

    assertThat(after.positions())
        .as("[%s] close race terminal position", caseId)
        .noneMatch(position -> "OPEN".equals(position.status()));
    if ("USER_CLOSE_COMPETES_WITH_STOP_LOSS".equals(condition)) {
      assertThat(after.protections())
          .as("[%s] protection race terminal carriers", caseId)
          .noneMatch(protection -> Set.of(
                  "PENDING_ACTIVATION",
                  "PENDING",
                  "WORKING")
              .contains(protection.status()));
    }
  }

  private static String expectedWinnerOrigin(
      ScenarioAction race,
      String winnerRef
  ) {
    if (winnerRef.equals(race.parameters().orderId())) {
      return "OCO_DUAL_TRIGGER".equals(race.parameters().condition())
          ? "OCO"
          : "USER";
    }
    return switch (race.parameters().condition()) {
      case "OCO_DUAL_TRIGGER" -> "OCO";
      case "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL" -> "BATCH_CLOSE";
      case "USER_CLOSE_COMPETES_WITH_LIQUIDATION" -> "LIQUIDATION";
      case "USER_CLOSE_COMPETES_WITH_STOP_LOSS" -> "PROTECTIVE";
      default -> throw new IllegalArgumentException(
          "Unsupported economic race " + race.parameters().condition());
    };
  }

  private static <T> List<T> appended(
      String caseId,
      String label,
      List<T> before,
      List<T> after
  ) {
    assertThat(after.size())
        .as("[%s] %s entries cannot disappear during the race", caseId, label)
        .isGreaterThanOrEqualTo(before.size());
    return after.subList(before.size(), after.size());
  }

}
