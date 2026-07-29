package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepthPendingStopPlanningTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

  @Test
  void pendingStopMarketSkipsExecutableBookAndPlansBaseQuantityHold() {
    DemoExecutionPolicy policy = policy();
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan plan = service.preparePendingStop(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.STOP_MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        snapshot());

    assertThat(plan.sourceOrderType()).isEqualTo(OrderType.STOP_MARKET);
    assertThat(plan.executableType()).isEqualTo(OrderType.MARKET);
    assertThat(plan.initialOrderStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(plan.matchingResult().fills()).isEmpty();
    assertThat(plan.matchingResult().filledQuantity()).isEqualByComparingTo("0");
    assertThat(plan.matchingResult().remainingQuantity()).isEqualByComparingTo("1");
    assertThat(plan.matchingResult().terminalOrWorkingStatus()).isEqualTo(OrderStatus.PENDING);

    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(BigDecimal.ZERO, plan);
    assertThat(holds.initialHold()).isEqualByComparingTo("90.04500000");
    assertThat(holds.holdAfterEachFill()).isEmpty();
  }

  @Test
  void pendingStopLimitSkipsMarketableBookAndKeepsActivationStatus() {
    DemoExecutionPolicy policy = policy();
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan plan = service.preparePendingStop(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.STOP_LIMIT,
        TimeInForce.GTC,
        decimal("1"),
        decimal("100"),
        snapshot());

    assertThat(plan.sourceOrderType()).isEqualTo(OrderType.STOP_LIMIT);
    assertThat(plan.executableType()).isEqualTo(OrderType.LIMIT);
    assertThat(plan.initialOrderStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(plan.matchingResult().fills()).isEmpty();
    assertThat(plan.matchingResult().remainingQuantity()).isEqualByComparingTo("1");

    DepthOrderExecutionService.DepthHoldPlan holds = service.planSpot(BigDecimal.ZERO, plan);
    assertThat(holds.initialHold()).isEqualByComparingTo("100.05000000");
    assertThat(holds.holdAfterEachFill()).isEmpty();
  }

  @Test
  void triggeredStopRetainsSourceTypeAndRejectsAnInvalidExecutableMapping() {
    DemoExecutionPolicy policy = policy();
    DepthOrderExecutionService service = service(policy);

    DepthOrderExecutionService.DepthMatchPlan triggered = service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.STOP_MARKET,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot());

    assertThat(triggered.sourceOrderType()).isEqualTo(OrderType.STOP_MARKET);
    assertThat(triggered.executableType()).isEqualTo(OrderType.MARKET);
    assertThat(triggered.matchingResult().fills()).hasSize(1);

    assertThatThrownBy(() -> service.prepare(
        policy,
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        OrderType.STOP_LIMIT,
        OrderType.MARKET,
        TimeInForce.GTC,
        decimal("1"),
        null,
        false,
        LiquidityRole.TAKER,
        snapshot()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mapping");
  }

  private static DepthOrderExecutionService service(DemoExecutionPolicy policy) {
    return new DepthOrderExecutionService(
        () -> policy,
        mock(TradeRepository.class),
        mock(OrderFillService.class),
        mock(OrderEventService.class),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static DemoExecutionPolicy policy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.0002"),
        decimal("0.0005"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(),
        List.of(new DemoBookLevel(decimal("90"), decimal("1"))),
        null);
  }

  private static ExecutableMarketSnapshot snapshot() {
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "PUBLIC",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("89"),
        decimal("90"),
        decimal("89.5"),
        null,
        null,
        NOW,
        NOW.plusSeconds(60));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
