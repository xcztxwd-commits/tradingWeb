package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoMatchingEngineTest {

  private final DemoMatchingEngine engine = new DemoMatchingEngine();

  @Test
  void buy_sweeps_asks_in_price_order_and_stops_at_quantity() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, "2.5", null,
        List.of(), asks("100@1", "101@2", "102@5"), null));

    assertThat(result.fills()).extracting(DemoMatchFill::price)
        .containsExactly(decimal("100"), decimal("101"));
    assertThat(result.fills()).extracting(DemoMatchFill::quantity)
        .containsExactly(decimal("1"), decimal("1.5"));
    assertThat(result.filledQuantity()).isEqualByComparingTo("2.5");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("0");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void per_tick_cap_produces_partial_fill() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, "4", null,
        List.of(), asks("100@1", "101@5"), decimal("2")));

    assertThat(result.fills()).extracting(DemoMatchFill::quantity)
        .containsExactly(decimal("1"), decimal("1"));
    assertThat(result.filledQuantity()).isEqualByComparingTo("2");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("2");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
  }

  @Test
  void ioc_cancels_remainder_after_available_depth() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, "4", null,
        List.of(), asks("100@1", "101@2"), null));

    assertThat(result.filledQuantity()).isEqualByComparingTo("3");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("1");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  void fok_returns_zero_fills_when_full_quantity_is_unavailable() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.FOK, "4", null,
        List.of(), asks("100@1", "101@2"), null));

    assertThat(result.fills()).isEmpty();
    assertThat(result.filledQuantity()).isEqualByComparingTo("0");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("4");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  void fok_returns_zero_fills_when_tick_cap_is_smaller_than_full_available_depth() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.FOK, "4", null,
        List.of(), asks("100@4"), decimal("3")));

    assertThat(result.fills()).isEmpty();
    assertThat(result.filledQuantity()).isEqualByComparingTo("0");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("4");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  void post_only_never_consumes_depth() {
    DemoMatchingRequest request = request(
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1", "101",
        depthPolicy(List.of(), asks("100@1"), null), true, LiquidityRole.TAKER, null);

    DemoMatchingResult result = engine.match(request);

    assertThat(result.fills()).isEmpty();
    assertThat(result.filledQuantity()).isEqualByComparingTo("0");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("1");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.REJECTED);
  }

  @Test
  void simple_mode_returns_one_full_fill() {
    DemoMatchingResult result = engine.match(request(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, "2", null,
        simplePolicy(), false, LiquidityRole.TAKER, decimal("123.45")));

    assertThat(result.fills()).containsExactly(new DemoMatchFill(
        decimal("2"), decimal("123.45"), LiquidityRole.TAKER, decimal("0.0005")));
    assertThat(result.filledQuantity()).isEqualByComparingTo("2");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("0");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void simple_mode_post_only_admission_never_executes_the_canonical_fill() {
    DemoMatchingResult result = engine.match(request(
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "2", "125",
        simplePolicy(), true, LiquidityRole.TAKER, decimal("123.45")));

    assertThat(result.fills()).isEmpty();
    assertThat(result.filledQuantity()).isEqualByComparingTo("0");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("2");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.REJECTED);
  }

  @Test
  void simple_buy_limit_does_not_fill_at_a_price_above_the_limit() {
    DemoMatchingResult result = engine.match(request(
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "2", "123.44",
        simplePolicy(), false, LiquidityRole.TAKER, decimal("123.45")));

    assertThat(result.fills()).isEmpty();
    assertThat(result.filledQuantity()).isEqualByComparingTo("0");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("2");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void simple_sell_limit_ioc_does_not_fill_at_a_price_below_the_limit() {
    DemoMatchingResult result = engine.match(request(
        OrderSide.SELL, OrderType.LIMIT, TimeInForce.IOC, "2", "123.46",
        simplePolicy(), false, LiquidityRole.TAKER, decimal("123.45")));

    assertThat(result.fills()).isEmpty();
    assertThat(result.filledQuantity()).isEqualByComparingTo("0");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("2");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  void sell_sweeps_bids_in_descending_price_order() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.SELL, OrderType.MARKET, TimeInForce.GTC, "2.5", null,
        bids("99@1", "101@2", "100@5"), List.of(), null));

    assertThat(result.fills()).extracting(DemoMatchFill::price)
        .containsExactly(decimal("101"), decimal("100"));
    assertThat(result.fills()).extracting(DemoMatchFill::quantity)
        .containsExactly(decimal("2"), decimal("0.5"));
  }

  @Test
  void limit_price_equal_to_book_level_is_marketable() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1", "100",
        List.of(), asks("100@1", "101@1"), null));

    assertThat(result.fills()).containsExactly(new DemoMatchFill(
        decimal("1"), decimal("100"), LiquidityRole.TAKER, decimal("0.0005")));
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void gtc_with_no_marketability_remains_pending() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1", "99",
        List.of(), asks("100@1"), null));

    assertThat(result.fills()).isEmpty();
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void gtc_with_partial_fill_remains_partially_filled() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, "2", "100",
        bids("101@1"), List.of(), null));

    assertThat(result.filledQuantity()).isEqualByComparingTo("1");
    assertThat(result.remainingQuantity()).isEqualByComparingTo("1");
    assertThat(result.terminalOrWorkingStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
  }

  @Test
  void maker_liquidity_role_uses_maker_fee_rate() {
    DemoMatchingResult result = engine.match(depthRequest(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, "1", null,
        List.of(), asks("100@1"), null, LiquidityRole.MAKER));

    assertThat(result.fills()).singleElement().extracting(DemoMatchFill::feeRate)
        .isEqualTo(decimal("0.0002"));
  }

  @Test
  void rejects_invalid_request_input() {
    assertThatThrownBy(() -> engine.match(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.match(request(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, "0", null,
        depthPolicy(List.of(), List.of(), null), false, LiquidityRole.TAKER, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.match(request(
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "1", null,
        depthPolicy(List.of(), List.of(), null), false, LiquidityRole.TAKER, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.match(request(
        OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, "1", null,
        depthPolicy(List.of(), List.of(), null), false, LiquidityRole.TAKER, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.match(request(
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, "1", null,
        simplePolicy(), false, LiquidityRole.TAKER, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DemoMatchingRequest depthRequest(
      OrderSide side,
      OrderType orderType,
      TimeInForce timeInForce,
      String quantity,
      String limitPrice,
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks,
      BigDecimal cap
  ) {
    return depthRequest(side, orderType, timeInForce, quantity, limitPrice, bids, asks, cap,
        LiquidityRole.TAKER);
  }

  private static DemoMatchingRequest depthRequest(
      OrderSide side,
      OrderType orderType,
      TimeInForce timeInForce,
      String quantity,
      String limitPrice,
      List<DemoBookLevel> bids,
      List<DemoBookLevel> asks,
      BigDecimal cap,
      LiquidityRole liquidityRole
  ) {
    return request(side, orderType, timeInForce, quantity, limitPrice,
        depthPolicy(bids, asks, cap), false, liquidityRole, null);
  }

  private static DemoMatchingRequest request(
      OrderSide side,
      OrderType orderType,
      TimeInForce timeInForce,
      String quantity,
      String limitPrice,
      DemoExecutionPolicy policy,
      boolean postOnly,
      LiquidityRole liquidityRole,
      BigDecimal simpleFillPrice
  ) {
    return new DemoMatchingRequest(
        "BTCUSDT", side, orderType, timeInForce, decimal(quantity),
        limitPrice == null ? null : decimal(limitPrice), policy, postOnly, liquidityRole,
        simpleFillPrice);
  }

  private static DemoExecutionPolicy simplePolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE, decimal("0.0002"), decimal("0.0005"), decimal("0.001"),
        decimal("0.0001"), List.of(), List.of(), null);
  }

  private static DemoExecutionPolicy depthPolicy(
      List<DemoBookLevel> bids, List<DemoBookLevel> asks, BigDecimal cap
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH, decimal("0.0002"), decimal("0.0005"), decimal("0.001"),
        decimal("0.0001"), bids, asks, cap);
  }

  private static List<DemoBookLevel> asks(String... levels) {
    return levels(levels);
  }

  private static List<DemoBookLevel> bids(String... levels) {
    return levels(levels);
  }

  private static List<DemoBookLevel> levels(String... levels) {
    return java.util.Arrays.stream(levels)
        .map(level -> level.split("@"))
        .map(parts -> new DemoBookLevel(decimal(parts[0]), decimal(parts[1])))
        .toList();
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
