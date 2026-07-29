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

class TradingLabSpotHttpIT extends TradingLabHttpIntegrationSupport {

  @Test
  void spotRunUsesMainAdminAndValidationBusinessHttpWithExactAccountingEvidence() {
    var fixture = TradingLabHttpScenarioFactory.spot(currentConfig());
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
          .as("Spot run failureCode=%s", terminal.failureCode())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      RawReport report = downloadAndValidate(runId);
      assertAll(
          () -> assertRequiredRealHttpHops(report.root()),
          () -> assertExactSpotAccounting(report.root(), fixture),
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

  private static void assertRequiredRealHttpHops(JsonNode report) {
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
            "public order API",
            value -> value.contains("/api/trading/orders")),
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
        .as("missing real HTTP hop categories; captured URLs=%s", urls)
        .isEmpty();
  }

  private static void assertExactSpotAccounting(
      JsonNode report,
      TradingLabHttpScenarioFactory.SpotScenario fixture
  ) {
    JsonNode state = report.path("actualState").path("state");
    assertThat(state.isObject()).isTrue();
    JsonNode order = item(
        stateItems(state, "orders"),
        candidate ->
            fixture.symbol().equals(candidate.path("symbol").asText())
                && "BUY".equals(candidate.path("side").asText()),
        "Spot BUY order");
    assertThat(order.path("productType").asText()).isEqualTo("CRYPTO_SPOT");
    assertThat(order.path("quantityUnit").asText()).isEqualTo("QUOTE");
    assertThat(decimal(order, "originalQuantity"))
        .isEqualByComparingTo(fixture.quoteBudget());
    assertThat(order.path("status").asText()).isEqualTo("FILLED");

    JsonNode trade = item(
        stateItems(state, "trades"),
        candidate -> order.path("id").asText().equals(candidate.path("orderId").asText()),
        "trade for the Spot BUY order");
    BigDecimal fillPrice = decimal(trade, "price");
    BigDecimal filledBase = decimal(trade, "lots");
    BigDecimal gross = money(fillPrice.multiply(filledBase));
    BigDecimal independentlyDerivedFee =
        money(gross.multiply(fixture.takerFeeRate()));
    assertThat(exactMoney(decimal(trade, "fee")))
        .as("exact USDT taker fee derived from fill price, base quantity, and frozen fee rate")
        .isEqualTo(exactMoney(independentlyDerivedFee));

    JsonNode position = item(
        stateItems(state, "positions"),
        candidate -> fixture.symbol().equals(candidate.path("symbol").asText()),
        "Spot position");
    assertThat(position.path("instrumentType").asText()).isEqualTo("SPOT");
    assertThat(position.path("positionUnit").asText()).isEqualTo(fixture.baseAsset());
    assertThat(decimal(position, "lots")).isEqualByComparingTo(filledBase);
    BigDecimal expectedAverageCost =
        gross.divide(filledBase, 8, RoundingMode.HALF_UP);
    assertThat(exactMoney(decimal(position, "openPrice")))
        .as("single-fill Spot average cost")
        .isEqualTo(exactMoney(expectedAverageCost));

    JsonNode feeLedger = item(
        stateItems(state, "assetLedger"),
        candidate ->
            "TRADE_FEE".equals(candidate.path("entryType").asText())
                && trade.path("id").asText().equals(candidate.path("referenceId").asText())
                && fixture.quoteAsset().equals(candidate.path("asset").asText()),
        "USDT TRADE_FEE asset-ledger entry");
    assertThat(exactMoney(decimal(feeLedger, "amount")))
        .isEqualTo(exactMoney(independentlyDerivedFee.negate()));

    JsonNode quoteWallet = item(
        stateItems(state, "walletBalances"),
        candidate -> fixture.quoteAsset().equals(candidate.path("asset").asText()),
        "USDT wallet balance");
    BigDecimal expectedAvailable =
        money(fixture.initialQuoteBalance().subtract(gross).subtract(independentlyDerivedFee));
    assertThat(exactMoney(decimal(quoteWallet, "available")))
        .isEqualTo(exactMoney(expectedAvailable));
    assertThat(exactMoney(decimal(quoteWallet, "total")))
        .isEqualTo(exactMoney(expectedAvailable));
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(8, RoundingMode.HALF_UP);
  }

  private static String exactMoney(BigDecimal value) {
    return money(value).toPlainString();
  }

  private record RequiredHop(String name, Predicate<String> path) {
  }
}
