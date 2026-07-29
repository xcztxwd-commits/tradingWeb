package com.fxplatform.tradinglab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class TradingLabIsolatedPerpetualHttpIT extends TradingLabHttpIntegrationSupport {

  private static final int MONEY_SCALE = 8;
  private static final int INTERNAL_SCALE = 18;

  @Test
  void isolatedOpenReduceAndFullCloseHaveExactRealHttpRiskAndAccountingEvidence() {
    var fixture = TradingLabPerpetualHttpScenarioFactory.isolated(currentConfig());
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    long scenarioVersion = scenario.path("version").asLong();

    RunView accepted = startRun(scenarioId, fixture.runRequest(scenarioVersion));
    UUID runId = accepted.id();
    try {
      assertThat(accepted.state()).isEqualTo("QUEUED");
      assertThat(accepted.totalTicks()).isEqualTo(fixture.totalTicks());
      RunView terminal = awaitTerminal(runId);
      assertThat(terminal.state())
          .as("isolated Perpetual run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      RawReport report = downloadAndValidate(runId);
      assertAll(
          () -> assertRequiredPerpetualHttpHops(report.root()),
          () -> assertExactIsolatedLifecycle(report.root(), fixture),
          () -> {
            assertThat(report.root().path("actualState").path("terminalState").asText())
                .isEqualTo("COMPLETED");
            assertThat(report.root().path("errors")).isEmpty();
            assertThat(report.root().path("cleanup").path("status").asText())
                .isEqualTo("SUCCEEDED");
          });
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  private static void assertExactIsolatedLifecycle(
      JsonNode report,
      TradingLabPerpetualHttpScenarioFactory.IsolatedScenario fixture
  ) {
    BigDecimal entry = marketFill(
        "BUY",
        fixture.lastPrices().get(0),
        fixture.instrument().tickSize(),
        fixture.spreadSteps(),
        fixture.slippageRate());
    BigDecimal firstClose = marketFill(
        "SELL",
        fixture.lastPrices().get(1),
        fixture.instrument().tickSize(),
        fixture.spreadSteps(),
        fixture.slippageRate());
    BigDecimal finalClose = marketFill(
        "SELL",
        fixture.lastPrices().get(2),
        fixture.instrument().tickSize(),
        fixture.spreadSteps(),
        fixture.slippageRate());
    BigDecimal openFee = fee(
        fixture.openQuantity(),
        entry,
        fixture.takerFeeRate());
    BigDecimal firstCloseFee = fee(
        fixture.closeQuantity(),
        firstClose,
        fixture.takerFeeRate());
    BigDecimal finalCloseFee = fee(
        fixture.closeQuantity(),
        finalClose,
        fixture.takerFeeRate());
    BigDecimal firstRealized = pnl(
        "BUY",
        fixture.closeQuantity(),
        entry,
        firstClose);
    BigDecimal finalRealized = pnl(
        "BUY",
        fixture.closeQuantity(),
        entry,
        finalClose);

    JsonNode finalState = report.path("actualState").path("state");
    assertThat(finalState.isObject()).isTrue();
    List<JsonNode> orders = stateItems(finalState, "orders");
    List<JsonNode> trades = stateItems(finalState, "trades");
    assertThat(orders).hasSize(3);
    assertThat(trades).hasSize(3);

    JsonNode openTrade = tradeAtPrice(trades, entry, "isolated open trade");
    JsonNode firstCloseTrade =
        tradeAtPrice(trades, firstClose, "isolated partial-close trade");
    JsonNode finalCloseTrade =
        tradeAtPrice(trades, finalClose, "isolated full-close trade");
    JsonNode openOrder = orderForTrade(orders, openTrade);
    JsonNode firstCloseOrder = orderForTrade(orders, firstCloseTrade);
    JsonNode finalCloseOrder = orderForTrade(orders, finalCloseTrade);
    assertOrder(openOrder, "BUY", fixture.openQuantity(), false);
    assertOrder(firstCloseOrder, "SELL", fixture.closeQuantity(), true);
    assertOrder(finalCloseOrder, "SELL", fixture.closeQuantity(), true);
    assertTrade(openTrade, "BUY", fixture.openQuantity(), entry, openFee, BigDecimal.ZERO);
    assertTrade(
        firstCloseTrade,
        "SELL",
        fixture.closeQuantity(),
        firstClose,
        firstCloseFee,
        firstRealized);
    assertTrade(
        finalCloseTrade,
        "SELL",
        fixture.closeQuantity(),
        finalClose,
        finalCloseFee,
        finalRealized);

    JsonNode tickOne = stateAtTick(report, 1L);
    JsonNode tickTwo = stateAtTick(report, 2L);
    JsonNode tickThree = stateAtTick(report, 3L);
    List<JsonNode> tickOnePositions = stateItems(tickOne, "positions");
    List<JsonNode> tickTwoPositions = stateItems(tickTwo, "positions");
    assertThat(tickOnePositions).singleElement();
    assertThat(tickTwoPositions).singleElement();
    JsonNode opened = tickOnePositions.getFirst();
    JsonNode reduced = tickTwoPositions.getFirst();
    assertThat(reduced.path("id").asText()).isEqualTo(opened.path("id").asText());

    BigDecimal openMargin = margin(
        fixture.openQuantity(),
        entry,
        fixture.leverage());
    BigDecimal tickOneUpl = pnl(
        "BUY",
        fixture.openQuantity(),
        entry,
        fixture.lastPrices().get(0));
    BigDecimal tickOneMaintenance = maintenance(
        fixture.openQuantity(),
        fixture.lastPrices().get(0),
        fixture.instrument().maintenanceMarginRate());
    assertPosition(
        opened,
        fixture,
        fixture.openQuantity(),
        entry,
        fixture.lastPrices().get(0),
        tickOneUpl,
        BigDecimal.ZERO,
        openMargin,
        tickOneMaintenance,
        isolatedLiquidation(
            "BUY",
            entry,
            fixture.openQuantity(),
            openMargin,
            BigDecimal.ZERO,
            fixture.instrument().maintenanceMarginRate(),
            fixture.takerFeeRate()));
    BigDecimal tickOneBalance = money(fixture.initialUsdt().subtract(openFee));
    assertOpenSummary(
        tickOne.path("summary"),
        tickOneBalance,
        tickOneUpl,
        openMargin,
        tickOneMaintenance,
        money(fixture.openQuantity().multiply(fixture.lastPrices().get(0))),
        true);

    BigDecimal reducedMargin = openMargin
        .multiply(fixture.closeQuantity())
        .divide(fixture.openQuantity(), MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal tickTwoUpl = pnl(
        "BUY",
        fixture.closeQuantity(),
        entry,
        fixture.lastPrices().get(1));
    BigDecimal tickTwoMaintenance = maintenance(
        fixture.closeQuantity(),
        fixture.lastPrices().get(1),
        fixture.instrument().maintenanceMarginRate());
    assertPosition(
        reduced,
        fixture,
        fixture.closeQuantity(),
        entry,
        fixture.lastPrices().get(1),
        tickTwoUpl,
        firstRealized,
        reducedMargin,
        tickTwoMaintenance,
        isolatedLiquidation(
            "BUY",
            entry,
            fixture.closeQuantity(),
            reducedMargin,
            BigDecimal.ZERO,
            fixture.instrument().maintenanceMarginRate(),
            fixture.takerFeeRate()));
    BigDecimal tickTwoBalance = money(
        tickOneBalance.add(firstRealized).subtract(firstCloseFee));
    assertOpenSummary(
        tickTwo.path("summary"),
        tickTwoBalance,
        tickTwoUpl,
        reducedMargin,
        tickTwoMaintenance,
        money(fixture.closeQuantity().multiply(fixture.lastPrices().get(1))),
        true);

    assertThat(stateItems(tickThree, "positions"))
        .as("no open position remains after the full reduce-only close")
        .isEmpty();
    assertThat(stateItems(finalState, "positions")).isEmpty();
    BigDecimal finalBalance = money(
        tickTwoBalance.add(finalRealized).subtract(finalCloseFee));
    assertClosedSummary(finalState.path("summary"), finalBalance);
    assertWalletUnchanged(
        stateItems(finalState, "walletBalances"),
        fixture.initialUsdt());

    List<String> pnlLedgerAmounts = stateItems(finalState, "cashLedger").stream()
        .filter(entryNode -> "TRADE_PNL".equals(entryNode.path("entryType").asText()))
        .map(entryNode -> exactMoney(decimal(entryNode, "amount")))
        .toList();
    assertThat(pnlLedgerAmounts)
        .containsExactlyInAnyOrder(
            exactMoney(firstRealized),
            exactMoney(finalRealized));
    List<String> feeLedgerAmounts = stateItems(finalState, "cashLedger").stream()
        .filter(entryNode -> "TRADE_FEE".equals(entryNode.path("entryType").asText()))
        .map(entryNode -> exactMoney(decimal(entryNode, "amount")))
        .toList();
    assertThat(feeLedgerAmounts)
        .containsExactlyInAnyOrder(
            exactMoney(openFee.negate()),
            exactMoney(firstCloseFee.negate()),
            exactMoney(finalCloseFee.negate()));
  }

  private static void assertPosition(
      JsonNode position,
      TradingLabPerpetualHttpScenarioFactory.IsolatedScenario fixture,
      BigDecimal quantity,
      BigDecimal entry,
      BigDecimal mark,
      BigDecimal unrealized,
      BigDecimal realized,
      BigDecimal margin,
      BigDecimal maintenance,
      BigDecimal liquidation
  ) {
    assertThat(position.path("symbol").asText())
        .isEqualTo(fixture.instrument().symbol());
    assertThat(position.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(position.path("side").asText()).isEqualTo("BUY");
    assertThat(position.path("positionMode").asText()).isEqualTo("HEDGE");
    assertThat(position.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(position.path("marginMode").asText()).isEqualTo("ISOLATED");
    assertThat(position.path("status").asText()).isEqualTo("OPEN");
    assertThat(position.path("leverage").asInt()).isEqualTo(fixture.leverage());
    assertMoney(position, "lots", quantity);
    assertMoney(position, "openPrice", entry);
    assertMoney(position, "markPrice", mark);
    assertMoney(position, "notional", money(quantity.multiply(mark)));
    assertMoney(position, "floatingPnl", unrealized);
    assertMoney(position, "floatingPnlRatio", ratio(unrealized, margin));
    assertMoney(position, "realizedPnl", realized);
    assertMoney(position, "fundingPnl", BigDecimal.ZERO);
    assertMoney(position, "marginHeld", margin);
    assertMoney(position, "maintenanceMargin", maintenance);
    assertThat(decimal(position, "maintenanceMarginRate"))
        .isEqualByComparingTo(fixture.instrument().maintenanceMarginRate());
    assertMoney(position, "liquidationPrice", liquidation);
  }

  private static void assertOpenSummary(
      JsonNode summary,
      BigDecimal balance,
      BigDecimal unrealized,
      BigDecimal margin,
      BigDecimal maintenance,
      BigDecimal positionValue,
      boolean isolated
  ) {
    BigDecimal equity = money(balance.add(unrealized));
    BigDecimal freeMargin = isolated
        ? money(balance.subtract(margin))
        : money(equity.subtract(margin));
    assertMoney(summary, "balance", balance);
    assertMoney(summary, "equity", equity);
    assertMoney(summary, "usedMargin", margin);
    assertMoney(summary, "freeMargin", freeMargin);
    assertMoney(summary, "marginLevel", ratio(equity.multiply(new BigDecimal("100")), margin));
    assertMoney(summary, "openFloatingPnl", unrealized);
    assertMoney(summary, "maintenanceMargin", maintenance);
    assertMoney(summary, "positionValue", positionValue);
    assertMoney(summary, "marginAvailable", freeMargin);
  }

  private static void assertClosedSummary(JsonNode summary, BigDecimal balance) {
    assertMoney(summary, "balance", balance);
    assertMoney(summary, "equity", balance);
    assertMoney(summary, "usedMargin", BigDecimal.ZERO);
    assertMoney(summary, "freeMargin", balance);
    assertThat(summary.get("marginLevel")).isNotNull();
    assertThat(summary.get("marginLevel").isNull()).isTrue();
    assertMoney(summary, "openFloatingPnl", BigDecimal.ZERO);
    assertMoney(summary, "maintenanceMargin", BigDecimal.ZERO);
    assertMoney(summary, "positionValue", BigDecimal.ZERO);
    assertMoney(summary, "marginAvailable", balance);
  }

  private static void assertWalletUnchanged(
      List<JsonNode> wallets,
      BigDecimal initialUsdt
  ) {
    JsonNode wallet = item(
        wallets,
        candidate -> "USDT".equals(candidate.path("asset").asText()),
        "USDT wallet");
    assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
    assertMoney(wallet, "total", initialUsdt);
    assertMoney(wallet, "available", initialUsdt);
    assertMoney(wallet, "locked", BigDecimal.ZERO);
  }

  private static void assertOrder(
      JsonNode order,
      String side,
      BigDecimal quantity,
      boolean reduceOnly
  ) {
    assertThat(order.path("symbol").asText())
        .isEqualTo(TradingLabPerpetualHttpScenarioFactory.BTC_PERPETUAL);
    assertThat(order.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(order.path("side").asText()).isEqualTo(side);
    assertThat(order.path("orderType").asText()).isEqualTo("MARKET");
    assertThat(order.path("positionMode").asText()).isEqualTo("HEDGE");
    assertThat(order.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(order.path("marginMode").asText()).isEqualTo("ISOLATED");
    assertThat(order.path("quantityUnit").asText()).isEqualTo("BASE");
    assertThat(order.path("reduceOnly").asBoolean()).isEqualTo(reduceOnly);
    assertThat(order.path("status").asText()).isEqualTo("FILLED");
    for (String field : List.of(
        "lots", "quantity", "originalQuantity", "baseQuantity", "filledQuantity")) {
      assertMoney(order, field, quantity);
    }
    assertMoney(order, "remainingQuantity", BigDecimal.ZERO);
  }

  private static void assertTrade(
      JsonNode trade,
      String side,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal fee,
      BigDecimal realized
  ) {
    assertThat(trade.path("symbol").asText())
        .isEqualTo(TradingLabPerpetualHttpScenarioFactory.BTC_PERPETUAL);
    assertThat(trade.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(trade.path("side").asText()).isEqualTo(side);
    assertThat(trade.path("positionSide").asText()).isEqualTo("LONG");
    assertThat(trade.path("marginMode").asText()).isEqualTo("ISOLATED");
    assertThat(trade.path("feeAsset").asText()).isEqualTo("USDT");
    assertMoney(trade, "lots", quantity);
    assertMoney(trade, "price", price);
    assertMoney(trade, "fee", fee);
    assertMoney(trade, "realizedPnl", realized);
  }

  private static JsonNode tradeAtPrice(
      List<JsonNode> trades,
      BigDecimal price,
      String description
  ) {
    return item(
        trades,
        candidate -> decimal(candidate, "price").compareTo(price) == 0,
        description);
  }

  private static JsonNode orderForTrade(List<JsonNode> orders, JsonNode trade) {
    String orderId = trade.path("orderId").asText();
    assertThat(orderId).isNotBlank();
    return item(
        orders,
        candidate -> orderId.equals(candidate.path("id").asText()),
        "order " + orderId);
  }

  private static JsonNode stateAtTick(JsonNode report, long tickSequence) {
    JsonNode latest = null;
    for (JsonNode event : report.path("checkpoints")) {
      if ("STATE_SNAPSHOT".equals(event.path("type").asText())
          && event.path("payload").path("tickSequence").asLong() == tickSequence) {
        latest = event.path("payload").path("state");
      }
    }
    assertThat(latest).as("STATE_SNAPSHOT at Tick " + tickSequence).isNotNull();
    assertThat(latest.isObject()).as("state at Tick " + tickSequence).isTrue();
    return latest.deepCopy();
  }

  private static void assertRequiredPerpetualHttpHops(JsonNode report) {
    List<String> urls = apiTraceUrls(report);
    List<RequiredHop> required = List.of(
        new RequiredHop(
            "main Admin run API",
            value -> value.contains("/api/admin/trading-lab/scenarios/")
                && value.endsWith("/runs")),
        new RequiredHop(
            "validation authentication",
            value -> value.contains("/api/auth/register")
                || value.contains("/api/auth/login")),
        new RequiredHop(
            "position mode configuration",
            value -> value.contains("/api/accounts/")
                && value.endsWith("/position-mode")),
        new RequiredHop(
            "symbol leverage and margin configuration",
            value -> value.contains("/api/accounts/")
                && value.contains("/symbols/")
                && value.endsWith("/settings")),
        new RequiredHop(
            "public order API",
            value -> value.contains("/api/trading/orders")),
        new RequiredHop(
            "public positions API",
            value -> value.contains("/api/trading/positions")),
        new RequiredHop(
            "public trades API",
            value -> value.contains("/api/trading/trades")),
        new RequiredHop(
            "wallet balances",
            value -> value.contains("/wallet-balances")),
        new RequiredHop(
            "asset ledger",
            value -> value.contains("/asset-ledger")),
        new RequiredHop(
            "cash ledger",
            value -> value.contains("/api/ledger")),
        new RequiredHop(
            "account summary",
            value -> value.contains("/summary")));
    ArrayList<String> missing = new ArrayList<>();
    for (RequiredHop hop : required) {
      if (urls.stream().noneMatch(hop.path())) {
        missing.add(hop.name());
      }
    }
    assertThat(missing)
        .as("missing real Perpetual HTTP hop categories; captured URLs=%s", urls)
        .isEmpty();
  }

  private static BigDecimal marketFill(
      String side,
      BigDecimal last,
      BigDecimal tickSize,
      int spreadSteps,
      BigDecimal slippageRate
  ) {
    BigDecimal bid = last.subtract(
        tickSize.multiply(BigDecimal.valueOf(spreadSteps / 2L)));
    BigDecimal ask = bid.add(tickSize.multiply(BigDecimal.valueOf(spreadSteps)));
    BigDecimal reference = "BUY".equals(side) ? ask : bid;
    BigDecimal slippage = reference.multiply(slippageRate);
    return money("BUY".equals(side)
        ? reference.add(slippage)
        : reference.subtract(slippage));
  }

  private static BigDecimal fee(
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal feeRate
  ) {
    return money(quantity.abs().multiply(price).multiply(feeRate));
  }

  private static BigDecimal margin(
      BigDecimal quantity,
      BigDecimal entry,
      int leverage
  ) {
    return quantity.abs()
        .multiply(entry)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        .divide(BigDecimal.valueOf(leverage), MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal maintenance(
      BigDecimal quantity,
      BigDecimal mark,
      BigDecimal maintenanceMarginRate
  ) {
    return money(
        money(quantity.abs().multiply(mark)).multiply(maintenanceMarginRate));
  }

  private static BigDecimal pnl(
      String side,
      BigDecimal quantity,
      BigDecimal entry,
      BigDecimal close
  ) {
    BigDecimal difference = "BUY".equals(side)
        ? close.subtract(entry)
        : entry.subtract(close);
    return money(difference.multiply(quantity.abs()));
  }

  private static BigDecimal isolatedLiquidation(
      String side,
      BigDecimal entry,
      BigDecimal quantity,
      BigDecimal margin,
      BigDecimal funding,
      BigDecimal maintenanceMarginRate,
      BigDecimal closeTakerFeeRate
  ) {
    BigDecimal marginPerBase = margin.add(funding)
        .divide(quantity.abs(), INTERNAL_SCALE, RoundingMode.HALF_UP);
    BigDecimal numerator = "BUY".equals(side)
        ? entry.subtract(marginPerBase)
        : entry.add(marginPerBase);
    BigDecimal denominator = "BUY".equals(side)
        ? BigDecimal.ONE.subtract(maintenanceMarginRate).subtract(closeTakerFeeRate)
        : BigDecimal.ONE.add(maintenanceMarginRate).add(closeTakerFeeRate);
    return numerator.divide(denominator, MONEY_SCALE, RoundingMode.HALF_UP)
        .max(money(BigDecimal.ZERO));
  }

  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    return numerator.divide(denominator, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String exactMoney(BigDecimal value) {
    return money(value).toPlainString();
  }

  private static void assertMoney(
      JsonNode object,
      String field,
      BigDecimal expected
  ) {
    assertThat(exactMoney(decimal(object, field)))
        .as(field)
        .isEqualTo(exactMoney(expected));
  }

  private record RequiredHop(String name, Predicate<String> path) {
  }
}
