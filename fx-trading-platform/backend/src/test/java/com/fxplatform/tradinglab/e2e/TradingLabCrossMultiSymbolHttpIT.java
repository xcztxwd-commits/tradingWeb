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

class TradingLabCrossMultiSymbolHttpIT extends TradingLabHttpIntegrationSupport {

  private static final int MONEY_SCALE = 8;
  private static final int INTERNAL_SCALE = 18;

  @Test
  void bitcoinLongAndEtherShortShareExactCrossUsdtRiskAndFundingOverRealHttp() {
    var fixture = TradingLabPerpetualHttpScenarioFactory.cross(currentConfig());
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
          .as("Cross multi-symbol run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      RawReport report = downloadAndValidate(runId);
      assertAll(
          () -> assertRequiredCrossHttpHops(report.root()),
          () -> assertExactCrossState(report.root(), fixture),
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

  private static void assertExactCrossState(
      JsonNode report,
      TradingLabPerpetualHttpScenarioFactory.CrossScenario fixture
  ) {
    ExpectedPosition bitcoin = expectedPosition(
        fixture.bitcoin(),
        "BUY",
        "LONG",
        fixture.bitcoinQuantity(),
        fixture.bitcoinLast(),
        fixture,
        funding("BUY", fixture.bitcoinQuantity(), fixture.bitcoinLast(), fixture.fundingRate()));
    ExpectedPosition ether = expectedPosition(
        fixture.ether(),
        "SELL",
        "SHORT",
        fixture.etherQuantity(),
        fixture.etherLast(),
        fixture,
        funding("SELL", fixture.etherQuantity(), fixture.etherLast(), fixture.fundingRate()));
    BigDecimal balance = money(
        fixture.initialUsdt()
            .subtract(bitcoin.fee())
            .subtract(ether.fee())
            .add(bitcoin.funding())
            .add(ether.funding()));
    BigDecimal bitcoinLiquidation = crossLiquidation(balance, bitcoin, ether);
    BigDecimal etherLiquidation = crossLiquidation(balance, ether, bitcoin);
    assertThat(bitcoinLiquidation)
        .as("BTC shared Cross liquidation estimate")
        .isPositive();
    assertThat(etherLiquidation)
        .as("ETH shared Cross liquidation estimate")
        .isPositive();

    JsonNode state = report.path("actualState").path("state");
    assertThat(state.isObject()).isTrue();
    List<JsonNode> orders = stateItems(state, "orders");
    List<JsonNode> trades = stateItems(state, "trades");
    List<JsonNode> positions = stateItems(state, "positions");
    assertThat(orders).hasSize(2);
    assertThat(trades).hasSize(2);
    assertThat(positions).hasSize(2);

    JsonNode bitcoinPosition = itemForSymbol(positions, bitcoin.rules().symbol());
    JsonNode etherPosition = itemForSymbol(positions, ether.rules().symbol());
    JsonNode bitcoinTrade = itemForSymbol(trades, bitcoin.rules().symbol());
    JsonNode etherTrade = itemForSymbol(trades, ether.rules().symbol());
    JsonNode bitcoinOrder = orderForTrade(orders, bitcoinTrade);
    JsonNode etherOrder = orderForTrade(orders, etherTrade);
    assertOrder(bitcoinOrder, bitcoin);
    assertOrder(etherOrder, ether);
    assertTrade(bitcoinTrade, bitcoin);
    assertTrade(etherTrade, ether);
    assertPosition(bitcoinPosition, bitcoin, bitcoinLiquidation, fixture);
    assertPosition(etherPosition, ether, etherLiquidation, fixture);

    BigDecimal totalUpl = money(bitcoin.unrealized().add(ether.unrealized()));
    BigDecimal totalMargin = money(bitcoin.margin().add(ether.margin()));
    BigDecimal totalMaintenance =
        money(bitcoin.maintenance().add(ether.maintenance()));
    BigDecimal totalPositionValue =
        money(bitcoin.markNotional().add(ether.markNotional()));
    BigDecimal equity = money(balance.add(totalUpl));
    BigDecimal freeMargin = money(equity.subtract(totalMargin));
    JsonNode summary = state.path("summary");
    assertMoney(summary, "balance", balance);
    assertMoney(summary, "equity", equity);
    assertMoney(summary, "usedMargin", totalMargin);
    assertMoney(summary, "freeMargin", freeMargin);
    assertMoney(
        summary,
        "marginLevel",
        ratio(equity.multiply(new BigDecimal("100")), totalMargin));
    assertMoney(summary, "openFloatingPnl", totalUpl);
    assertMoney(summary, "maintenanceMargin", totalMaintenance);
    assertMoney(summary, "positionValue", totalPositionValue);
    assertMoney(summary, "marginAvailable", freeMargin);

    assertWalletUnchanged(
        stateItems(state, "walletBalances"),
        fixture.initialUsdt());
    List<JsonNode> settlements = stateItems(state, "fundingSettlements");
    assertThat(settlements).hasSize(2);
    assertFundingSettlement(
        itemForSymbol(settlements, bitcoin.rules().symbol()),
        bitcoinPosition,
        bitcoin,
        fixture.fundingRate());
    assertFundingSettlement(
        itemForSymbol(settlements, ether.rules().symbol()),
        etherPosition,
        ether,
        fixture.fundingRate());
    assertFundingLedger(state, settlements, bitcoin, ether);

    List<String> feeLedgerAmounts = stateItems(state, "cashLedger").stream()
        .filter(entry -> "TRADE_FEE".equals(entry.path("entryType").asText()))
        .map(entry -> exactMoney(decimal(entry, "amount")))
        .toList();
    assertThat(feeLedgerAmounts)
        .containsExactlyInAnyOrder(
            exactMoney(bitcoin.fee().negate()),
            exactMoney(ether.fee().negate()));
  }

  private static ExpectedPosition expectedPosition(
      TradingLabPerpetualHttpScenarioFactory.InstrumentRules rules,
      String side,
      String positionSide,
      BigDecimal quantity,
      BigDecimal mark,
      TradingLabPerpetualHttpScenarioFactory.CrossScenario fixture,
      BigDecimal funding
  ) {
    BigDecimal entry = marketFill(
        side,
        mark,
        rules.tickSize(),
        fixture.spreadSteps(),
        fixture.slippageRate());
    BigDecimal markNotional = money(quantity.abs().multiply(mark));
    return new ExpectedPosition(
        rules,
        side,
        positionSide,
        quantity,
        entry,
        mark,
        fee(quantity, entry, fixture.takerFeeRate()),
        pnl(side, quantity, entry, mark),
        margin(quantity, entry, fixture.leverage()),
        money(markNotional.multiply(rules.maintenanceMarginRate())),
        money(markNotional.multiply(fixture.takerFeeRate())),
        fixture.takerFeeRate(),
        markNotional,
        funding);
  }

  private static void assertOrder(JsonNode order, ExpectedPosition expected) {
    assertThat(order.path("symbol").asText()).isEqualTo(expected.rules().symbol());
    assertThat(order.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(order.path("side").asText()).isEqualTo(expected.side());
    assertThat(order.path("orderType").asText()).isEqualTo("MARKET");
    assertThat(order.path("positionMode").asText()).isEqualTo("HEDGE");
    assertThat(order.path("positionSide").asText()).isEqualTo(expected.positionSide());
    assertThat(order.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(order.path("quantityUnit").asText()).isEqualTo("BASE");
    assertThat(order.path("reduceOnly").asBoolean()).isFalse();
    assertThat(order.path("status").asText()).isEqualTo("FILLED");
    for (String field : List.of(
        "lots", "quantity", "originalQuantity", "baseQuantity", "filledQuantity")) {
      assertMoney(order, field, expected.quantity());
    }
    assertMoney(order, "remainingQuantity", BigDecimal.ZERO);
  }

  private static void assertTrade(JsonNode trade, ExpectedPosition expected) {
    assertThat(trade.path("symbol").asText()).isEqualTo(expected.rules().symbol());
    assertThat(trade.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(trade.path("side").asText()).isEqualTo(expected.side());
    assertThat(trade.path("positionSide").asText()).isEqualTo(expected.positionSide());
    assertThat(trade.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(trade.path("feeAsset").asText()).isEqualTo("USDT");
    assertMoney(trade, "lots", expected.quantity());
    assertMoney(trade, "price", expected.entry());
    assertMoney(trade, "fee", expected.fee());
    assertMoney(trade, "realizedPnl", BigDecimal.ZERO);
  }

  private static void assertPosition(
      JsonNode position,
      ExpectedPosition expected,
      BigDecimal liquidation,
      TradingLabPerpetualHttpScenarioFactory.CrossScenario fixture
  ) {
    assertThat(position.path("symbol").asText()).isEqualTo(expected.rules().symbol());
    assertThat(position.path("productType").asText()).isEqualTo("LINEAR_PERP");
    assertThat(position.path("side").asText()).isEqualTo(expected.side());
    assertThat(position.path("positionMode").asText()).isEqualTo("HEDGE");
    assertThat(position.path("positionSide").asText()).isEqualTo(expected.positionSide());
    assertThat(position.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(position.path("status").asText()).isEqualTo("OPEN");
    assertThat(position.path("leverage").asInt()).isEqualTo(fixture.leverage());
    assertMoney(position, "lots", expected.quantity());
    assertMoney(position, "openPrice", expected.entry());
    assertMoney(position, "markPrice", expected.mark());
    assertMoney(position, "notional", expected.markNotional());
    assertMoney(position, "floatingPnl", expected.unrealized());
    assertMoney(
        position,
        "floatingPnlRatio",
        ratio(expected.unrealized(), expected.margin()));
    assertMoney(position, "realizedPnl", BigDecimal.ZERO);
    assertMoney(position, "fundingPnl", expected.funding());
    assertMoney(position, "marginHeld", expected.margin());
    assertMoney(position, "maintenanceMargin", expected.maintenance());
    assertThat(decimal(position, "maintenanceMarginRate"))
        .isEqualByComparingTo(expected.rules().maintenanceMarginRate());
    assertMoney(position, "liquidationPrice", liquidation);
  }

  private static void assertFundingSettlement(
      JsonNode settlement,
      JsonNode position,
      ExpectedPosition expected,
      BigDecimal fundingRate
  ) {
    assertThat(settlement.path("positionId").asText())
        .isEqualTo(position.path("id").asText());
    assertThat(settlement.path("asset").asText()).isEqualTo("USDT");
    assertThat(settlement.path("positionSide").asText())
        .isEqualTo(expected.positionSide());
    assertThat(settlement.path("marginMode").asText()).isEqualTo("CROSS");
    assertThat(settlement.path("source").asText()).isEqualTo("validation");
    assertThat(settlement.path("ledgerEntryId").asText()).isNotBlank();
    assertThat(decimal(settlement, "fundingRate")).isEqualByComparingTo(fundingRate);
    assertMoney(settlement, "markPrice", expected.mark());
    assertMoney(settlement, "amount", expected.funding());
    assertMoney(settlement, "isolatedMarginAfter", BigDecimal.ZERO);
    assertMoney(settlement, "shortfall", BigDecimal.ZERO);
  }

  private static void assertFundingLedger(
      JsonNode state,
      List<JsonNode> settlements,
      ExpectedPosition bitcoin,
      ExpectedPosition ether
  ) {
    List<JsonNode> entries = stateItems(state, "cashLedger").stream()
        .filter(entry -> "FUNDING_FEE".equals(entry.path("entryType").asText()))
        .toList();
    assertThat(entries).hasSize(2);
    for (JsonNode settlement : settlements) {
      JsonNode entry = item(
          entries,
          candidate -> settlement.path("ledgerEntryId").asText()
              .equals(candidate.path("id").asText()),
          "funding ledger " + settlement.path("ledgerEntryId").asText());
      assertThat(entry.path("referenceType").asText())
          .isEqualTo("FUNDING_SETTLEMENT");
      assertThat(entry.path("referenceId").asText())
          .isEqualTo(settlement.path("id").asText());
      assertThat(entry.path("currency").asText()).isEqualTo("USDT");
      assertMoney(entry, "amount", decimal(settlement, "amount"));
    }
    assertThat(entries.stream()
        .map(entry -> exactMoney(decimal(entry, "amount")))
        .toList()).containsExactlyInAnyOrder(
            exactMoney(bitcoin.funding()),
            exactMoney(ether.funding()));
  }

  private static void assertWalletUnchanged(
      List<JsonNode> wallets,
      BigDecimal initialUsdt
  ) {
    JsonNode wallet = item(
        wallets,
        candidate -> "USDT".equals(candidate.path("asset").asText()),
        "shared USDT wallet");
    assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
    assertMoney(wallet, "total", initialUsdt);
    assertMoney(wallet, "available", initialUsdt);
    assertMoney(wallet, "locked", BigDecimal.ZERO);
  }

  private static JsonNode itemForSymbol(List<JsonNode> items, String symbol) {
    List<JsonNode> matches = items.stream()
        .filter(candidate -> symbol.equals(candidate.path("symbol").asText()))
        .toList();
    assertThat(matches).as(symbol).singleElement();
    return matches.getFirst();
  }

  private static JsonNode orderForTrade(List<JsonNode> orders, JsonNode trade) {
    String orderId = trade.path("orderId").asText();
    assertThat(orderId).isNotBlank();
    return item(
        orders,
        candidate -> orderId.equals(candidate.path("id").asText()),
        "order " + orderId);
  }

  private static void assertRequiredCrossHttpHops(JsonNode report) {
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
            "BTC leverage and margin configuration",
            value -> value.contains("/api/accounts/")
                && value.contains(
                    "/symbols/" + TradingLabPerpetualHttpScenarioFactory.BTC_PERPETUAL + "/")
                && value.endsWith("/settings")),
        new RequiredHop(
            "ETH leverage and margin configuration",
            value -> value.contains("/api/accounts/")
                && value.contains(
                    "/symbols/" + TradingLabPerpetualHttpScenarioFactory.ETH_PERPETUAL + "/")
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
            "public funding settlements API",
            value -> value.contains("/api/trading/funding/settlements")),
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
        .as("missing real Cross Perpetual HTTP hop categories; captured URLs=%s", urls)
        .isEmpty();
  }

  private static BigDecimal crossLiquidation(
      BigDecimal accountBalance,
      ExpectedPosition target,
      ExpectedPosition other
  ) {
    BigDecimal fixedCrossEquity = accountBalance.add(other.unrealized());
    BigDecimal fixedThreshold = other.maintenance().add(other.closeFee());
    BigDecimal targetEntryNotional =
        target.quantity().abs().multiply(target.entry());
    BigDecimal equityAtZero;
    BigDecimal equitySlope;
    if ("BUY".equals(target.side())) {
      equityAtZero = fixedCrossEquity.subtract(targetEntryNotional);
      equitySlope = target.quantity().abs();
    } else {
      equityAtZero = fixedCrossEquity.add(targetEntryNotional);
      equitySlope = target.quantity().abs().negate();
    }
    BigDecimal thresholdSlope = target.quantity().abs().multiply(
        target.rules().maintenanceMarginRate().add(target.closeFeeRate()));
    BigDecimal gapAtZero = equityAtZero.subtract(fixedThreshold);
    BigDecimal gapSlope = equitySlope.subtract(thresholdSlope);
    if (gapSlope.signum() > 0) {
      return nonNegativePrice(gapAtZero.negate().divide(
          gapSlope,
          INTERNAL_SCALE,
          RoundingMode.HALF_UP));
    }
    return nonNegativePrice(gapAtZero.divide(
        gapSlope.negate(),
        INTERNAL_SCALE,
        RoundingMode.HALF_UP));
  }

  private static BigDecimal nonNegativePrice(BigDecimal price) {
    return price.max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
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
    return money(quantity.abs().multiply(entry))
        .divide(BigDecimal.valueOf(leverage), MONEY_SCALE, RoundingMode.HALF_UP);
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

  private static BigDecimal funding(
      String side,
      BigDecimal quantity,
      BigDecimal mark,
      BigDecimal fundingRate
  ) {
    BigDecimal unsigned = quantity.abs().multiply(mark).multiply(fundingRate);
    return money("BUY".equals(side) ? unsigned.negate() : unsigned);
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

  private record ExpectedPosition(
      TradingLabPerpetualHttpScenarioFactory.InstrumentRules rules,
      String side,
      String positionSide,
      BigDecimal quantity,
      BigDecimal entry,
      BigDecimal mark,
      BigDecimal fee,
      BigDecimal unrealized,
      BigDecimal margin,
      BigDecimal maintenance,
      BigDecimal closeFee,
      BigDecimal closeFeeRate,
      BigDecimal markNotional,
      BigDecimal funding
  ) {
  }

  private record RequiredHop(String name, Predicate<String> path) {
  }
}
