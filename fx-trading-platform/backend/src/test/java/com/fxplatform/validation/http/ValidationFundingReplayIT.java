package com.fxplatform.validation.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.validation.http.ValidationHttpIntegrationSupport.RunResult;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-fixed-runtime")
class ValidationFundingReplayIT {

  @Test
  void tickFundingAuthorityIsPersistedBeforeActionAndSettledAfterTheRealFill() {
    ValidationHttpIntegrationSupport http = new ValidationHttpIntegrationSupport();
    long generation = http.reset().generation();
    var request = ValidationHttpIntegrationSupport.fundingReplay(generation);

    RunResult run = http.startAndAwait(request);
    RunResult replay = http.startAndAwait(request);

    List<JsonNode> positions = run.items("positions");
    assertUniqueIds(positions);
    assertThat(positions).singleElement().satisfies(item -> {
      assertThat(item.path("symbol").asText())
          .isEqualTo(ValidationHttpIntegrationSupport.BTC_PERPETUAL);
      assertThat(item.path("side").asText()).isEqualTo("BUY");
      assertThat(item.path("positionSide").asText()).isEqualTo("LONG");
      assertThat(item.path("marginMode").asText()).isEqualTo("CROSS");
      assertThat(ValidationHttpIntegrationSupport.decimalField(item, "lots"))
          .isEqualByComparingTo("0.01000000");
    });
    JsonNode position = positions.getFirst();
    List<JsonNode> orders = run.items("orders");
    assertUniqueIds(orders);
    assertThat(orders).singleElement();
    JsonNode order = orders.getFirst();
    List<JsonNode> trades = run.items("trades");
    assertUniqueIds(trades);
    assertThat(trades).singleElement();
    JsonNode trade = trades.getFirst();

    List<JsonNode> settlements = run.items("fundingSettlements");
    assertUniqueIds(settlements);
    assertThat(settlements).singleElement().satisfies(item -> {
      assertThat(item.path("symbol").asText())
          .isEqualTo(ValidationHttpIntegrationSupport.BTC_PERPETUAL);
      assertThat(ValidationHttpIntegrationSupport.decimalField(item, "fundingRate"))
          .isEqualByComparingTo(ValidationHttpIntegrationSupport.FUNDING_RATE);
      assertThat(ValidationHttpIntegrationSupport.decimalField(item, "markPrice"))
          .isEqualByComparingTo("50000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(item, "amount"))
          .isEqualByComparingTo("-0.50000000");
      assertThat(item.path("asset").asText()).isEqualTo("USDT");
      assertThat(item.path("positionSide").asText()).isEqualTo("LONG");
      assertThat(item.path("marginMode").asText()).isEqualTo("CROSS");
      assertThat(item.path("source").asText()).isEqualTo("validation");
      assertThat(item.path("ledgerEntryId").asText()).isNotBlank();
      assertThat(ValidationHttpIntegrationSupport.decimalField(item, "isolatedMarginAfter"))
          .isZero();
      assertThat(ValidationHttpIntegrationSupport.decimalField(item, "shortfall")).isZero();
    });
    JsonNode settlement = settlements.getFirst();
    assertThat(settlement.path("positionId").asText()).isEqualTo(position.path("id").asText());

    List<JsonNode> assetLedger = run.items("assetLedger");
    assertUniqueIds(assetLedger);
    assertThat(assetLedger).hasSize(2).allSatisfy(entry -> {
      assertThat(entry.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(entry.path("asset").asText()).isEqualTo("USDT");
    });
    assertThat(assetLedger.stream().map(entry -> entry.path("entryType").asText()).toList())
        .containsExactlyInAnyOrder("DEMO_INIT", "VALIDATION_SEED");
    List<JsonNode> visibleLedger = run.items("cashLedger");
    assertUniqueIds(visibleLedger);
    assertThat(visibleLedger).hasSize(9);
    Set<String> assetIds = new HashSet<>(
        assetLedger.stream().map(entry -> entry.path("id").asText()).toList());
    List<JsonNode> cashOnly = visibleLedger.stream()
        .filter(entry -> !assetIds.contains(entry.path("id").asText()))
        .toList();
    assertUniqueIds(cashOnly);
    assertThat(cashOnly).hasSize(7);
    assertThat(cashOnly.stream()
        .map(entry -> entry.path("entryType").asText())
        .toList()).containsExactlyInAnyOrder(
            "DEMO_INIT",
            "VALIDATION_SEED",
            "ORDER_HOLD",
            "ORDER_RELEASE",
            "MARGIN_HOLD",
            "TRADE_FEE",
            "FUNDING_FEE");
    singleEntry(cashOnly, "DEMO_INIT", "DEMO_ACCOUNT", order.path("accountId").asText());
    singleEntry(cashOnly, "VALIDATION_SEED", "VALIDATION_SEED", null);
    singleEntry(cashOnly, "ORDER_HOLD", "ORDER", order.path("id").asText());
    singleEntry(cashOnly, "ORDER_RELEASE", "ORDER", order.path("id").asText());
    singleEntry(cashOnly, "MARGIN_HOLD", "POSITION", position.path("id").asText());
    singleEntry(cashOnly, "TRADE_FEE", "TRADE", trade.path("id").asText());
    List<JsonNode> fundingEntries = cashOnly.stream()
        .filter(entry -> "FUNDING_FEE".equals(entry.path("entryType").asText()))
        .toList();
    assertThat(fundingEntries).singleElement();
    JsonNode fundingLedger = fundingEntries.getFirst();
    assertThat(fundingLedger.path("id").asText())
        .isEqualTo(settlement.path("ledgerEntryId").asText());
    assertThat(fundingLedger.path("referenceType").asText())
        .isEqualTo("FUNDING_SETTLEMENT");
    assertThat(fundingLedger.path("referenceId").asText())
        .isEqualTo(settlement.path("id").asText());
    assertThat(fundingLedger.path("currency").asText()).isEqualTo("USDT");

    JsonNode summary = run.finalBusinessState().path("summary");
    assertThat(ValidationHttpIntegrationSupport.decimalField(fundingLedger, "amount"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(settlement, "amount"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(fundingLedger, "balanceAfter"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(settlement, "balanceAfter"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "balance"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(settlement, "balanceAfter"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(position, "fundingPnl"))
        .isEqualByComparingTo(
            ValidationHttpIntegrationSupport.decimalField(settlement, "amount"));

    List<JsonNode> wallets = run.items("walletBalances");
    assertThat(wallets).singleElement().satisfies(wallet -> {
      assertThat(wallet.path("walletType").asText()).isEqualTo("SPOT");
      assertThat(wallet.path("asset").asText()).isEqualTo("USDT");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "total"))
          .isEqualByComparingTo("100000.00000000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "available"))
          .isEqualByComparingTo("100000.00000000");
      assertThat(ValidationHttpIntegrationSupport.decimalField(wallet, "locked")).isZero();
    });
    assertThat(assetLedger.stream()
        .map(entry -> entry.path("entryType").asText())
        .toList()).doesNotContain("FUNDING_FEE");

    List<JsonNode> feeEntries = cashOnly.stream()
        .filter(entry -> "TRADE_FEE".equals(entry.path("entryType").asText()))
        .toList();
    assertThat(feeEntries).singleElement();
    BigDecimal expectedBalance = new BigDecimal("100000.00000000")
        .add(ValidationHttpIntegrationSupport.decimalField(
            feeEntries.getFirst(), "amount"))
        .add(ValidationHttpIntegrationSupport.decimalField(fundingLedger, "amount"));
    assertThat(ValidationHttpIntegrationSupport.decimalField(summary, "balance"))
        .isEqualByComparingTo(expectedBalance);

    List<JsonNode> systemSteps = run.apiTraces("SYSTEM_STEP");
    assertThat(systemSteps).hasSize(2).allSatisfy(trace -> {
      assertThat(trace.path("payload").path("outcome").asText()).isEqualTo("SUCCEEDED");
      assertThat(trace.path("payload").path("status").asInt()).isEqualTo(200);
    });
    List<JsonNode> publicActions = run.apiTraces("PUBLIC_ACTION");
    assertThat(publicActions).singleElement().satisfies(trace -> {
      assertThat(trace.path("payload").path("outcome").asText()).isEqualTo("SUCCEEDED");
      assertThat(trace.path("payload").path("status").asInt()).isEqualTo(200);
    });
    assertThat(systemSteps.getFirst().path("sequence").asLong())
        .isLessThan(publicActions.getFirst().path("sequence").asLong());
    assertThat(publicActions.getFirst().path("sequence").asLong())
        .isLessThan(systemSteps.getLast().path("sequence").asLong());

    assertThat(replay.allEvents()).isEqualTo(run.allEvents());
    assertThat(replay.finalBusinessState()).isEqualTo(run.finalBusinessState());
    assertThat(replay.items("fundingSettlements")).singleElement();
    assertThat(replay.items("fundingSettlements").getFirst().path("id").asText())
        .isEqualTo(settlement.path("id").asText());
    assertThat(replay.items("walletBalances")).isEqualTo(run.items("walletBalances"));
    assertThat(replay.items("assetLedger")).isEqualTo(run.items("assetLedger"));
    assertThat(replay.items("cashLedger")).isEqualTo(run.items("cashLedger"));
    assertThat(replay.finalBusinessState().path("summary")).isEqualTo(summary);
  }

  private static JsonNode singleEntry(
      List<JsonNode> entries,
      String entryType,
      String referenceType,
      String referenceId
  ) {
    List<JsonNode> matches = entries.stream()
        .filter(entry -> entryType.equals(entry.path("entryType").asText()))
        .filter(entry -> referenceType.equals(entry.path("referenceType").asText()))
        .filter(entry -> referenceId == null
            || referenceId.equals(entry.path("referenceId").asText()))
        .toList();
    assertThat(matches).as(entryType + " " + referenceType).singleElement();
    return matches.getFirst();
  }

  private static void assertUniqueIds(List<JsonNode> items) {
    List<String> ids = items.stream().map(item -> item.path("id").asText()).toList();
    assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
  }
}
