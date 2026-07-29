package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.AccountState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.EventState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.OrderState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.PositionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.TradeState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.WalletState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScenarioHarnessValueObjectsTest {

  @Test
  void contextKeepsIdentityCurrentPriceAndBidirectionalLogicalReferences() {
    ScenarioDefinition scenario = ScenarioCatalog.spot().findFirst().orElseThrow();
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String email = "scenario-" + userId + "@example.test";
    UserPrincipal principal = new UserPrincipal(userId, email, "USER");
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    ScenarioContext context =
        new ScenarioContext(scenario, userId, email, principal, account);
    UUID orderId = UUID.randomUUID();

    context.putRef("order-1", orderId);
    context.putRef("order-alias", orderId);
    context.currentPriceStep(scenario.priceSteps().getLast());

    assertThat(context.scenario()).isSameAs(scenario);
    assertThat(context.userId()).isEqualTo(userId);
    assertThat(context.accountId()).isEqualTo(accountId);
    assertThat(context.email()).isEqualTo(email);
    assertThat(context.principal()).isSameAs(principal);
    assertThat(context.account()).isSameAs(account);
    assertThat(context.findRef("order-1")).contains(orderId);
    assertThat(context.requireRef("order-alias")).isEqualTo(orderId);
    assertThat(context.logicalRefs(orderId)).containsExactly("order-1", "order-alias");
    assertThat(context.currentPriceStep()).isEqualTo(scenario.priceSteps().getLast());
  }

  @Test
  void contextRejectsRebindingOneLogicalReferenceToAnotherDatabaseId() {
    ScenarioContext context = context(ScenarioCatalog.spot().findFirst().orElseThrow());
    context.putRef("order-1", UUID.randomUUID());

    assertThatThrownBy(() -> context.putRef("order-1", UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("order-1");
  }

  @Test
  void contextExplicitlyRebindsCurrentPositionWhileKeepingHistoricalReverseAlias()
      throws Exception {
    ScenarioContext context = context(ScenarioCatalog.perpetual().findFirst().orElseThrow());
    String slotRef = "BTCUSDT-PERP:BOTH";
    UUID closedPositionId = UUID.randomUUID();
    UUID replacementPositionId = UUID.randomUUID();
    context.putRef(slotRef, closedPositionId);

    ScenarioContext.class
        .getDeclaredMethod("rebindRef", String.class, UUID.class)
        .invoke(context, slotRef, replacementPositionId);

    assertThat(context.findRef(slotRef)).contains(replacementPositionId);
    assertThat(context.logicalRefs(closedPositionId)).contains(slotRef);
    assertThat(context.logicalRefs(replacementPositionId)).contains(slotRef);
  }

  @Test
  void actualResultDefensivelyCopiesCheckpointsAndExposesTheFinalOne() {
    Checkpoint checkpoint = new Checkpoint(1, "action-1", snapshot("1.0"), null);
    ActualScenarioResult result =
        new ActualScenarioResult("VALUE_OBJECT_CASE", List.of(checkpoint));

    assertThat(result.caseId()).isEqualTo("VALUE_OBJECT_CASE");
    assertThat(result.finalCheckpoint()).isEqualTo(checkpoint);
    assertThat(result.failure()).isEmpty();
    assertThatThrownBy(() -> result.checkpoints().add(checkpoint))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void assertionsCompareBigDecimalsByValueAtEveryNestedLevel() {
    String caseId = "DECIMAL_SCALE_CASE";
    ExpectedScenarioResult expected = new ExpectedScenarioResult(
        caseId,
        List.of(new Checkpoint(1, "action-1", snapshot("1.0"), null)),
        List.of("trace is intentionally not persisted"));
    ActualScenarioResult actual = new ActualScenarioResult(
        caseId,
        List.of(new Checkpoint(1, "action-1", snapshot("1.00000000"), null)));

    ScenarioAssertions.assertScenarioEquals(caseId, expected, actual);
  }

  @Test
  void assertionFailuresNameTheCaseAndNestedField() {
    String caseId = "NESTED_FAILURE_CASE";
    ExpectedScenarioResult expected = new ExpectedScenarioResult(
        caseId,
        List.of(new Checkpoint(1, "action-1", snapshot("1.0"), null)),
        List.of());
    ActualScenarioResult actual = new ActualScenarioResult(
        caseId,
        List.of(new Checkpoint(1, "action-1", snapshot("2.0"), null)));

    assertThatThrownBy(
        () -> ScenarioAssertions.assertScenarioEquals(caseId, expected, actual))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(caseId)
        .hasMessageContaining("holdAmount");
  }

  @Test
  void assertionNullDecimalMismatchReportsTheFieldInsteadOfThrowingNullPointerException() {
    String caseId = "NULL_DECIMAL_MISMATCH";
    ExpectedScenarioResult expected = new ExpectedScenarioResult(
        caseId,
        List.of(new Checkpoint(
            1,
            "action-1",
            snapshotWithAverageFill(new BigDecimal("100.00000000")),
            null)),
        List.of());
    ActualScenarioResult actual = new ActualScenarioResult(
        caseId,
        List.of(new Checkpoint(1, "action-1", snapshotWithAverageFill(null), null)));

    assertThatThrownBy(
        () -> ScenarioAssertions.assertScenarioEquals(caseId, expected, actual))
        .isInstanceOf(AssertionError.class)
        .isNotInstanceOf(NullPointerException.class)
        .hasMessageContaining(caseId)
        .hasMessageContaining("avgFillPrice");
  }

  @Test
  void assertionsRejectMismatchedCaseIdsBeforeComparingState() {
    ExpectedScenarioResult expected = new ExpectedScenarioResult(
        "EXPECTED_CASE",
        List.of(new Checkpoint(1, "action-1", snapshot("1"), null)),
        List.of());
    ActualScenarioResult actual = new ActualScenarioResult(
        "ACTUAL_CASE",
        List.of(new Checkpoint(1, "action-1", snapshot("1"), null)));

    assertThatThrownBy(
        () -> ScenarioAssertions.assertScenarioEquals("REQUESTED_CASE", expected, actual))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("REQUESTED_CASE")
        .hasMessageContaining("caseId");
  }

  @Test
  void balanceRaceAssertionAcceptsEitherDeclaredWinnerButRejectsUnknownIdentity() {
    ScenarioDefinition scenario = ScenarioCatalog.all().stream()
        .filter(candidate -> candidate.caseId().equals("SPOT_BALANCE_RACE"))
        .findFirst()
        .orElseThrow();
    String primary = scenario.actions().getFirst().parameters().clientOrderId();
    String competitor = scenario.actions().getFirst().parameters().competingOrderId();
    ExpectedScenarioResult expected = new ExpectedScenarioResult(
        scenario.caseId(),
        List.of(new Checkpoint(
            1,
            scenario.actions().getFirst().parameters().actionId(),
            snapshotWithTradeKey(primary),
            null)),
        List.of());

    ScenarioAssertions.assertScenarioEquals(
        scenario,
        expected,
        new ActualScenarioResult(
            scenario.caseId(),
            List.of(new Checkpoint(
                1,
                scenario.actions().getFirst().parameters().actionId(),
                snapshotWithTradeKey(competitor),
                null))));

    assertThatThrownBy(() -> ScenarioAssertions.assertScenarioEquals(
        scenario,
        expected,
        new ActualScenarioResult(
            scenario.caseId(),
            List.of(new Checkpoint(
                1,
                scenario.actions().getFirst().parameters().actionId(),
                snapshotWithTradeKey("unknown-race-request"),
                null)))))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("unknown-race-request");
  }

  @Test
  void concurrentEconomicRaceAssertionsAcceptEitherDeclaredCatalogWinner() {
    for (String caseId : List.of(
        "SPOT_OCO_DUAL_TRIGGER_RACE",
        "PERP_CLOSE_VS_BATCH_RACE",
        "PERP_CLOSE_VS_LIQUIDATION_RACE",
        "PERP_CLOSE_VS_PROTECTION_RACE")) {
      ScenarioDefinition scenario = scenario(caseId);
      ScenarioAction race = scenario.actions().getLast();
      Snapshot before = snapshot("1.00000000");
      Snapshot primary = raceSnapshot(
          scenario,
          before,
          race.parameters().orderId(),
          primaryOrigin(race.parameters().condition()),
          false);
      ExpectedScenarioResult expected = raceExpected(scenario, before, primary);
      Snapshot competitor = raceSnapshot(
          scenario,
          before,
          race.parameters().competingOrderId(),
          competitorOrigin(race.parameters().condition()),
          false);
      ExpectedScenarioResult competitorExpected =
          raceExpected(scenario, before, competitor);

      ScenarioAssertions.assertScenarioEquals(
          scenario,
          List.of(expected, competitorExpected),
          raceActual(scenario, before, primary));
      ScenarioAssertions.assertScenarioEquals(
          scenario,
          List.of(expected, competitorExpected),
          raceActual(scenario, before, competitor));
    }
  }

  @Test
  void concurrentEconomicRaceAssertionsRejectAnIllegalWinnerOrSecondSettlement() {
    ScenarioDefinition scenario = scenario("PERP_CLOSE_VS_LIQUIDATION_RACE");
    ScenarioAction race = scenario.actions().getLast();
    Snapshot before = snapshot("1.00000000");
    ExpectedScenarioResult expected = raceExpected(
        scenario,
        before,
        raceSnapshot(
            scenario,
            before,
            race.parameters().orderId(),
            "USER",
            false));
    ExpectedScenarioResult competitorExpected = raceExpected(
        scenario,
        before,
        raceSnapshot(
            scenario,
            before,
            race.parameters().competingOrderId(),
            "LIQUIDATION",
            false));

    assertThatThrownBy(() -> ScenarioAssertions.assertScenarioEquals(
        scenario,
        List.of(expected, competitorExpected),
        raceActual(
            scenario,
            before,
            raceSnapshot(scenario, before, "undeclared-winner", "LIQUIDATION", false))))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("declared race winner");

    assertThatThrownBy(() -> ScenarioAssertions.assertScenarioEquals(
        scenario,
        List.of(expected, competitorExpected),
        raceActual(
            scenario,
            before,
            raceSnapshot(
                scenario,
                before,
                race.parameters().competingOrderId(),
                "LIQUIDATION",
                true))))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("exactly one new trade");
  }

  @Test
  void concurrentEconomicRaceAssertionsRejectSyntheticCompetitorWalletState() {
    ScenarioDefinition scenario = scenario("PERP_CLOSE_VS_BATCH_RACE");
    ScenarioAction race = scenario.actions().getLast();
    Snapshot before = snapshot("1.00000000");
    Snapshot primary = raceSnapshot(
        scenario,
        before,
        race.parameters().orderId(),
        primaryOrigin(race.parameters().condition()),
        false);
    Snapshot competitor = raceSnapshot(
        scenario,
        before,
        race.parameters().competingOrderId(),
        competitorOrigin(race.parameters().condition()),
        false);
    ExpectedScenarioResult competitorExpected =
        raceExpected(scenario, before, competitor);
    WalletState original = competitor.wallets().getFirst();
    Snapshot wrongWallet = new Snapshot(
        competitor.orders(),
        competitor.trades(),
        competitor.positions(),
        List.of(new WalletState(
            original.walletType(),
            original.asset(),
            new BigDecimal("999.00000000"),
            original.available(),
            original.locked())),
        competitor.account(),
        competitor.ledger(),
        competitor.protections(),
        competitor.events());

    assertThatThrownBy(() -> ScenarioAssertions.assertScenarioEquals(
        scenario,
        List.of(
            raceExpected(scenario, before, primary),
            competitorExpected),
        raceActual(scenario, before, wrongWallet)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("wallet");
  }

  @Test
  void concurrentEconomicRaceAssertionsRejectSyntheticCompetitorOrderType() {
    ScenarioDefinition scenario = scenario("PERP_CLOSE_VS_PROTECTION_RACE");
    ScenarioAction race = scenario.actions().getLast();
    Snapshot before = snapshot("1.00000000");
    Snapshot primary = raceSnapshot(
        scenario,
        before,
        race.parameters().orderId(),
        primaryOrigin(race.parameters().condition()),
        false);
    Snapshot competitor = raceSnapshot(
        scenario,
        before,
        race.parameters().competingOrderId(),
        competitorOrigin(race.parameters().condition()),
        false);
    ExpectedScenarioResult competitorExpected =
        raceExpected(scenario, before, competitor);
    List<OrderState> wrongOrders = competitor.orders().stream()
        .map(order -> order.ref().equals(race.parameters().competingOrderId())
            ? new OrderState(
                order.ref(),
                order.side(),
                OrderType.LIMIT,
                order.status(),
                order.quantity(),
                order.filledQuantity(),
                order.remainingQuantity(),
                order.avgFillPrice(),
                order.fee(),
                order.feeAsset(),
                order.liquidityRole(),
                order.reduceOnly(),
                order.origin(),
                order.parentRef(),
                order.contingencyRef(),
                order.holdAsset(),
                order.holdAmount(),
                order.holdOwnerRef(),
                order.errorCode())
            : order)
        .toList();
    Snapshot wrongOrderType = new Snapshot(
        wrongOrders,
        competitor.trades(),
        competitor.positions(),
        competitor.wallets(),
        competitor.account(),
        competitor.ledger(),
        competitor.protections(),
        competitor.events());

    assertThatThrownBy(() -> ScenarioAssertions.assertScenarioEquals(
        scenario,
        List.of(
            raceExpected(scenario, before, primary),
            competitorExpected),
        raceActual(scenario, before, wrongOrderType)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("type");
  }

  private static ScenarioContext context(ScenarioDefinition scenario) {
    UUID userId = UUID.randomUUID();
    String email = "scenario-" + userId + "@example.test";
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    account.setUserId(userId);
    return new ScenarioContext(
        scenario,
        userId,
        email,
        new UserPrincipal(userId, email, "USER"),
        account);
  }

  private static ScenarioDefinition scenario(String caseId) {
    return ScenarioCatalog.all().stream()
        .filter(candidate -> candidate.caseId().equals(caseId))
        .findFirst()
        .orElseThrow();
  }

  private static ExpectedScenarioResult raceExpected(
      ScenarioDefinition scenario,
      Snapshot before,
      Snapshot after
  ) {
    return new ExpectedScenarioResult(
        scenario.caseId(),
        raceCheckpoints(scenario, before, after),
        List.of());
  }

  private static ActualScenarioResult raceActual(
      ScenarioDefinition scenario,
      Snapshot before,
      Snapshot after
  ) {
    return new ActualScenarioResult(
        scenario.caseId(),
        raceCheckpoints(scenario, before, after));
  }

  private static List<Checkpoint> raceCheckpoints(
      ScenarioDefinition scenario,
      Snapshot before,
      Snapshot after
  ) {
    List<Checkpoint> checkpoints = new ArrayList<>(scenario.actions().size());
    for (int index = 0; index < scenario.actions().size(); index++) {
      ScenarioAction action = scenario.actions().get(index);
      checkpoints.add(new Checkpoint(
          index + 1,
          action.parameters().actionId(),
          index == scenario.actions().size() - 1 ? after : before,
          null));
    }
    return List.copyOf(checkpoints);
  }

  private static Snapshot raceSnapshot(
      ScenarioDefinition scenario,
      Snapshot before,
      String winner,
      String origin,
      boolean duplicateSettlement
  ) {
    ScenarioAction race = scenario.actions().getLast();
    String primary = race.parameters().orderId();
    String competitor = race.parameters().competingOrderId();
    String loser = winner.equals(primary) ? competitor : primary;
    boolean oco = "OCO_DUAL_TRIGGER".equals(race.parameters().condition());

    List<OrderState> orders = new ArrayList<>(before.orders());
    orders.add(raceOrder(winner, "FILLED", origin));
    if (oco) {
      orders.add(raceOrder(loser, "CANCELED", "USER"));
    }

    TradeState settlement = new TradeState(
        winner + "-trade-1",
        winner,
        OrderSide.SELL,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        new BigDecimal("0.10000000"),
        "USDT",
        BigDecimal.ZERO,
        scenario.productType(),
        PositionSide.BOTH,
        scenario.marginMode(),
        LiquidityRole.TAKER,
        winner);
    List<TradeState> trades = new ArrayList<>(before.trades());
    trades.add(settlement);
    if (duplicateSettlement) {
      trades.add(new TradeState(
          winner + "-trade-2",
          winner,
          settlement.side(),
          settlement.quantity(),
          settlement.price(),
          settlement.quoteNotional(),
          settlement.fee(),
          settlement.feeAsset(),
          settlement.realizedPnl(),
          settlement.productType(),
          settlement.positionSide(),
          settlement.marginMode(),
          settlement.liquidityRole(),
          winner + "-duplicate"));
    }

    List<PositionState> positions = scenario.productType() == ProductType.LINEAR_PERP
        ? before.positions().stream().map(ScenarioHarnessValueObjectsTest::closed).toList()
        : before.positions();
    List<LedgerState> ledger = new ArrayList<>(before.ledger());
    ledger.add(new LedgerState(
        ledger.size() + 1,
        "TRADE_FEE",
        "USDT",
        new BigDecimal("-0.10000000"),
        new BigDecimal("0.90000000"),
        "TRADE",
        settlement.ref(),
        "TRADE_FEE"));
    List<EventState> events = new ArrayList<>(before.events());
    events.add(new EventState(
        events.size() + 1,
        "ORDER_FILLED",
        winner,
        "ACCEPTED",
        "FILLED",
        ""));
    if (oco) {
      events.add(new EventState(
          events.size() + 1,
          "ORDER_CANCELED",
          loser,
          "PENDING",
          "CANCELED",
          ""));
    }
    return new Snapshot(
        orders,
        trades,
        positions,
        before.wallets(),
        before.account(),
        ledger,
        before.protections(),
        events);
  }

  private static OrderState raceOrder(String ref, String status, String origin) {
    boolean filled = "FILLED".equals(status);
    return new OrderState(
        ref,
        OrderSide.SELL,
        OrderType.MARKET,
        status,
        BigDecimal.ONE,
        filled ? BigDecimal.ONE : BigDecimal.ZERO,
        filled ? BigDecimal.ZERO : BigDecimal.ONE,
        filled ? BigDecimal.ONE : null,
        filled ? new BigDecimal("0.10000000") : BigDecimal.ZERO,
        "USDT",
        filled ? LiquidityRole.TAKER : null,
        true,
        origin,
        "",
        "",
        "",
        BigDecimal.ZERO,
        "",
        "");
  }

  private static PositionState closed(PositionState value) {
    return new PositionState(
        value.slot(),
        "CLOSED",
        value.side(),
        value.positionMode(),
        value.positionSide(),
        value.marginMode(),
        BigDecimal.ZERO,
        value.averageEntry(),
        value.markPrice(),
        value.realizedPnl(),
        BigDecimal.ZERO,
        value.fundingPnl(),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        value.leverage());
  }

  private static String competitorOrigin(String condition) {
    return switch (condition) {
      case "OCO_DUAL_TRIGGER" -> "OCO";
      case "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL" -> "BATCH_CLOSE";
      case "USER_CLOSE_COMPETES_WITH_LIQUIDATION" -> "LIQUIDATION";
      case "USER_CLOSE_COMPETES_WITH_STOP_LOSS" -> "PROTECTIVE";
      default -> throw new IllegalArgumentException(condition);
    };
  }

  private static String primaryOrigin(String condition) {
    return "OCO_DUAL_TRIGGER".equals(condition) ? "OCO" : "USER";
  }

  private static Snapshot snapshot(String decimal) {
    BigDecimal value = new BigDecimal(decimal);
    return snapshot(value, value);
  }

  private static Snapshot snapshotWithAverageFill(BigDecimal averageFill) {
    BigDecimal value = new BigDecimal("1.00000000");
    return snapshot(value, averageFill);
  }

  private static Snapshot snapshotWithTradeKey(String uniqueKey) {
    Snapshot snapshot = snapshot("1.00000000");
    TradeState trade = snapshot.trades().getFirst();
    TradeState replacement = new TradeState(
        trade.ref(),
        trade.orderRef(),
        trade.side(),
        trade.quantity(),
        trade.price(),
        trade.quoteNotional(),
        trade.fee(),
        trade.feeAsset(),
        trade.realizedPnl(),
        trade.productType(),
        trade.positionSide(),
        trade.marginMode(),
        trade.liquidityRole(),
        uniqueKey);
    return new Snapshot(
        snapshot.orders(),
        List.of(replacement),
        snapshot.positions(),
        snapshot.wallets(),
        snapshot.account(),
        snapshot.ledger(),
        snapshot.protections(),
        snapshot.events());
  }

  private static Snapshot snapshot(BigDecimal value, BigDecimal averageFill) {
    OrderState order = new OrderState(
        "order-1",
        OrderSide.BUY,
        OrderType.MARKET,
        "FILLED",
        value,
        value,
        BigDecimal.ZERO,
        averageFill,
        value,
        "USDT",
        LiquidityRole.TAKER,
        false,
        "USER",
        "",
        "",
        "USDT",
        value,
        "order-1",
        "");
    TradeState trade = new TradeState(
        "order-1-trade-1",
        "order-1",
        OrderSide.BUY,
        value,
        value,
        value,
        value,
        "USDT",
        value,
        ProductType.CRYPTO_SPOT,
        PositionSide.BOTH,
        MarginMode.CASH,
        LiquidityRole.TAKER,
        "client-1");
    PositionState position = new PositionState(
        "BTC",
        "OPEN",
        OrderSide.BUY,
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        MarginMode.CASH,
        value,
        value,
        value,
        value,
        value,
        value,
        value,
        value,
        value,
        value,
        1);
    WalletState wallet = new WalletState("SPOT", "USDT", value, value, BigDecimal.ZERO);
    AccountState account =
        new AccountState(value, value, value, value, value, value);
    LedgerState ledger =
        new LedgerState(1, "TYPE", "USDT", value, value, "ORDER", "order-1", "TYPE");
    EventState event =
        new EventState(1, "ORDER_FILLED", "order-1", "PENDING", "FILLED", "");
    return new Snapshot(
        List.of(order),
        List.of(trade),
        List.of(position),
        List.of(wallet),
        account,
        List.of(ledger),
        List.of(),
        List.of(event));
  }
}
