package com.fxplatform.trading.scenario.oracle;

import static com.fxplatform.trading.scenario.oracle.ScenarioDecimalMath.ceil8;
import static com.fxplatform.trading.scenario.oracle.ScenarioDecimalMath.floorToStep;
import static com.fxplatform.trading.scenario.oracle.ScenarioDecimalMath.s8;
import static com.fxplatform.trading.scenario.oracle.ScenarioDecimalMath.zero;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.scenario.ExpectedScenarioResult;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.AccountState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.EventState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.FailureState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.OrderState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.PositionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.TradeState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.WalletState;
import com.fxplatform.trading.scenario.ScenarioAction;
import com.fxplatform.trading.scenario.ScenarioAction.Parameters;
import com.fxplatform.trading.scenario.ScenarioDefinition;
import com.fxplatform.trading.scenario.ScenarioPriceStep;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpotScenarioOracle {

  private static final BigDecimal QUANTITY_STEP = new BigDecimal("0.0001");
  private static final BigDecimal PRICE_TICK = new BigDecimal("0.1");
  private static final BigDecimal MIN_NOTIONAL = new BigDecimal("5");
  private static final Set<String> EXTERNAL_FAILURE_CONDITIONS = Set.of(
      "SECOND_RESERVATION_LOSES_BALANCE_RACE");

  public ExpectedScenarioResult calculate(ScenarioDefinition scenario) {
    return calculate(scenario, false);
  }

  public List<ExpectedScenarioResult> calculateAll(ScenarioDefinition scenario) {
    ExpectedScenarioResult primary = calculate(scenario, false);
    boolean hasOcoRace = scenario.actions().stream()
        .anyMatch(action -> action.type() == ScenarioAction.Type.RACE
            && "OCO_DUAL_TRIGGER".equals(action.parameters().condition()));
    return hasOcoRace
        ? List.of(primary, calculate(scenario, true))
        : List.of(primary);
  }

  private ExpectedScenarioResult calculate(
      ScenarioDefinition scenario,
      boolean competitorWins
  ) {
    Objects.requireNonNull(scenario, "scenario");
    if (scenario.productType() != ProductType.CRYPTO_SPOT) {
      throw new IllegalArgumentException("Spot Oracle only accepts CRYPTO_SPOT");
    }

    State state = State.initial(scenario);
    List<Checkpoint> checkpoints = new ArrayList<>(scenario.actions().size());
    List<String> trace = new ArrayList<>();
    for (int index = 0; index < scenario.actions().size(); index++) {
      ScenarioAction action = scenario.actions().get(index);
      ScenarioPriceStep market = scenario.priceSteps().get(
          Math.min(index + 1, scenario.priceSteps().size() - 1));
      FailureState failure = apply(
          scenario, state, action, market, trace, competitorWins);
      state.revalue(market);
      checkpoints.add(new Checkpoint(
          index + 1,
          action.parameters().actionId(),
          state.snapshot(scenario, market),
          failure));
    }
    return new ExpectedScenarioResult(scenario.caseId(), checkpoints, trace);
  }

  private FailureState apply(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction action,
      ScenarioPriceStep market,
      List<String> trace,
      boolean competitorWins
  ) {
    if (action.type() == ScenarioAction.Type.RACE
        && "TWO_6000_USDT_ORDERS_COMPETE_FOR_10000".equals(
            action.parameters().condition())) {
      return balanceRace(scenario, state, action, market, trace);
    }
    FailureState validation = validate(state, action, market);
    if (validation != null) {
      trace.add(action.parameters().actionId() + ": rejected from input/state: "
          + validation.code());
      return validation;
    }
    if (!action.parameters().failureCondition().isBlank()) {
      String condition = action.parameters().failureCondition();
      if (!EXTERNAL_FAILURE_CONDITIONS.contains(condition)) {
        throw new IllegalArgumentException(
            "Spot failure condition is not an external-event whitelist member: " + condition);
      }
      trace.add(action.parameters().actionId() + ": rejected before mutation: "
          + condition);
      return new FailureState(
          externalFailureCode(condition),
          "BusinessException",
          true);
    }

    return switch (action.type()) {
      case BUY, SELL, PARTIAL_SELL ->
          applyMarketFill(scenario, state, action, market, null, trace);
      case PLACE_ORDER -> placeOrder(scenario, state, action, market, trace);
      case CREATE_OCO -> {
        createOco(state, action, trace);
        yield null;
      }
      case CANCEL -> {
        cancel(state, action, trace);
        yield null;
      }
      case MODIFY -> {
        modify(scenario, state, action, market, trace);
        yield null;
      }
      case TRIGGER -> {
        trigger(scenario, state, action, market, trace);
        yield null;
      }
      case REPLAY -> {
        trace.add(action.parameters().actionId() + ": idempotent replay, no new mutation");
        yield null;
      }
      case RACE -> {
        triggerRace(scenario, state, action, market, trace, competitorWins);
        yield null;
      }
      case REVALUE -> {
        trace.add(action.parameters().actionId() + ": final controlled-market revaluation");
        yield null;
      }
      default -> {
        trace.add(action.parameters().actionId() + ": no Spot state transition");
        yield null;
      }
    };
  }

  private FailureState balanceRace(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction action,
      ScenarioPriceStep market,
      List<String> trace
  ) {
    applyMarketFill(scenario, state, action, market, null, trace);
    trace.add(action.parameters().actionId()
        + ": one concurrent order committed; the competing reservation lost");
    return new FailureState("INSUFFICIENT_BALANCE", "BusinessException", false);
  }

  private static FailureState validate(
      State state,
      ScenarioAction action,
      ScenarioPriceStep market
  ) {
    Parameters parameters = action.parameters();
    String code = null;
    if (action.type() == ScenarioAction.Type.CHANGE_LEVERAGE
        || action.type() == ScenarioAction.Type.SET_PROTECTION
        || Boolean.TRUE.equals(parameters.reduceOnly())) {
      code = "INVALID_SPOT_ORDER_FIELDS";
    } else if (parameters.orderType() == OrderType.STOP) {
      code = "INVALID_SPOT_ORDER_TYPE";
    } else if (action.quantity() != null
        && parameters.quantityUnit() == QuantityUnit.BASE
        && action.quantity().remainder(QUANTITY_STEP).signum() != 0) {
      code = "QUANTITY_STEP_MISMATCH";
    } else if (parameters.price() != null
        && parameters.price().remainder(PRICE_TICK).signum() != 0) {
      code = "PRICE_TICK_MISMATCH";
    } else if (isOrderAction(action)
        && action.quantity() != null
        && notional(action, market).compareTo(MIN_NOTIONAL) < 0) {
      code = "ORDER_NOTIONAL_TOO_SMALL";
    } else if (action.type() == ScenarioAction.Type.REPLAY
        && "FINGERPRINT_MISMATCH".equals(parameters.condition())) {
      code = "DUPLICATE_CLIENT_ORDER_ID";
    } else if (isOrderAction(action) && action.quantity() != null) {
      if (parameters.side() == OrderSide.SELL
          && parameters.quantityUnit() == QuantityUnit.BASE
          && action.quantity().compareTo(state.available("BTC")) > 0) {
        code = "INSUFFICIENT_BALANCE";
      } else if (parameters.side() == OrderSide.BUY
          && requiredQuote(action, market).compareTo(state.available("USDT")) > 0) {
        code = "INSUFFICIENT_BALANCE";
      }
    }
    return code == null ? null : new FailureState(code, "BusinessException", true);
  }

  private static boolean isOrderAction(ScenarioAction action) {
    return switch (action.type()) {
      case BUY, SELL, PARTIAL_SELL, PLACE_ORDER, REPLAY -> true;
      default -> false;
    };
  }

  private static BigDecimal notional(
      ScenarioAction action,
      ScenarioPriceStep market
  ) {
    if (action.parameters().quantityUnit() == QuantityUnit.QUOTE) {
      return action.quantity();
    }
    BigDecimal price = action.parameters().price();
    if (price == null) {
      price = action.parameters().side() == OrderSide.SELL ? market.bid() : market.ask();
    }
    return action.quantity().multiply(price);
  }

  private static BigDecimal requiredQuote(
      ScenarioAction action,
      ScenarioPriceStep market
  ) {
    if (action.parameters().quantityUnit() == QuantityUnit.QUOTE) {
      return action.quantity();
    }
    BigDecimal feeRate = action.parameters().worstFeeRate() == null
        ? BigDecimal.ZERO : action.parameters().worstFeeRate();
    return notional(action, market).multiply(BigDecimal.ONE.add(feeRate));
  }

  private static String externalFailureCode(String condition) {
    return switch (condition) {
      case "SECOND_RESERVATION_LOSES_BALANCE_RACE" -> "INSUFFICIENT_BALANCE";
      default -> throw new IllegalArgumentException(
          "Unsupported external Spot failure condition: " + condition);
    };
  }

  private FailureState placeOrder(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction action,
      ScenarioPriceStep market,
      List<String> trace
  ) {
    Parameters parameters = action.parameters();
    if (parameters.orderType() == OrderType.MARKET) {
      return applyMarketFill(scenario, state, action, market, null, trace);
    }

    OrderSide side = parameters.side();
    if (parameters.orderType() == OrderType.LIMIT && marketable(side, parameters.price(), market)) {
      return applyMarketFill(
          scenario, state, action, market, LiquidityRole.TAKER, trace);
    }

    BigDecimal baseQuantity = s8(action.quantity());
    String holdAsset;
    BigDecimal hold;
    if (side == OrderSide.SELL) {
      holdAsset = "BTC";
      hold = ceil8(baseQuantity);
    } else if (parameters.orderType() == OrderType.LIMIT) {
      holdAsset = "USDT";
      hold = ceil8(baseQuantity
          .multiply(parameters.price())
          .multiply(BigDecimal.ONE.add(parameters.worstFeeRate())));
    } else {
      holdAsset = "USDT";
      BigDecimal authority = parameters.triggerPrice().max(market.ask());
      hold = ceil8(baseQuantity
          .multiply(authority)
          .multiply(BigDecimal.ONE.add(parameters.slippageRate()))
          .multiply(BigDecimal.ONE.add(parameters.worstFeeRate())));
    }

    MutableOrder order = MutableOrder.pending(action, baseQuantity);
    order.holdAsset = holdAsset;
    order.holdAmount = hold;
    state.orders.put(order.ref, order);
    state.lockWithLedger(
        holdAsset,
        hold,
        "SPOT_ORDER_LOCK",
        "ORDER",
        order.ref);
    state.event("ORDER_PENDING", order.ref, "ACCEPTED", "PENDING", "");
    trace.add(action.parameters().actionId() + ": hold " + holdAsset + "=" + hold);
    return null;
  }

  private FailureState applyMarketFill(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction action,
      ScenarioPriceStep market,
      LiquidityRole forcedRole,
      List<String> trace
  ) {
    Parameters parameters = action.parameters();
    OrderSide side = parameters.side();
    LiquidityRole role = forcedRole == null ? LiquidityRole.TAKER : forcedRole;
    BigDecimal fillPrice = fillPrice(side, parameters.orderType(), parameters.price(), market);
    BigDecimal feeRate = role == LiquidityRole.MAKER
        ? parameters.makerFeeRate() : parameters.takerFeeRate();
    BigDecimal baseQuantity = parameters.quantityUnit() == QuantityUnit.QUOTE
        ? floorToStep(
            action.quantity().divide(
                fillPrice.multiply(BigDecimal.ONE.add(feeRate)),
                24,
                RoundingMode.DOWN),
            QUANTITY_STEP)
        : action.quantity();
    baseQuantity = s8(baseQuantity);
    BigDecimal grossQuote = s8(baseQuantity.multiply(fillPrice));
    String orderRef = parameters.orderId().isBlank()
        ? parameters.actionId() + "-order" : parameters.orderId();
    MutableOrder order = state.orders.get(orderRef);
    String fillFromStatus;
    if (order == null) {
      order = MutableOrder.pending(action, baseQuantity);
      state.orders.put(orderRef, order);
      fillFromStatus = "ACCEPTED";
    } else if ("TRIGGERED".equals(action.detail())) {
      fillFromStatus = "WORKING";
    } else {
      fillFromStatus = "PENDING";
    }
    String tradeRef = orderRef + "-trade-1";

    BigDecimal fee;
    BigDecimal realized = zero();
    if (side == OrderSide.BUY) {
      fee = s8(grossQuote.multiply(feeRate));
      state.consumeBuy(order, grossQuote, fee, tradeRef);
      state.creditAvailableWithLedger(
          "BTC",
          baseQuantity,
          "SPOT_BUY_CREDIT",
          "TRADE",
          tradeRef);
      state.buyPosition(baseQuantity, grossQuote);
      trace.add(parameters.actionId()
          + ": quoteBudget=" + action.quantity()
          + ", fill=" + fillPrice
          + ", base=" + baseQuantity
          + ", feeQuote=" + fee
          + ", totalQuote=" + s8(grossQuote.add(fee)));
      order.feeAsset = "USDT";
    } else {
      fee = s8(grossQuote.multiply(feeRate));
      BigDecimal netQuote = s8(grossQuote.subtract(fee));
      realized = state.sellPosition(baseQuantity, grossQuote, fee);
      state.consumeSell(order, baseQuantity, tradeRef);
      state.creditAvailableWithLedger(
          "USDT",
          grossQuote,
          "SPOT_SELL_CREDIT",
          "TRADE",
          tradeRef);
      state.debitAvailableWithLedger(
          "USDT",
          fee,
          "TRADE_FEE",
          "TRADE",
          tradeRef);
      trace.add(parameters.actionId()
          + ": base=" + baseQuantity
          + ", fill=" + fillPrice
          + ", grossQuote=" + grossQuote
          + ", feeQuote=" + fee
          + ", realized=" + realized);
      order.feeAsset = "USDT";
    }

    order.status = "FILLED";
    order.filledQuantity = baseQuantity;
    order.remainingQuantity = zero();
    order.avgFillPrice = fillPrice;
    order.fee = fee;
    order.liquidityRole = role;
    order.holdAmount = zero();
    state.trades.add(new TradeState(
        tradeRef,
        orderRef,
        side,
        baseQuantity,
        fillPrice,
        grossQuote,
        fee,
        order.feeAsset,
        zero(),
        ProductType.CRYPTO_SPOT,
        PositionSide.BOTH,
        MarginMode.CASH,
        role,
        order.clientOrderId));
    state.event("ORDER_FILLED", orderRef, fillFromStatus, "FILLED", "");
    return null;
  }

  private void createOco(State state, ScenarioAction action, List<String> trace) {
    Parameters parameters = action.parameters();
    BigDecimal quantity = s8(action.quantity());
    String ownerRef = parameters.orderId();
    String group = parameters.clientOrderId();

    MutableOrder limit = MutableOrder.pending(
        ownerRef, OrderSide.SELL, OrderType.LIMIT, quantity,
        parameters.price(), null, false);
    limit.clientOrderId = group + ":OCO:L";
    limit.origin = "OCO";
    limit.contingencyRef = group;
    limit.holdAsset = "BTC";
    limit.holdAmount = ceil8(quantity);
    limit.holdOwnerRef = ownerRef;
    MutableOrder stop = MutableOrder.pending(
        parameters.competingOrderId(), OrderSide.SELL, OrderType.STOP_MARKET, quantity,
        null, parameters.triggerPrice(), false);
    stop.clientOrderId = group + ":OCO:S";
    stop.origin = "OCO";
    stop.contingencyRef = group;
    stop.holdAsset = "BTC";
    stop.holdAmount = zero();
    stop.holdOwnerRef = ownerRef;
    state.orders.put(limit.ref, limit);
    state.orders.put(stop.ref, stop);
    state.lockWithLedger(
        "BTC",
        ceil8(quantity),
        "SPOT_ORDER_LOCK",
        "ORDER",
        ownerRef);
    state.event("ORDER_PENDING", limit.ref, "ACCEPTED", "PENDING", "");
    state.event("ORDER_PENDING", stop.ref, "ACCEPTED", "PENDING", "");
    trace.add(parameters.actionId() + ": OCO shared hold BTC=" + quantity);
  }

  private void cancel(State state, ScenarioAction action, List<String> trace) {
    MutableOrder target = state.orders.get(action.parameters().orderId());
    if (target == null) {
      return;
    }
    if (!target.contingencyRef.isBlank()) {
      releaseOwnerHold(state, target.holdOwnerRef, "SPOT_ORDER_RELEASE");
      state.orders.values().stream()
          .filter(order -> order.contingencyRef.equals(target.contingencyRef))
          .forEach(order -> {
            order.status = "CANCELED";
            order.remainingQuantity = zero();
            order.holdAmount = zero();
            state.event("ORDER_CANCELED", order.ref, "PENDING", "CANCELED", "");
          });
    } else {
      releaseOwnerHold(state, target.ref, "SPOT_ORDER_RELEASE");
      target.status = "CANCELED";
      target.remainingQuantity = zero();
      state.event("ORDER_CANCELED", target.ref, "PENDING", "CANCELED", "");
    }
    trace.add(action.parameters().actionId() + ": canceled " + target.ref);
  }

  private void modify(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction action,
      ScenarioPriceStep market,
      List<String> trace
  ) {
    MutableOrder target = state.orders.get(action.parameters().orderId());
    if (target == null || !"PENDING".equals(target.status)) {
      return;
    }
    target.price = action.parameters().price();
    target.holdAsset = target.side == OrderSide.SELL ? "BTC" : "USDT";

    if (marketable(target.side, target.price, market)) {
      BigDecimal executionPrice = fillPrice(
          target.side, target.type, target.price, market);
      BigDecimal grossQuote = s8(target.quantity.multiply(executionPrice));
      BigDecimal spent = target.side == OrderSide.BUY
          ? s8(grossQuote.add(s8(grossQuote.multiply(new BigDecimal("0.0005")))))
          : s8(target.quantity);
      BigDecimal increase = s8(spent.subtract(target.holdAmount).max(BigDecimal.ZERO));
      if (increase.signum() > 0) {
        state.lockWithLedger(
            target.holdAsset,
            increase,
            "SPOT_ORDER_LOCK",
            "ORDER_MODIFICATION",
            action.parameters().actionId());
        target.holdAmount = s8(target.holdAmount.add(increase));
      }
      state.event("ORDER_MODIFIED", target.ref, "PENDING", "PENDING", "");
      trace.add(action.parameters().actionId()
          + ": modified order became marketable; hold increase=" + increase);
      applyMarketFill(
          scenario,
          state,
          modifiedFillAction(action, target),
          market,
          LiquidityRole.TAKER,
          trace);
      return;
    }

    BigDecimal hold = target.side == OrderSide.SELL
        ? ceil8(target.quantity)
        : ceil8(target.quantity.multiply(target.price)
            .multiply(BigDecimal.ONE.add(new BigDecimal("0.0005"))));
    BigDecimal delta = s8(hold.subtract(target.holdAmount));
    if (delta.signum() > 0) {
      state.lockWithLedger(
          target.holdAsset,
          delta,
          "SPOT_ORDER_LOCK",
          "ORDER_MODIFICATION",
          action.parameters().actionId());
    } else if (delta.signum() < 0) {
      state.releaseWithLedger(
          target.holdAsset,
          delta.abs(),
          "SPOT_ORDER_RELEASE",
          "ORDER_MODIFICATION",
          action.parameters().actionId());
    }
    target.holdAmount = hold;
    state.event("ORDER_MODIFIED", target.ref, "PENDING", "PENDING", "");
    trace.add(action.parameters().actionId() + ": modified hold=" + hold);
  }

  private static ScenarioAction modifiedFillAction(
      ScenarioAction modification,
      MutableOrder target
  ) {
    Parameters value = modification.parameters();
    return new ScenarioAction(
        ScenarioAction.Type.PLACE_ORDER,
        PositionSide.BOTH,
        target.quantity,
        new Parameters(
            value.actionId(),
            target.clientOrderId,
            target.ref,
            "",
            "",
            target.price,
            target.triggerPrice,
            null,
            null,
            null,
            new BigDecimal("0.0005"),
            new BigDecimal("0.0002"),
            new BigDecimal("0.0005"),
            new BigDecimal("0.0005"),
            new BigDecimal("0.0001"),
            null,
            null,
            QuantityUnit.BASE,
            false,
            target.side,
            target.type,
            null,
            value.triggerExecutionType(),
            value.triggerPriceType(),
            value.condition(),
            ""),
        "MODIFIED_FILL");
  }

  private void trigger(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction trigger,
      ScenarioPriceStep market,
      List<String> trace
  ) {
    MutableOrder target = state.orders.get(trigger.parameters().orderId());
    if (target == null || !"PENDING".equals(target.status)) {
      return;
    }
    ScenarioAction synthetic = new ScenarioAction(
        target.type == OrderType.LIMIT ? ScenarioAction.Type.PLACE_ORDER : ScenarioAction.Type.BUY,
        PositionSide.BOTH,
        target.quantity,
            new Parameters(
                trigger.parameters().actionId(),
                target.clientOrderId,
                target.ref,
            "",
            "",
            target.price,
            target.triggerPrice,
            null,
            null,
            null,
            target.type == OrderType.LIMIT
                ? new BigDecimal("0.0002") : new BigDecimal("0.0005"),
            new BigDecimal("0.0002"),
            new BigDecimal("0.0005"),
            new BigDecimal("0.0005"),
            new BigDecimal("0.0001"),
            null,
            null,
            QuantityUnit.BASE,
            false,
            target.side,
            target.type,
            null,
            trigger.parameters().triggerExecutionType(),
            trigger.parameters().triggerPriceType(),
            trigger.parameters().condition(),
            ""),
        "TRIGGERED");
    if (!target.contingencyRef.isBlank()) {
      String holdOwnerRef = target.holdOwnerRef;
      state.orders.values().stream()
          .filter(order -> order.contingencyRef.equals(target.contingencyRef))
          .filter(order -> !order.ref.equals(target.ref))
          .forEach(order -> {
            order.status = "CANCELED";
            order.remainingQuantity = zero();
            if (!order.ref.equals(holdOwnerRef)) {
              order.holdAmount = zero();
            }
            state.event("ORDER_CANCELED", order.ref, "PENDING", "CANCELED", "");
          });
    }
    applyMarketFill(
        scenario,
        state,
        synthetic,
        market,
        target.type == OrderType.LIMIT ? LiquidityRole.MAKER : LiquidityRole.TAKER,
        trace);
  }

  private void triggerRace(
      ScenarioDefinition scenario,
      State state,
      ScenarioAction race,
      ScenarioPriceStep market,
      List<String> trace,
      boolean competitorWins
  ) {
    String winner = competitorWins
        ? race.parameters().competingOrderId()
        : race.parameters().orderId();
    MutableOrder winnerOrder = state.orders.get(winner);
    ScenarioPriceStep executionMarket = market;
    if (competitorWins
        && winnerOrder != null
        && winnerOrder.triggerPrice != null) {
      BigDecimal triggerPrice = winnerOrder.triggerPrice;
      executionMarket = new ScenarioPriceStep(
          market.path(),
          market.sequence(),
          market.bid() == null ? null : market.bid().min(triggerPrice),
          market.ask(),
          market.last() == null ? triggerPrice : market.last().min(triggerPrice),
          market.mark(),
          market.index(),
          market.source(),
          market.asOf(),
          market.expiresAt(),
          market.missingFields());
    }
    Parameters value = race.parameters();
    trigger(
        scenario,
        state,
        new ScenarioAction(
            ScenarioAction.Type.TRIGGER,
            PositionSide.BOTH,
            null,
            new Parameters(
                value.actionId(),
                value.clientOrderId(),
                winner,
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
                ""),
            "RACE_WINNER"),
        executionMarket,
        trace);
  }

  private static boolean marketable(
      OrderSide side,
      BigDecimal limitPrice,
      ScenarioPriceStep market
  ) {
    return side == OrderSide.BUY
        ? limitPrice.compareTo(market.ask()) >= 0
        : limitPrice.compareTo(market.bid()) <= 0;
  }

  private static BigDecimal fillPrice(
      OrderSide side,
      OrderType orderType,
      BigDecimal limitPrice,
      ScenarioPriceStep market
  ) {
    if (orderType == OrderType.LIMIT && limitPrice != null) {
      return s8(side == OrderSide.BUY
          ? market.ask().min(limitPrice)
          : market.bid().max(limitPrice));
    }
    BigDecimal reference = side == OrderSide.BUY ? market.ask() : market.bid();
    BigDecimal slippage = reference.multiply(new BigDecimal("0.0001"));
    return s8(side == OrderSide.BUY
        ? reference.add(slippage)
        : reference.subtract(slippage));
  }

  private static void releaseOwnerHold(
      State state,
      String ownerRef,
      String entryType
  ) {
    MutableOrder owner = state.orders.get(ownerRef);
    if (owner == null || owner.holdAmount.signum() == 0) {
      return;
    }
    state.releaseWithLedger(
        owner.holdAsset,
        owner.holdAmount,
        entryType,
        "ORDER",
        owner.ref);
    owner.holdAmount = zero();
  }

  private static final class State {

    private final Map<String, MutableWallet> wallets = new LinkedHashMap<>();
    private final Map<String, MutableOrder> orders = new LinkedHashMap<>();
    private final List<TradeState> trades = new ArrayList<>();
    private final List<LedgerState> ledger = new ArrayList<>();
    private final List<EventState> events = new ArrayList<>();
    private BigDecimal positionQuantity = zero();
    private BigDecimal averageCost = zero();
    private BigDecimal realizedPnl = zero();
    private BigDecimal unrealizedPnl = zero();
    private BigDecimal markPrice = zero();
    private BigDecimal accountBalance = zero();
    private int ledgerSequence;
    private int eventSequence;

    private static State initial(ScenarioDefinition scenario) {
      State state = new State();
      scenario.initialBalances().forEach((asset, value) ->
          state.wallets.put(asset, MutableWallet.available(asset, new BigDecimal(value))));
      state.wallets.putIfAbsent("USDT", MutableWallet.available("USDT", zero()));
      state.wallets.putIfAbsent("BTC", MutableWallet.available("BTC", zero()));
      state.accountBalance = s8(new BigDecimal(
          scenario.initialBalances().getOrDefault("USDT", "0")));
      if (!"NONE".equals(scenario.initialPosition())) {
        String value = scenario.initialPosition().substring(
            scenario.initialPosition().indexOf('=') + 1);
        String[] parts = value.split("@", -1);
        state.positionQuantity = s8(new BigDecimal(parts[0]));
        state.averageCost = s8(new BigDecimal(parts[1]));
      }
      return state;
    }

    private void revalue(ScenarioPriceStep market) {
      markPrice = s8(market.last());
      unrealizedPnl = positionQuantity.signum() == 0
          ? zero()
          : s8(markPrice.subtract(averageCost).multiply(positionQuantity));
    }

    private void buyPosition(BigDecimal baseQuantity, BigDecimal grossQuote) {
      BigDecimal nextQuantity = s8(positionQuantity.add(baseQuantity));
      BigDecimal nextCost = positionQuantity.multiply(averageCost).add(grossQuote);
      averageCost = nextQuantity.signum() == 0
          ? zero()
          : s8(nextCost.divide(nextQuantity, 18, RoundingMode.HALF_UP));
      positionQuantity = nextQuantity;
    }

    private BigDecimal sellPosition(
        BigDecimal soldQuantity,
        BigDecimal grossQuote,
        BigDecimal feeQuote
    ) {
      BigDecimal costQuantity = positionQuantity.min(soldQuantity);
      BigDecimal costBasis = costQuantity.multiply(averageCost);
      BigDecimal delta = s8(grossQuote.subtract(costBasis).subtract(feeQuote));
      realizedPnl = s8(realizedPnl.add(delta));
      positionQuantity = s8(positionQuantity.subtract(soldQuantity).max(BigDecimal.ZERO));
      if (positionQuantity.signum() == 0) {
        averageCost = zero();
        unrealizedPnl = zero();
      }
      return delta;
    }

    private void consumeBuy(
        MutableOrder order,
        BigDecimal grossQuote,
        BigDecimal feeQuote,
        String tradeRef
    ) {
      BigDecimal totalSpend = s8(grossQuote.add(feeQuote));
      MutableOrder owner = holdOwner(order);
      if (owner != null && owner.holdAmount.signum() > 0) {
        BigDecimal held = owner.holdAmount;
        if (totalSpend.compareTo(held) > 0) {
          BigDecimal topUp = s8(totalSpend.subtract(held));
          lockWithLedger(
              "USDT",
              topUp,
              "SPOT_ORDER_LOCK",
              "ORDER_TRIGGER",
              owner.ref);
          owner.holdAmount = s8(owner.holdAmount.add(topUp));
          held = owner.holdAmount;
        }
        debitLockedWithLedger(
            "USDT",
            grossQuote,
            "SPOT_BUY_DEBIT",
            "TRADE",
            tradeRef);
        debitLockedWithLedger(
            "USDT",
            feeQuote,
            "TRADE_FEE",
            "TRADE",
            tradeRef);
        BigDecimal release = s8(held.subtract(totalSpend));
        if (release.signum() > 0) {
          releaseWithLedger(
              "USDT",
              release,
              "ORDER_RELEASE",
              "ORDER",
              owner.ref);
        }
        owner.holdAmount = zero();
      } else {
        debitAvailableWithLedger(
            "USDT",
            grossQuote,
            "SPOT_BUY_DEBIT",
            "TRADE",
            tradeRef);
        debitAvailableWithLedger(
            "USDT",
            feeQuote,
            "TRADE_FEE",
            "TRADE",
            tradeRef);
      }
    }

    private void consumeSell(
        MutableOrder order,
        BigDecimal quantity,
        String tradeRef
    ) {
      MutableOrder owner = holdOwner(order);
      if (owner != null && owner.holdAmount.signum() > 0) {
        BigDecimal held = owner.holdAmount;
        debitLockedWithLedger(
            "BTC",
            quantity,
            "SPOT_SELL_DEBIT",
            "TRADE",
            tradeRef);
        BigDecimal release = s8(held.subtract(quantity));
        if (release.signum() > 0) {
          releaseWithLedger(
              "BTC",
              release,
              "ORDER_RELEASE",
              "ORDER",
              owner.ref);
        }
        owner.holdAmount = zero();
      } else {
        debitAvailableWithLedger(
            "BTC",
            quantity,
            "SPOT_SELL_DEBIT",
            "TRADE",
            tradeRef);
      }
    }

    private MutableOrder holdOwner(MutableOrder order) {
      if (!order.holdOwnerRef.isBlank()) {
        return orders.get(order.holdOwnerRef);
      }
      return order.holdAmount.signum() > 0 ? order : null;
    }

    private void debitAvailableWithLedger(
        String asset,
        BigDecimal amount,
        String type,
        String referenceType,
        String reference
    ) {
      MutableWallet wallet = wallet(asset);
      wallet.available = s8(wallet.available.subtract(amount));
      wallet.total = s8(wallet.total.subtract(amount));
      ledger(type, asset, amount.negate(), wallet.available, referenceType, reference);
    }

    private void creditAvailableWithLedger(
        String asset,
        BigDecimal amount,
        String type,
        String referenceType,
        String reference
    ) {
      MutableWallet wallet = wallet(asset);
      wallet.available = s8(wallet.available.add(amount));
      wallet.total = s8(wallet.total.add(amount));
      ledger(type, asset, amount, wallet.available, referenceType, reference);
    }

    private void lockWithLedger(
        String asset,
        BigDecimal amount,
        String type,
        String referenceType,
        String reference
    ) {
      MutableWallet wallet = wallet(asset);
      wallet.available = s8(wallet.available.subtract(amount));
      wallet.locked = s8(wallet.locked.add(amount));
      ledger(type, asset, amount.negate(), wallet.available, referenceType, reference);
    }

    private void releaseWithLedger(
        String asset,
        BigDecimal amount,
        String type,
        String referenceType,
        String reference
    ) {
      MutableWallet wallet = wallet(asset);
      wallet.locked = s8(wallet.locked.subtract(amount));
      wallet.available = s8(wallet.available.add(amount));
      ledger(type, asset, amount, wallet.available, referenceType, reference);
    }

    private void debitLockedWithLedger(
        String asset,
        BigDecimal amount,
        String type,
        String referenceType,
        String reference
    ) {
      MutableWallet wallet = wallet(asset);
      wallet.locked = s8(wallet.locked.subtract(amount));
      wallet.total = s8(wallet.total.subtract(amount));
      ledger(type, asset, amount.negate(), wallet.available, referenceType, reference);
    }

    private MutableWallet wallet(String asset) {
      return wallets.computeIfAbsent(asset, key -> MutableWallet.available(key, zero()));
    }

    private BigDecimal available(String asset) {
      return wallet(asset).available;
    }

    private void ledger(
        String type,
        String asset,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String referenceType,
        String reference
    ) {
      ledger.add(new LedgerState(
          ++ledgerSequence,
          type,
          asset,
          s8(amount),
          s8(balanceAfter),
          referenceType,
          reference,
          type));
    }

    private void event(
        String type,
        String ref,
        String fromStatus,
        String toStatus,
        String code
    ) {
      events.add(new EventState(
          ++eventSequence, type, ref, fromStatus, toStatus, code));
    }

    private Snapshot snapshot(ScenarioDefinition scenario, ScenarioPriceStep market) {
      List<OrderState> orderStates = orders.values().stream()
          .sorted(Comparator.comparing(order -> order.ref))
          .map(MutableOrder::snapshot)
          .toList();
      List<WalletState> walletStates = wallets.values().stream()
          .sorted(Comparator.comparing(wallet -> wallet.asset))
          .map(MutableWallet::snapshot)
          .toList();
      PositionState position = new PositionState(
          "BTC",
          positionQuantity.signum() == 0 ? "CLOSED" : "OPEN",
          OrderSide.BUY,
          PositionMode.ONE_WAY,
          PositionSide.BOTH,
          MarginMode.CASH,
          positionQuantity,
          averageCost,
          markPrice,
          realizedPnl,
          unrealizedPnl,
          zero(),
          zero(),
          zero(),
          zero(),
          s8(positionQuantity.multiply(markPrice)),
          1);
      return new Snapshot(
          orderStates,
          trades,
          List.of(position),
          walletStates,
          new AccountState(
              accountBalance,
              accountBalance,
              zero(),
              accountBalance,
              zero(),
              zero()),
          ledger,
          List.of(),
          events);
    }
  }

  private static final class MutableWallet {

    private final String asset;
    private BigDecimal total;
    private BigDecimal available;
    private BigDecimal locked;

    private MutableWallet(
        String asset,
        BigDecimal total,
        BigDecimal available,
        BigDecimal locked
    ) {
      this.asset = asset;
      this.total = s8(total);
      this.available = s8(available);
      this.locked = s8(locked);
    }

    private static MutableWallet available(String asset, BigDecimal amount) {
      return new MutableWallet(asset, amount, amount, zero());
    }

    private WalletState snapshot() {
      return new WalletState("SPOT", asset, total, available, locked);
    }
  }

  private static final class MutableOrder {

    private final String ref;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal quantity;
    private final boolean reduceOnly;
    private String status = "PENDING";
    private BigDecimal filledQuantity = zero();
    private BigDecimal remainingQuantity;
    private BigDecimal avgFillPrice;
    private BigDecimal price;
    private BigDecimal triggerPrice;
    private BigDecimal fee = zero();
    private String feeAsset = "";
    private LiquidityRole liquidityRole;
    private String contingencyRef = "";
    private String holdAsset = "";
    private BigDecimal holdAmount = zero();
    private String holdOwnerRef = "";
    private String clientOrderId = "";
    private String origin = "USER";

    private MutableOrder(
        String ref,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal triggerPrice,
        boolean reduceOnly
    ) {
      this.ref = ref;
      this.side = side;
      this.type = type;
      this.quantity = s8(quantity);
      this.remainingQuantity = s8(quantity);
      this.price = price;
      this.triggerPrice = triggerPrice;
      this.reduceOnly = reduceOnly;
    }

    private static MutableOrder pending(ScenarioAction action, BigDecimal baseQuantity) {
      Parameters parameters = action.parameters();
      String ref = parameters.orderId().isBlank()
          ? parameters.actionId() + "-order" : parameters.orderId();
      MutableOrder order = pending(
          ref,
          parameters.side(),
          parameters.orderType(),
          baseQuantity,
          parameters.price(),
          parameters.triggerPrice(),
          Boolean.TRUE.equals(parameters.reduceOnly()));
      order.clientOrderId = parameters.clientOrderId();
      return order;
    }

    private static MutableOrder pending(
        String ref,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal triggerPrice,
        boolean reduceOnly
    ) {
      return new MutableOrder(
          ref, side, type, quantity, price, triggerPrice, reduceOnly);
    }

    private OrderState snapshot() {
      return new OrderState(
          ref,
          side,
          type,
          status,
          quantity,
          filledQuantity,
          remainingQuantity,
          avgFillPrice,
          fee,
          feeAsset,
          liquidityRole,
          reduceOnly,
          origin,
          "",
          contingencyRef,
          holdAsset,
          holdAmount,
          holdOwnerRef,
          "");
    }
  }
}
