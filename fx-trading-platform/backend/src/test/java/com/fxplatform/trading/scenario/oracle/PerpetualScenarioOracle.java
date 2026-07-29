package com.fxplatform.trading.scenario.oracle;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.scenario.ExpectedScenarioResult;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.AccountState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.EventState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.FailureState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.LedgerState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.OrderState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.PositionState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.ProtectionState;
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

public final class PerpetualScenarioOracle {

  private static final int SCALE = 8;
  private static final int DIVISION_SCALE = 18;
  private static final int DEFAULT_LEVERAGE = 10;
  private static final BigDecimal BASE_STEP = decimal("0.0001");
  private static final BigDecimal PRICE_TICK = decimal("0.1");
  private static final BigDecimal CONTRACT_SIZE = BigDecimal.ONE;
  private static final BigDecimal CONTRACT_MULTIPLIER = BigDecimal.ONE;
  private static final BigDecimal DEFAULT_MMR = decimal("0.005");
  private static final BigDecimal DEFAULT_MAKER_FEE_RATE = decimal("0.0002");
  private static final BigDecimal DEFAULT_TAKER_FEE_RATE = decimal("0.0005");
  private static final BigDecimal DEFAULT_LIQUIDATION_FEE_RATE = decimal("0.005");
  private static final BigDecimal DEFAULT_SLIPPAGE_RATE = decimal("0.0001");
  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final Set<String> EXTERNAL_FAILURE_CONDITIONS = Set.of(
      "ADMIN_PERMISSION_REQUIRED",
      "LOCK_WAIT_EXCEEDS_MARKET_BUNDLE_TTL",
      "INJECT_FAILURE_AFTER_ORDER_BEFORE_TRADE",
      "INJECT_FAILURE_AFTER_TRADE_BEFORE_LEDGER",
      "STALE_SLOT_REJECTS_BATCH_MEMBER",
      "LOSING_CLOSE_OBSERVES_STALE_POSITION");
  private static final Set<String> ECONOMIC_RACE_CONDITIONS = Set.of(
      "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL",
      "USER_CLOSE_COMPETES_WITH_LIQUIDATION",
      "USER_CLOSE_COMPETES_WITH_STOP_LOSS");

  public ExpectedScenarioResult calculate(ScenarioDefinition scenario) {
    return calculate(scenario, false);
  }

  public List<ExpectedScenarioResult> calculateAll(ScenarioDefinition scenario) {
    ExpectedScenarioResult primary = calculate(scenario, false);
    boolean hasEconomicRace = scenario.actions().stream()
        .anyMatch(action -> action.type() == ScenarioAction.Type.RACE
            && ECONOMIC_RACE_CONDITIONS.contains(action.parameters().condition()));
    return hasEconomicRace
        ? List.of(primary, calculate(scenario, true))
        : List.of(primary);
  }

  private ExpectedScenarioResult calculate(
      ScenarioDefinition scenario,
      boolean competitorWins
  ) {
    Objects.requireNonNull(scenario, "scenario");
    if (scenario.productType() != ProductType.LINEAR_PERP) {
      throw new IllegalArgumentException("Perpetual Oracle only accepts LINEAR_PERP");
    }

    State state = State.initial(scenario);
    state.revalue(scenario.priceSteps().getFirst(), DEFAULT_MMR);
    List<Checkpoint> checkpoints = new ArrayList<>(scenario.actions().size());
    List<String> trace = new ArrayList<>();
    for (int index = 0; index < scenario.actions().size(); index++) {
      ScenarioAction action = scenario.actions().get(index);
      ScenarioPriceStep market = scenario.priceSteps().get(
          Math.min(index + 1, scenario.priceSteps().size() - 1));
      FailureState failure = apply(
          state, action, market, trace, competitorWins);
      if (action.type() != ScenarioAction.Type.SET_PROTECTION) {
        state.revalue(market, maintenanceRate(action.parameters()));
      }
      checkpoints.add(new Checkpoint(
          index + 1,
          action.parameters().actionId(),
          state.snapshot(),
          failure));
    }
    return new ExpectedScenarioResult(scenario.caseId(), checkpoints, trace);
  }

  private FailureState apply(
      State state,
      ScenarioAction action,
      ScenarioPriceStep market,
      List<String> trace,
      boolean competitorWins
  ) {
    FailureState validation = state.validate(action, market);
    if (validation != null) {
      trace.add(action.parameters().actionId() + ": rejected from input/state: "
          + validation.code());
      state.reject(action, validation.code());
      return validation;
    }
    if (action.type() == ScenarioAction.Type.RACE
        && "TWO_FULL_CLOSES_COMPETE_FOR_ONE_POSITION".equals(
            action.parameters().condition())) {
      state.observeMarketSource(market, trace);
      state.race(action, market, trace, competitorWins);
      trace.add(action.parameters().actionId()
          + ": competing close observed the committed winner");
      return new FailureState("POSITION_NOT_FOUND", "BusinessException", false);
    }
    if (!action.parameters().failureCondition().isBlank()) {
      String condition = action.parameters().failureCondition();
      if (!EXTERNAL_FAILURE_CONDITIONS.contains(condition)) {
        throw new IllegalArgumentException(
            "Perpetual failure condition is not an external-event whitelist member: "
                + condition);
      }
      String code = externalFailureCode(condition);
      trace.add(action.parameters().actionId() + ": rejected before mutation: "
          + condition);
      state.reject(action, code);
      return new FailureState(
          code,
          "BusinessException",
          true);
    }

    state.observeMarketSource(market, trace);
    return switch (action.type()) {
      case ADD, PARTIAL_CLOSE, FULL_CLOSE, REVERSE ->
          state.marketFill(action, market, trace);
      case PLACE_ORDER -> state.placeOrFill(action, market, trace);
      case MODIFY -> state.modify(action, market, trace);
      case CANCEL -> {
        state.cancel(action, trace);
        yield null;
      }
      case TRIGGER -> state.trigger(action, market, trace);
      case REPLAY -> {
        state.replay(action, trace);
        yield null;
      }
      case CHANGE_LEVERAGE -> state.changeLeverage(action, trace);
      case ADJUST_MARGIN -> state.adjustMargin(action, trace);
      case SETTLE_FUNDING -> {
        state.settleFunding(action, market, trace);
        yield null;
      }
      case SET_PROTECTION -> state.setProtection(action, market, trace);
      case LIQUIDATE -> {
        state.liquidate(action, market, trace);
        yield null;
      }
      case CANCEL_ALL -> {
        state.cancelAll(action, trace);
        yield null;
      }
      case CLOSE_ALL -> {
        state.closeAll(action, market, "BATCH_CLOSE", trace);
        yield null;
      }
      case ADMIN_FORCE_CLOSE -> {
        state.adminForceClose(action, market, trace);
        yield null;
      }
      case RACE -> {
        state.race(action, market, trace, competitorWins);
        yield null;
      }
      case REVALUE -> {
        trace.add(action.parameters().actionId() + ": terminal mark revalue");
        yield null;
      }
      case ROLLBACK -> {
        trace.add(action.parameters().actionId() + ": rollback preserved pre-action state");
        yield null;
      }
      case BUY, SELL, PARTIAL_SELL, CREATE_OCO ->
          throw new IllegalArgumentException(
              "Unsupported action for LINEAR_PERP: " + action.type());
    };
  }

  private static String externalFailureCode(String condition) {
    return switch (condition) {
      case "STALE_SLOT_REJECTS_BATCH_MEMBER",
           "LOSING_CLOSE_OBSERVES_STALE_POSITION" -> "POSITION_VERSION_CONFLICT";
      case "ADMIN_PERMISSION_REQUIRED" -> "FORBIDDEN";
      case "LOCK_WAIT_EXCEEDS_MARKET_BUNDLE_TTL" -> "MARKET_DATA_STALE";
      case "INJECT_FAILURE_AFTER_ORDER_BEFORE_TRADE",
           "INJECT_FAILURE_AFTER_TRADE_BEFORE_LEDGER" -> "EXECUTION_UNAVAILABLE";
      default -> throw new IllegalArgumentException(
          "Unsupported external Perpetual failure condition: " + condition);
    };
  }

  private static BigDecimal maintenanceRate(Parameters parameters) {
    return parameters.maintenanceMarginRate() == null
        ? DEFAULT_MMR : parameters.maintenanceMarginRate();
  }

  private static BigDecimal takerFeeRate(Parameters parameters) {
    return parameters.takerFeeRate() == null
        ? DEFAULT_TAKER_FEE_RATE : parameters.takerFeeRate();
  }

  private static BigDecimal liquidationFeeRate(Parameters parameters) {
    return parameters.liquidationFeeRate() == null
        ? DEFAULT_LIQUIDATION_FEE_RATE : parameters.liquidationFeeRate();
  }

  private static BigDecimal slippageRate(Parameters parameters) {
    return parameters.slippageRate() == null
        ? DEFAULT_SLIPPAGE_RATE : parameters.slippageRate();
  }

  private static BigDecimal fillPrice(ScenarioAction action, ScenarioPriceStep market) {
    Parameters parameters = action.parameters();
    OrderSide side = parameters.side() == null
        ? action.direction() == PositionSide.SHORT ? OrderSide.SELL : OrderSide.BUY
        : parameters.side();
    if (parameters.orderType() == OrderType.LIMIT && parameters.price() != null) {
      return s8(side == OrderSide.BUY
          ? market.ask().min(parameters.price())
          : market.bid().max(parameters.price()));
    }
    BigDecimal reference = side == OrderSide.BUY ? market.ask() : market.bid();
    BigDecimal slippage = reference.multiply(slippageRate(parameters));
    return s8(side == OrderSide.BUY
        ? reference.add(slippage)
        : reference.subtract(slippage));
  }

  private static BigDecimal normalizeQuantity(
      ScenarioAction action,
      ScenarioPriceStep market
  ) {
    BigDecimal quantity = Objects.requireNonNull(action.quantity(), "action.quantity");
    QuantityUnit unit = Objects.requireNonNull(
        action.parameters().quantityUnit(), "quantityUnit");
    BigDecimal base = switch (unit) {
      case BASE -> quantity;
      case QUOTE -> floorToStep(
          quantity.divide(market.mark(), DIVISION_SCALE, RoundingMode.DOWN),
          BASE_STEP);
      case CONTRACTS -> quantity.multiply(CONTRACT_SIZE).multiply(CONTRACT_MULTIPLIER);
    };
    return s8(base);
  }

