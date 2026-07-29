package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationSpotReplayIT {

  @Test
  void marketBuyMutatesOrdersTradesWalletAndAssetLedgerThroughPublicHttp() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();

    var request = ValidationHttpIntegrationSupport.spotReplay(generation);
    RunResult run = http.startAndAwait(request);
    RunResult replay = http.startAndAwait(request);

    List<JsonNode> orders = run.items("orders");
    assertUniqueIds(orders);
    assertThat(orders).hasSize(2).allSatisfy(order -> {
      assertThat(order.path("symbol").asText())
          .isEqualTo(ValidationHttpIntegrationSupport.BTC_SPOT);
      assertThat(order.path("productType").asText()).isEqualTo("CRYPTO_SPOT");
      assertThat(order.path("marginMode").asText()).isEqualTo("CASH");
      assertThat(order.path("status").asText()).isEqualTo("FILLED");
      assertThat(order.path("positionSide").asText()).isEqualTo("BOTH");
      assertThat(order.path("reduceOnly").asBoolean()).isFalse();
      assertThat(ValidationHttpIntegrationSupport.decimalField(order, "remainingQuantity"))
          .isZero();
    });
    JsonNode buyOrder = itemWith(orders, "side", "BUY");
    JsonNode sellOrder = itemWith(orders, "side", "SELL");
    assertThat(buyOrder.path("quantityUnit").asText()).isEqualTo("QUOTE");
    assertThat(ValidationHttpIntegrationSupport.decimalField(buyOrder, "originalQuantity"))
        .isEqualByComparingTo("500.00000000");
    assertThat(ValidationHttpIntegrationSupport.decimalField(buyOrder, "baseQuantity"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(buyOrder, "filledQuantity"));
    assertThat(sellOrder.path("quantityUnit").asText()).isEqualTo("BASE");
    for (String field : List.of(
        "lots", "quantity", "originalQuantity", "baseQuantity", "filledQuantity")) {
      assertThat(ValidationHttpIntegrationSupport.decimalField(sellOrder, field))
          .as("spot SELL " + field)
          .isEqualByComparingTo("0.00500000");
    }

    List<JsonNode> trades = run.items("trades");
    assertUniqueIds(trades);
    assertThat(trades).hasSize(2).allSatisfy(trade -> {
      assertThat(trade.path("symbol").asText())
          .isEqualTo(ValidationHttpIntegrationSupport.BTC_SPOT);
      assertThat(trade.path("productType").asText()).isEqualTo("CRYPTO_SPOT");
      assertThat(trade.path("positionSide").asText()).isEqualTo("BOTH");
      assertThat(trade.path("marginMode").asText()).isEqualTo("CASH");
      assertThat(ValidationHttpIntegrationSupport.decimalField(trade, "lots")).isPositive();
    });
    JsonNode buyTrade = itemWith(trades, "side", "BUY");
    JsonNode sellTrade = itemWith(trades, "side", "SELL");
    BigDecimal buyBase = ValidationHttpIntegrationSupport.decimalField(buyTrade, "lots");
    BigDecimal sellBase = ValidationHttpIntegrationSupport.decimalField(sellTrade, "lots");
    assertTradeMatchesOrder(buyTrade, buyOrder);
    assertTradeMatchesOrder(sellTrade, sellOrder);
    assertThat(ValidationHttpIntegrationSupport.decimalField(sellTrade, "lots"))
        .isEqualByComparingTo("0.00500000");
    assertThat(run.items("positions")).singleElement().satisfies(position -> {
      assertThat(position.path("symbol").asText())
          .isEqualTo(ValidationHttpIntegrationSupport.BTC_SPOT);
      assertThat(position.path("instrumentType").asText()).isEqualTo("SPOT");
      assertThat(position.path("marginMode").asText()).isEqualTo("CASH");
      assertThat(position.path("positionSide").asText()).isEqualTo("BOTH");
      assertThat(position.path("positionUnit").asText()).isEqualTo("BTC");
      assertThat(ValidationHttpIntegrationSupport.decimalField(position, "lots"))
          .isEqualByComparingTo(money(buyBase.subtract(sellBase)));
    });

    JsonNode bitcoin = itemWith(run.items("walletBalances"), "asset", "BTC");
    JsonNode tether = itemWith(run.items("walletBalances"), "asset", "USDT");
    BigDecimal buyGross = money(buyBase.multiply(
        ValidationHttpIntegrationSupport.decimalField(buyTrade, "price")));
    BigDecimal sellGross = money(sellBase.multiply(
        ValidationHttpIntegrationSupport.decimalField(sellTrade, "price")));
    BigDecimal buyFee = ValidationHttpIntegrationSupport.decimalField(buyTrade, "fee");
    BigDecimal sellFee = ValidationHttpIntegrationSupport.decimalField(sellTrade, "fee");
    BigDecimal expectedBitcoin = money(buyBase.subtract(sellBase));
    BigDecimal expectedTether = money(new BigDecimal("100000.00000000")
        .subtract(buyGross)
        .subtract(buyFee)
        .add(sellGross)
        .subtract(sellFee));
    assertWallet(bitcoin, expectedBitcoin);
    assertWallet(tether, expectedTether);

    List<JsonNode> assetLedger = run.items("assetLedger");
    assertUniqueIds(assetLedger);
    assertThat(assetLedger).hasSize(8);
    assertThat(assetLedger.stream()
        .map(entry -> entry.path("entryType").asText())
        .toList()).containsExactlyInAnyOrder(
            "DEMO_INIT",
            "VALIDATION_SEED",
            "SPOT_BUY_DEBIT",
            "SPOT_BUY_CREDIT",
            "TRADE_FEE",
            "SPOT_SELL_DEBIT",
            "SPOT_SELL_CREDIT",
            "TRADE_FEE");
    assertThat(assetLedger.stream()
        .map(ValidationSpotReplayIT::businessKey)
        .toList()).doesNotHaveDuplicates();
    JsonNode buyDebit = singleLedger(assetLedger, "SPOT_BUY_DEBIT", buyTrade);
    JsonNode buyCredit = singleLedger(assetLedger, "SPOT_BUY_CREDIT", buyTrade);
    JsonNode buyFeeEntry = singleLedger(assetLedger, "TRADE_FEE", buyTrade);
    JsonNode sellDebit = singleLedger(assetLedger, "SPOT_SELL_DEBIT", sellTrade);
    JsonNode sellCredit = singleLedger(assetLedger, "SPOT_SELL_CREDIT", sellTrade);
    JsonNode sellFeeEntry = singleLedger(assetLedger, "TRADE_FEE", sellTrade);
    assertAmount(buyDebit, buyGross.negate());
    assertAmount(buyCredit, buyBase);
    assertAmount(buyFeeEntry, buyFee.negate());
    assertAmount(sellDebit, sellBase.negate());
    assertAmount(sellCredit, sellGross);
    assertAmount(sellFeeEntry, sellFee.negate());
    assertThat(ValidationHttpIntegrationSupport.decimalField(buyCredit, "balanceAfter"))
        .isEqualByComparingTo(buyBase);
    assertThat(ValidationHttpIntegrationSupport.decimalField(sellDebit, "balanceAfter"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(bitcoin, "available"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(sellFeeEntry, "balanceAfter"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(tether, "available"));

    List<JsonNode> visibleLedger = run.items("cashLedger");
    assertUniqueIds(visibleLedger);
    assertThat(visibleLedger).hasSize(10);
    Set<String> assetLedgerIds = new HashSet<>(
        assetLedger.stream().map(entry -> entry.path("id").asText()).toList());
    assertThat(visibleLedger.stream()
        .filter(entry -> assetLedgerIds.contains(entry.path("id").asText()))
        .toList()).hasSameSizeAs(assetLedger);
    List<JsonNode> cashOnly = visibleLedger.stream()
        .filter(entry -> !assetLedgerIds.contains(entry.path("id").asText()))
        .toList();
    assertThat(cashOnly).hasSize(2);
    assertThat(cashOnly.stream().map(entry -> entry.path("entryType").asText()).toList())
        .containsExactlyInAnyOrder("DEMO_INIT", "VALIDATION_SEED");

    JsonNode summary = run.finalBusinessState().path("summary");
    assertThat(summary.path("id").asText()).isNotBlank();
    assertThat(summary.path("accountType").asText()).isEqualTo("DEMO");
    assertThat(summary.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "balance"))
        .isEqualByComparingTo("100000.00000000");
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "equity"))
        .isEqualByComparingTo("100000.00000000");
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "usedMargin")).isZero();
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "freeMargin"))
        .isEqualByComparingTo("100000.00000000");
    assertThat(run.events("CHECKPOINT")).hasSize(2);
    assertThat(run.apiTraces("PUBLIC_ACTION")).hasSize(2).allSatisfy(trace -> {
      assertThat(trace.path("payload").path("outcome").asText()).isEqualTo("SUCCEEDED");
      assertThat(trace.path("payload").path("status").asInt()).isEqualTo(200);
    });

    assertThat(replay.allEvents()).isEqualTo(run.allEvents());
    assertThat(replay.finalBusinessState()).isEqualTo(run.finalBusinessState());
    assertThat(replay.items("orders").stream().map(item -> item.path("id").asText()).toList())
        .containsExactlyInAnyOrderElementsOf(
            run.items("orders").stream().map(item -> item.path("id").asText()).toList());
    assertThat(replay.items("trades").stream().map(item -> item.path("id").asText()).toList())
        .containsExactlyInAnyOrderElementsOf(
            run.items("trades").stream().map(item -> item.path("id").asText()).toList());
  }

  private static void assertTradeMatchesOrder(JsonNode trade, JsonNode order) {
    assertThat(trade.path("orderId").asText()).isEqualTo(order.path("id").asText());
    assertThat(trade.path("side").asText()).isEqualTo(order.path("side").asText());
    assertThat(ValidationHttpIntegrationSupport.decimalField(trade, "lots"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(order, "filledQuantity"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(trade, "fee"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(order, "fee"));
  }

  private static JsonNode singleLedger(
      List<JsonNode> entries,
      String entryType,
      JsonNode trade
  ) {
    List<JsonNode> matches = entries.stream()
        .filter(entry -> entryType.equals(entry.path("entryType").asText()))
        .filter(entry -> "TRADE".equals(entry.path("referenceType").asText()))
        .filter(entry -> trade.path("id").asText().equals(entry.path("referenceId").asText()))
        .toList();
    assertThat(matches).as(entryType + " for trade " + trade.path("id").asText())
        .singleElement();
    return matches.getFirst();
  }

  private static void assertWallet(JsonNode wallet, BigDecimal expected) {
    assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
    assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "total"))
        .isEqualByComparingTo(expected);
    assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "available"))
        .isEqualByComparingTo(expected);
    assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "locked")).isZero();
  }

  private static void assertAmount(JsonNode entry, BigDecimal expected) {
    assertThat(ValidationHttpIntegrationSupport.decimalField(entry, "amount"))
        .isEqualByComparingTo(expected);
  }

  private static void assertUniqueIds(List<JsonNode> items) {
    List<String> ids = items.stream().map(item -> item.path("id").asText()).toList();
    assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
  }

  private static String businessKey(JsonNode entry) {
    return entry.path("entryType").asText()
        + "|" + entry.path("referenceType").asText()
        + "|" + entry.path("referenceId").asText()
        + "|" + entry.path("asset").asText();
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(8, RoundingMode.HALF_UP);
  }

  private static JsonNode itemWith(
      List<JsonNode> items,
      String field,
      String expected
  ) {
    return items.stream()
        .filter(item -> expected.equals(item.path(field).asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Missing state item with " + field + "=" + expected));
  }
}
