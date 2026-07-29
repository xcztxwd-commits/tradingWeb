package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ScenarioResultReaderContractTest {

  @Test
  void readerSqlCoversEveryPersistedSnapshotSourceAndRequiredField() throws Exception {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java"));

    assertThat(source).contains(
        "trading.orders",
        "trading.trades",
        "trading.positions",
        "trading.spot_positions",
        "core.wallet_balances",
        "core.trading_accounts",
        "ledger.ledger_entries",
        "ledger.asset_ledger_entries",
        "trading.order_events");
    assertThat(source).contains(
        "filled_quantity",
        "remaining_quantity",
        "avg_fill_price",
        "fee_asset",
        "liquidity_role",
        "reduce_only",
        "order_origin",
        "parent_order_id",
        "parent_position_id",
        "contingency_group_id",
        "hold_owner_order_id",
        "reject_code",
        "realized_pnl",
        "funding_pnl",
        "margin_held",
        "initial_margin",
        "maintenance_margin",
        "operation_type",
        "protection_type",
        "trigger_price_type",
        "trigger_execution_type",
        "event_type",
        "reason_code",
        "return row.origin()",
        "orderRef + \"-trade-1\"",
        "entry_type = 'BANKRUPTCY_SHORTFALL'",
        "String reference = logicalRef(context, referenceId)");
    assertThat(source).doesNotContain(
        "modificationActions",
        "modificationReferences",
        "\"ORDER_MODIFICATION\".equals(referenceType)");
  }

  @Test
  void readerUsesTheSharedLedgerSequenceToBreakTiedTimestamps() throws Exception {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java"));

    assertThat(source).contains(
        "operation_type, sequence_no",
        "ORDER BY sequence_no");
    assertThat(source).doesNotContain(
        "ledger_order",
        "WHEN 'ORDER_HOLD' THEN 10");
  }

  @Test
  void readerExposesSnapshotReadsForExecutorCheckpointsAndThePlanInterface()
      throws Exception {
    assertThat(ScenarioResultReader.class.getDeclaredMethod(
        "readSnapshot", ScenarioContext.class)).isNotNull();
    assertThat(ScenarioResultReader.class.getDeclaredMethod(
        "read", ScenarioContext.class)).isNotNull();
  }

  @Test
  void readerRebindsOnlyTheCurrentPerpetualPositionSlots() throws Exception {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java"));

    assertThat(source).contains(
        "context.rebindRef(slotRef, id)",
        "context.rebindRef(\"position:\" + slot, id)");
    assertThat(source).doesNotContain(
        "context.putRef(slotRef, id)",
        "context.putRef(\"position:\" + slot, id)");
  }

  @Test
  void readerKeepsOriginalProtectionQuantityAfterTheCarrierOrderFills() throws Exception {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java"));

    assertThat(source).contains(
        "protectionSlot(context, row),",
        "row.quantity(),");
    assertThat(source).doesNotContain(
        "protectionSlot(context, row),\n          row.remainingQuantity(),");
  }

  @Test
  void readerUsesProtectionReferencesOnlyForProtectionEvents() throws Exception {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java"));

    assertThat(source).contains(
        "String eventType = rs.getString(\"event_type\")",
        "eventType.startsWith(\"PROTECTION_\")",
        "eventType,");
  }

  @Test
  void spotAndPerpetualAccountSnapshotsUseThePersistedCoreSummary() throws Exception {
    String source = Files.readString(Path.of(
        "src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java"));

    assertThat(source).contains(
        "persisted.balance()",
        "persisted.equity()",
        "persisted.usedMargin()",
        "persisted.freeMargin()");
    assertThat(source).doesNotContain("quote.add(base.multiply(mark))");
  }

  @Test
  void spotUnrealizedPnlUsesTheCurrentControlledMarkInsteadOfThePersistedValue() {
    assertThat(ScenarioResultReader.spotUnrealized(
        new BigDecimal("110.00000000"),
        new BigDecimal("100.00000000"),
        new BigDecimal("2.00000000")))
        .isEqualByComparingTo("20.00000000");
  }
}
