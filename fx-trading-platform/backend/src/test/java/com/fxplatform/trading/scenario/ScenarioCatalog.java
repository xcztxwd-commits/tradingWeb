package com.fxplatform.trading.scenario;

import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.scenario.ScenarioAction.Parameters;
import com.fxplatform.trading.scenario.ScenarioAction.Type;
import com.fxplatform.trading.scenario.ScenarioPriceStep.MarketField;
import com.fxplatform.trading.scenario.ScenarioPriceStep.PricePath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ScenarioCatalog {

  private static final Instant PRICE_TIME = Instant.parse("2026-07-16T00:00:00Z");
  private static final String PACKAGE = "com.fxplatform.trading.scenario.";
  private static final BigDecimal MAKER_FEE_RATE = decimal("0.0002");
  private static final BigDecimal TAKER_FEE_RATE = decimal("0.0005");
  private static final BigDecimal WORST_FEE_RATE = TAKER_FEE_RATE;
  private static final BigDecimal SLIPPAGE_RATE = decimal("0.0001");
  private static final BigDecimal MAINTENANCE_MARGIN_RATE = decimal("0.005");
  private static final BigDecimal LIQUIDATION_FEE_RATE = decimal("0.005");

  private static final List<PricePath> PERP_CORE_PRICE_PATHS = List.of(
      PricePath.UP_UP_UP,
      PricePath.UP_UP_DOWN,
      PricePath.UP_DOWN_UP,
      PricePath.UP_DOWN_DOWN,
      PricePath.DOWN_UP_UP,
      PricePath.DOWN_UP_DOWN,
      PricePath.DOWN_DOWN_UP,
      PricePath.DOWN_DOWN_DOWN);

  private static final List<List<Type>> PERP_CORE_ACTION_PATHS = List.of(
      List.of(Type.ADD, Type.ADD),
      List.of(Type.ADD, Type.PARTIAL_CLOSE),
      List.of(Type.PARTIAL_CLOSE, Type.ADD),
      List.of(Type.PARTIAL_CLOSE, Type.PARTIAL_CLOSE));

  private static final List<ScenarioDefinition> ALL = buildCatalog();

  private ScenarioCatalog() {
  }

  public static List<ScenarioDefinition> all() {
    return ALL;
  }

  public static Stream<ScenarioDefinition> spot() {
    return ALL.stream()
        .filter(scenario -> scenario.productType() == ProductType.CRYPTO_SPOT);
  }

  public static Stream<ScenarioDefinition> perpetual() {
    return ALL.stream()
        .filter(scenario -> scenario.productType() == ProductType.LINEAR_PERP);
  }

  private static List<ScenarioDefinition> buildCatalog() {
    List<ScenarioDefinition> scenarios = new ArrayList<>();
    addSpotCore(scenarios);
    addPerpetualCore(scenarios);
    addSpotSupplemental(scenarios);
    addPerpetualSupplemental(scenarios);
    long uniqueIds = scenarios.stream().map(ScenarioDefinition::caseId).distinct().count();
    if (uniqueIds != scenarios.size()) {
      throw new IllegalStateException("Scenario caseId values must be unique");
    }
    return scenarios.stream()
        .sorted(Comparator.comparing(ScenarioDefinition::caseId))
        .toList();
  }

  private static void addSpotCore(List<ScenarioDefinition> scenarios) {
    List<List<Type>> paths = List.of(
        List.of(Type.BUY, Type.BUY),
        List.of(Type.BUY, Type.PARTIAL_SELL),
        List.of(Type.PARTIAL_SELL, Type.BUY),
        List.of(Type.PARTIAL_SELL, Type.PARTIAL_SELL));
    List<PricePath> prices = List.of(
        PricePath.UP_UP_UP,
        PricePath.UP_UP_DOWN,
        PricePath.UP_DOWN_UP,
        PricePath.UP_DOWN_DOWN);
    for (int index = 0; index < paths.size(); index++) {
      List<Type> actionTypes = paths.get(index);
      String actionPath = actionTypes.stream().map(Enum::name).reduce((left, right) ->
          left + "_" + right).orElseThrow();
      String caseId = "SPOT_CORE_" + actionPath;
      QuantityUnit unit = actionTypes.equals(List.of(Type.BUY, Type.BUY))
          ? QuantityUnit.QUOTE
          : QuantityUnit.BASE;
      Builder builder = spotCase(
          caseId,
          prices.get(index),
          unit,
          coreActions(caseId, actionTypes, PositionSide.BOTH, ProductType.CRYPTO_SPOT, unit)
              .toArray(ScenarioAction[]::new));
      if (actionTypes.getFirst() == Type.PARTIAL_SELL) {
        builder.initialPosition("BTC=2.00000000@100.00000000");
      }
      scenarios.add(builder.build());
    }
  }

  private static void addPerpetualCore(List<ScenarioDefinition> scenarios) {
    for (PricePath pricePath : PERP_CORE_PRICE_PATHS) {
      for (List<Type> actionTypes : PERP_CORE_ACTION_PATHS) {
        String actionPath = actionTypes.stream().map(Enum::name).reduce((left, right) ->
            left + "_" + right).orElseThrow();
        for (PositionSide direction : List.of(PositionSide.LONG, PositionSide.SHORT)) {
          String caseId = "PERP_CORE_" + pricePath + "_" + actionPath + "_" + direction;
          Builder builder = perpCase(
              caseId,
              pricePath,
              QuantityUnit.CONTRACTS,
              coreActions(
                  caseId,
                  actionTypes,
                  direction,
                  ProductType.LINEAR_PERP,
                  QuantityUnit.CONTRACTS).toArray(ScenarioAction[]::new));
          if (actionTypes.getFirst() == Type.PARTIAL_CLOSE) {
            builder.initialPosition(direction + "=3.00000000@100.00000000");
          }
          scenarios.add(builder.build());
        }
      }
    }
  }

  private static List<ScenarioAction> coreActions(
      String caseId,
      List<Type> types,
      PositionSide direction,
      ProductType productType,
      QuantityUnit unit
  ) {
    List<ScenarioAction> actions = new ArrayList<>(types.size() + 1);
    for (int index = 0; index < types.size(); index++) {
      Type type = types.get(index);
      QuantityUnit actionUnit = productType == ProductType.CRYPTO_SPOT
          ? type == Type.BUY ? QuantityUnit.QUOTE : QuantityUnit.BASE
          : unit;
      String quantity = productType == ProductType.CRYPTO_SPOT
          ? type == Type.BUY
              ? (index == 0 ? "100.00000000" : "50.00000000")
              : (index == 0 ? "1.00000000" : "0.50000000")
          : type == Type.ADD
              ? (index == 0 ? "2.00000000" : "1.00000000")
              : "1.00000000";
      boolean reduceOnly = type == Type.PARTIAL_CLOSE || type == Type.FULL_CLOSE;
      actions.add(productType == ProductType.CRYPTO_SPOT
          ? spotOrder(caseId, index + 1, type, direction, quantity, actionUnit, reduceOnly)
          : perpOrder(caseId, index + 1, type, direction, quantity, unit, reduceOnly));
    }
    actions.add(operation(
        caseId,
        types.size() + 1,
        Type.REVALUE,
        direction,
        "FINAL_MARK_REVALUE"));
    return List.copyOf(actions);
  }

  private static void addSpotSupplemental(List<ScenarioDefinition> scenarios) {
    String id = "SPOT_SELL_PROFIT";
    scenarios.add(spotCase(id, PricePath.UP_UP_UP, QuantityUnit.BASE,
        spotOrder(id, 1, Type.BUY, PositionSide.BOTH, "200", QuantityUnit.QUOTE, false),
        spotOrder(id, 2, Type.SELL, PositionSide.BOTH, "1", QuantityUnit.BASE, false)).build());

    id = "SPOT_SELL_LOSS";
    scenarios.add(spotCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.BASE,
        spotOrder(id, 1, Type.BUY, PositionSide.BOTH, "200", QuantityUnit.QUOTE, false),
        spotOrder(id, 2, Type.SELL, PositionSide.BOTH, "1", QuantityUnit.BASE, false)).build());

    id = "SPOT_SELL_GROSS_BREAKEVEN";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotOrder(id, 1, Type.BUY, PositionSide.BOTH, "200", QuantityUnit.QUOTE, false),
        spotOrder(id, 2, Type.SELL, PositionSide.BOTH, "1", QuantityUnit.BASE, false)).build());

    id = "SPOT_ADD_UP";
    scenarios.add(spotCase(id, PricePath.UP_UP_UP, QuantityUnit.QUOTE,
        spotOrder(id, 1, Type.BUY, PositionSide.BOTH, "100", QuantityUnit.QUOTE, false),
        spotOrder(id, 2, Type.BUY, PositionSide.BOTH, "50", QuantityUnit.QUOTE, false)).build());

    id = "SPOT_ADD_DOWN";
    scenarios.add(spotCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.QUOTE,
        spotOrder(id, 1, Type.BUY, PositionSide.BOTH, "100", QuantityUnit.QUOTE, false),
        spotOrder(id, 2, Type.BUY, PositionSide.BOTH, "50", QuantityUnit.QUOTE, false)).build());

    id = "SPOT_MULTI_BUY_SELL";
    scenarios.add(spotCase(id, PricePath.UP_DOWN_UP, QuantityUnit.BASE,
        spotOrder(id, 1, Type.BUY, PositionSide.BOTH, "200", QuantityUnit.QUOTE, false),
        spotOrder(id, 2, Type.BUY, PositionSide.BOTH, "100", QuantityUnit.QUOTE, false),
        spotOrder(id, 3, Type.PARTIAL_SELL, PositionSide.BOTH, "1", QuantityUnit.BASE, false),
        spotOrder(id, 4, Type.SELL, PositionSide.BOTH, "2", QuantityUnit.BASE, false)).build());

    id = "SPOT_FULL_ZERO_REBUY_RESET";
    scenarios.add(spotCase(id, PricePath.UP_DOWN_UP, QuantityUnit.BASE,
        spotOrder(id, 1, Type.SELL, PositionSide.BOTH, "10", QuantityUnit.BASE, false),
        spotOrder(id, 2, Type.BUY, PositionSide.BOTH, "100", QuantityUnit.QUOTE, false))
        .initialPosition("BTC=10.00000000@90.00000000")
        .build());

    id = "SPOT_LIMIT_IMMEDIATE";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotLimit(id, 1, Type.PLACE_ORDER, PositionSide.BOTH, "1", QuantityUnit.BASE, "101"))
        .orderType(OrderType.LIMIT)
        .build());

    id = "SPOT_LIMIT_WAIT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotLimit(id, 1, Type.PLACE_ORDER, PositionSide.BOTH, "1", QuantityUnit.BASE, "95"))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "SPOT_LIMIT_MODIFY";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotLimit(id, 1, Type.PLACE_ORDER, PositionSide.BOTH, "1", QuantityUnit.BASE, "95"),
        modify(id, 2, PositionSide.BOTH, 1, "101", false))
        .orderType(OrderType.LIMIT)
        .build());

    id = "SPOT_LIMIT_CANCEL";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotLimit(id, 1, Type.PLACE_ORDER, PositionSide.BOTH, "1", QuantityUnit.BASE, "95"),
        cancel(id, 2, PositionSide.BOTH, 1))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "SPOT_LIMIT_TRIGGER";
    scenarios.add(spotCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.BASE,
        spotLimit(id, 1, Type.PLACE_ORDER, PositionSide.BOTH, "1", QuantityUnit.BASE, "85"),
        trigger(
            id,
            2,
            PositionSide.BOTH,
            orderId(id, 1),
            TriggerExecutionType.LIMIT,
            TriggerPriceType.LAST_PRICE,
            "LIMIT_BECOMES_MARKETABLE"))
        .orderType(OrderType.LIMIT)
        .build());

    id = "SPOT_STOP_MARKET_PENDING";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotStop(id, 1, PositionSide.BOTH, "1", QuantityUnit.BASE, "110"))
        .orderType(OrderType.STOP_MARKET)
        .expectedTrades("NONE")
        .build());

    id = "SPOT_STOP_MARKET_EXACT";
    scenarios.add(spotCase(id, PricePath.TOUCH_EXACTLY, QuantityUnit.BASE,
        spotStop(id, 1, PositionSide.BOTH, "1", QuantityUnit.BASE, "105"),
        trigger(
            id,
            2,
            PositionSide.BOTH,
            orderId(id, 1),
            TriggerExecutionType.MARKET,
            TriggerPriceType.LAST_PRICE,
            "LAST_TOUCHES_TRIGGER"))
        .priceSteps(customPriceSteps(PricePath.TOUCH_EXACTLY, "100", "104", "105", "105"))
        .orderType(OrderType.STOP_MARKET)
        .build());

    id = "SPOT_STOP_MARKET_CROSS";
    scenarios.add(spotCase(id, PricePath.CROSS_THRESHOLD, QuantityUnit.BASE,
        spotStop(id, 1, PositionSide.BOTH, "1", QuantityUnit.BASE, "105"),
        trigger(
            id,
            2,
            PositionSide.BOTH,
            orderId(id, 1),
            TriggerExecutionType.MARKET,
            TriggerPriceType.LAST_PRICE,
            "LAST_CROSSES_TRIGGER"))
        .orderType(OrderType.STOP_MARKET)
        .build());

    id = "SPOT_STOP_MARKET_GAP";
    scenarios.add(spotCase(id, PricePath.GAP_THROUGH_THRESHOLD, QuantityUnit.BASE,
        spotStop(id, 1, PositionSide.BOTH, "1", QuantityUnit.BASE, "105"),
        trigger(
            id,
            2,
            PositionSide.BOTH,
            orderId(id, 1),
            TriggerExecutionType.MARKET,
            TriggerPriceType.LAST_PRICE,
            "LAST_GAPS_THROUGH_TRIGGER"))
        .orderType(OrderType.STOP_MARKET)
        .build());

    id = "SPOT_OCO_LIMIT_WIN";
    scenarios.add(spotCase(id, PricePath.UP_UP_UP, QuantityUnit.BASE,
        createOco(id, 1, "1", "115", "85"),
        trigger(
            id,
            2,
            PositionSide.BOTH,
            id + "-limit",
            TriggerExecutionType.LIMIT,
            TriggerPriceType.LAST_PRICE,
            "LIMIT_LEG_WINS"))
        .orderType(OrderType.LIMIT)
        .build());

    id = "SPOT_OCO_STOP_WIN";
    scenarios.add(spotCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.BASE,
        createOco(id, 1, "1", "115", "85"),
        trigger(
            id,
            2,
            PositionSide.BOTH,
            id + "-stop",
            TriggerExecutionType.MARKET,
            TriggerPriceType.LAST_PRICE,
            "STOP_LEG_WINS"))
        .orderType(OrderType.LIMIT)
        .build());

    id = "SPOT_OCO_GROUP_CANCEL";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        createOco(id, 1, "1", "110", "90"),
        action(
            Type.CANCEL,
            PositionSide.BOTH,
            null,
            params(id, 2)
                .orderId(id + "-limit")
                .competingOrderId(id + "-stop")
                .condition("CANCEL_ENTIRE_OCO_GROUP"),
            "CANCEL_OCO"))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "SPOT_OCO_DUAL_TRIGGER_RACE";
    scenarios.add(spotCase(id, PricePath.OSCILLATE_AROUND_THRESHOLD, QuantityUnit.BASE,
        createOco(id, 1, "1", "110", "90"),
        race(id, 2, PositionSide.BOTH, id + "-limit", id + "-stop", "OCO_DUAL_TRIGGER"))
        .orderType(OrderType.LIMIT)
        .resilience()
        .build());

    id = "SPOT_INSUFFICIENT_BALANCE";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        spotOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "101",
            QuantityUnit.QUOTE,
            false))
        .initialBalance("USDT", "100.00000000")
        .failure(ErrorCode.INSUFFICIENT_BALANCE, "QUOTE_BUDGET_EXCEEDS_AVAILABLE")
        .build());

    id = "SPOT_OVERSELL";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotOrder(
            id,
            1,
            Type.SELL,
            PositionSide.BOTH,
            "10.00010000",
            QuantityUnit.BASE,
            false))
        .failure(ErrorCode.INSUFFICIENT_BALANCE, "SELL_BASE_EXCEEDS_AVAILABLE")
        .build());

    id = "SPOT_QUANTITY_PRECISION_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "0.000000001",
            QuantityUnit.BASE,
            false))
        .failure(ErrorCode.QUANTITY_STEP_MISMATCH, "QUANTITY_SCALE_EXCEEDS_INSTRUMENT")
        .resilience()
        .build());

    id = "SPOT_PRICE_PRECISION_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "1",
            QuantityUnit.BASE,
            "100.123456789"))
        .orderType(OrderType.LIMIT)
        .failure(ErrorCode.PRICE_TICK_MISMATCH, "PRICE_SCALE_EXCEEDS_INSTRUMENT")
        .resilience()
        .build());

    id = "SPOT_MIN_NOTIONAL_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "0.0001",
            QuantityUnit.BASE,
            "100"))
        .orderType(OrderType.LIMIT)
        .failure(ErrorCode.ORDER_NOTIONAL_TOO_SMALL, "NOTIONAL_BELOW_MINIMUM")
        .resilience()
        .build());

    id = "SPOT_BALANCE_RACE";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        spotBalanceRace(
            id,
            1,
            "6000",
            orderId(id, 1),
            orderId(id, 2),
            "TWO_6000_USDT_ORDERS_COMPETE_FOR_10000"))
        .initialBalance("USDT", "10000.00000000")
        .failure(ErrorCode.INSUFFICIENT_BALANCE, "SECOND_RESERVATION_LOSES_BALANCE_RACE")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "SPOT_CLIENT_ORDER_REPLAY";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        spotOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "100",
            QuantityUnit.QUOTE,
            false),
        replay(id, 2, PositionSide.BOTH, "100", QuantityUnit.QUOTE, false, false))
        .resilience()
        .build());

    id = "SPOT_CLIENT_ORDER_CONFLICT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        spotOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "100",
            QuantityUnit.QUOTE,
            false),
        replay(id, 2, PositionSide.BOTH, "200", QuantityUnit.QUOTE, false, true))
        .failure(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, "CLIENT_ORDER_FINGERPRINT_MISMATCH")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "SPOT_LEGACY_STOP_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        spotOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.BOTH,
            "100",
            QuantityUnit.QUOTE,
            false,
            OrderType.STOP))
        .orderType(OrderType.STOP)
        .failure(ErrorCode.INVALID_SPOT_ORDER_TYPE, "LEGACY_STOP_NOT_PUBLICLY_SUPPORTED")
        .resilience()
        .build());

    id = "SPOT_LEVERAGE_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        changeLeverage(id, 1, PositionSide.BOTH, 10))
        .leverage(10)
        .failure(ErrorCode.INVALID_SPOT_ORDER_FIELDS, "SPOT_LEVERAGE_NOT_SUPPORTED")
        .resilience()
        .build());

    id = "SPOT_REDUCE_ONLY_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        spotOrder(id, 1, Type.SELL, PositionSide.BOTH, "1", QuantityUnit.BASE, true))
        .reduceOnly(true)
        .failure(ErrorCode.INVALID_SPOT_ORDER_FIELDS, "SPOT_REDUCE_ONLY_NOT_SUPPORTED")
        .resilience()
        .build());

    id = "SPOT_PROTECTION_REJECT";
    scenarios.add(spotCase(id, PricePath.FLAT, QuantityUnit.BASE,
        protection(
            id,
            1,
            PositionSide.BOTH,
            "1",
            QuantityUnit.BASE,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.LAST_PRICE,
            "90",
            null))
        .failure(ErrorCode.INVALID_SPOT_ORDER_FIELDS, "PERP_PROTECTION_NOT_SUPPORTED_ON_SPOT")
        .expectedProtections("SPOT_PROTECTION_REQUEST_REJECTED")
        .resilience()
        .build());
  }

  private static void addPerpetualSupplemental(List<ScenarioDefinition> scenarios) {
    String id = "PERP_CLOSE_PROFIT";
    scenarios.add(perpCase(id, PricePath.UP_UP_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.FULL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true)).build());

    id = "PERP_CLOSE_LOSS";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.FULL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true)).build());

    id = "PERP_CLOSE_GROSS_BREAKEVEN";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.FULL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true))
        .priceSteps(customPriceSteps(
            PricePath.FLAT,
            "100",
            "100",
            "101.02010201",
            "101.02010201"))
        .build());

    id = "PERP_SAME_SIDE_ADD";
    scenarios.add(perpCase(id, PricePath.UP_DOWN_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false))
        .build());

    id = "PERP_SAME_SIDE_PARTIAL_CLOSE";
    scenarios.add(perpCase(id, PricePath.UP_UP_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.PARTIAL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true)).build());

    id = "PERP_SAME_SIDE_FULL_CLOSE";
    scenarios.add(perpCase(id, PricePath.UP_UP_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.FULL_CLOSE,
            PositionSide.LONG,
            "2",
            QuantityUnit.CONTRACTS,
            true)).build());

    id = "PERP_LONG_TO_SHORT_REVERSAL";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.REVERSE, PositionSide.SHORT, "2", QuantityUnit.CONTRACTS, false))
        .build());

    id = "PERP_SHORT_TO_LONG_REVERSAL";
    scenarios.add(perpCase(id, PricePath.UP_UP_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.REVERSE, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false))
        .build());

    id = "PERP_REDUCE_ONLY_BELOW";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.PARTIAL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true))
        .reduceOnly(true)
        .build());

    id = "PERP_REDUCE_ONLY_EQUAL";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.PARTIAL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true))
        .reduceOnly(true)
        .build());

    id = "PERP_REDUCE_ONLY_ABOVE_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.PARTIAL_CLOSE,
            PositionSide.LONG,
            "2",
            QuantityUnit.CONTRACTS,
            true))
        .reduceOnly(true)
        .failure(ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION, "CLOSE_QUANTITY_EXCEEDS_OPEN_POSITION")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "PERP_ONE_WAY_NETTING";
    scenarios.add(perpCase(id, PricePath.UP_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.SHORT, "2", QuantityUnit.CONTRACTS, false))
        .positionMode(PositionMode.ONE_WAY)
        .positionSide(PositionSide.BOTH)
        .build());

    id = "PERP_HEDGE_INDEPENDENT";
    scenarios.add(perpCase(id, PricePath.UP_DOWN_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .build());

    id = "PERP_CROSS_MARGIN";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false))
        .marginMode(MarginMode.CROSS)
        .build());

    id = "PERP_ISOLATED_MARGIN";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false))
        .marginMode(MarginMode.ISOLATED)
        .build());

    id = "PERP_LEVERAGE_1X";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 1))
        .leverage(1)
        .build());

    id = "PERP_LEVERAGE_10X";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 10))
        .leverage(10)
        .build());

    id = "PERP_LEVERAGE_MAX";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 100))
        .leverage(100)
        .build());

    id = "PERP_LEVERAGE_OVER_MAX_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 101))
        .leverage(101)
        .failure(ErrorCode.LEVERAGE_OUT_OF_RANGE, "TARGET_LEVERAGE_EXCEEDS_INSTRUMENT_MAX")
        .resilience()
        .build());

    id = "PERP_LEVERAGE_UP";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 20))
        .leverage(20)
        .initialPosition("LONG=1.00000000@100.00000000,MARGIN=10.00000000")
        .build());

    id = "PERP_LEVERAGE_DOWN_SAFE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 5))
        .leverage(5)
        .initialPosition("LONG=1.00000000@100.00000000,MARGIN=10.00000000")
        .build());

    id = "PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        changeLeverage(id, 1, PositionSide.LONG, 1))
        .leverage(1)
        .initialBalance("USDT", "50000.00000000")
        .initialPosition("LONG=1000.00000000@100.00000000,MARGIN=10000.00000000")
        .failure(ErrorCode.INSUFFICIENT_MARGIN, "LOWER_LEVERAGE_REQUIRES_UNAVAILABLE_MARGIN")
        .resilience()
        .build());

    id = "PERP_ISOLATED_MARGIN_ADD";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        adjustMargin(id, 2, PositionSide.LONG, "100"))
        .marginMode(MarginMode.ISOLATED)
        .build());

    id = "PERP_ISOLATED_MARGIN_REDUCE_SAFE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        adjustMargin(id, 2, PositionSide.LONG, "-1"))
        .marginMode(MarginMode.ISOLATED)
        .build());

    id = "PERP_ISOLATED_MARGIN_REDUCE_UNSAFE_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        adjustMargin(id, 2, PositionSide.LONG, "-100000"))
        .marginMode(MarginMode.ISOLATED)
        .failure(ErrorCode.MARGIN_REDUCTION_UNSAFE, "REDUCTION_DROPS_MARGIN_BELOW_MAINTENANCE")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "PERP_ORDER_MARKET";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.PLACE_ORDER, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false))
        .build());

    id = "PERP_LIMIT_IMMEDIATE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "101"))
        .orderType(OrderType.LIMIT)
        .build());

    id = "PERP_LIMIT_WAIT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "95"))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "PERP_LIMIT_MODIFY";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "95"),
        modify(id, 2, PositionSide.LONG, 1, "96", true))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "PERP_LIMIT_CANCEL";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "95"),
        cancel(id, 2, PositionSide.LONG, 1))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "PERP_LIMIT_TRIGGER";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "85"),
        trigger(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 1),
            TriggerExecutionType.LIMIT,
            TriggerPriceType.MARK_PRICE,
            "LIMIT_BECOMES_MARKETABLE"))
        .orderType(OrderType.LIMIT)
        .build());

    id = "PERP_STOP_MARKET_PENDING";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpStop(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "105"))
        .orderType(OrderType.STOP_MARKET)
        .expectedTrades("NONE")
        .build());

    id = "PERP_STOP_MARKET_EXACT";
    scenarios.add(perpCase(id, PricePath.TOUCH_EXACTLY, QuantityUnit.CONTRACTS,
        perpStop(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "105"),
        trigger(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 1),
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "MARK_TOUCHES_TRIGGER"))
        .priceSteps(customPriceSteps(
            PricePath.TOUCH_EXACTLY,
            "100",
            "104",
            "104.9",
            "104.9"))
        .orderType(OrderType.STOP_MARKET)
        .build());

    id = "PERP_STOP_MARKET_CROSS";
    scenarios.add(perpCase(id, PricePath.CROSS_THRESHOLD, QuantityUnit.CONTRACTS,
        perpStop(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "105"),
        trigger(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 1),
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "MARK_CROSSES_TRIGGER"))
        .orderType(OrderType.STOP_MARKET)
        .build());

    id = "PERP_STOP_MARKET_GAP";
    scenarios.add(perpCase(id, PricePath.GAP_THROUGH_THRESHOLD, QuantityUnit.CONTRACTS,
        perpStop(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "105"),
        trigger(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 1),
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "MARK_GAPS_THROUGH_TRIGGER"))
        .orderType(OrderType.STOP_MARKET)
        .build());

    id = "PERP_ATTACHED_TP";
    scenarios.add(perpCase(id, PricePath.UP_UP_UP, QuantityUnit.CONTRACTS,
        perpOrderWithAttachedProtection(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.TAKE_PROFIT,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "120",
            null))
        .expectedProtections("ATTACHED_TP_ACTIVE")
        .build());

    id = "PERP_ATTACHED_SL";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrderWithAttachedProtection(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "80",
            null))
        .expectedProtections("ATTACHED_SL_ACTIVE")
        .build());

    id = "PERP_INDEPENDENT_TP";
    scenarios.add(perpCase(id, PricePath.UP_UP_UP, QuantityUnit.CONTRACTS,
        protection(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.TAKE_PROFIT,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "120",
            null))
        .initialPosition("LONG=1.00000000@100.00000000")
        .expectedProtections("INDEPENDENT_TP_ACTIVE")
        .build());

    id = "PERP_INDEPENDENT_SL";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        protection(
            id,
            1,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "80",
            null))
        .initialPosition("LONG=1.00000000@100.00000000")
        .expectedProtections("INDEPENDENT_SL_ACTIVE")
        .build());

    id = "PERP_TP_MARKET";
    scenarios.add(perpCase(id, PricePath.UP_UP_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.TAKE_PROFIT,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "125",
            null),
        trigger(
            id,
            3,
            PositionSide.LONG,
            orderId(id, 2),
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "TAKE_PROFIT_TRIGGERED"))
        .expectedProtections("TP_MARKET_FILLED")
        .build());

    id = "PERP_TP_LIMIT";
    scenarios.add(perpCase(id, PricePath.UP_UP_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.TAKE_PROFIT,
            TriggerExecutionType.LIMIT,
            TriggerPriceType.MARK_PRICE,
            "125",
            "129"),
        trigger(
            id,
            3,
            PositionSide.LONG,
            orderId(id, 2),
            TriggerExecutionType.LIMIT,
            TriggerPriceType.MARK_PRICE,
            "TAKE_PROFIT_TRIGGERED"))
        .orderType(OrderType.LIMIT)
        .expectedProtections("TP_LIMIT_FILLED")
        .build());

    id = "PERP_SL_MARKET";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "75",
            null),
        trigger(
            id,
            3,
            PositionSide.LONG,
            orderId(id, 2),
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "STOP_LOSS_TRIGGERED"))
        .expectedProtections("SL_MARKET_FILLED")
        .build());

    id = "PERP_SL_LIMIT";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.LIMIT,
            TriggerPriceType.MARK_PRICE,
            "75",
            "69"),
        trigger(
            id,
            3,
            PositionSide.LONG,
            orderId(id, 2),
            TriggerExecutionType.LIMIT,
            TriggerPriceType.MARK_PRICE,
            "STOP_LOSS_TRIGGERED"))
        .orderType(OrderType.LIMIT)
        .expectedProtections("SL_LIMIT_FILLED")
        .build());

    id = "PERP_MULTI_PROTECTION_TRIGGER";
    scenarios.add(perpCase(id, PricePath.UP_DOWN_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.TAKE_PROFIT,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "110",
            null),
        protection(
            id,
            3,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "90",
            null),
        trigger(
            id,
            4,
            PositionSide.LONG,
            orderId(id, 2),
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "ONLY_MATCHED_PROTECTION_TRIGGERS"))
        .expectedProtections("ONE_TRIGGERED,ONE_ACTIVE")
        .build());

    id = "PERP_PROTECTION_RESIZE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "2",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "90",
            null),
        perpOrder(
            id,
            3,
            Type.PARTIAL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true))
        .expectedProtections("RESIZED_FROM_2_TO_1")
        .build());

    id = "PERP_PROTECTION_EXPIRE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "90",
            null),
        perpOrder(
            id,
            3,
            Type.FULL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true))
        .expectedProtections("EXPIRED_AFTER_POSITION_CLOSE")
        .build());

    id = "PERP_PROTECTION_LIMIT_10";
    scenarios.add(perpCase(
        id,
        PricePath.FLAT,
        QuantityUnit.CONTRACTS,
        protectionLimitActions(id, 10).toArray(ScenarioAction[]::new))
        .expectedProtections("10_ACTIVE")
        .build());

    id = "PERP_PROTECTION_LIMIT_11_REJECT";
    scenarios.add(perpCase(
        id,
        PricePath.FLAT,
        QuantityUnit.CONTRACTS,
        protectionLimitActions(id, 11).toArray(ScenarioAction[]::new))
        .failure(ErrorCode.PROTECTION_LIMIT_EXCEEDED, "ELEVENTH_ACTIVE_PROTECTION_EXCEEDS_LIMIT")
        .priorSuccessThenRejected()
        .expectedProtections("10_ACTIVE,11TH_REJECTED")
        .resilience()
        .build());

    id = "PERP_FUNDING_POSITIVE_LONG";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 2, PositionSide.LONG, "0.001", "POSITIVE_RATE_LONG_PAYS"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .build());

    id = "PERP_FUNDING_POSITIVE_SHORT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 2, PositionSide.SHORT, "0.001", "POSITIVE_RATE_SHORT_RECEIVES"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.SHORT)
        .build());

    id = "PERP_FUNDING_NEGATIVE_LONG";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 2, PositionSide.LONG, "-0.001", "NEGATIVE_RATE_LONG_RECEIVES"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .build());

    id = "PERP_FUNDING_NEGATIVE_SHORT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 2, PositionSide.SHORT, "-0.001", "NEGATIVE_RATE_SHORT_PAYS"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.SHORT)
        .build());

    id = "PERP_FUNDING_ZERO_LONG";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 2, PositionSide.LONG, "0", "ZERO_RATE_NO_TRANSFER"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .build());

    id = "PERP_FUNDING_ZERO_SHORT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 2, PositionSide.SHORT, "0", "ZERO_RATE_NO_TRANSFER"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.SHORT)
        .build());

    id = "PERP_FUNDING_ADD_BEFORE_SETTLEMENT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        settleFunding(id, 3, PositionSide.LONG, "0.001", "SETTLE_FINAL_QUANTITY_3"))
        .build());

    id = "PERP_FUNDING_REDUCE_BEFORE_SETTLEMENT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.PARTIAL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true),
        settleFunding(id, 3, PositionSide.LONG, "0.001", "SETTLE_FINAL_QUANTITY_1"))
        .build());

    id = "PERP_FUNDING_FULL_CLOSE_BEFORE_SETTLEMENT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(
            id,
            2,
            Type.FULL_CLOSE,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            true),
        settleFunding(id, 3, PositionSide.LONG, "0.001", "CLOSED_POSITION_OWES_ZERO"))
        .build());

    id = "PERP_FUNDING_CROSS_LIQUIDATION";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "100", QuantityUnit.CONTRACTS, false),
        settleFunding(
            id,
            2,
            PositionSide.LONG,
            "0.2000",
            "FUNDING_DEBIT_BREACHES_CROSS_MAINTENANCE"),
        liquidate(id, 3, PositionSide.LONG, "100.1", "FUNDING_DRIVEN_CROSS_LIQUIDATION"))
        .marginMode(MarginMode.CROSS)
        .initialBalance("USDT", "1015.00000000")
        .expectedEvents("ORDER_FILLED,FUNDING_SETTLED,LIQUIDATION_CHECKED")
        .build());

    id = "PERP_FUNDING_ISOLATED_LIQUIDATION";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "100", QuantityUnit.CONTRACTS, false),
        settleFunding(
            id,
            2,
            PositionSide.LONG,
            "0.2000",
            "FUNDING_DEBIT_BREACHES_ISOLATED_MAINTENANCE"),
        liquidate(id, 3, PositionSide.LONG, "100.1", "FUNDING_DRIVEN_ISOLATED_LIQUIDATION"))
        .marginMode(MarginMode.ISOLATED)
        .initialBalance("USDT", "1015.00000000")
        .expectedEvents("ORDER_FILLED,FUNDING_SETTLED,LIQUIDATION_CHECKED")
        .build());

    id = "PERP_LIQUIDATION_SAFE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        liquidate(id, 2, PositionSide.LONG, "100.1", "SAFE_MARGIN_RATIO_ABOVE_THRESHOLD"))
        .build());

    id = "PERP_LIQUIDATION_EXACT_BOUNDARY";
    scenarios.add(perpCase(id, PricePath.TOUCH_EXACTLY, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "10", QuantityUnit.CONTRACTS, false),
        liquidate(id, 2, PositionSide.SHORT, "105", "MARK_EQUALS_LIQUIDATION_BOUNDARY"))
        .leverage(100)
        .initialBalance("USDT", "61.37195025")
        .priceSteps(customPriceSteps(
            PricePath.TOUCH_EXACTLY,
            "100",
            "100",
            "104.9",
            "104.9"))
        .build());

    id = "PERP_LIQUIDATION_BEYOND_BOUNDARY";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "10", QuantityUnit.CONTRACTS, false),
        liquidate(id, 2, PositionSide.LONG, "80.1", "MARK_BEYOND_LIQUIDATION_BOUNDARY"))
        .initialBalance("USDT", "108.90000000")
        .build());

    id = "PERP_LIQUIDATION_GAP";
    scenarios.add(perpCase(id, PricePath.GAP_THROUGH_THRESHOLD, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "10", QuantityUnit.CONTRACTS, false),
        liquidate(id, 2, PositionSide.SHORT, "112.1", "MARK_GAPS_THROUGH_LIQUIDATION_BOUNDARY"))
        .initialBalance("USDT", "112.50000000")
        .build());

    id = "PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL";
    scenarios.add(perpCase(id, PricePath.GAP_THROUGH_THRESHOLD, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.SHORT, "100", QuantityUnit.CONTRACTS, false),
        liquidate(id, 2, PositionSide.SHORT, "130", "BANKRUPTCY_SHORTFALL_AFTER_GAP"))
        .initialBalance("USDT", "2000.00000000")
        .priceSteps(customPriceSteps(
            PricePath.GAP_THROUGH_THRESHOLD,
            "100",
            "102",
            "129.9",
            "129.9"))
        .expectedLedger("LEDGER_RECONCILED")
        .build());

    id = "PERP_MULTI_POSITION_RECOVERY";
    scenarios.add(perpCase(id, PricePath.DOWN_UP_UP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "10", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.SHORT, "2", QuantityUnit.CONTRACTS, false),
        liquidate(id, 3, PositionSide.SHORT, "110.1", "LIQUIDATE_ONLY_UNSAFE_SLOT"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.SHORT)
        .marginMode(MarginMode.ISOLATED)
        .build());

    id = "PERP_CASCADING_LIQUIDATION";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "100", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.SHORT, "50", QuantityUnit.CONTRACTS, false),
        liquidate(
            id,
            3,
            PositionSide.BOTH,
            "70.1",
            "CROSS_ACCOUNT_BREACH_CLOSES_BOTH_HEDGE_SLOTS_IN_SEQUENCE"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .leverage(100)
        .initialBalance("USDT", "200.00000000")
        .priceSteps(customPriceSteps(
            PricePath.DOWN_DOWN_DOWN,
            "100",
            "100",
            "100",
            "70"))
        .build());

    id = "PERP_CANCEL_ALL";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "95"),
        perpLimit(
            id,
            2,
            Type.PLACE_ORDER,
            PositionSide.SHORT,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "105"),
        operation(id, 3, Type.CANCEL_ALL, PositionSide.BOTH, "CANCEL_ALL_OPEN_ORDERS"))
        .orderType(OrderType.LIMIT)
        .expectedTrades("NONE")
        .build());

    id = "PERP_CANCEL_ALL_PARTIAL_FAILURE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "101"),
        perpLimit(
            id,
            2,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "95"),
        operation(
            id,
            3,
            Type.CANCEL_ALL,
            PositionSide.BOTH,
            "TERMINAL_MEMBER_EXCLUDED_WHILE_ACTIVE_MEMBER_CANCELS"))
        .orderType(OrderType.LIMIT)
        .expectedOrder("FILLED_MEMBER_PRESERVED,ACTIVE_MEMBER_CANCELED")
        .expectedEvents("ORDER_FILLED,ORDER_CANCELED")
        .resilience()
        .build());

    id = "PERP_CLOSE_ALL_SUCCESS";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false),
        operation(id, 3, Type.CLOSE_ALL, PositionSide.BOTH, "CLOSE_ALL_HEDGE_SLOTS"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .build());

    id = "PERP_CLOSE_ALL_PARTIAL_FAILURE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.SHORT, "1", QuantityUnit.CONTRACTS, false),
        operation(id, 3, Type.CLOSE_ALL, PositionSide.BOTH, "ONE_SLOT_VERSION_BECOMES_STALE"))
        .positionMode(PositionMode.HEDGE)
        .positionSide(PositionSide.LONG)
        .expectedOrder("BATCH_PARTIAL_SUCCESS,ONE_ITEM_FAILED")
        .expectedEvents("ORDER_FILLED,BATCH_ITEM_FAILED")
        .resilience()
        .build());

    id = "PERP_ADMIN_FORCE_CLOSE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        operation(
            id,
            2,
            Type.ADMIN_FORCE_CLOSE,
            PositionSide.LONG,
            "AUTHORIZED_ADMIN_CLOSES_DEMO_POSITION"))
        .build());

    id = "PERP_ADMIN_FORCE_CLOSE_PERMISSION_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        operation(
            id,
            2,
            Type.ADMIN_FORCE_CLOSE,
            PositionSide.LONG,
            "NON_ADMIN_ATTEMPTS_FORCE_CLOSE"))
        .failure(ErrorCode.FORBIDDEN, "ADMIN_PERMISSION_REQUIRED")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "PERP_CLOSE_VS_PROTECTION_RACE";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        protection(
            id,
            2,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            ProtectionType.STOP_LOSS,
            TriggerExecutionType.MARKET,
            TriggerPriceType.MARK_PRICE,
            "75",
            null),
        race(
            id,
            3,
            PositionSide.LONG,
            orderId(id, 3),
            orderId(id, 2),
            "USER_CLOSE_COMPETES_WITH_STOP_LOSS"))
        .resilience()
        .build());

    id = "PERP_CLOSE_VS_LIQUIDATION_RACE";
    scenarios.add(perpCase(id, PricePath.DOWN_DOWN_DOWN, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "10", QuantityUnit.CONTRACTS, false),
        race(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 2),
            orderId(id, 3),
            "USER_CLOSE_COMPETES_WITH_LIQUIDATION"))
        .initialBalance("USDT", "108.90000000")
        .resilience()
        .build());

    id = "PERP_CLOSE_VS_BATCH_RACE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        race(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 2),
            orderId(id, 3),
            "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL"))
        .resilience()
        .build());

    id = "PERP_SAME_POSITION_DOUBLE_CLOSE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        race(
            id,
            2,
            PositionSide.LONG,
            orderId(id, 2),
            orderId(id, 3),
            "TWO_FULL_CLOSES_COMPETE_FOR_ONE_POSITION"))
        .failure(ErrorCode.POSITION_NOT_FOUND, "LOSING_CLOSE_OBSERVES_CLOSED_POSITION")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "PERP_STALE_MARKET";
    scenarios.add(perpCase(id, PricePath.STALE_MARKET, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false))
        .failure(ErrorCode.MARKET_DATA_STALE, "EXPIRES_AT_PRECEDES_AS_OF")
        .resilience()
        .build());

    id = "PERP_INCOMPLETE_MARKET";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false))
        .priceSteps(priceSteps(PricePath.FLAT, Set.of(MarketField.MARK)))
        .failure(ErrorCode.MARKET_BUNDLE_INCOMPLETE, "MARK_FIELD_MISSING")
        .resilience()
        .build());

    id = "PERP_PROVIDER_SWITCH_WITH_GAP";
    scenarios.add(perpCase(id, PricePath.PROVIDER_SWITCH_WITH_GAP, QuantityUnit.CONTRACTS,
        perpOrder(id, 1, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false),
        perpOrder(id, 2, Type.ADD, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, false))
        .expectedEvents("MARKET_SOURCE_CHANGED,ORDER_ACCEPTED,ORDER_FILLED")
        .resilience()
        .build());

    id = "PERP_LOCK_WAIT_EXPIRY";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false))
        .failure(ErrorCode.MARKET_DATA_STALE, "LOCK_WAIT_EXCEEDS_MARKET_BUNDLE_TTL")
        .resilience()
        .build());

    id = "PERP_TRADE_ROLLBACK";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false))
        .failure(ErrorCode.EXECUTION_UNAVAILABLE, "INJECT_FAILURE_AFTER_ORDER_BEFORE_TRADE")
        .resilience()
        .build());

    id = "PERP_LEDGER_ROLLBACK";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false))
        .failure(ErrorCode.EXECUTION_UNAVAILABLE, "INJECT_FAILURE_AFTER_TRADE_BEFORE_LEDGER")
        .resilience()
        .build());

    id = "PERP_CLIENT_ORDER_REPLAY";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false),
        replay(id, 2, PositionSide.LONG, "1", QuantityUnit.CONTRACTS, true, false))
        .resilience()
        .build());

    id = "PERP_CLIENT_ORDER_CONFLICT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false),
        replay(id, 2, PositionSide.LONG, "2", QuantityUnit.CONTRACTS, true, true))
        .failure(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, "CLIENT_ORDER_FINGERPRINT_MISMATCH")
        .priorSuccessThenRejected()
        .resilience()
        .build());

    id = "PERP_QUANTITY_BASE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.BASE,
        perpOrder(id, 1, Type.PLACE_ORDER, PositionSide.LONG, "0.01", QuantityUnit.BASE, false))
        .build());

    id = "PERP_QUANTITY_QUOTE";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.QUOTE,
        perpOrder(id, 1, Type.PLACE_ORDER, PositionSide.LONG, "100", QuantityUnit.QUOTE, false))
        .build());

    id = "PERP_QUANTITY_CONTRACTS";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false))
        .build());

    id = "PERP_QUANTITY_STEP_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1.000000001",
            QuantityUnit.CONTRACTS,
            false))
        .failure(
            ErrorCode.CONTRACT_QUANTITY_NOT_INTEGRAL,
            "CONTRACT_QUANTITY_NOT_MULTIPLE_OF_STEP")
        .resilience()
        .build());

    id = "PERP_PRICE_PRECISION_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpLimit(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "100.123456789"))
        .orderType(OrderType.LIMIT)
        .failure(ErrorCode.PRICE_TICK_MISMATCH, "PRICE_SCALE_EXCEEDS_INSTRUMENT")
        .resilience()
        .build());

    id = "PERP_MIN_SIZE_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.BASE,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "0.00000001",
            QuantityUnit.BASE,
            false))
        .failure(ErrorCode.QUANTITY_CONVERTS_TO_ZERO, "QUANTITY_BELOW_ONE_EFFECTIVE_STEP")
        .resilience()
        .build());

    id = "PERP_PARTIAL_FILL_COMPAT_REJECT";
    scenarios.add(perpCase(id, PricePath.FLAT, QuantityUnit.CONTRACTS,
        perpOrder(
            id,
            1,
            Type.PLACE_ORDER,
            PositionSide.LONG,
            "1",
            QuantityUnit.CONTRACTS,
            false,
            "PARTIAL_FILL_INPUT"))
        .failure(ErrorCode.PARTIAL_FILL_NOT_SUPPORTED, "PARTIALLY_FILLED_INPUT_IS_COMPAT_ONLY")
        .expectedOrder("PARTIALLY_FILLED compatibility input rejected")
        .resilience()
        .build());
  }

  private static Builder spotCase(
      String caseId,
      PricePath pricePath,
      QuantityUnit quantityUnit,
      ScenarioAction... actions
  ) {
    return new Builder(caseId, ProductType.CRYPTO_SPOT)
        .quantityUnit(quantityUnit)
        .priceSteps(priceSteps(pricePath, Set.of()))
        .actions(List.of(actions));
  }

  private static Builder perpCase(
      String caseId,
      PricePath pricePath,
      QuantityUnit quantityUnit,
      ScenarioAction... actions
  ) {
    return new Builder(caseId, ProductType.LINEAR_PERP)
        .quantityUnit(quantityUnit)
        .priceSteps(priceSteps(pricePath, Set.of()))
        .actions(List.of(actions));
  }

  private static List<ScenarioAction> protectionLimitActions(String caseId, int count) {
    List<ScenarioAction> actions = new ArrayList<>(count + 1);
    actions.add(perpOrder(
        caseId,
        1,
        Type.ADD,
        PositionSide.LONG,
        "20",
        QuantityUnit.CONTRACTS,
        false));
    for (int index = 1; index <= count; index++) {
      boolean takeProfit = index % 2 == 1;
      actions.add(protection(
          caseId,
          index + 1,
          PositionSide.LONG,
          "1",
          QuantityUnit.CONTRACTS,
          takeProfit ? ProtectionType.TAKE_PROFIT : ProtectionType.STOP_LOSS,
          TriggerExecutionType.MARKET,
          TriggerPriceType.MARK_PRICE,
          takeProfit ? Integer.toString(110 + index) : Integer.toString(90 - index),
          null));
    }
    return List.copyOf(actions);
  }

  private static ScenarioAction spotOrder(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean reduceOnly
  ) {
    return spotOrder(
        caseId, step, type, direction, quantity, unit, reduceOnly, OrderType.MARKET);
  }

  private static ScenarioAction spotOrder(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean reduceOnly,
      OrderType orderType
  ) {
    return action(
        type,
        direction,
        quantity,
        params(caseId, step)
            .order(step)
            .pricing(false, unit)
            .side(spotSide(type, unit))
            .orderType(orderType)
            .reduceOnly(reduceOnly),
        type.name());
  }

  private static ScenarioAction spotLimit(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      String price
  ) {
    return action(
        type,
        direction,
        quantity,
        params(caseId, step)
            .order(step)
            .pricing(false, unit)
            .side(OrderSide.BUY)
            .orderType(OrderType.LIMIT)
            .price(price),
        type.name() + "@" + price);
  }

  private static ScenarioAction spotStop(
      String caseId,
      int step,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      String triggerPrice
  ) {
    return action(
        Type.PLACE_ORDER,
        direction,
        quantity,
        params(caseId, step)
            .order(step)
            .pricing(false, unit)
            .side(OrderSide.BUY)
            .orderType(OrderType.STOP_MARKET)
            .trigger(triggerPrice, TriggerExecutionType.MARKET, TriggerPriceType.LAST_PRICE),
        "STOP_MARKET@" + triggerPrice);
  }

  private static ScenarioAction perpOrder(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean reduceOnly
  ) {
    return perpOrder(caseId, step, type, direction, quantity, unit, reduceOnly, "");
  }

  private static ScenarioAction perpOrder(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean reduceOnly,
      String condition
  ) {
    return action(
        type,
        direction,
        quantity,
        params(caseId, step)
            .order(step)
            .pricing(true, unit)
            .side(perpetualSide(type, direction))
            .orderType(OrderType.MARKET)
            .reduceOnly(reduceOnly)
            .condition(condition),
        type.name());
  }

  private static ScenarioAction perpOrderWithAttachedProtection(
      String caseId,
      int step,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      ProtectionType protectionType,
      TriggerExecutionType executionType,
      TriggerPriceType priceType,
      String triggerPrice,
      String limitPrice
  ) {
    ParamBuilder parameters = params(caseId, step)
        .order(step)
        .protectionId(caseId + "-protection-" + step)
        .pricing(true, unit)
        .side(perpetualSide(Type.ADD, direction))
        .orderType(OrderType.MARKET)
        .reduceOnly(false)
        .protectionType(protectionType)
        .trigger(triggerPrice, executionType, priceType)
        .condition("ATTACHED_TO_ENTRY");
    if (limitPrice != null) {
      parameters.price(limitPrice);
    }
    return action(
        Type.ADD,
        direction,
        quantity,
        parameters,
        "ADD_WITH_ATTACHED_" + protectionType);
  }

  private static ScenarioAction perpLimit(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean reduceOnly,
      String price
  ) {
    return action(
        type,
        direction,
        quantity,
        params(caseId, step)
            .order(step)
            .pricing(true, unit)
            .side(perpetualSide(type, direction))
            .orderType(OrderType.LIMIT)
            .reduceOnly(reduceOnly)
            .price(price),
        type.name() + "@" + price);
  }

  private static ScenarioAction perpStop(
      String caseId,
      int step,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean reduceOnly,
      String triggerPrice
  ) {
    return action(
        Type.PLACE_ORDER,
        direction,
        quantity,
        params(caseId, step)
            .order(step)
            .pricing(true, unit)
            .side(perpetualSide(Type.PLACE_ORDER, direction))
            .orderType(OrderType.STOP_MARKET)
            .reduceOnly(reduceOnly)
            .trigger(triggerPrice, TriggerExecutionType.MARKET, TriggerPriceType.MARK_PRICE),
        "STOP_MARKET@" + triggerPrice);
  }

  private static ScenarioAction modify(
      String caseId,
      int step,
      PositionSide direction,
      int targetOrderStep,
      String price,
      boolean perpetual
  ) {
    return action(
        Type.MODIFY,
        direction,
        null,
        params(caseId, step)
            .orderId(orderId(caseId, targetOrderStep))
            .price(price)
            .condition(perpetual ? "PERP_ORDER_MODIFICATION" : "SPOT_ORDER_MODIFICATION"),
        "MODIFY@" + price);
  }

  private static ScenarioAction cancel(
      String caseId,
      int step,
      PositionSide direction,
      int targetOrderStep
  ) {
    return action(
        Type.CANCEL,
        direction,
        null,
        params(caseId, step).orderId(orderId(caseId, targetOrderStep)),
        "CANCEL");
  }

  private static ScenarioAction trigger(
      String caseId,
      int step,
      PositionSide direction,
      String targetOrderId,
      TriggerExecutionType executionType,
      TriggerPriceType priceType,
      String condition
  ) {
    return action(
        Type.TRIGGER,
        direction,
        null,
        params(caseId, step)
            .orderId(targetOrderId)
            .triggerExecutionType(executionType)
            .triggerPriceType(priceType)
            .condition(condition),
        "TRIGGER");
  }

  private static ScenarioAction replay(
      String caseId,
      int step,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      boolean perpetual,
      boolean conflicting
  ) {
    return action(
        Type.REPLAY,
        direction,
        quantity,
        params(caseId, step)
            .clientOrderId(clientOrderId(caseId, 1))
            .orderId(orderId(caseId, 1))
            .pricing(perpetual, unit)
            .side(perpetual
                ? perpetualSide(Type.PLACE_ORDER, direction)
                : unit == QuantityUnit.QUOTE ? OrderSide.BUY : OrderSide.SELL)
            .orderType(OrderType.MARKET)
            .condition(conflicting ? "FINGERPRINT_MISMATCH" : "EXACT_REPLAY"),
        conflicting ? "CONFLICTING_REPLAY" : "EXACT_REPLAY");
  }

  private static ScenarioAction createOco(
      String caseId,
      int step,
      String quantity,
      String limitPrice,
      String stopPrice
  ) {
    return action(
        Type.CREATE_OCO,
        PositionSide.BOTH,
        quantity,
        params(caseId, step)
            .clientOrderId(clientOrderId(caseId, step))
            .orderId(caseId + "-limit")
            .competingOrderId(caseId + "-stop")
            .pricing(false, QuantityUnit.BASE)
            .side(OrderSide.SELL)
            .orderType(OrderType.LIMIT)
            .price(limitPrice)
            .trigger(stopPrice, TriggerExecutionType.MARKET, TriggerPriceType.LAST_PRICE)
            .condition("ONE_SHARED_HOLD"),
        "OCO");
  }

  private static OrderSide spotSide(Type type, QuantityUnit unit) {
    return switch (type) {
      case BUY -> OrderSide.BUY;
      case SELL, PARTIAL_SELL -> OrderSide.SELL;
      default -> unit == QuantityUnit.QUOTE ? OrderSide.BUY : OrderSide.SELL;
    };
  }

  private static OrderSide perpetualSide(Type type, PositionSide direction) {
    return switch (type) {
      case PARTIAL_CLOSE, FULL_CLOSE ->
          direction == PositionSide.SHORT ? OrderSide.BUY : OrderSide.SELL;
      default -> direction == PositionSide.SHORT ? OrderSide.SELL : OrderSide.BUY;
    };
  }

  private static ScenarioAction race(
      String caseId,
      int step,
      PositionSide direction,
      String firstOrderId,
      String secondOrderId,
      String condition
  ) {
    return action(
        Type.RACE,
        direction,
        null,
        params(caseId, step)
            .orderId(firstOrderId)
            .competingOrderId(secondOrderId)
            .condition(condition),
        "RACE");
  }

  private static ScenarioAction spotBalanceRace(
      String caseId,
      int step,
      String quoteQuantity,
      String firstOrderId,
      String secondOrderId,
      String condition
  ) {
    return action(
        Type.RACE,
        PositionSide.BOTH,
        quoteQuantity,
        params(caseId, step)
            .clientOrderId(clientOrderId(caseId, step))
            .orderId(firstOrderId)
            .competingOrderId(secondOrderId)
            .pricing(false, QuantityUnit.QUOTE)
            .side(OrderSide.BUY)
            .orderType(OrderType.MARKET)
            .reduceOnly(false)
            .condition(condition),
        "CONCURRENT_BUY_RACE");
  }

  private static ScenarioAction changeLeverage(
      String caseId,
      int step,
      PositionSide direction,
      int leverage
  ) {
    return action(
        Type.CHANGE_LEVERAGE,
        direction,
        null,
        params(caseId, step)
            .leverage(leverage)
            .pricing(true, QuantityUnit.CONTRACTS),
        "LEVERAGE=" + leverage);
  }

  private static ScenarioAction adjustMargin(
      String caseId,
      int step,
      PositionSide direction,
      String delta
  ) {
    return action(
        Type.ADJUST_MARGIN,
        direction,
        null,
        params(caseId, step)
            .marginDelta(delta)
            .pricing(true, QuantityUnit.CONTRACTS),
        "MARGIN_DELTA=" + delta);
  }

  private static ScenarioAction protection(
      String caseId,
      int step,
      PositionSide direction,
      String quantity,
      QuantityUnit unit,
      ProtectionType protectionType,
      TriggerExecutionType executionType,
      TriggerPriceType priceType,
      String triggerPrice,
      String limitPrice
  ) {
    ParamBuilder parameters = params(caseId, step)
        .clientOrderId(clientOrderId(caseId, step))
        .orderId(orderId(caseId, step))
        .protectionId(caseId + "-protection-" + step)
        .pricing(true, unit)
        .protectionType(protectionType)
        .trigger(triggerPrice, executionType, priceType);
    if (limitPrice != null) {
      parameters.price(limitPrice);
    }
    return action(
        Type.SET_PROTECTION,
        direction,
        quantity,
        parameters,
        protectionType + "@" + triggerPrice);
  }

  private static ScenarioAction settleFunding(
      String caseId,
      int step,
      PositionSide direction,
      String fundingRate,
      String condition
  ) {
    return action(
        Type.SETTLE_FUNDING,
        direction,
        null,
        params(caseId, step)
            .fundingRate(fundingRate)
            .pricing(true, QuantityUnit.CONTRACTS)
            .condition(condition),
        "FUNDING=" + fundingRate);
  }

  private static ScenarioAction liquidate(
      String caseId,
      int step,
      PositionSide direction,
      String triggerPrice,
      String condition
  ) {
    return action(
        Type.LIQUIDATE,
        direction,
        null,
        params(caseId, step)
            .orderId(orderId(caseId, step))
            .triggerPrice(triggerPrice)
            .pricing(true, QuantityUnit.CONTRACTS)
            .condition(condition),
        "LIQUIDATION_CHECK");
  }

  private static ScenarioAction operation(
      String caseId,
      int step,
      Type type,
      PositionSide direction,
      String condition
  ) {
    return action(
        type,
        direction,
        null,
        params(caseId, step).condition(condition),
        type.name());
  }

  private static ScenarioAction action(
      Type type,
      PositionSide direction,
      String quantity,
      ParamBuilder parameters,
      String detail
  ) {
    return new ScenarioAction(
        type,
        direction,
        quantity == null ? null : decimal(quantity),
        parameters.build(),
        detail);
  }

  private static ParamBuilder params(String caseId, int step) {
    return new ParamBuilder(caseId + "-action-" + step);
  }

  private static String clientOrderId(String caseId, int step) {
    return caseId + "-client-" + step;
  }

  private static String orderId(String caseId, int step) {
    return caseId + "-order-" + step;
  }

  private static List<ScenarioPriceStep> priceSteps(
      PricePath path,
      Set<MarketField> missingFields
  ) {
    int[] moves = switch (path) {
      case UP_UP_UP -> new int[] {10, 10, 10};
      case UP_UP_DOWN -> new int[] {10, 10, -10};
      case UP_DOWN_UP -> new int[] {10, -10, 10};
      case UP_DOWN_DOWN -> new int[] {10, -10, -10};
      case DOWN_UP_UP -> new int[] {-10, 10, 10};
      case DOWN_UP_DOWN -> new int[] {-10, 10, -10};
      case DOWN_DOWN_UP -> new int[] {-10, -10, 10};
      case DOWN_DOWN_DOWN -> new int[] {-10, -10, -10};
      case FLAT -> new int[] {0, 0, 0};
      case TOUCH_EXACTLY -> new int[] {5, 0, 0};
      case CROSS_THRESHOLD -> new int[] {4, 2, -1};
      case GAP_THROUGH_THRESHOLD -> new int[] {2, 10, -3};
      case OSCILLATE_AROUND_THRESHOLD -> new int[] {5, -10, 10};
      case STALE_MARKET -> new int[] {1, 1, 1};
      case PROVIDER_SWITCH_WITH_GAP -> new int[] {1, 8, -2};
    };
    List<ScenarioPriceStep> steps = new ArrayList<>(4);
    BigDecimal mid = decimal("100.00000000");
    for (int sequence = 0; sequence < 4; sequence++) {
      if (sequence > 0) {
        mid = mid.add(BigDecimal.valueOf(moves[sequence - 1]));
      }
      Instant asOf = PRICE_TIME.plusSeconds(sequence);
      Instant expiresAt = path == PricePath.STALE_MARKET && sequence > 0
          ? asOf.minusSeconds(1)
          : asOf.plusSeconds(5);
      String source = path == PricePath.PROVIDER_SWITCH_WITH_GAP && sequence >= 2
          ? "okx"
          : "binance";
      steps.add(new ScenarioPriceStep(
          path,
          sequence,
          missingFields.contains(MarketField.BID)
              ? null : mid.subtract(decimal("0.50000000")),
          missingFields.contains(MarketField.ASK)
              ? null : mid.add(decimal("0.50000000")),
          missingFields.contains(MarketField.LAST) ? null : mid,
          missingFields.contains(MarketField.MARK)
              ? null : mid.add(decimal("0.10000000")),
          missingFields.contains(MarketField.INDEX)
              ? null : mid.subtract(decimal("0.10000000")),
          missingFields.contains(MarketField.SOURCE) ? null : source,
          missingFields.contains(MarketField.AS_OF) ? null : asOf,
          missingFields.contains(MarketField.EXPIRES_AT) ? null : expiresAt,
          missingFields));
    }
    return List.copyOf(steps);
  }

  private static List<ScenarioPriceStep> customPriceSteps(
      PricePath path,
      String... mids
  ) {
    if (mids.length != 4) {
      throw new IllegalArgumentException("custom price path requires exactly four mids");
    }
    List<ScenarioPriceStep> steps = new ArrayList<>(mids.length);
    for (int sequence = 0; sequence < mids.length; sequence++) {
      BigDecimal mid = decimal(mids[sequence]);
      Instant asOf = PRICE_TIME.plusSeconds(sequence);
      steps.add(new ScenarioPriceStep(
          path,
          sequence,
          mid.subtract(decimal("0.50000000")),
          mid.add(decimal("0.50000000")),
          mid,
          mid.add(decimal("0.10000000")),
          mid.subtract(decimal("0.10000000")),
          "binance",
          asOf,
          asOf.plusSeconds(5),
          Set.of()));
    }
    return List.copyOf(steps);
  }

  private static ScenarioAction withFailure(ScenarioAction action, String failureCondition) {
    Parameters value = action.parameters();
    Parameters failed = new Parameters(
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
        value.condition(),
        failureCondition);
    return new ScenarioAction(
        action.type(),
        action.direction(),
        action.quantity(),
        failed,
        action.detail());
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private static final class Builder {

    private final String caseId;
    private final ProductType productType;
    private PositionMode positionMode = PositionMode.ONE_WAY;
    private PositionSide positionSide = PositionSide.BOTH;
    private MarginMode marginMode;
    private int leverage;
    private OrderType orderType = OrderType.MARKET;
    private QuantityUnit quantityUnit;
    private boolean reduceOnly;
    private Map<String, String> initialBalances;
    private String initialPosition = "NONE";
    private List<String> initialOrders = List.of();
    private List<ScenarioPriceStep> priceSteps = List.of();
    private List<ScenarioAction> actions = List.of();
    private String expectedError = "";
    private String failureCondition = "";
    private String expectedOrder;
    private String expectedTrades;
    private String expectedPosition;
    private String expectedWallet;
    private String expectedAccount;
    private String expectedLedger;
    private String expectedProtections = "NONE";
    private String expectedEvents;
    private boolean resilience;

    private Builder(String caseId, ProductType productType) {
      this.caseId = caseId;
      this.productType = productType;
      boolean spot = productType == ProductType.CRYPTO_SPOT;
      this.marginMode = spot ? MarginMode.CASH : MarginMode.CROSS;
      this.leverage = spot ? 1 : 10;
      this.quantityUnit = spot ? QuantityUnit.QUOTE : QuantityUnit.CONTRACTS;
      LinkedHashMap<String, String> balances = new LinkedHashMap<>();
      balances.put("USDT", "100000.00000000");
      if (spot) {
        balances.put("BTC", "10.00000000");
      }
      this.initialBalances = balances;
    }

    private Builder positionMode(PositionMode value) {
      this.positionMode = value;
      return this;
    }

    private Builder positionSide(PositionSide value) {
      this.positionSide = value;
      return this;
    }

    private Builder marginMode(MarginMode value) {
      this.marginMode = value;
      return this;
    }

    private Builder leverage(int value) {
      this.leverage = value;
      return this;
    }

    private Builder orderType(OrderType value) {
      this.orderType = value;
      return this;
    }

    private Builder quantityUnit(QuantityUnit value) {
      this.quantityUnit = value;
      return this;
    }

    private Builder reduceOnly(boolean value) {
      this.reduceOnly = value;
      return this;
    }

    private Builder initialBalance(String asset, String value) {
      LinkedHashMap<String, String> balances = new LinkedHashMap<>(initialBalances);
      balances.put(asset, value);
      this.initialBalances = balances;
      return this;
    }

    private Builder initialPosition(String value) {
      this.initialPosition = value;
      return this;
    }

    private Builder initialOrders(List<String> value) {
      this.initialOrders = List.copyOf(value);
      return this;
    }

    private Builder priceSteps(List<ScenarioPriceStep> value) {
      this.priceSteps = List.copyOf(value);
      return this;
    }

    private Builder actions(List<ScenarioAction> value) {
      this.actions = List.copyOf(value);
      return this;
    }

    private Builder failure(String error, String condition) {
      this.expectedError = error;
      this.failureCondition = condition;
      return this;
    }

    private Builder expectedOrder(String value) {
      this.expectedOrder = value;
      return this;
    }

    private Builder expectedTrades(String value) {
      this.expectedTrades = value;
      return this;
    }

    private Builder expectedLedger(String value) {
      this.expectedLedger = value;
      return this;
    }

    private Builder expectedProtections(String value) {
      this.expectedProtections = value;
      return this;
    }

    private Builder expectedEvents(String value) {
      this.expectedEvents = value;
      return this;
    }

    private Builder priorSuccessThenRejected() {
      this.expectedOrder = "PRIOR_ORDER_FILLED,FINAL_ACTION_REJECTED";
      this.expectedTrades = "PRIOR_ACTION_TRADE_PRESERVED";
      this.expectedPosition = productType + "_PRIOR_POSITION_PRESERVED";
      this.expectedWallet = "PRIOR_WALLET_MUTATION_PRESERVED";
      this.expectedAccount = "PRIOR_ACCOUNT_MUTATION_PRESERVED";
      this.expectedLedger = "PRIOR_LEDGER_MUTATION_PRESERVED";
      this.expectedEvents = "ORDER_ACCEPTED,ORDER_FILLED,REJECTION_RECORDED";
      return this;
    }

    private Builder resilience() {
      this.resilience = true;
      return this;
    }

    private ScenarioDefinition build() {
      if (priceSteps.isEmpty() || actions.isEmpty()) {
        throw new IllegalStateException(caseId + " must define price steps and actions");
      }
      boolean rejected = !expectedError.isBlank();
      List<ScenarioAction> effectiveActions = actions;
      if (rejected && !failureCondition.isBlank()) {
        List<ScenarioAction> mutable = new ArrayList<>(actions);
        mutable.set(mutable.size() - 1, withFailure(mutable.getLast(), failureCondition));
        effectiveActions = List.copyOf(mutable);
      }
      String order = expectedOrder != null
          ? expectedOrder : rejected ? "REJECTED_WITHOUT_MUTATION" : "FULL_FILL_ONLY";
      String trades = expectedTrades != null
          ? expectedTrades : rejected ? "NONE" : "TRADES_MATCH_ACTION_CHAIN";
      String position = expectedPosition != null
          ? expectedPosition : rejected ? "UNCHANGED" : productType + "_POSITION_RECONCILED";
      String wallet = expectedWallet != null
          ? expectedWallet : rejected ? "UNCHANGED" : "WALLET_RECONCILED";
      String account = expectedAccount != null
          ? expectedAccount : rejected ? "UNCHANGED" : "ACCOUNT_SUMMARY_RECONCILED";
      String ledger = expectedLedger != null
          ? expectedLedger : rejected ? "NONE" : "LEDGER_RECONCILED";
      String events = expectedEvents != null
          ? expectedEvents : rejected ? "REJECTION_RECORDED" : "ORDER_ACCEPTED,ORDER_FILLED";
      return new ScenarioDefinition(
          caseId,
          "P0",
          productType,
          positionMode,
          positionSide,
          marginMode,
          leverage,
          orderType,
          quantityUnit,
          reduceOnly,
          initialBalances,
          initialPosition,
          initialOrders,
          priceSteps,
          effectiveActions,
          rejected ? "REQUEST_REJECTED" : "ACTION_CHAIN_COMPLETED",
          order,
          trades,
          position,
          wallet,
          account,
          ledger,
          expectedProtections,
          events,
          expectedError,
          PACKAGE + (resilience
              ? "ScenarioResilienceIT"
              : productType == ProductType.CRYPTO_SPOT
                  ? "SpotScenarioMatrixIT"
                  : "PerpetualScenarioMatrixIT"),
          "executesScenario",
          "NOT_RUN");
    }
  }

  private static final class ParamBuilder {

    private final String actionId;
    private String clientOrderId = "";
    private String orderId = "";
    private String competingOrderId = "";
    private String protectionId = "";
    private BigDecimal price;
    private BigDecimal triggerPrice;
    private Integer leverage;
    private BigDecimal marginDelta;
    private BigDecimal fundingRate;
    private BigDecimal feeRate;
    private BigDecimal makerFeeRate;
    private BigDecimal takerFeeRate;
    private BigDecimal worstFeeRate;
    private BigDecimal slippageRate;
    private BigDecimal maintenanceMarginRate;
    private BigDecimal liquidationFeeRate;
    private QuantityUnit quantityUnit;
    private Boolean reduceOnly;
    private OrderSide side;
    private OrderType orderType;
    private ProtectionType protectionType;
    private TriggerExecutionType triggerExecutionType;
    private TriggerPriceType triggerPriceType;
    private String condition = "";
    private String failureCondition = "";

    private ParamBuilder(String actionId) {
      this.actionId = actionId;
    }

    private ParamBuilder order(int step) {
      int effectiveStep = step;
      this.clientOrderId = actionId.substring(0, actionId.lastIndexOf("-action-"))
          + "-client-" + effectiveStep;
      this.orderId = actionId.substring(0, actionId.lastIndexOf("-action-"))
          + "-order-" + effectiveStep;
      return this;
    }

    private ParamBuilder clientOrderId(String value) {
      this.clientOrderId = value;
      return this;
    }

    private ParamBuilder orderId(String value) {
      this.orderId = value;
      return this;
    }

    private ParamBuilder competingOrderId(String value) {
      this.competingOrderId = value;
      return this;
    }

    private ParamBuilder protectionId(String value) {
      this.protectionId = value;
      return this;
    }

    private ParamBuilder price(String value) {
      this.price = decimal(value);
      return this;
    }

    private ParamBuilder triggerPrice(String value) {
      this.triggerPrice = decimal(value);
      return this;
    }

    private ParamBuilder leverage(int value) {
      this.leverage = value;
      return this;
    }

    private ParamBuilder marginDelta(String value) {
      this.marginDelta = decimal(value);
      return this;
    }

    private ParamBuilder fundingRate(String value) {
      this.fundingRate = decimal(value);
      return this;
    }

    private ParamBuilder pricing(boolean perpetual, QuantityUnit unit) {
      this.feeRate = TAKER_FEE_RATE;
      this.makerFeeRate = MAKER_FEE_RATE;
      this.takerFeeRate = TAKER_FEE_RATE;
      this.worstFeeRate = WORST_FEE_RATE;
      this.slippageRate = SLIPPAGE_RATE;
      this.quantityUnit = unit;
      if (perpetual) {
        this.maintenanceMarginRate = MAINTENANCE_MARGIN_RATE;
        this.liquidationFeeRate = LIQUIDATION_FEE_RATE;
      }
      return this;
    }

    private ParamBuilder reduceOnly(boolean value) {
      this.reduceOnly = value;
      return this;
    }

    private ParamBuilder side(OrderSide value) {
      this.side = value;
      return this;
    }

    private ParamBuilder orderType(OrderType value) {
      this.orderType = value;
      return this;
    }

    private ParamBuilder protectionType(ProtectionType value) {
      this.protectionType = value;
      return this;
    }

    private ParamBuilder trigger(
        String price,
        TriggerExecutionType executionType,
        TriggerPriceType priceType
    ) {
      this.triggerPrice = decimal(price);
      this.triggerExecutionType = executionType;
      this.triggerPriceType = priceType;
      return this;
    }

    private ParamBuilder triggerExecutionType(TriggerExecutionType value) {
      this.triggerExecutionType = value;
      return this;
    }

    private ParamBuilder triggerPriceType(TriggerPriceType value) {
      this.triggerPriceType = value;
      return this;
    }

    private ParamBuilder condition(String value) {
      this.condition = value;
      return this;
    }

    private Parameters build() {
      return new Parameters(
          actionId,
          clientOrderId,
          orderId,
          competingOrderId,
          protectionId,
          price,
          triggerPrice,
          leverage,
          marginDelta,
          fundingRate,
          feeRate,
          makerFeeRate,
          takerFeeRate,
          worstFeeRate,
          slippageRate,
          maintenanceMarginRate,
          liquidationFeeRate,
          quantityUnit,
          reduceOnly,
          side,
          orderType,
          protectionType,
          triggerExecutionType,
          triggerPriceType,
          condition,
          failureCondition);
    }
  }
}