  private static boolean isMarketable(ScenarioAction action, ScenarioPriceStep market) {
    Parameters parameters = action.parameters();
    if (parameters.orderType() != OrderType.LIMIT || parameters.price() == null) {
      return parameters.orderType() == OrderType.MARKET;
    }
    return parameters.side() == OrderSide.BUY
        ? parameters.price().compareTo(market.ask()) >= 0
        : parameters.price().compareTo(market.bid()) <= 0;
  }

  private static BigDecimal directionalPnl(
      PositionSide direction,
      BigDecimal entry,
      BigDecimal exit,
      BigDecimal quantity
  ) {
    return s8((direction == PositionSide.SHORT
        ? entry.subtract(exit)
        : exit.subtract(entry)).multiply(quantity));
  }

  private static String slotRef(PositionSide slot) {
    return SYMBOL + ":" + slot;
  }

  private static BigDecimal s8(BigDecimal value) {
    return Objects.requireNonNull(value, "value").setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
    return value.divide(step, 0, RoundingMode.DOWN).multiply(step);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private record CrossLiquidationCharge(
      PositionSide slot,
      String orderRef,
      BigDecimal feeDue
  ) {
  }

  private static final class State {

    private final ScenarioDefinition scenario;
    private final Map<PositionSide, MutablePosition> positions = new LinkedHashMap<>();
    private final Map<String, MutableOrder> orders = new LinkedHashMap<>();
    private final List<TradeState> trades = new ArrayList<>();
    private final List<LedgerState> ledger = new ArrayList<>();
    private final List<MutableProtection> protections = new ArrayList<>();
    private final List<EventState> events = new ArrayList<>();
    private final List<CrossLiquidationCharge> pendingCrossLiquidationCharges =
        new ArrayList<>();
    private BigDecimal walletBalance;
    private BigDecimal bankruptcyShortfall = zero();
    private String marketSource;
    private int settingLeverage;
    private int ledgerSequence;
    private int eventSequence;
    private int protectionSequence;

    private State(
        ScenarioDefinition scenario,
        BigDecimal walletBalance,
        int initialLeverage
    ) {
      this.scenario = scenario;
      this.walletBalance = s8(walletBalance);
      this.settingLeverage = initialLeverage;
      this.marketSource = scenario.priceSteps().getFirst().source();
    }

    private static State initial(ScenarioDefinition scenario) {
      int initialLeverage = !scenario.actions().isEmpty()
          && scenario.actions().getFirst().type() == ScenarioAction.Type.CHANGE_LEVERAGE
          ? DEFAULT_LEVERAGE
          : scenario.leverage();
      State state = new State(
          scenario,
          new BigDecimal(scenario.initialBalances().getOrDefault("USDT", "0")),
          initialLeverage);
      if ("NONE".equals(scenario.initialPosition())) {
        return state;
      }

      String[] attributes = scenario.initialPosition().split(",");
      String[] position = attributes[0].split("=", 2);
      PositionSide direction = PositionSide.valueOf(position[0]);
      String[] quantityAndEntry = position[1].split("@", 2);
      BigDecimal quantity = s8(new BigDecimal(quantityAndEntry[0]));
      BigDecimal entry = s8(new BigDecimal(quantityAndEntry[1]));
      PositionSide slot = scenario.positionMode() == PositionMode.ONE_WAY
          ? PositionSide.BOTH : direction;
      MutablePosition mutable = MutablePosition.open(
          slot,
          direction,
          scenario.positionMode(),
          scenario.marginMode(),
          quantity,
          entry,
          initialLeverage);
      if (attributes.length > 1 && attributes[1].startsWith("MARGIN=")) {
        BigDecimal held = s8(new BigDecimal(attributes[1].substring("MARGIN=".length())));
        mutable.manualMargin = s8(held.subtract(mutable.initialMargin));
      }
      state.positions.put(slot, mutable);
      return state;
    }

    private void observeMarketSource(
        ScenarioPriceStep market,
        List<String> trace
    ) {
      if (Objects.equals(marketSource, market.source())) {
        return;
      }
      event(
          "MARKET_SOURCE_CHANGED",
          SYMBOL,
          marketSource,
          market.source(),
          "");
      trace.add("market source changed " + marketSource + " -> " + market.source());
      marketSource = market.source();
    }

    private void reject(ScenarioAction action, String code) {
      // Validation failures in the covered P0 paths occur before an order event is persisted.
    }

    private FailureState validate(
        ScenarioAction action,
        ScenarioPriceStep market
    ) {
      if (requiresFreshMarket(action) && !market.missingFields().isEmpty()) {
        return failure("MARKET_BUNDLE_INCOMPLETE");
      }
      if (requiresFreshMarket(action)
          && !market.expiresAt().isAfter(market.asOf())) {
        return failure("MARKET_DATA_STALE");
      }
      if ("PARTIAL_FILL_INPUT".equals(action.parameters().condition())) {
        return failure("PARTIAL_FILL_NOT_SUPPORTED");
      }
      if (requiresQuantity(action)) {
        BigDecimal requested = Objects.requireNonNull(action.quantity(), "action.quantity");
        QuantityUnit unit = Objects.requireNonNull(
            action.parameters().quantityUnit(), "quantityUnit");
        if (unit == QuantityUnit.CONTRACTS
            && requested.remainder(BigDecimal.ONE).signum() != 0) {
          return failure("CONTRACT_QUANTITY_NOT_INTEGRAL");
        }
        if (unit == QuantityUnit.BASE && requested.compareTo(BASE_STEP) < 0) {
          return failure("QUANTITY_CONVERTS_TO_ZERO");
        }
      }

      BigDecimal price = action.parameters().price();
      if (price != null && price.remainder(PRICE_TICK).signum() != 0) {
        return failure("PRICE_TICK_MISMATCH");
      }

      if (action.type() == ScenarioAction.Type.REPLAY
          && "FINGERPRINT_MISMATCH".equals(action.parameters().condition())) {
        return failure("DUPLICATE_CLIENT_ORDER_ID");
      }
      if (action.type() == ScenarioAction.Type.CHANGE_LEVERAGE) {
        int target = Objects.requireNonNull(action.parameters().leverage(), "leverage");
        if (target < 1 || target > 100) {
          return failure("LEVERAGE_OUT_OF_RANGE");
        }
        if (requiresUnavailableLeverageMargin(target)) {
          return failure("INSUFFICIENT_MARGIN");
        }
      }
      if (action.type() == ScenarioAction.Type.ADJUST_MARGIN
          && unsafeMarginReduction(action)) {
        return failure("MARGIN_REDUCTION_UNSAFE");
      }
      if (action.type() == ScenarioAction.Type.SET_PROTECTION
          && protections.stream().filter(MutableProtection::active).count() >= 10) {
        return failure("PROTECTION_LIMIT_EXCEEDED");
      }
      if (requiresQuantity(action)) {
        FailureState invalidClose = validateClose(action, normalizeQuantity(action, market));
        if (invalidClose != null) {
          return invalidClose;
        }
      }
      return null;
    }

    private boolean requiresUnavailableLeverageMargin(int target) {
      BigDecimal requiredIncrease = positions.values().stream()
          .filter(MutablePosition::open)
          .map(position -> s8(position.quantity.multiply(position.averageEntry)
              .divide(BigDecimal.valueOf(target), DIVISION_SCALE, RoundingMode.HALF_UP)
              .subtract(position.initialMargin)
              .max(BigDecimal.ZERO)))
          .reduce(zero(), BigDecimal::add);
      if (requiredIncrease.signum() == 0) {
        return false;
      }
      BigDecimal committed = positions.values().stream()
          .filter(MutablePosition::open)
          .map(MutablePosition::marginHeld)
          .reduce(zero(), BigDecimal::add);
      committed = s8(committed.add(orders.values().stream()
          .filter(order -> "PENDING".equals(order.status))
          .map(order -> order.holdAmount)
          .reduce(zero(), BigDecimal::add)));
      BigDecimal available = s8(walletBalance.subtract(committed).max(BigDecimal.ZERO));
      return requiredIncrease.compareTo(available) > 0;
    }

    private boolean unsafeMarginReduction(ScenarioAction action) {
      MutablePosition position = positions.get(slot(action.direction()));
      if (position == null || !position.open()) {
        return false;
      }
      BigDecimal delta = s8(action.parameters().marginDelta());
      if (delta.signum() >= 0) {
        return false;
      }
      BigDecimal nextHeld = s8(position.marginHeld().add(delta));
      BigDecimal closeFee = s8(position.markNotional.multiply(DEFAULT_TAKER_FEE_RATE));
      BigDecimal nextEquity = s8(nextHeld.add(position.fundingPnl).add(position.unrealizedPnl));
      return nextHeld.signum() < 0
          || nextEquity.compareTo(s8(position.maintenanceMargin.add(closeFee))) <= 0;
    }

    private static boolean requiresQuantity(ScenarioAction action) {
      return switch (action.type()) {
        case ADD, PARTIAL_CLOSE, FULL_CLOSE, PLACE_ORDER, REVERSE, SET_PROTECTION -> true;
        default -> false;
      };
    }

    private static boolean requiresFreshMarket(ScenarioAction action) {
      return switch (action.type()) {
        case ADD, PARTIAL_CLOSE, FULL_CLOSE, PLACE_ORDER, MODIFY, TRIGGER,
             REVERSE, SET_PROTECTION, LIQUIDATE, CLOSE_ALL, ADMIN_FORCE_CLOSE, RACE -> true;
        default -> false;
      };
    }

    private static FailureState failure(String code) {
      return new FailureState(code, "BusinessException", true);
    }

    private FailureState placeOrFill(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      BigDecimal quantity = normalizeQuantity(action, market);
      FailureState invalid = validateClose(action, quantity);
      if (invalid != null) {
        reject(action, invalid.code());
        return invalid;
      }
      MutableOrder order = acceptOrder(action, quantity, market);
      if (isMarketable(action, market)) {
        return fillOrder(
            action,
            market,
            trace,
            order,
            LiquidityRole.TAKER,
            "ACCEPTED");
      }
      event("ORDER_PENDING", order.ref, "ACCEPTED", "PENDING", "");
      trace.add(action.parameters().actionId()
          + ": pending hold USDT=" + order.holdAmount);
      return null;
    }

    private FailureState marketFill(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      BigDecimal quantity = normalizeQuantity(action, market);
      FailureState invalid = validateClose(action, quantity);
      if (invalid != null) {
        reject(action, invalid.code());
        return invalid;
      }
      MutableOrder order = acceptOrder(action, quantity, market);
      return fillOrder(
          action, market, trace, order, LiquidityRole.TAKER, "ACCEPTED");
    }

    private FailureState validateClose(ScenarioAction action, BigDecimal quantity) {
      boolean close = action.type() == ScenarioAction.Type.PARTIAL_CLOSE
          || action.type() == ScenarioAction.Type.FULL_CLOSE
          || Boolean.TRUE.equals(action.parameters().reduceOnly());
      MutablePosition current = positions.get(slot(action.direction()));
      if (close && (current == null || !current.open()
          || quantity.compareTo(current.quantity) > 0)) {
        return new FailureState(
            "REDUCE_ONLY_EXCEEDS_POSITION", "BusinessException", true);
      }
      return null;
    }

    private MutableOrder acceptOrder(
        ScenarioAction action,
        BigDecimal quantity,
        ScenarioPriceStep market
    ) {
      MutableOrder order = MutableOrder.pending(action, quantity);
      order.holdAsset = "USDT";
      order.holdAmount = orderHold(action, quantity, market);
      if (action.type() == ScenarioAction.Type.PARTIAL_CLOSE
          || action.type() == ScenarioAction.Type.FULL_CLOSE) {
        order.parentRef = slotRef(slot(action.direction()));
        order.clientOrderId = order.ref;
      }
      orders.put(order.ref, order);
      ledger("ORDER_HOLD", order.holdAmount, "ORDER", order.ref, "ORDER_HOLD");
      return order;
    }

    private BigDecimal orderHold(
        ScenarioAction action,
        BigDecimal quantity,
        ScenarioPriceStep market
    ) {
      BigDecimal estimatedFill = PerpetualScenarioOracle.fillPrice(action, market);
      BigDecimal limitPrice = action.parameters().price();
      boolean limit = action.parameters().orderType() == OrderType.LIMIT
          && limitPrice != null;
      BigDecimal marginAndFeePrice = s8(limit
          ? limitPrice.max(estimatedFill)
          : estimatedFill);
      BigDecimal closeWorstPrice = s8(limit
          && action.parameters().side() == OrderSide.SELL
          ? limitPrice.min(estimatedFill)
          : marginAndFeePrice);
      BigDecimal notional = s8(quantity.multiply(marginAndFeePrice));
      BigDecimal fee = s8(notional.multiply(
          action.parameters().worstFeeRate() == null
              ? takerFeeRate(action.parameters())
              : action.parameters().worstFeeRate()));
      MutablePosition current = positions.get(slot(action.direction()));
      if (current == null || !current.open()) {
        return s8(notional.divide(
            BigDecimal.valueOf(settingLeverage),
            DIVISION_SCALE,
            RoundingMode.HALF_UP).add(fee));
      }

      boolean close = Boolean.TRUE.equals(action.parameters().reduceOnly())
          || action.type() == ScenarioAction.Type.PARTIAL_CLOSE
          || action.type() == ScenarioAction.Type.FULL_CLOSE;
      if (close) {
        BigDecimal adverse = current.direction == PositionSide.SHORT
            ? closeWorstPrice.subtract(market.mark()).max(BigDecimal.ZERO)
            : market.mark().subtract(closeWorstPrice).max(BigDecimal.ZERO);
        return s8(adverse.multiply(quantity.min(current.quantity)).add(fee));
      }
      if (current.direction != action.direction()) {
        BigDecimal closed = quantity.min(current.quantity);
        BigDecimal adverse = current.direction == PositionSide.SHORT
            ? closeWorstPrice.subtract(market.mark()).max(BigDecimal.ZERO)
            : market.mark().subtract(closeWorstPrice).max(BigDecimal.ZERO);
        BigDecimal remainder = s8(quantity.subtract(closed).max(BigDecimal.ZERO));
        BigDecimal remainderMargin = s8(remainder.multiply(marginAndFeePrice).divide(
            BigDecimal.valueOf(settingLeverage),
            DIVISION_SCALE,
            RoundingMode.HALF_UP));
        return s8(adverse.multiply(closed).add(remainderMargin).add(fee));
      }
      return s8(notional.divide(
          BigDecimal.valueOf(settingLeverage),
          DIVISION_SCALE,
          RoundingMode.HALF_UP).add(fee));
    }

    private FailureState fillOrder(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace,
        MutableOrder order,
        LiquidityRole role,
        String fillFromStatus
    ) {
      BigDecimal quantity = order.remainingQuantity;
      PositionSide slot = slot(order.direction);
      MutablePosition before = positions.get(slot);
      BigDecimal marginBefore = before == null ? zero() : before.marginHeld();
      BigDecimal fundingRealized = isolatedFundingRealized(before, quantity, order.reduceOnly);
      PositionSide directionBefore = before == null ? null : before.direction;
      boolean wasOpen = before != null && before.open();
      boolean close = order.reduceOnly;
      BigDecimal price = fillPrice(order, market);
      BigDecimal realized = transition(action, quantity, price, close);
      MutablePosition resulting = positions.get(slot);
      if (fundingRealized.signum() != 0) {
        resulting.fundingPnl = s8(resulting.fundingPnl.subtract(fundingRealized));
      }
      boolean reversed = wasOpen && resulting != null && resulting.open()
          && directionBefore != resulting.direction;
      if (resulting != null && resulting.open() && (!wasOpen || reversed)) {
        resulting.parentOrderRef = order.ref;
      }

      BigDecimal marginAfter =
          resulting == null || !resulting.open() ? zero() : resulting.marginHeld();
      BigDecimal notional = s8(quantity.multiply(price));
      BigDecimal feeRate = role == LiquidityRole.MAKER
          ? order.makerFeeRate : order.takerFeeRate;
      BigDecimal fee = s8(notional.multiply(feeRate));
      if (realized.signum() != 0) {
        changeWallet(realized);
      }
      if (fundingRealized.signum() != 0) {
        changeWallet(fundingRealized);
      }
      changeWallet(fee.negate());
      releaseOrderHold(order);
      recordMarginChange(slot, marginBefore, marginAfter, reversed);
      String tradeRef = order.ref + "-trade-1";
      if (realized.signum() != 0) {
        ledger("TRADE_PNL", realized, "POSITION", slotRef(slot), "TRADE_PNL");
      }
      if (fundingRealized.signum() != 0) {
        ledger(
            "FUNDING_FEE",
            fundingRealized,
            "POSITION",
            slotRef(slot),
            "FUNDING_FEE");
      }
      ledger("TRADE_FEE", fee.negate(), "TRADE", tradeRef, "TRADE_FEE");

      order.fill(price, fee, role);
      PositionSide tradeSide = resulting == null ? slot : resulting.slot;
      trades.add(new TradeState(
          tradeRef,
          order.ref,
          order.side,
          quantity,
          price,
          notional,
          fee,
          "USDT",
          realized,
          ProductType.LINEAR_PERP,
          tradeSide,
          scenario.marginMode(),
          role,
          order.clientOrderId));
      boolean waitsForCrossLiquidationSettlement =
          "USER_CLOSE_COMPETES_WITH_LIQUIDATION".equals(
              action.parameters().condition());
      if (positions.values().stream().noneMatch(MutablePosition::open)
          && !waitsForCrossLiquidationSettlement) {
        capBankruptcyShortfall(
            "ORDER",
            order.ref,
            action.parameters().actionId(),
            trace);
      }
      if ("ATTACHED_TO_ENTRY".equals(action.parameters().condition())
          && resulting != null
          && resulting.open()) {
        createProtection(
            action,
            quantity.min(resulting.quantity),
            resulting,
            action.parameters().protectionId(),
            order.ref,
            market,
            trace);
      }
      reconcileProtections(slot);
      event("ORDER_FILLED", order.ref, fillFromStatus, "FILLED", "");
      trace.add(action.parameters().actionId()
          + ": base=" + quantity
          + ", fill=" + price
          + ", realized=" + realized
          + ", fee=" + fee);
      return null;
    }

    private BigDecimal isolatedFundingRealized(
        MutablePosition position,
        BigDecimal quantity,
        boolean reduceOnly
    ) {
      if (!reduceOnly
          || position == null
          || !position.open()
          || position.marginMode != MarginMode.ISOLATED
          || position.fundingPnl.signum() == 0) {
        return zero();
      }
      BigDecimal closedQuantity = position.quantity.min(quantity);
      return position.fundingPnl.multiply(closedQuantity)
          .divide(position.quantity, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal fillPrice(MutableOrder order, ScenarioPriceStep market) {
      if (order.type == OrderType.LIMIT && order.limitPrice != null) {
        return s8(order.side == OrderSide.BUY
            ? market.ask().min(order.limitPrice)
            : market.bid().max(order.limitPrice));
      }
      BigDecimal reference = order.side == OrderSide.BUY ? market.ask() : market.bid();
      BigDecimal slippage = reference.multiply(order.slippageRate);
      return s8(order.side == OrderSide.BUY
          ? reference.add(slippage)
          : reference.subtract(slippage));
    }

    private void releaseOrderHold(MutableOrder order) {
      if (order.holdAmount.signum() == 0) {
        return;
      }
      BigDecimal released = order.holdAmount;
      order.holdAmount = zero();
      ledger("ORDER_RELEASE", released, "ORDER", order.ref, "ORDER_RELEASE");
    }

    private void recordMarginChange(
        PositionSide slot,
        BigDecimal before,
        BigDecimal after,
        boolean reversed
    ) {
      if (reversed) {
        if (before.signum() > 0) {
          ledger("MARGIN_RELEASE", before, "POSITION", slotRef(slot), "MARGIN_RELEASE");
        }
        if (after.signum() > 0) {
          ledger("MARGIN_HOLD", after, "POSITION", slotRef(slot), "MARGIN_HOLD");
        }
        return;
      }
      BigDecimal delta = s8(after.subtract(before));
      if (delta.signum() > 0) {
        ledger("MARGIN_HOLD", delta, "POSITION", slotRef(slot), "MARGIN_HOLD");
      } else if (delta.signum() < 0) {
        ledger(
            "MARGIN_RELEASE",
            delta.negate(),
            "POSITION",
            slotRef(slot),
            "MARGIN_RELEASE");
      }
    }

    private void changeWallet(BigDecimal delta) {
      walletBalance = s8(walletBalance.add(delta));
    }

    private BigDecimal transition(
        ScenarioAction action,
        BigDecimal quantity,
        BigDecimal price,
        boolean close
    ) {
      PositionSide desired = action.direction();
      PositionSide slot = slot(desired);
      MutablePosition position = positions.get(slot);
      if (position == null) {
        if (close) {
          return zero();
        }
        positions.put(slot, MutablePosition.open(
            slot,
            desired,
            scenario.positionMode(),
            scenario.marginMode(),
            quantity,
            price,
            settingLeverage));
        return zero();
      }

      if (close) {
        return position.close(quantity, price);
      }
      if (scenario.positionMode() == PositionMode.HEDGE
          || !position.open()
          || position.direction == desired) {
        position.add(desired, quantity, price, settingLeverage);
        return zero();
      }

      BigDecimal oldQuantity = position.quantity;
      BigDecimal closedQuantity = oldQuantity.min(quantity);
      BigDecimal realized = position.close(closedQuantity, price);
      if (quantity.compareTo(oldQuantity) > 0) {
        position.reopen(desired, s8(quantity.subtract(oldQuantity)), price, settingLeverage);
      }
      return realized;
    }

    private FailureState changeLeverage(ScenarioAction action, List<String> trace) {
      int target = Objects.requireNonNull(action.parameters().leverage(), "leverage");
      if (target < 1 || target > 100) {
        reject(action, "LEVERAGE_OUT_OF_RANGE");
        return new FailureState("LEVERAGE_OUT_OF_RANGE", "BusinessException", true);
      }
      int previousSetting = settingLeverage;
      boolean changedPosition = false;
      for (MutablePosition position : positions.values()) {
        if (!position.open()) {
          continue;
        }
        changedPosition = true;
        int previousLeverage = position.leverage;
        BigDecimal marginBefore = position.marginHeld();
        BigDecimal oldInitial = position.initialMargin;
        position.leverage = target;
        position.recomputeInitialMargin();
        BigDecimal delta = s8(position.initialMargin.subtract(oldInitial));
        recordMarginChange(position.slot, marginBefore, position.marginHeld(), false);
        trace.add(action.parameters().actionId()
            + ": leverage=" + target + ", deltaIM=" + delta);
      }
      settingLeverage = target;
      if (!changedPosition) {
        trace.add(action.parameters().actionId() + ": leverage setting=" + target);
      }
      return null;
    }

    private FailureState adjustMargin(ScenarioAction action, List<String> trace) {
      MutablePosition position = positions.get(slot(action.direction()));
      if (position == null || !position.open()) {
        return null;
      }
      BigDecimal delta = s8(action.parameters().marginDelta());
      BigDecimal nextHeld = s8(position.marginHeld().add(delta));
      BigDecimal closeFee = s8(position.markNotional.multiply(DEFAULT_TAKER_FEE_RATE));
      BigDecimal nextEquity = s8(nextHeld.add(position.fundingPnl).add(position.unrealizedPnl));
      BigDecimal threshold = s8(position.maintenanceMargin.add(closeFee));
      if (delta.signum() < 0
          && (nextHeld.signum() < 0 || nextEquity.compareTo(threshold) <= 0)) {
        trace.add(action.parameters().actionId() + ": unsafe isolated margin reduction");
        return new FailureState("MARGIN_REDUCTION_UNSAFE", "BusinessException", true);
      }
      BigDecimal heldBefore = position.marginHeld();
      position.manualMargin = s8(position.manualMargin.add(delta));
      recordMarginChange(position.slot, heldBefore, position.marginHeld(), false);
      trace.add(action.parameters().actionId()
          + ": marginDelta=" + delta + ", marginHeld=" + position.marginHeld());
      return null;
    }

    private void settleFunding(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      MutablePosition position = positions.get(slot(action.direction()));
      BigDecimal cashFlow = zero();
      if (position != null && position.open()) {
        BigDecimal notional = s8(position.quantity.abs().multiply(market.mark()));
        BigDecimal signed = s8(notional.multiply(action.parameters().fundingRate()));
        cashFlow = position.direction == PositionSide.SHORT ? signed : signed.negate();
        cashFlow = s8(cashFlow);
        BigDecimal fundingPool = position.marginMode == MarginMode.ISOLATED
            ? s8(position.marginHeld().add(position.fundingPnl).max(BigDecimal.ZERO))
            : s8(walletBalance.max(BigDecimal.ZERO));
        BigDecimal shortfall =
            s8(fundingPool.add(cashFlow).negate().max(BigDecimal.ZERO));
        BigDecimal appliedCashFlow = s8(cashFlow.add(shortfall));
        position.fundingPnl = s8(position.fundingPnl.add(appliedCashFlow));
        if (position.marginMode == MarginMode.CROSS) {
          changeWallet(appliedCashFlow);
        }
        if (cashFlow.signum() != 0) {
          ledger(
              "FUNDING_FEE",
              cashFlow,
              "FUNDING_SETTLEMENT",
              action.parameters().actionId(),
              "FUNDING_FEE");
          if (shortfall.signum() > 0) {
            bankruptcyShortfall = s8(bankruptcyShortfall.add(shortfall));
            ledger(
                "BANKRUPTCY_SHORTFALL",
                shortfall,
                "FUNDING_SETTLEMENT",
                action.parameters().actionId(),
                "BANKRUPTCY_SHORTFALL");
          }
        }
      }
      trace.add(action.parameters().actionId() + ": fundingCashFlow=" + cashFlow);
    }

    private FailureState setProtection(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      long activeCount = protections.stream().filter(MutableProtection::active).count();
      if (activeCount >= 10) {
        reject(action, "PROTECTION_LIMIT_EXCEEDED");
        return new FailureState(
            "PROTECTION_LIMIT_EXCEEDED", "BusinessException", true);
      }
      PositionSide slot = slot(action.direction());
      BigDecimal quantity = normalizeQuantity(action, market);
      MutablePosition position = positions.get(slot);
      createProtection(
          action,
          quantity,
          position,
          action.parameters().orderId(),
          slotRef(slot),
          market,
          trace);
      reconcileProtectionBudget(slot, action.parameters().protectionType());
      return null;
    }

    private void createProtection(
        ScenarioAction action,
        BigDecimal quantity,
        MutablePosition position,
        String carrierRef,
        String parentRef,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      PositionSide slot = slot(action.direction());
      MutableProtection protection = new MutableProtection(
          action.parameters().protectionId(),
          carrierRef,
          action.parameters().protectionType(),
          slot,
          quantity,
          s8(action.parameters().triggerPrice()),
          action.parameters().price() == null ? null : s8(action.parameters().price()),
          action.parameters().triggerExecutionType(),
          action.parameters().triggerPriceType(),
          ++protectionSequence);
      protections.add(protection);

      MutableOrder order =
          MutableOrder.protection(action, quantity, protection.ref, slot, carrierRef);
      order.parentRef = parentRef;
      order.holdAsset = "USDT";
      order.holdAmount = zero();
      orders.put(order.ref, order);
      event(
          "PROTECTION_CREATED",
          protection.ref,
          "",
          "PENDING_ACTIVATION",
          "");
      if ("ATTACHED_TO_ENTRY".equals(action.parameters().condition())) {
        event(
            "PROTECTION_ACTIVATED",
            protection.ref,
            "PENDING_ACTIVATION",
            "PENDING_ACTIVATION",
            "");
      }
      trace.add(action.parameters().actionId()
          + ": protection=" + protection.ref + ", quantity=" + protection.quantity);
    }

    private BigDecimal protectionHold(
        MutablePosition position,
        BigDecimal quantity,
        ScenarioAction action,
        ScenarioPriceStep market
    ) {
      BigDecimal authority = action.parameters().price() == null
          ? action.parameters().triggerPrice() : action.parameters().price();
      if (authority == null) {
        authority = market.mark();
      }
      BigDecimal notional = s8(quantity.multiply(authority));
      BigDecimal fee = s8(notional.multiply(takerFeeRate(action.parameters())));
      if (position == null || !position.open()) {
        return fee;
      }
      BigDecimal adverse = position.direction == PositionSide.SHORT
          ? authority.subtract(position.averageEntry).max(BigDecimal.ZERO)
          : position.averageEntry.subtract(authority).max(BigDecimal.ZERO);
      return s8(adverse.multiply(quantity.min(position.quantity)).add(fee));
    }

    private void reconcileProtections(PositionSide slot) {
      for (ProtectionType type : ProtectionType.values()) {
        reconcileProtectionBudget(slot, type);
      }
    }

    private void reconcileProtectionBudget(PositionSide slot, ProtectionType type) {
      MutablePosition position = positions.get(slot);
      BigDecimal budget = position == null || !position.open()
          ? zero() : position.quantity;
      List<MutableProtection> active = protections.stream()
          .filter(protection -> protection.slot == slot)
          .filter(protection -> protection.type == type)
          .filter(MutableProtection::active)
          .sorted(Comparator.comparingInt(
              (MutableProtection protection) -> protection.createdSequence).reversed())
          .toList();
      BigDecimal total = active.stream()
          .map(protection -> protection.quantity)
          .reduce(zero(), BigDecimal::add);
      BigDecimal excess = s8(total.subtract(budget).max(BigDecimal.ZERO));
      for (MutableProtection protection : active) {
        if (excess.signum() == 0) {
          break;
        }
        BigDecimal reduction = protection.quantity.min(excess);
        BigDecimal previous = protection.quantity;
        protection.quantity = s8(protection.quantity.subtract(reduction));
        excess = s8(excess.subtract(reduction));
        MutableOrder order = orders.get(protection.orderRef);
        if (order != null) {
          order.quantity = protection.quantity;
          order.remainingQuantity = protection.quantity;
          BigDecimal nextHold = previous.signum() == 0
              ? zero()
              : s8(order.holdAmount.multiply(protection.quantity).divide(
                  previous, DIVISION_SCALE, RoundingMode.HALF_UP));
          BigDecimal released = s8(order.holdAmount.subtract(nextHold));
          order.holdAmount = nextHold;
          if (released.signum() > 0) {
            ledger(
                "ORDER_RELEASE",
                released,
                "ORDER",
                order.ref,
                "ORDER_RELEASE");
          }
        }
        if (protection.quantity.signum() == 0) {
          protection.status = "EXPIRED";
          if (order != null) {
            order.status = "EXPIRED";
          }
          event(
              "PROTECTION_EXPIRED",
              protection.ref,
              "PENDING_ACTIVATION",
              "EXPIRED",
              "");
        } else {
          event(
              "PROTECTION_RESIZED",
              protection.ref,
              "PENDING_ACTIVATION",
              "PENDING_ACTIVATION",
              "");
        }
      }
    }

    private FailureState modify(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      MutableOrder order = orders.get(action.parameters().orderId());
      if (order == null || !"PENDING".equals(order.status)) {
        reject(action, "ORDER_NOT_CANCELABLE");
        return new FailureState("ORDER_NOT_CANCELABLE", "BusinessException", true);
      }
      BigDecimal previousHold = order.holdAmount;
      order.limitPrice = action.parameters().price();
      ScenarioAction synthetic = actionForOrder(order, action);
      BigDecimal nextHold = orderHold(synthetic, order.remainingQuantity, market);
      BigDecimal delta = s8(nextHold.subtract(previousHold));
      order.holdAmount = nextHold;
      if (delta.signum() > 0) {
        ledger("ORDER_HOLD", delta, "ORDER", order.ref, "ORDER_HOLD");
      } else if (delta.signum() < 0) {
        ledger("ORDER_RELEASE", delta.negate(), "ORDER", order.ref, "ORDER_RELEASE");
      }
      event("ORDER_MODIFIED", order.ref, "PENDING", "PENDING", "");
      trace.add(action.parameters().actionId() + ": modified " + order.ref);
      if (marketable(order, market)) {
        return fillOrder(
            synthetic, market, trace, order, LiquidityRole.TAKER, "PENDING");
      }
      return null;
    }

    private void cancel(ScenarioAction action, List<String> trace) {
      MutableOrder order = orders.get(action.parameters().orderId());
      if (order == null || !"PENDING".equals(order.status)) {
        trace.add(action.parameters().actionId() + ": cancel found no pending order");
        return;
      }
      cancelOrder(order);
      trace.add(action.parameters().actionId() + ": canceled " + order.ref);
    }

    private void cancelOrder(MutableOrder order) {
      releaseOrderHold(order);
      order.status = "CANCELED";
      order.remainingQuantity = zero();
      protections.stream()
          .filter(protection -> protection.orderRef.equals(order.ref))
          .filter(MutableProtection::active)
          .forEach(protection -> protection.status = "EXPIRED");
      event("ORDER_CANCELED", order.ref, "PENDING", "CANCELED", "");
    }

    private FailureState trigger(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      MutableOrder order = orders.get(action.parameters().orderId());
      if (order == null
          || !("PENDING".equals(order.status)
          || "PENDING_ACTIVATION".equals(order.status))) {
        trace.add(action.parameters().actionId() + ": trigger already consumed");
        return null;
      }
      MutableProtection protection = protections.stream()
          .filter(candidate -> candidate.orderRef.equals(order.ref))
          .findFirst()
          .orElse(null);
      ScenarioAction synthetic;
      if (protection != null) {
        order.type = protection.executionType == TriggerExecutionType.LIMIT
            ? OrderType.LIMIT : OrderType.MARKET;
        synthetic = actionForOrder(order, action);
        order.holdAmount = orderHold(synthetic, order.remainingQuantity, market);
        ledger("ORDER_HOLD", order.holdAmount, "ORDER", order.ref, "ORDER_HOLD");
        if (protection.executionType == TriggerExecutionType.LIMIT
            && !marketable(order, market)) {
          order.status = "PENDING";
          event(
              "PROTECTION_TRIGGERED",
              protection.ref,
              "PENDING_ACTIVATION",
              "PENDING",
              "");
          trace.add(action.parameters().actionId()
              + ": protection limit triggered and remains pending");
          return null;
        }
        protection.status = "TRIGGERING";
        order.status = "ACCEPTED";
        event(
            "PROTECTION_TRIGGERED",
            protection.ref,
            "PENDING_ACTIVATION",
            "ACCEPTED",
            "");
      } else {
        synthetic = actionForOrder(order, action);
        BigDecimal requiredHold = orderHold(
            synthetic,
            order.remainingQuantity,
            market);
        BigDecimal increase = s8(requiredHold.subtract(order.holdAmount)
            .max(BigDecimal.ZERO));
        if (increase.signum() > 0) {
          order.holdAmount = s8(order.holdAmount.add(increase));
          ledger("ORDER_HOLD", increase, "ORDER", order.ref, "ORDER_HOLD");
        }
      }
      FailureState failure = fillOrder(
          synthetic,
          market,
          trace,
          order,
          protection == null && order.type == OrderType.LIMIT
              ? LiquidityRole.MAKER : LiquidityRole.TAKER,
          protection == null ? "WORKING" : "ACCEPTED");
      if (protection != null && failure == null) {
        protection.status = "FILLED";
      }
      return failure;
    }

    private boolean marketable(MutableOrder order, ScenarioPriceStep market) {
      boolean limitExecution = order.type == OrderType.LIMIT
          || order.triggerExecutionType == TriggerExecutionType.LIMIT;
      if (!limitExecution || order.limitPrice == null) {
        return order.type == OrderType.MARKET
            || order.triggerExecutionType == TriggerExecutionType.MARKET;
      }
      return order.side == OrderSide.BUY
          ? order.limitPrice.compareTo(market.ask()) >= 0
          : order.limitPrice.compareTo(market.bid()) <= 0;
    }

    private void replay(ScenarioAction action, List<String> trace) {
      trace.add(action.parameters().actionId() + ": exact replay, no new mutation");
    }

    private void cancelAll(ScenarioAction action, List<String> trace) {
      orders.values().stream()
          .filter(order -> "PENDING".equals(order.status))
          .toList()
          .forEach(this::cancelOrder);
      trace.add(action.parameters().actionId() + ": canceled all pending orders");
    }

    private void closeAll(
        ScenarioAction action,
        ScenarioPriceStep market,
        String origin,
        List<String> trace
    ) {
      boolean injectStaleMember =
          "ONE_SLOT_VERSION_BECOMES_STALE".equals(action.parameters().condition());
      int memberIndex = 0;
      for (MutablePosition position : positions.values().stream()
          .filter(MutablePosition::open)
          .toList()) {
        if (injectStaleMember && memberIndex++ > 0) {
          trace.add(action.parameters().actionId()
              + ": stale batch member preserved " + position.slot);
          continue;
        }
        String ref = action.parameters().actionId() + "-" + position.slot;
        ScenarioAction close = systemCloseAction(action, position, ref);
        marketFill(close, market, trace);
        MutableOrder order = orders.get(ref);
        if (order != null) {
          order.origin = origin;
        }
      }
      trace.add(action.parameters().actionId() + ": closed all open slots");
    }

    private void adminForceClose(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      MutablePosition position = positions.get(slot(action.direction()));
      if (position == null || !position.open()) {
        trace.add(action.parameters().actionId() + ": no open slot for admin close");
        return;
      }
      String ref = action.parameters().actionId() + "-order";
      ScenarioAction close = systemCloseAction(action, position, ref);
      marketFill(close, market, trace);
      MutableOrder order = orders.get(ref);
      if (order != null) {
        order.origin = "ADMIN_FORCE_CLOSE";
      }
      ledger("FORCED_CLOSE", zero(), "POSITION", slotRef(position.slot), "FORCED_CLOSE");
    }

    private void race(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace,
        boolean competitorWins
    ) {
      MutablePosition position = positions.get(slot(action.direction()));
      if (position == null || !position.open()) {
        trace.add(action.parameters().actionId() + ": race observed an already closed slot");
        return;
      }
      if (competitorWins) {
        String competitor = action.parameters().competingOrderId();
        switch (action.parameters().condition()) {
          case "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL" -> {
            ScenarioAction close = systemCloseAction(action, position, competitor);
            marketFill(close, market, trace);
            MutableOrder order = orders.get(competitor);
            if (order != null) {
              order.origin = "BATCH_CLOSE";
            }
          }
          case "USER_CLOSE_COMPETES_WITH_LIQUIDATION" ->
              liquidate(withOrderRef(action, competitor), market, trace);
          case "USER_CLOSE_COMPETES_WITH_STOP_LOSS" ->
              trigger(withOrderRef(action, competitor), market, trace);
          default -> throw new IllegalArgumentException(
              "Unsupported competitor race " + action.parameters().condition());
        }
        trace.add(action.parameters().actionId() + ": winner=" + competitor);
        return;
      }
      ScenarioAction winner =
          systemCloseAction(action, position, action.parameters().orderId());
      marketFill(winner, market, trace);
      if ("USER_CLOSE_COMPETES_WITH_LIQUIDATION".equals(
          action.parameters().condition())) {
        settleCrossLiquidation(action, trace);
      }
      MutableOrder order = orders.get(action.parameters().orderId());
      trace.add(action.parameters().actionId()
          + ": winner=" + action.parameters().orderId());
    }

    private ScenarioAction withOrderRef(
        ScenarioAction action,
        String orderRef
    ) {
      Parameters value = action.parameters();
      return new ScenarioAction(
          action.type(),
          action.direction(),
          action.quantity(),
          new Parameters(
              value.actionId(),
              orderRef,
              orderRef,
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
              value.failureCondition()),
          action.detail());
    }

    private ScenarioAction actionForOrder(
        MutableOrder order,
        ScenarioAction cause
    ) {
      return new ScenarioAction(
          order.reduceOnly ? ScenarioAction.Type.PARTIAL_CLOSE : ScenarioAction.Type.ADD,
          order.direction,
          order.remainingQuantity,
          new Parameters(
              cause.parameters().actionId(),
              order.clientOrderId,
              order.ref,
              "",
              order.parentRef,
              order.limitPrice,
              order.triggerPrice,
              null,
              null,
              null,
              order.takerFeeRate,
              order.makerFeeRate,
              order.takerFeeRate,
              order.takerFeeRate,
              order.slippageRate,
              DEFAULT_MMR,
              DEFAULT_LIQUIDATION_FEE_RATE,
              QuantityUnit.BASE,
              order.reduceOnly,
              order.side,
              order.type,
              order.protectionType,
              order.triggerExecutionType,
              order.triggerPriceType,
              cause.parameters().condition(),
              ""),
          "TRIGGERED_ORDER");
    }

    private ScenarioAction systemCloseAction(
        ScenarioAction cause,
        MutablePosition position,
        String orderRef
    ) {
      return new ScenarioAction(
          ScenarioAction.Type.FULL_CLOSE,
          position.direction,
          position.quantity,
          new Parameters(
              cause.parameters().actionId(),
              orderRef,
              orderRef,
              "",
              "",
              null,
              null,
              null,
              null,
              null,
              DEFAULT_TAKER_FEE_RATE,
              DEFAULT_MAKER_FEE_RATE,
              DEFAULT_TAKER_FEE_RATE,
              DEFAULT_TAKER_FEE_RATE,
              DEFAULT_SLIPPAGE_RATE,
              DEFAULT_MMR,
              DEFAULT_LIQUIDATION_FEE_RATE,
              QuantityUnit.BASE,
              true,
              position.direction == PositionSide.SHORT ? OrderSide.BUY : OrderSide.SELL,
              OrderType.MARKET,
              null,
              null,
              null,
              cause.parameters().condition(),
              ""),
          "SYSTEM_CLOSE");
    }

    private void liquidate(
        ScenarioAction action,
        ScenarioPriceStep market,
        List<String> trace
    ) {
      BigDecimal riskMark = action.parameters().triggerPrice() == null
          ? s8(market.mark()) : s8(action.parameters().triggerPrice());
      for (MutablePosition candidate : positions.values()) {
        candidate.revalue(riskMark, maintenanceRate(action.parameters()));
      }

      List<MutablePosition> candidates =
          scenario.positionMode() == PositionMode.HEDGE
              && action.direction() == PositionSide.BOTH
              ? positions.values().stream()
                  .filter(MutablePosition::open)
                  .sorted(Comparator.comparing(position -> position.slot))
                  .toList()
              : positions.values().stream()
                  .filter(position -> position.slot == slot(action.direction()))
                  .filter(MutablePosition::open)
                  .sorted(Comparator.comparing(position -> position.slot))
                  .toList();
      if (candidates.isEmpty()) {
        PositionSide checkedSlot = slot(action.direction());
        trace.add(action.parameters().actionId() + ": liquidation check safe");
        return;
      }

      boolean multipleCandidates = candidates.size() > 1;
      for (MutablePosition position : candidates) {
        if (!position.open()) {
          continue;
        }
        if (!liquidatable(position, action.parameters())) {
          trace.add(action.parameters().actionId()
              + ": " + position.slot
              + " equity remains above maintenance and close fees");
          continue;
        }
        CrossLiquidationCharge crossCharge = liquidatePosition(
            action,
            position,
            liquidationFillPrice(action, position, market),
            multipleCandidates,
            trace);
        if (crossCharge != null) {
          pendingCrossLiquidationCharges.add(crossCharge);
        }
      }

      if (!pendingCrossLiquidationCharges.isEmpty()) {
        settleCrossLiquidation(action, trace);
      }
    }

    private BigDecimal liquidationFillPrice(
        ScenarioAction action,
        MutablePosition position,
        ScenarioPriceStep market
    ) {
      BigDecimal reference = position.direction == PositionSide.SHORT
          ? market.ask()
          : market.bid();
      BigDecimal slippage = reference.multiply(slippageRate(action.parameters()));
      return s8(position.direction == PositionSide.SHORT
          ? reference.add(slippage)
          : reference.subtract(slippage));
    }

    private CrossLiquidationCharge liquidatePosition(
        ScenarioAction action,
        MutablePosition position,
        BigDecimal executionPrice,
        boolean qualifyOrderRef,
        List<String> trace
    ) {
      PositionSide slot = position.slot;
      BigDecimal quantity = position.quantity;
      BigDecimal marginReleased = position.marginHeld();
      BigDecimal balanceBeforeFill = walletBalance;
      BigDecimal isolatedFunding = position.marginMode == MarginMode.ISOLATED
          ? position.fundingPnl : zero();
      MarginMode marginMode = position.marginMode;
      PositionSide direction = position.direction;
      BigDecimal realized = position.close(quantity, executionPrice);
      if (marginMode == MarginMode.ISOLATED) {
        position.fundingPnl = zero();
      }
      BigDecimal notional = s8(quantity.multiply(executionPrice));
      BigDecimal takerFee = s8(notional.multiply(takerFeeRate(action.parameters())));
      BigDecimal liquidationFee =
          s8(notional.multiply(liquidationFeeRate(action.parameters())));
      String orderRef = qualifyOrderRef
          ? action.parameters().orderId() + "-" + slot
          : action.parameters().orderId();

      MutableOrder order = MutableOrder.liquidation(
          orderRef,
          direction == PositionSide.SHORT ? OrderSide.BUY : OrderSide.SELL,
          slot,
          quantity,
          executionPrice,
          takerFee);
      orders.put(order.ref, order);
      String tradeRef = order.ref + "-trade-1";
      trades.add(new TradeState(
          tradeRef,
          order.ref,
          order.side,
          quantity,
          executionPrice,
          notional,
          takerFee,
          "USDT",
          realized,
          ProductType.LINEAR_PERP,
          slot,
          scenario.marginMode(),
          LiquidityRole.TAKER,
          orderRef));
      if (realized.signum() != 0) {
        changeWallet(realized);
      }
      if (isolatedFunding.signum() != 0) {
        changeWallet(isolatedFunding);
      }
      changeWallet(takerFee.negate());
      ledger(
          "MARGIN_RELEASE",
          marginReleased,
          "POSITION",
          slotRef(slot),
          "MARGIN_RELEASE");
      if (realized.signum() != 0) {
        ledger("TRADE_PNL", realized, "POSITION", slotRef(slot), "TRADE_PNL");
      }
      if (isolatedFunding.signum() != 0) {
        ledger(
            "FUNDING_FEE",
            isolatedFunding,
            "POSITION",
            slotRef(slot),
            "FUNDING_FEE");
      }
      ledger(
          "TRADE_FEE",
          takerFee.negate(),
          "TRADE",
          tradeRef,
          "TRADE_FEE");
      CrossLiquidationCharge crossCharge = null;
      if (marginMode == MarginMode.ISOLATED) {
        settleIsolatedLiquidation(
            action,
            slot,
            orderRef,
            balanceBeforeFill,
            marginReleased,
            liquidationFee,
            trace);
      } else {
        crossCharge = new CrossLiquidationCharge(slot, orderRef, liquidationFee);
      }
      ledger("FORCED_CLOSE", zero(), "POSITION", slotRef(slot), "FORCED_CLOSE");
      reconcileProtections(slot);
      event("ORDER_FILLED", orderRef, "ACCEPTED", "FILLED", "");
      trace.add(action.parameters().actionId()
          + ": slot=" + slot
          + ": realized=" + realized
          + ", takerFee=" + takerFee
          + ", liquidationFee=" + liquidationFee);
      return crossCharge;
    }

    private void settleIsolatedLiquidation(
        ScenarioAction action,
        PositionSide slot,
        String orderRef,
        BigDecimal balanceBeforeFill,
        BigDecimal marginBeforeFill,
        BigDecimal contractualFee,
        List<String> trace
    ) {
      BigDecimal coreDebit =
          s8(balanceBeforeFill.subtract(walletBalance).max(BigDecimal.ZERO));
      BigDecimal coreShortfall =
          s8(coreDebit.subtract(marginBeforeFill).max(BigDecimal.ZERO));
      if (coreShortfall.signum() > 0) {
        changeWallet(coreShortfall);
      }
      BigDecimal balanceFloorShortfall =
          s8(walletBalance.negate().max(BigDecimal.ZERO));
      if (balanceFloorShortfall.signum() > 0) {
        changeWallet(balanceFloorShortfall);
        coreShortfall = s8(coreShortfall.add(balanceFloorShortfall));
      }

      BigDecimal remainingCapacity =
          s8(marginBeforeFill.subtract(coreDebit).max(BigDecimal.ZERO));
      BigDecimal chargedFee = s8(contractualFee
          .min(remainingCapacity)
          .min(walletBalance.max(BigDecimal.ZERO)));
      if (chargedFee.signum() > 0) {
        changeWallet(chargedFee.negate());
        ledger(
            "LIQUIDATION_FEE",
            chargedFee.negate(),
            "POSITION",
            slotRef(slot),
            "LIQUIDATION_FEE");
      }

      BigDecimal feeShortfall =
          s8(contractualFee.subtract(chargedFee).max(BigDecimal.ZERO));
      BigDecimal totalShortfall = s8(coreShortfall.add(feeShortfall));
      if (totalShortfall.signum() > 0) {
        bankruptcyShortfall = s8(bankruptcyShortfall.add(totalShortfall));
        ledger(
            "BANKRUPTCY_SHORTFALL",
            totalShortfall,
            "LIQUIDATION_ORDER",
            orderRef,
            "BANKRUPTCY_SHORTFALL");
        trace.add(action.parameters().actionId()
            + ": bankruptcyShortfall=" + bankruptcyShortfall);
      }
    }

    private void settleCrossLiquidation(
        ScenarioAction action,
        List<String> trace
    ) {
      BigDecimal protectedIsolatedMargin = positions.values().stream()
          .filter(MutablePosition::open)
          .filter(position -> position.marginMode == MarginMode.ISOLATED)
          .map(MutablePosition::marginHeld)
          .reduce(zero(), BigDecimal::add);
      protectedIsolatedMargin = s8(protectedIsolatedMargin);
      BigDecimal coreShortfall = s8(
          protectedIsolatedMargin.subtract(walletBalance).max(BigDecimal.ZERO));
      if (coreShortfall.signum() > 0) {
        changeWallet(coreShortfall);
      }

      boolean hasOpenCross = positions.values().stream()
          .filter(MutablePosition::open)
          .anyMatch(position -> position.marginMode == MarginMode.CROSS);
      if (hasOpenCross) {
        recordCrossLiquidationShortfall(action, coreShortfall, trace);
        return;
      }

      List<CrossLiquidationCharge> charges = pendingCrossLiquidationCharges.stream()
          .sorted(Comparator.comparing(CrossLiquidationCharge::slot)
              .thenComparing(CrossLiquidationCharge::orderRef))
          .toList();
      BigDecimal totalFee = s8(charges.stream()
          .map(CrossLiquidationCharge::feeDue)
          .reduce(zero(), BigDecimal::add));
      BigDecimal feeCapacity = s8(
          walletBalance.subtract(protectedIsolatedMargin).max(BigDecimal.ZERO));
      BigDecimal chargedFee = s8(totalFee.min(feeCapacity));
      BigDecimal remainingCharge = chargedFee;
      for (CrossLiquidationCharge charge : charges) {
        BigDecimal itemCharge = s8(charge.feeDue().min(remainingCharge));
        if (itemCharge.signum() > 0) {
          changeWallet(itemCharge.negate());
          ledger(
              "LIQUIDATION_FEE",
              itemCharge.negate(),
              "POSITION",
              slotRef(charge.slot()),
              "LIQUIDATION_FEE");
        }
        remainingCharge = s8(remainingCharge.subtract(itemCharge));
      }

      BigDecimal feeShortfall =
          s8(totalFee.subtract(chargedFee).max(BigDecimal.ZERO));
      recordCrossLiquidationShortfall(
          action,
          s8(coreShortfall.add(feeShortfall)),
          trace);
      pendingCrossLiquidationCharges.clear();
    }

    private void recordCrossLiquidationShortfall(
        ScenarioAction action,
        BigDecimal shortfall,
        List<String> trace
    ) {
      if (shortfall.signum() <= 0) {
        return;
      }
      bankruptcyShortfall = s8(bankruptcyShortfall.add(shortfall));
      ledger(
          "BANKRUPTCY_SHORTFALL",
          shortfall,
          "CROSS_LIQUIDATION_SETTLEMENT",
          action.parameters().actionId(),
          "BANKRUPTCY_SHORTFALL");
      trace.add(action.parameters().actionId()
          + ": bankruptcyShortfall=" + bankruptcyShortfall);
    }

    private boolean liquidatable(
        MutablePosition target,
        Parameters parameters
    ) {
      BigDecimal closeRate = takerFeeRate(parameters);
      BigDecimal liquidationRate = liquidationFeeRate(parameters);
      if (target.marginMode == MarginMode.ISOLATED) {
        BigDecimal equity = s8(
            target.marginHeld().add(target.fundingPnl).add(target.unrealizedPnl));
        BigDecimal fees = s8(target.markNotional.multiply(
            closeRate.add(liquidationRate)));
        BigDecimal threshold = s8(target.maintenanceMargin.add(fees));
        return equity.compareTo(threshold) <= 0;
      }

      List<MutablePosition> cross = positions.values().stream()
          .filter(MutablePosition::open)
          .filter(position -> position.marginMode == MarginMode.CROSS)
          .toList();
      BigDecimal unrealized = cross.stream()
          .map(position -> position.unrealizedPnl)
          .reduce(zero(), BigDecimal::add);
      BigDecimal equity = s8(walletBalance.add(unrealized));
      BigDecimal threshold = cross.stream()
          .map(position -> s8(position.maintenanceMargin.add(
              position.markNotional.multiply(closeRate.add(liquidationRate)))))
          .reduce(zero(), BigDecimal::add);
      return equity.compareTo(s8(threshold)) <= 0;
    }

    private void capBankruptcyShortfall(
        String referenceType,
        String reference,
        String actionId,
        List<String> trace
    ) {
      if (walletBalance.signum() >= 0) {
        return;
      }
      BigDecimal shortfall = walletBalance.negate();
      walletBalance = zero();
      bankruptcyShortfall = s8(bankruptcyShortfall.add(shortfall));
      ledger(
          "BANKRUPTCY_SHORTFALL",
          shortfall,
          referenceType,
          reference,
          "BANKRUPTCY_SHORTFALL");
      trace.add(actionId + ": bankruptcyShortfall=" + bankruptcyShortfall);
    }

    private void ledger(
        String type,
        BigDecimal amount,
        String referenceType,
        String reference,
        String operationType
    ) {
      ledger.add(new LedgerState(
          ++ledgerSequence,
          type,
          "USDT",
          s8(amount),
          walletBalance,
          referenceType,
          reference,
          operationType));
    }

    private void event(
        String type,
        String reference,
        String from,
        String to,
        String code
    ) {
      events.add(new EventState(++eventSequence, type, reference, from, to, code));
    }

    private void revalue(ScenarioPriceStep market, BigDecimal maintenanceRate) {
      for (MutablePosition position : positions.values()) {
        position.revalue(market.mark(), maintenanceRate);
      }
    }

    private PositionSide slot(PositionSide direction) {
      return scenario.positionMode() == PositionMode.ONE_WAY
          ? PositionSide.BOTH : direction;
    }

    private Snapshot snapshot() {
      List<PositionState> positionStates = positions.values().stream()
          .sorted(Comparator.comparing(position -> position.slot))
          .map(MutablePosition::snapshot)
          .toList();
      BigDecimal positionMargin = positionStates.stream()
          .map(PositionState::marginHeld)
          .reduce(zero(), BigDecimal::add);
      BigDecimal pendingHolds = orders.values().stream()
          .filter(order -> "PENDING".equals(order.status))
          .map(order -> order.holdAmount)
          .reduce(zero(), BigDecimal::add);
      BigDecimal locked = s8(positionMargin.add(pendingHolds));
      BigDecimal available = s8(walletBalance.subtract(locked).max(BigDecimal.ZERO));
      BigDecimal isolatedPrincipal = positionStates.stream()
          .filter(position -> position.marginMode() == MarginMode.ISOLATED)
          .map(PositionState::marginHeld)
          .reduce(zero(), BigDecimal::add);
      BigDecimal crossPositionMargin = positionStates.stream()
          .filter(position -> position.marginMode() == MarginMode.CROSS)
          .map(PositionState::marginHeld)
          .reduce(zero(), BigDecimal::add);
      BigDecimal crossOrderHolds = orders.values().stream()
          .filter(order -> "PENDING".equals(order.status))
          .filter(order -> !isolatedPositionBacked(order))
          .map(order -> order.holdAmount)
          .reduce(zero(), BigDecimal::add);
      BigDecimal crossUpl = positionStates.stream()
          .filter(position -> position.marginMode() == MarginMode.CROSS)
          .map(PositionState::unrealizedPnl)
          .reduce(zero(), BigDecimal::add);
      BigDecimal crossAvailable = s8(walletBalance
          .subtract(isolatedPrincipal)
          .add(crossUpl)
          .subtract(crossPositionMargin)
          .subtract(crossOrderHolds));
      BigDecimal unrealized = positionStates.stream()
          .map(PositionState::unrealizedPnl)
          .reduce(zero(), BigDecimal::add);
      BigDecimal isolatedFunding = positionStates.stream()
          .filter(position -> position.marginMode() == MarginMode.ISOLATED)
          .map(PositionState::fundingPnl)
          .reduce(zero(), BigDecimal::add);
      BigDecimal equity = s8(walletBalance.add(unrealized).add(isolatedFunding));
      BigDecimal maintenance = positionStates.stream()
          .map(PositionState::maintenanceMargin)
          .reduce(zero(), BigDecimal::add);

      return new Snapshot(
          orders.values().stream()
              .sorted(Comparator.comparing(order -> order.ref))
              .map(MutableOrder::snapshot)
              .toList(),
          trades,
          positionStates,
          List.of(new WalletState("PERP", "USDT", walletBalance, available, locked)),
          new AccountState(
              walletBalance,
              equity,
              locked,
              crossAvailable,
              s8(maintenance),
              bankruptcyShortfall),
          ledger,
          protections.stream()
              .sorted(Comparator.comparingInt(protection -> protection.createdSequence))
              .map(MutableProtection::snapshot)
              .toList(),
          events);
    }

    private boolean isolatedPositionBacked(MutableOrder order) {
      if (scenario.marginMode() != MarginMode.ISOLATED
          || !order.reduceOnly
          || order.parentRef.isBlank()) {
        return false;
      }
      MutablePosition parent = positions.get(slot(order.direction));
      return parent != null
          && parent.open()
          && slotRef(parent.slot).equals(order.parentRef);
    }
  }

  private static final class MutablePosition {

    private final PositionSide slot;
    private final PositionMode positionMode;
    private final MarginMode marginMode;
    private PositionSide direction;
    private BigDecimal quantity;
    private BigDecimal averageEntry;
    private BigDecimal markPrice = zero();
    private BigDecimal realizedPnl = zero();
    private BigDecimal unrealizedPnl = zero();
    private BigDecimal fundingPnl = zero();
    private BigDecimal manualMargin = zero();
    private BigDecimal initialMargin = zero();
    private BigDecimal maintenanceMargin = zero();
    private BigDecimal markNotional = zero();
    private String parentOrderRef = "";
    private int leverage;

    private MutablePosition(
        PositionSide slot,
        PositionSide direction,
        PositionMode positionMode,
        MarginMode marginMode,
        BigDecimal quantity,
        BigDecimal averageEntry,
        int leverage
    ) {
      this.slot = slot;
      this.direction = direction;
      this.positionMode = positionMode;
      this.marginMode = marginMode;
      this.quantity = s8(quantity);
      this.averageEntry = s8(averageEntry);
      this.leverage = leverage;
      recomputeInitialMargin();
    }

    private static MutablePosition open(
        PositionSide slot,
        PositionSide direction,
        PositionMode positionMode,
        MarginMode marginMode,
        BigDecimal quantity,
        BigDecimal averageEntry,
        int leverage
    ) {
      return new MutablePosition(
          slot, direction, positionMode, marginMode, quantity, averageEntry, leverage);
    }

    private boolean open() {
      return quantity.signum() > 0;
    }

    private void add(
        PositionSide desired,
        BigDecimal addedQuantity,
        BigDecimal fillPrice,
        int targetLeverage
    ) {
      if (!open()) {
        reopen(desired, addedQuantity, fillPrice, targetLeverage);
        return;
      }
      BigDecimal nextQuantity = s8(quantity.add(addedQuantity));
      BigDecimal weighted = quantity.multiply(averageEntry)
          .add(addedQuantity.multiply(fillPrice));
      averageEntry = s8(weighted.divide(
          nextQuantity, DIVISION_SCALE, RoundingMode.HALF_UP));
      quantity = nextQuantity;
      direction = desired;
      leverage = targetLeverage;
      recomputeInitialMargin();
    }

    private BigDecimal close(BigDecimal requestedQuantity, BigDecimal fillPrice) {
      BigDecimal closedQuantity = quantity.min(requestedQuantity);
      BigDecimal realized = directionalPnl(
          direction, averageEntry, fillPrice, closedQuantity);
      realizedPnl = s8(realizedPnl.add(realized));
      quantity = s8(quantity.subtract(closedQuantity));
      if (!open()) {
        quantity = zero();
        averageEntry = zero();
        unrealizedPnl = zero();
        initialMargin = zero();
        maintenanceMargin = zero();
        markNotional = zero();
        manualMargin = zero();
      } else {
        recomputeInitialMargin();
      }
      return realized;
    }

    private void reopen(
        PositionSide desired,
        BigDecimal nextQuantity,
        BigDecimal fillPrice,
        int targetLeverage
    ) {
      direction = desired;
      quantity = s8(nextQuantity);
      averageEntry = s8(fillPrice);
      realizedPnl = zero();
      unrealizedPnl = zero();
      fundingPnl = zero();
      markPrice = zero();
      maintenanceMargin = zero();
      markNotional = zero();
      leverage = targetLeverage;
      manualMargin = zero();
      recomputeInitialMargin();
    }

    private void recomputeInitialMargin() {
      initialMargin = open()
          ? s8(quantity.multiply(averageEntry).divide(
              BigDecimal.valueOf(leverage), DIVISION_SCALE, RoundingMode.HALF_UP))
          : zero();
    }

    private void revalue(BigDecimal mark, BigDecimal maintenanceRate) {
      if (!open()) {
        markPrice = zero();
        unrealizedPnl = zero();
        maintenanceMargin = zero();
        markNotional = zero();
        return;
      }
      markPrice = s8(mark);
      markNotional = s8(quantity.multiply(markPrice));
      unrealizedPnl = directionalPnl(direction, averageEntry, markPrice, quantity);
      maintenanceMargin = s8(markNotional.multiply(maintenanceRate));
    }

    private BigDecimal marginHeld() {
      return open() ? s8(initialMargin.add(manualMargin)) : zero();
    }

    private PositionState snapshot() {
      return new PositionState(
          slotRef(slot),
          open() ? "OPEN" : "CLOSED",
          direction == PositionSide.SHORT ? OrderSide.SELL : OrderSide.BUY,
          positionMode,
          slot,
          marginMode,
          quantity,
          averageEntry,
          markPrice,
          realizedPnl,
          unrealizedPnl,
          fundingPnl,
          marginHeld(),
          initialMargin,
          maintenanceMargin,
          markNotional,
          leverage);
    }
  }

  private static final class MutableOrder {

    private final String ref;
    private final OrderSide side;
    private OrderType type;
    private BigDecimal quantity;
    private final boolean reduceOnly;
    private PositionSide direction;
    private String status;
    private BigDecimal filledQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal averageFillPrice;
    private BigDecimal fee;
    private String feeAsset;
    private LiquidityRole liquidityRole;
    private String origin;
    private String parentRef = "";
    private String contingencyRef = "";
    private String holdAsset = "";
    private BigDecimal holdAmount = zero();
    private String holdOwnerRef = "";
    private String errorCode = "";
    private String clientOrderId = "";
    private BigDecimal limitPrice;
    private BigDecimal triggerPrice;
    private BigDecimal makerFeeRate = DEFAULT_MAKER_FEE_RATE;
    private BigDecimal takerFeeRate = DEFAULT_TAKER_FEE_RATE;
    private BigDecimal slippageRate = DEFAULT_SLIPPAGE_RATE;
    private ProtectionType protectionType;
    private TriggerExecutionType triggerExecutionType;
    private TriggerPriceType triggerPriceType;

    private MutableOrder(
        String ref,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        boolean reduceOnly,
        String status
    ) {
      this.ref = ref;
      this.side = side;
      this.type = type;
      this.quantity = s8(quantity);
      this.reduceOnly = reduceOnly;
      this.status = status;
      this.filledQuantity = zero();
      this.remainingQuantity = this.quantity;
      this.fee = zero();
      this.feeAsset = "";
      this.origin = "USER";
    }

    private static MutableOrder pending(ScenarioAction action, BigDecimal quantity) {
      Parameters parameters = action.parameters();
      MutableOrder order = new MutableOrder(
          parameters.orderId().isBlank()
              ? parameters.actionId() + "-order" : parameters.orderId(),
          parameters.side() == null
              ? action.direction() == PositionSide.SHORT ? OrderSide.SELL : OrderSide.BUY
              : parameters.side(),
          parameters.orderType() == null ? OrderType.MARKET : parameters.orderType(),
          quantity,
          Boolean.TRUE.equals(parameters.reduceOnly()),
          "PENDING");
      order.direction = action.direction();
      order.clientOrderId = parameters.clientOrderId();
      order.limitPrice = parameters.price();
      order.triggerPrice = parameters.triggerPrice();
      order.makerFeeRate = parameters.makerFeeRate() == null
          ? DEFAULT_MAKER_FEE_RATE : parameters.makerFeeRate();
      order.takerFeeRate = parameters.takerFeeRate() == null
          ? DEFAULT_TAKER_FEE_RATE : parameters.takerFeeRate();
      order.slippageRate = parameters.slippageRate() == null
          ? DEFAULT_SLIPPAGE_RATE : parameters.slippageRate();
      order.protectionType = parameters.protectionType();
      order.triggerExecutionType = parameters.triggerExecutionType();
      order.triggerPriceType = parameters.triggerPriceType();
      return order;
    }

    private static MutableOrder filled(
        ScenarioAction action,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee
    ) {
      Parameters parameters = action.parameters();
      MutableOrder order = new MutableOrder(
          parameters.orderId().isBlank()
              ? parameters.actionId() + "-order" : parameters.orderId(),
          parameters.side() == null
              ? action.direction() == PositionSide.SHORT ? OrderSide.SELL : OrderSide.BUY
              : parameters.side(),
          parameters.orderType() == null ? OrderType.MARKET : parameters.orderType(),
          quantity,
          Boolean.TRUE.equals(parameters.reduceOnly()),
          "FILLED");
      order.filledQuantity = quantity;
      order.remainingQuantity = zero();
      order.averageFillPrice = price;
      order.fee = fee;
      order.feeAsset = "USDT";
      order.liquidityRole = LiquidityRole.TAKER;
      order.direction = action.direction();
      return order;
    }

    private static MutableOrder protection(
        ScenarioAction action,
        BigDecimal quantity,
        String protectionRef,
        PositionSide slot,
        String carrierRef
    ) {
      Parameters parameters = action.parameters();
      MutableOrder order = new MutableOrder(
          carrierRef,
          action.direction() == PositionSide.SHORT ? OrderSide.BUY : OrderSide.SELL,
          OrderType.STOP_MARKET,
          quantity,
          true,
          "PENDING_ACTIVATION");
      order.origin = "PROTECTIVE";
      order.parentRef = protectionRef;
      order.direction = action.direction();
      order.clientOrderId = carrierRef;
      order.limitPrice = parameters.price();
      order.triggerPrice = parameters.triggerPrice();
      order.makerFeeRate = parameters.makerFeeRate() == null
          ? DEFAULT_MAKER_FEE_RATE : parameters.makerFeeRate();
      order.takerFeeRate = parameters.takerFeeRate() == null
          ? DEFAULT_TAKER_FEE_RATE : parameters.takerFeeRate();
      order.slippageRate = parameters.slippageRate() == null
          ? DEFAULT_SLIPPAGE_RATE : parameters.slippageRate();
      order.protectionType = parameters.protectionType();
      order.triggerExecutionType = parameters.triggerExecutionType();
      order.triggerPriceType = parameters.triggerPriceType();
      return order;
    }

    private static MutableOrder liquidation(
        String ref,
        OrderSide side,
        PositionSide slot,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee
    ) {
      MutableOrder order = new MutableOrder(
          ref, side, OrderType.MARKET, quantity, true, "FILLED");
      order.filledQuantity = quantity;
      order.remainingQuantity = zero();
      order.averageFillPrice = price;
      order.fee = fee;
      order.feeAsset = "USDT";
      order.liquidityRole = LiquidityRole.TAKER;
      order.origin = "LIQUIDATION";
      order.direction = side == OrderSide.BUY ? PositionSide.SHORT : PositionSide.LONG;
      order.parentRef = slotRef(slot);
      order.holdAsset = "USDT";
      return order;
    }

    private void fill(BigDecimal price, BigDecimal chargedFee, LiquidityRole role) {
      status = "FILLED";
      filledQuantity = remainingQuantity;
      remainingQuantity = zero();
      averageFillPrice = price;
      fee = chargedFee;
      feeAsset = "USDT";
      liquidityRole = role;
      holdAmount = zero();
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
          averageFillPrice,
          fee,
          feeAsset,
          liquidityRole,
          reduceOnly,
          origin,
          parentRef,
          contingencyRef,
          holdAsset,
          holdAmount,
          holdOwnerRef,
          errorCode);
    }
  }

  private static final class MutableProtection {

    private final String ref;
    private final String orderRef;
    private final ProtectionType type;
    private final PositionSide slot;
    private final BigDecimal triggerPrice;
    private final BigDecimal limitPrice;
    private final TriggerExecutionType executionType;
    private final TriggerPriceType triggerPriceType;
    private final int createdSequence;
    private String status = "ACTIVE";
    private BigDecimal quantity;

    private MutableProtection(
        String ref,
        String orderRef,
        ProtectionType type,
        PositionSide slot,
        BigDecimal quantity,
        BigDecimal triggerPrice,
        BigDecimal limitPrice,
        TriggerExecutionType executionType,
        TriggerPriceType triggerPriceType,
        int createdSequence
    ) {
      this.ref = ref;
      this.orderRef = orderRef;
      this.type = type;
      this.slot = slot;
      this.quantity = s8(quantity);
      this.triggerPrice = triggerPrice;
      this.limitPrice = limitPrice;
      this.executionType = executionType;
      this.triggerPriceType = triggerPriceType;
      this.createdSequence = createdSequence;
    }

    private boolean active() {
      return "ACTIVE".equals(status) || "TRIGGERED".equals(status);
    }

    private ProtectionState snapshot() {
      return new ProtectionState(
          ref,
          type,
          status,
          slotRef(slot),
          slot,
          quantity,
          triggerPrice,
          triggerPriceType,
          executionType,
          limitPrice,
          createdSequence);
    }
  }
}
