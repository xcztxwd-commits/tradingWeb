package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class FullFillCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-07-12T02:00:00Z");

  @Mock
  private ExecutionAdapter executionAdapter;

  private FullFillCoordinator coordinator;

  @BeforeEach
  void setUp() {
    coordinator = new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @ParameterizedTest
  @MethodSource("priceAndRoleCases")
  void calculatesCanonicalPriceSlippageFeeAndLiquidityRole(
      OrderSide side,
      FullFillExecutionPath path,
      String limitPrice,
      String expectedPrice,
      String expectedSlippage,
      LiquidityRole expectedRole,
      String expectedFee
  ) {
    stubFullIntent("2");

    FullFillResult result = coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, side, path, "2", limitPrice),
        spotSnapshot("BTCUSDT"));

    assertThat(result.filledPrice()).isEqualByComparingTo(expectedPrice);
    assertThat(result.slippage()).isEqualByComparingTo(expectedSlippage);
    assertThat(result.liquidityRole()).isEqualTo(expectedRole);
    assertThat(result.feeRate()).isEqualByComparingTo(
        expectedRole == LiquidityRole.MAKER ? "0.0002" : "0.0005");
    assertThat(result.fee()).isEqualByComparingTo(expectedFee);
    assertThat(result.feeAsset()).isEqualTo(side == OrderSide.BUY ? "BTC" : "USDT");
    assertThat(result.filledQuantity()).isEqualByComparingTo("2");
    assertThat(result.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
    assertThat(result.providerCode()).isEqualTo("binance");
    assertThat(result.providerSymbol()).isEqualTo("BTCUSDT");
    assertThat(result.asOf()).isEqualTo(NOW.minusSeconds(1));
    assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(2));
  }

  static Stream<Arguments> priceAndRoleCases() {
    return Stream.of(
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.MARKET, null,
            "100.0100", "0.0100", LiquidityRole.TAKER, "0.0010"),
        Arguments.of(OrderSide.SELL, FullFillExecutionPath.MARKET, null,
            "98.9901", "0.0099", LiquidityRole.TAKER, "0.09899010"),
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.IMMEDIATE_LIMIT, "101",
            "100", "0", LiquidityRole.TAKER, "0.0010"),
        Arguments.of(OrderSide.SELL, FullFillExecutionPath.IMMEDIATE_LIMIT, "98",
            "99", "0", LiquidityRole.TAKER, "0.0990"),
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.RESTING_LIMIT, "101",
            "100", "0", LiquidityRole.MAKER, "0.0004"),
        Arguments.of(OrderSide.SELL, FullFillExecutionPath.RESTING_LIMIT, "98",
            "99", "0", LiquidityRole.MAKER, "0.0396"),
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.TRIGGERED_STOP_MARKET, null,
            "100.0100", "0.0100", LiquidityRole.TAKER, "0.0010"));
  }

  @Test
  void linearPerpetualFeeUsesFilledQuoteNotionalAndUsdt() {
    stubFullIntent("2");

    FullFillResult result = coordinator.execute(
        request("BTCUSDT-PERP", ProductType.LINEAR_PERP, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "2", null),
        perpSnapshot("BTCUSDT-PERP", "100", "99"));

    assertThat(result.filledPrice()).isEqualByComparingTo("100.0100");
    assertThat(result.fee()).isEqualByComparingTo("0.10001000");
    assertThat(result.feeAsset()).isEqualTo("USDT");
  }

  @Test
  void passesTheOriginalExecutionIntentWithoutInventingIdentityFields() {
    CreateOrderRequest intent = intent(
        "BTCUSDT",
        OrderSide.BUY,
        com.fxplatform.trading.enums.OrderType.MARKET,
        "2",
        null,
        "client-original");
    stubFullIntent("2");

    coordinator.execute(
        new FullFillRequest(
            intent,
            "BTCUSDT",
            ProductType.CRYPTO_SPOT,
            OrderSide.BUY,
            FullFillExecutionPath.MARKET,
            new BigDecimal("2"),
            null),
        spotSnapshot("BTCUSDT"));

    org.mockito.ArgumentCaptor<CreateOrderRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);
    org.mockito.Mockito.verify(executionAdapter).execute(captor.capture());
    assertThat(captor.getValue()).isSameAs(intent);
    assertThat(captor.getValue().accountId()).isEqualTo(intent.accountId());
    assertThat(captor.getValue().clientOrderId()).isEqualTo("client-original");
  }

  @Test
  void acceptsCanonicalSeparatorAliasesWithoutChangingTheOriginalIntent() {
    CreateOrderRequest intent = intent(
        "BTC/USDT",
        OrderSide.BUY,
        com.fxplatform.trading.enums.OrderType.MARKET,
        "2",
        null,
        "alias-intent");
    stubFullIntent("2");

    FullFillResult result = coordinator.execute(
        new FullFillRequest(
            intent,
            "BTCUSDT",
            ProductType.CRYPTO_SPOT,
            OrderSide.BUY,
            FullFillExecutionPath.MARKET,
            new BigDecimal("2"),
            null),
        spotSnapshot("BTCUSDT"));

    assertThat(result.filledPrice()).isEqualByComparingTo("100.0100");
    org.mockito.ArgumentCaptor<CreateOrderRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);
    org.mockito.Mockito.verify(executionAdapter).execute(captor.capture());
    assertThat(captor.getValue()).isSameAs(intent);
  }

  @ParameterizedTest
  @MethodSource("invalidAdapterResults")
  void rejectsEveryNonFullAdapterResultBeforeItCanBecomeCanonical(ExecutionResult adapterResult) {
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(adapterResult);

    assertThatThrownBy(() -> coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "2", null),
        spotSnapshot("BTCUSDT")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PARTIAL_FILL_NOT_SUPPORTED"));
  }

  static Stream<ExecutionResult> invalidAdapterResults() {
    return Stream.of(
        intentResult(new BigDecimal("1"), BigDecimal.ZERO),
        intentResult(new BigDecimal("3"), BigDecimal.ZERO),
        intentResult(null, BigDecimal.ZERO),
        intentResult(new BigDecimal("2"), new BigDecimal("0.1")));
  }

  @Test
  void rejectsStaleSymbolProductAndMissingPerpetualMark() {
    FullFillRequest request = request("BTCUSDT-PERP", ProductType.LINEAR_PERP, OrderSide.BUY,
        FullFillExecutionPath.MARKET, "2", null);

    assertCode("MARKET_DATA_STALE", () -> coordinator.execute(
        request,
        new ExecutableMarketSnapshot(
            "BTCUSDT-PERP", ProductType.LINEAR_PERP, "binance-usdm", "BTCUSDT",
            MarketSourceMode.PUBLIC_EXTERNAL, new BigDecimal("99"), new BigDecimal("100"),
            new BigDecimal("99.5"), new BigDecimal("99.5"), new BigDecimal("99.4"),
            NOW.minusSeconds(3), NOW)));
    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> coordinator.execute(
        request,
        perpSnapshot("ETHUSDT-PERP", "100", "99")));
    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> coordinator.execute(
        request,
        new ExecutableMarketSnapshot(
            "BTCUSDT-PERP", ProductType.CRYPTO_SPOT, "binance-usdm", "BTCUSDT",
            MarketSourceMode.PUBLIC_EXTERNAL, new BigDecimal("99"), new BigDecimal("100"),
            new BigDecimal("99.5"), new BigDecimal("99.5"), new BigDecimal("99.4"),
            NOW.minusSeconds(1), NOW.plusSeconds(2))));
    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> coordinator.execute(
        request,
        perpSnapshot("BTCUSDT-PERP", null, "99")));
  }

  private void stubFullIntent(String quantity) {
    when(executionAdapter.execute(any(CreateOrderRequest.class)))
        .thenReturn(intentResult(new BigDecimal(quantity), BigDecimal.ZERO));
  }

  private static ExecutionResult intentResult(BigDecimal quantity, BigDecimal remaining) {
    return new ExecutionResult(
        null,
        NOW,
        quantity,
        remaining,
        BigDecimal.ZERO,
        null,
        BigDecimal.ZERO,
        null,
        null);
  }

  private static FullFillRequest request(
      String symbol,
      ProductType productType,
      OrderSide side,
      FullFillExecutionPath path,
      String quantity,
      String limitPrice
  ) {
    return new FullFillRequest(
        intent(
            symbol,
            side,
            switch (path) {
              case MARKET -> com.fxplatform.trading.enums.OrderType.MARKET;
              case IMMEDIATE_LIMIT, RESTING_LIMIT -> com.fxplatform.trading.enums.OrderType.LIMIT;
              case TRIGGERED_STOP_MARKET -> com.fxplatform.trading.enums.OrderType.STOP_MARKET;
            },
            quantity,
            limitPrice,
            "coordinator-test"),
        symbol,
        productType,
        side,
        path,
        new BigDecimal(quantity),
        limitPrice == null ? null : new BigDecimal(limitPrice));
  }

  private static CreateOrderRequest intent(
      String symbol,
      OrderSide side,
      com.fxplatform.trading.enums.OrderType orderType,
      String quantity,
      String price,
      String clientOrderId
  ) {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        symbol,
        side,
        orderType,
        price == null ? null : new BigDecimal(price),
        null,
        null,
        null,
        clientOrderId,
        clientOrderId,
        new BigDecimal(quantity),
        price == null ? null : new BigDecimal(price));
  }

  private static ExecutableMarketSnapshot spotSnapshot(String symbol) {
    return new ExecutableMarketSnapshot(
        symbol,
        ProductType.CRYPTO_SPOT,
        "binance",
        symbol,
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        null,
        null,
        NOW.minusSeconds(1),
        NOW.plusSeconds(2));
  }

  private static ExecutableMarketSnapshot perpSnapshot(String symbol, String ask, String bid) {
    return new ExecutableMarketSnapshot(
        symbol,
        ProductType.LINEAR_PERP,
        "binance-usdm",
        symbol.replace("-PERP", ""),
        MarketSourceMode.PUBLIC_EXTERNAL,
        bid == null ? null : new BigDecimal(bid),
        ask == null ? null : new BigDecimal(ask),
        new BigDecimal("99.5"),
        ask == null ? null : new BigDecimal("99.6"),
        new BigDecimal("99.4"),
        NOW.minusSeconds(1),
        NOW.plusSeconds(2));
  }

  private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
