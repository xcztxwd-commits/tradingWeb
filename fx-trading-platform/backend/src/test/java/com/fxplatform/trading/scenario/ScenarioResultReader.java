package com.fxplatform.trading.scenario;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.AccountState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.EventState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.OrderState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.PositionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.ProtectionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.TradeState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.WalletState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScenarioResultReader {

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);

  private static final String ORDERS_SQL = """
      SELECT id, client_order_id, side, order_type, status,
             COALESCE(base_quantity, quantity, lots, 0) AS logical_quantity,
             COALESCE(filled_quantity, 0) AS filled_quantity,
             COALESCE(remaining_quantity, 0) AS remaining_quantity,
             avg_fill_price, COALESCE(fee, 0) AS fee, fee_asset, liquidity_role,
             reduce_only, order_origin, parent_order_id, parent_position_id,
             contingency_group_id, hold_currency,
             COALESCE(hold_amount, 0) AS hold_amount,
             hold_owner_order_id, reject_code, protection_type, trigger_price,
             trigger_price_type, trigger_execution_type, price, position_side, created_at
      FROM trading.orders
      WHERE account_id = ?
      ORDER BY created_at, id
      """;

  private static final String TRADES_SQL = """
      SELECT t.id, t.order_id, t.side, t.lots, t.price, t.realized_pnl,
             COALESCE(t.fee, 0) AS fee, t.fee_asset, t.product_type,
             t.position_side, t.margin_mode, t.liquidity_role,
             o.client_order_id, o.order_origin, o.parent_position_id
      FROM trading.trades t
      JOIN trading.orders o ON o.id = t.order_id
      WHERE t.account_id = ?
      ORDER BY t.executed_at, t.id
      """;

  private static final String SPOT_POSITIONS_SQL = """
      SELECT id, asset, quantity, average_cost, realized_pnl, unrealized_pnl
      FROM trading.spot_positions
      WHERE account_id = ? AND wallet_type = 'SPOT'
      ORDER BY asset, cost_asset, id
      """;

  private static final String PERP_POSITIONS_SQL = """
      SELECT DISTINCT ON (symbol, position_side)
             id, symbol, status, side, position_mode, position_side, margin_mode,
             lots, open_price, COALESCE(mark_price, current_price, open_price) AS mark_price,
             realized_pnl, floating_pnl, funding_pnl, margin_held,
             initial_margin, maintenance_margin, notional, leverage
      FROM trading.positions
      WHERE account_id = ? AND product_type = 'LINEAR_PERP'
      ORDER BY symbol, position_side, opened_at DESC, id DESC
      """;

  private static final String WALLETS_SQL = """
      SELECT id, wallet_type, asset, total, available, locked
      FROM core.wallet_balances
      WHERE account_id = ? AND wallet_type = 'SPOT'
      ORDER BY wallet_type, asset
      """;

  private static final String ACCOUNT_SQL = """
      SELECT a.balance, a.equity, a.used_margin, a.free_margin,
             COALESCE((
               SELECT SUM(p.maintenance_margin)
               FROM trading.positions p
               WHERE p.account_id = a.id AND p.status = 'OPEN'
             ), 0) AS maintenance_margin,
             COALESCE((
               SELECT SUM(le.amount)
               FROM ledger.ledger_entries le
               WHERE le.account_id = a.id
                 AND le.entry_type = 'BANKRUPTCY_SHORTFALL'
             ), 0) AS bankruptcy_shortfall
      FROM core.trading_accounts a
      WHERE a.id = ?
      """;

  private static final String LEDGER_SQL = """
      SELECT id, entry_type AS type, asset, amount, balance_after,
             reference_type, reference_id, operation_type, sequence_no
      FROM ledger.asset_ledger_entries
      WHERE account_id = ? AND operation_type <> 'DEMO_INIT'
      UNION ALL
      SELECT id, entry_type AS type, currency AS asset, amount, balance_after,
             reference_type, reference_id, operation_type, sequence_no
      FROM ledger.ledger_entries
      WHERE account_id = ? AND operation_type <> 'DEMO_INIT'
      ORDER BY sequence_no, id
      """;

  private static final String EVENTS_SQL = """
      SELECT e.id, e.order_id, e.event_type, e.from_status, e.to_status,
             e.reason_code, e.created_at
      FROM trading.order_events e
      JOIN trading.orders o ON o.id = e.order_id
      WHERE o.account_id = ?
      ORDER BY e.created_at, e.id
      """;

  private final JdbcTemplate jdbcTemplate;

  public ScenarioResultReader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public ActualScenarioResult read(ScenarioContext context) {
    Objects.requireNonNull(context, "context");
    List<ScenarioAction> actions = context.scenario().actions();
    if (actions.isEmpty()) {
      return new ActualScenarioResult(context.scenario().caseId(), List.of());
    }
    ScenarioAction action = actions.getLast();
    return new ActualScenarioResult(
        context.scenario().caseId(),
        List.of(new Checkpoint(
            actions.size(),
            action.parameters().actionId(),
            readSnapshot(context),
            null)));
  }

  public Snapshot readSnapshot(ScenarioContext context) {
    Objects.requireNonNull(context, "context");
    List<OrderRow> rawOrders = readOrderRows(context.accountId());
    registerOrderReferences(context, rawOrders);
    List<PositionState> positions = readPositions(context);
    List<OrderState> orders = rawOrders.stream()
        .map(row -> orderState(context, row))
        .sorted(Comparator.comparing(OrderState::ref))
        .toList();
    List<TradeState> trades = readTrades(context);
    AccountRow persistedAccount = readAccount(context.accountId());
    List<WalletState> wallets = readWallets(context, positions, orders, persistedAccount);
    AccountState account = accountState(persistedAccount);
    List<LedgerState> ledger = readLedger(context);
    List<ProtectionState> protections = readProtections(context, rawOrders);
    List<EventState> events = readEvents(context, rawOrders);
    return new Snapshot(
        orders,
        trades,
        positions,
        wallets,
        account,
        ledger,
        protections,
        events);
  }

  private List<OrderRow> readOrderRows(UUID accountId) {
    return jdbcTemplate.query(ORDERS_SQL, (rs, rowNum) -> new OrderRow(
        uuid(rs, "id"),
        text(rs.getString("client_order_id")),
        OrderSide.valueOf(rs.getString("side")),
        OrderType.valueOf(rs.getString("order_type")),
        rs.getString("status"),
        decimal(rs, "logical_quantity"),
        decimal(rs, "filled_quantity"),
        decimal(rs, "remaining_quantity"),
        rs.getBigDecimal("avg_fill_price"),
        decimal(rs, "fee"),
        text(rs.getString("fee_asset")),
        enumValue(LiquidityRole.class, rs.getString("liquidity_role")),
        rs.getBoolean("reduce_only"),
        text(rs.getString("order_origin")),
        uuidOrNull(rs, "parent_order_id"),
        uuidOrNull(rs, "parent_position_id"),
        uuidOrNull(rs, "contingency_group_id"),
        text(rs.getString("hold_currency")),
        decimal(rs, "hold_amount"),
        uuidOrNull(rs, "hold_owner_order_id"),
        text(rs.getString("reject_code")),
        enumValue(ProtectionType.class, rs.getString("protection_type")),
        rs.getBigDecimal("trigger_price"),
        enumValue(TriggerPriceType.class, rs.getString("trigger_price_type")),
        enumValue(TriggerExecutionType.class, rs.getString("trigger_execution_type")),
        rs.getBigDecimal("price"),
        enumValue(PositionSide.class, rs.getString("position_side"))), accountId);
  }

  private void registerOrderReferences(ScenarioContext context, List<OrderRow> rows) {
    for (OrderRow row : rows) {
      if (context.logicalRefs(row.id()).isEmpty()) {
        context.putRef(inferredOrderRef(row), row.id());
      }
      if (row.protectionType() != null) {
        String protection = context.scenario().actions().stream()
            .map(ScenarioAction::parameters)
            .filter(parameters -> parameters.orderId().equals(orderRef(context, row)))
            .map(ScenarioAction.Parameters::protectionId)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse("");
        if (!protection.isBlank()) {
          context.putRef(protection, row.id());
        }
      }
    }
  }

  private OrderState orderState(ScenarioContext context, OrderRow row) {
    String parentRef;
    if (row.parentOrderId() != null) {
      parentRef = logicalRef(context, row.parentOrderId());
    } else if (row.parentPositionId() != null) {
      parentRef = positionRef(context, row.parentPositionId());
    } else {
      parentRef = "";
    }
    String contingencyRef = logicalRef(context, row.contingencyGroupId());
    return new OrderState(
        orderRef(context, row),
        row.side(),
        row.type(),
        row.status(),
        row.quantity(),
        row.filledQuantity(),
        row.remainingQuantity(),
        row.averageFillPrice(),
        row.fee(),
        row.feeAsset(),
        row.liquidityRole(),
        row.reduceOnly(),
        normalizedOrigin(context, row),
        parentRef,
        contingencyRef,
        row.holdAsset(),
        row.holdAmount(),
        logicalRef(context, row.holdOwnerOrderId()),
        row.rejectCode());
  }

  private List<TradeState> readTrades(ScenarioContext context) {
    return jdbcTemplate.query(TRADES_SQL, (rs, rowNum) -> {
      UUID tradeId = uuid(rs, "id");
      UUID orderId = uuid(rs, "order_id");
      String orderRef = logicalRef(context, orderId);
      String tradeRef = orderRef + "-trade-1";
      context.putRef(tradeRef, tradeId);
      BigDecimal quantity = decimal(rs, "lots");
      BigDecimal price = decimal(rs, "price");
      return new TradeState(
          tradeRef,
          orderRef,
          OrderSide.valueOf(rs.getString("side")),
          quantity,
          price,
          money(quantity.multiply(price)),
          decimal(rs, "fee"),
          text(rs.getString("fee_asset")),
          decimal(rs, "realized_pnl"),
          ProductType.valueOf(rs.getString("product_type")),
          PositionSide.valueOf(rs.getString("position_side")),
          MarginMode.valueOf(rs.getString("margin_mode")),
          LiquidityRole.valueOf(rs.getString("liquidity_role")),
          normalizedTradeKey(
              orderRef,
              ProductType.valueOf(rs.getString("product_type")),
              text(rs.getString("order_origin")),
              uuidOrNull(rs, "parent_position_id"),
              text(rs.getString("client_order_id"))));
    }, context.accountId());
  }

  private static String normalizedTradeKey(
      String orderRef,
      ProductType productType,
      String origin,
      UUID parentPositionId,
      String clientOrderId
  ) {
    if (productType == ProductType.LINEAR_PERP
        && (!"USER".equals(origin) || parentPositionId != null)) {
      return orderRef;
    }
    return clientOrderId;
  }

  private List<PositionState> readPositions(ScenarioContext context) {
    return context.scenario().productType() == ProductType.CRYPTO_SPOT
        ? readSpotPositions(context)
        : readPerpetualPositions(context);
  }

  private List<PositionState> readSpotPositions(ScenarioContext context) {
    List<PositionState> positions = jdbcTemplate.query(
        SPOT_POSITIONS_SQL,
        (rs, rowNum) -> {
          UUID id = uuid(rs, "id");
          String asset = rs.getString("asset");
          context.putRef(asset, id);
          BigDecimal quantity = decimal(rs, "quantity");
          BigDecimal averageCost = decimal(rs, "average_cost");
          BigDecimal mark = money(context.currentPriceStep().last());
          return new PositionState(
              asset,
              quantity.signum() == 0 ? "CLOSED" : "OPEN",
              OrderSide.BUY,
              PositionMode.ONE_WAY,
              PositionSide.BOTH,
              MarginMode.CASH,
              quantity,
              averageCost,
              mark,
              decimal(rs, "realized_pnl"),
              spotUnrealized(mark, averageCost, quantity),
              ZERO,
              ZERO,
              ZERO,
              ZERO,
              money(quantity.multiply(mark)),
              1);
        },
        context.accountId());
    if (!positions.isEmpty()) {
      return positions;
    }
    BigDecimal mark = money(context.currentPriceStep().last());
    return List.of(new PositionState(
        context.baseAsset(),
        "CLOSED",
        OrderSide.BUY,
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        MarginMode.CASH,
        ZERO,
        ZERO,
        mark,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        1));
  }

  private List<PositionState> readPerpetualPositions(ScenarioContext context) {
    return jdbcTemplate.query(PERP_POSITIONS_SQL, (rs, rowNum) -> {
      UUID id = uuid(rs, "id");
      String symbol = rs.getString("symbol");
      PositionSide slot = PositionSide.valueOf(rs.getString("position_side"));
      String slotRef = symbol + ":" + slot;
      context.rebindRef(slotRef, id);
      context.rebindRef("position:" + slot, id);
      boolean closed = "CLOSED".equals(rs.getString("status"));
      return new PositionState(
          slotRef,
          closed ? "CLOSED" : "OPEN",
          OrderSide.valueOf(rs.getString("side")),
          PositionMode.valueOf(rs.getString("position_mode")),
          slot,
          MarginMode.valueOf(rs.getString("margin_mode")),
          closed ? ZERO : decimal(rs, "lots"),
          closed ? ZERO : decimal(rs, "open_price"),
          closed ? ZERO : decimal(rs, "mark_price"),
          decimal(rs, "realized_pnl"),
          closed ? ZERO : decimal(rs, "floating_pnl"),
          decimal(rs, "funding_pnl"),
          closed ? ZERO : decimal(rs, "margin_held"),
          closed ? ZERO : decimal(rs, "initial_margin"),
          closed ? ZERO : decimal(rs, "maintenance_margin"),
          closed ? ZERO : decimal(rs, "notional"),
          rs.getInt("leverage"));
    }, context.accountId()).stream()
        .sorted(Comparator.comparing(PositionState::slot))
        .toList();
  }

  private List<WalletState> readWallets(
      ScenarioContext context,
      List<PositionState> positions,
      List<OrderState> orders,
      AccountRow account
  ) {
    if (context.scenario().productType() == ProductType.CRYPTO_SPOT) {
      return jdbcTemplate.query(WALLETS_SQL, (rs, rowNum) -> {
        UUID id = uuid(rs, "id");
        String walletType = rs.getString("wallet_type");
        String asset = rs.getString("asset");
        context.putRef("wallet:" + walletType + ":" + asset, id);
        return new WalletState(
            walletType,
            asset,
            decimal(rs, "total"),
            decimal(rs, "available"),
            decimal(rs, "locked"));
      }, context.accountId());
    }
    BigDecimal usedMargin = positions.stream()
        .map(PositionState::marginHeld)
        .reduce(ZERO, BigDecimal::add);
    BigDecimal pendingHolds = orders.stream()
        .filter(order -> "PENDING".equals(order.status()))
        .map(OrderState::holdAmount)
        .reduce(ZERO, BigDecimal::add);
    BigDecimal locked = money(usedMargin.add(pendingHolds));
    BigDecimal available = money(account.balance().subtract(locked).max(BigDecimal.ZERO));
    return List.of(new WalletState(
        "PERP",
        context.quoteAsset(),
        account.balance(),
        available,
        locked));
  }

  private AccountRow readAccount(UUID accountId) {
    return jdbcTemplate.queryForObject(ACCOUNT_SQL, (rs, rowNum) -> new AccountRow(
        decimal(rs, "balance"),
        decimal(rs, "equity"),
        decimal(rs, "used_margin"),
        decimal(rs, "free_margin"),
        decimal(rs, "maintenance_margin"),
        decimal(rs, "bankruptcy_shortfall")), accountId);
  }

  private AccountState accountState(AccountRow persisted) {
    return new AccountState(
        persisted.balance(),
        persisted.equity(),
        persisted.usedMargin(),
        persisted.freeMargin(),
        persisted.maintenanceMargin(),
        persisted.bankruptcyShortfall());
  }

  private List<LedgerState> readLedger(ScenarioContext context) {
    return jdbcTemplate.query(LEDGER_SQL, (rs, rowNum) -> {
      String referenceType = text(rs.getString("reference_type"));
      UUID referenceId = uuidOrNull(rs, "reference_id");
      String reference = logicalRef(context, referenceId);
      return new LedgerState(
          rowNum + 1,
          rs.getString("type"),
          rs.getString("asset"),
          decimal(rs, "amount"),
          decimal(rs, "balance_after"),
          referenceType,
          reference,
          rs.getString("operation_type"));
    }, context.accountId(), context.accountId());
  }

  private List<ProtectionState> readProtections(
      ScenarioContext context,
      List<OrderRow> orders
  ) {
    List<ProtectionState> protections = new ArrayList<>();
    for (OrderRow row : orders) {
      if (row.protectionType() == null) {
        continue;
      }
      int sequence = protections.size() + 1;
      protections.add(new ProtectionState(
          protectionRef(context, row),
          row.protectionType(),
          protectionStatus(row.status()),
          protectionPositionRef(context, row),
          protectionSlot(context, row),
          row.quantity(),
          money(row.triggerPrice()),
          row.triggerPriceType() == null
              ? TriggerPriceType.MARK_PRICE
              : row.triggerPriceType(),
          row.triggerExecutionType() == null
              ? row.type() == OrderType.LIMIT
                  ? TriggerExecutionType.LIMIT
                  : TriggerExecutionType.MARKET
              : row.triggerExecutionType(),
          row.limitPrice() == null ? null : money(row.limitPrice()),
          sequence));
    }
    return List.copyOf(protections);
  }

  private List<EventState> readEvents(
      ScenarioContext context,
      List<OrderRow> orders
  ) {
    return jdbcTemplate.query(EVENTS_SQL, (rs, rowNum) -> {
      UUID orderId = uuid(rs, "order_id");
      String eventType = rs.getString("event_type");
      OrderRow order = orders.stream()
          .filter(candidate -> candidate.id().equals(orderId))
          .findFirst()
          .orElse(null);
      String subject = order != null
          && order.protectionType() != null
          && eventType.startsWith("PROTECTION_")
          ? protectionRef(context, order)
          : logicalRef(context, orderId);
      return new EventState(
          rowNum + 1,
          eventType,
          subject,
          text(rs.getString("from_status")),
          text(rs.getString("to_status")),
          text(rs.getString("reason_code")));
    }, context.accountId());
  }

  private static String orderRef(ScenarioContext context, OrderRow row) {
    String inferred = inferredOrderRef(row);
    return context.logicalRefs(row.id()).stream()
        .filter(value -> value.equals(inferred))
        .findFirst()
        .orElseGet(() -> context.logicalRefs(row.id()).stream()
            .filter(value -> !value.contains("-protection-"))
            .filter(value -> !value.startsWith("position:"))
            .findFirst()
            .orElseGet(() -> context.logicalRefs(row.id()).stream()
                .findFirst()
                .orElse(inferred)));
  }

  private static String inferredOrderRef(OrderRow row) {
    if (!row.clientOrderId().isBlank() && row.clientOrderId().contains("-client-")) {
      return row.clientOrderId().replace("-client-", "-order-");
    }
    return row.id().toString();
  }

  private static String protectionRef(ScenarioContext context, OrderRow row) {
    return context.logicalRefs(row.id()).stream()
        .filter(value -> value.contains("-protection-"))
        .findFirst()
        .orElseGet(() -> context.scenario().actions().stream()
            .map(ScenarioAction::parameters)
            .filter(parameters -> parameters.orderId().equals(orderRef(context, row)))
            .map(ScenarioAction.Parameters::protectionId)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(orderRef(context, row)));
  }

  private static String protectionPositionRef(ScenarioContext context, OrderRow row) {
    if (row.parentPositionId() != null) {
      String ref = positionRef(context, row.parentPositionId());
      if (!ref.equals(row.parentPositionId().toString())) {
        return ref;
      }
    }
    return context.symbol() + ":" + protectionSlot(context, row);
  }

  private static PositionSide protectionSlot(ScenarioContext context, OrderRow row) {
    if (context.scenario().positionMode() == PositionMode.ONE_WAY) {
      return PositionSide.BOTH;
    }
    return row.positionSide() == null
        ? context.scenario().positionSide()
        : row.positionSide();
  }

  private static String protectionStatus(String orderStatus) {
    return switch (orderStatus) {
      case "PENDING", "WORKING", "PENDING_ACTIVATION" -> "ACTIVE";
      default -> orderStatus;
    };
  }

  private static String normalizedOrigin(ScenarioContext context, OrderRow row) {
    return row.origin();
  }

  private static String positionRef(ScenarioContext context, UUID id) {
    if (id == null) {
      return "";
    }
    return context.logicalRefs(id).stream()
        .filter(value -> value.startsWith(context.symbol() + ":"))
        .findFirst()
        .orElse(id.toString());
  }

  private static String logicalRef(ScenarioContext context, UUID id) {
    if (id == null) {
      return "";
    }
    return context.findLogicalRef(id).orElse(id.toString());
  }

  private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
    BigDecimal value = rs.getBigDecimal(column);
    return value == null ? ZERO : money(value);
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private static UUID uuidOrNull(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? ZERO : value.setScale(8, RoundingMode.HALF_UP);
  }

  static BigDecimal spotUnrealized(
      BigDecimal mark,
      BigDecimal averageCost,
      BigDecimal quantity
  ) {
    return money(mark.subtract(averageCost).multiply(quantity));
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
    return value == null || value.isBlank()
        ? null
        : Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
  }

  private record OrderRow(
      UUID id,
      String clientOrderId,
      OrderSide side,
      OrderType type,
      String status,
      BigDecimal quantity,
      BigDecimal filledQuantity,
      BigDecimal remainingQuantity,
      BigDecimal averageFillPrice,
      BigDecimal fee,
      String feeAsset,
      LiquidityRole liquidityRole,
      boolean reduceOnly,
      String origin,
      UUID parentOrderId,
      UUID parentPositionId,
      UUID contingencyGroupId,
      String holdAsset,
      BigDecimal holdAmount,
      UUID holdOwnerOrderId,
      String rejectCode,
      ProtectionType protectionType,
      BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      TriggerExecutionType triggerExecutionType,
      BigDecimal limitPrice,
      PositionSide positionSide
  ) {
  }

  private record AccountRow(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      BigDecimal maintenanceMargin,
      BigDecimal bankruptcyShortfall
  ) {
  }
}
