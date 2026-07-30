package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
    assertThat(result.feeAsset()).isEqualTo("USDT");
    assertThat(result.filledQuantity()).isEqualByComparingTo("2");
    assertThat(result.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.sourceMode()).isEqualTo(MarketSourceMode.PUBLIC_EXTERNAL);
    assertThat(result.providerCode()).isEqualTo("binance");
    assertThat(result.providerSymbol()).isEqualTo("BTCUSDT");
    assertThat(result.asOf()).isEqualTo(NOW.minusSeconds(1));
    assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(2));
  }

  @Test
  void springSelectsTheAuthorityAwareProductionConstructor() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext()) {
      context.registerBean(ExecutionAdapter.class, () -> executionAdapter);
      context.registerBean(
          DemoExecutionPolicyProvider.class,
          () -> DemoExecutionPolicy::defaults);
      context.registerBean(
          ExecutableMarketTimeAuthority.class,
          () -> new WallClockExecutableMarketTimeAuthority(
              Clock.fixed(NOW, ZoneOffset.UTC)));
      context.register(FullFillCoordinator.class);

      context.refresh();

      assertThat(context.getBean(FullFillCoordinator.class)).isNotNull();
    }
  }

  static Stream<Arguments> priceAndRoleCases() {
    return Stream.of(
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.MARKET, null,
            "100.0100", "0.0100", LiquidityRole.TAKER, "0.10001000"),
        Arguments.of(OrderSide.SELL, FullFillExecutionPath.MARKET, null,
            "98.9901", "0.0099", LiquidityRole.TAKER, "0.09899010"),
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.IMMEDIATE_LIMIT, "101",
            "100", "0", LiquidityRole.TAKER, "0.10000000"),
        Arguments.of(OrderSide.SELL, FullFillExecutionPath.IMMEDIATE_LIMIT, "98",
            "99", "0", LiquidityRole.TAKER, "0.0990"),
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.RESTING_LIMIT, "101",
            "100", "0", LiquidityRole.MAKER, "0.04000000"),
        Arguments.of(OrderSide.SELL, FullFillExecutionPath.RESTING_LIMIT, "98",
            "99", "0", LiquidityRole.MAKER, "0.0396"),
        Arguments.of(OrderSide.BUY, FullFillExecutionPath.TRIGGERED_STOP_MARKET, null,
            "100.0100", "0.0100", LiquidityRole.TAKER, "0.10001000"));
  }

  @Test
  void spotBuyChargesCanonicalQuoteNotionalFeeInUsdt() {
    stubFullIntent("1");
    ExecutableMarketSnapshot snapshot = new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "binance",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("100"),
        new BigDecimal("101"),
        new BigDecimal("100.5"),
        null,
        null,
        NOW.minusSeconds(1),
        NOW.plusSeconds(2));

    FullFillResult result = coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "1", null),
        snapshot);

    assertThat(result.filledPrice()).isEqualByComparingTo("101.0101");
    assertThat(result.feeAsset()).isEqualTo("USDT");
    assertThat(result.fee()).isEqualByComparingTo("0.05050505");
    assertThat(result.fee().scale()).isEqualTo(8);
  }

  @Test
  void executeUsesExactlyOnePolicySnapshot() {
    stubFullIntent("2");
    AtomicInteger snapshots = new AtomicInteger();
    DemoExecutionPolicyProvider provider = () -> {
      snapshots.incrementAndGet();
      return policy("0.0002", "0.0005", "0.0001");
    };
    coordinator = new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        provider);

    FullFillResult result = coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "2", null),
        spotSnapshot("BTCUSDT"));

    assertThat(snapshots).hasValue(1);
    assertThat(result.fee()).isEqualByComparingTo("0.10001000");
  }

  @Test
  void projectUsesExactlyOnePolicySnapshot() {
    AtomicInteger snapshots = new AtomicInteger();
    DemoExecutionPolicyProvider provider = () -> {
      snapshots.incrementAndGet();
      return policy("0.001", "0.002", "0.003");
    };
    coordinator = new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        provider);

    FullFillPricingProjection projection = coordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.MARKET,
        null,
        spotSnapshot("BTCUSDT"));

    assertThat(snapshots).hasValue(1);
    assertThat(projection.slippageRate()).isEqualByComparingTo("0.003");
    assertThat(projection.feeRate()).isEqualByComparingTo("0.002");
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
  void linearPerpetualNormalizesCanonicalPriceBeforeFeeCalculation() {
    stubFullIntent("0.0005");

    FullFillResult result = coordinator.execute(
        request("BTCUSDT-PERP", ProductType.LINEAR_PERP, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "0.0005", null),
        perpSnapshot("BTCUSDT-PERP", "50000.123456789", "49999.9"));

    assertThat(result.filledPrice()).isEqualByComparingTo("50005.12346913");
    assertThat(result.filledPrice().scale()).isEqualTo(8);
    assertThat(result.fee()).isEqualByComparingTo("0.01250128");
  }

  @Test
  void spotNormalizesCanonicalPriceToTheSupportedScale() {
    FullFillPricingProjection projection = coordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.MARKET,
        null,
        snapshot(
            ProductType.CRYPTO_SPOT,
            "49999.9",
            "50000.1234567890",
            "50000",
            null,
            null));

    assertThat(projection.filledPrice()).isEqualByComparingTo("50005.1234691347");
    assertThat(projection.filledPrice().scale()).isEqualTo(10);
    assertThat(projection.slippage()).isEqualByComparingTo("5.0000123457");
    assertThat(projection.slippage().scale()).isEqualTo(10);
  }

  @Test
  void projectsCanonicalPricingAndSharedRatesWithoutCallingExecutionAdapter() {
    FullFillPricingProjection projection = coordinator.project(
        ProductType.CRYPTO_SPOT,
        OrderSide.BUY,
        FullFillExecutionPath.MARKET,
        null,
        spotSnapshot("BTCUSDT"));

    assertThat(projection.filledPrice()).isEqualByComparingTo("100.0100");
    assertThat(projection.slippage()).isEqualByComparingTo("0.0100");
    assertThat(projection.slippageRate()).isEqualByComparingTo("0.0001");
    assertThat(projection.feeRate()).isEqualByComparingTo("0.0005");
    assertThat(projection.worstFeeRate()).isEqualByComparingTo("0.0005");
    assertThat(projection.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    verifyNoInteractions(executionAdapter);
  }

  @Test
  void exposesFreshnessGuardForPendingSnapshotBeforeFirstWrite() {
    MutableClock mutableClock = new MutableClock(NOW);
    coordinator = new FullFillCoordinator(executionAdapter, mutableClock);
    ExecutableMarketSnapshot snapshot = spotSnapshot("BTCUSDT");

    coordinator.requireFresh(snapshot);
    mutableClock.advance(Duration.ofSeconds(3));

    assertCode("MARKET_DATA_STALE", () -> coordinator.requireFresh(snapshot));
  }

  @Test
  void rejectsTheFillWhenTheAdapterCrossesTheSnapshotExpiry() {
    MutableClock mutableClock = new MutableClock(NOW);
    coordinator = new FullFillCoordinator(executionAdapter, mutableClock);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenAnswer(invocation -> {
      mutableClock.advance(Duration.ofSeconds(3));
      return intentResult(new BigDecimal("2"), BigDecimal.ZERO);
    });

    assertCode("MARKET_DATA_STALE", () -> coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "2", null),
        spotSnapshot("BTCUSDT")));
  }

  @Test
  void exposesAReusableFreshnessGuardForTheCanonicalFullFillResult() {
    MutableClock mutableClock = new MutableClock(NOW);
    coordinator = new FullFillCoordinator(executionAdapter, mutableClock);
    stubFullIntent("2");
    FullFillResult result = coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "2", null),
        spotSnapshot("BTCUSDT"));

    mutableClock.advance(Duration.ofSeconds(2));

    assertCode("MARKET_DATA_STALE", () -> coordinator.requireFresh(result));
  }

  @Test
  void wallClockAuthorityRejectsValidationMarkedFutureSnapshots() {
    Instant virtualTime = Instant.parse("2030-01-01T00:00:01Z");
    ExecutableMarketSnapshot validation = snapshotWithAuthority(
        spotSnapshot("BTCUSDT"),
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        virtualTime.plusSeconds(60));

    assertCode("MARKET_DATA_STALE", () -> coordinator.requireFresh(validation));
  }

  @Test
  void injectedAuthorityOwnsValidationFilledAtAndFinalFreshness() {
    Instant virtualTime = Instant.parse("2020-01-01T00:00:01Z");
    ExecutableMarketSnapshot validation = snapshotWithAuthority(
        spotSnapshot("BTCUSDT"),
        "validation",
        MarketSourceMode.LOCAL_SIMULATED,
        virtualTime,
        virtualTime.plusSeconds(60));
    ExecutableMarketTimeAuthority authority = org.mockito.Mockito.mock(
        ExecutableMarketTimeAuthority.class);
    when(authority.currentTime(validation)).thenReturn(virtualTime);
    when(authority.currentTime(any(FullFillResult.class))).thenReturn(virtualTime);
    coordinator = new FullFillCoordinator(
        executionAdapter,
        DemoExecutionPolicy::defaults,
        authority);
    stubFullIntent("2");

    FullFillResult result = coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
            FullFillExecutionPath.MARKET, "2", null),
        validation);

    assertThat(result.filledAt()).isEqualTo(virtualTime);
    coordinator.requireFresh(result);
  }

  @Test
  void stopMarketSellUsesBidSideSlippageAndTakerFee() {
    stubFullIntent("2");

    FullFillResult result = coordinator.execute(
        request("BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.SELL,
            FullFillExecutionPath.TRIGGERED_STOP_MARKET, "2", null),
        spotSnapshot("BTCUSDT"));

    assertThat(result.filledPrice()).isEqualByComparingTo("98.9901");
    assertThat(result.slippage()).isEqualByComparingTo("0.0099");
    assertThat(result.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(result.fee()).isEqualByComparingTo("0.09899010");
    assertThat(result.feeAsset()).isEqualTo("USDT");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingRequiredMarketPrices")
  void rejectsEveryMissingRequiredMarketPrice(
      String scenario,
      FullFillRequest request,
      ExecutableMarketSnapshot snapshot
  ) {
    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> coordinator.execute(request, snapshot));
  }

  @Test
  void sharedSnapshotValidatorRejectsAProductMismatch() {
    ExecutableMarketSnapshot wrongProduct = new ExecutableMarketSnapshot(
        "BTCUSDT-PERP",
        ProductType.CRYPTO_SPOT,
        "binance-usdm",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        new BigDecimal("99.6"),
        new BigDecimal("99.4"),
        NOW.minusSeconds(1),
        NOW.plusSeconds(2));

    assertCode(
        "MARKET_BUNDLE_INCOMPLETE",
        () -> ExecutableMarketSnapshots.requireComplete(
            "BTCUSDT-PERP", ProductType.LINEAR_PERP, wrongProduct));
  }

  static Stream<Arguments> missingRequiredMarketPrices() {
    FullFillRequest spotRequest = request(
        "BTCUSDT", ProductType.CRYPTO_SPOT, OrderSide.BUY,
        FullFillExecutionPath.MARKET, "2", null);
    FullFillRequest perpRequest = request(
        "BTCUSDT-PERP", ProductType.LINEAR_PERP, OrderSide.BUY,
        FullFillExecutionPath.MARKET, "2", null);
    return Stream.of(
        Arguments.of("spot bid", spotRequest,
            snapshot(ProductType.CRYPTO_SPOT, null, "100", "99.5", null, null)),
        Arguments.of("spot ask", spotRequest,
            snapshot(ProductType.CRYPTO_SPOT, "99", null, "99.5", null, null)),
        Arguments.of("spot last", spotRequest,
            snapshot(ProductType.CRYPTO_SPOT, "99", "100", null, null, null)),
        Arguments.of("perpetual bid", perpRequest,
            snapshot(ProductType.LINEAR_PERP, null, "100", "99.5", "99.6", "99.4")),
        Arguments.of("perpetual ask", perpRequest,
            snapshot(ProductType.LINEAR_PERP, "99", null, "99.5", "99.6", "99.4")),
        Arguments.of("perpetual last", perpRequest,
            snapshot(ProductType.LINEAR_PERP, "99", "100", null, "99.6", "99.4")),
        Arguments.of("perpetual mark", perpRequest,
            snapshot(ProductType.LINEAR_PERP, "99", "100", "99.5", null, "99.4")),
        Arguments.of("perpetual index", perpRequest,
            snapshot(ProductType.LINEAR_PERP, "99", "100", "99.5", "99.6", null)));
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

  private static ExecutableMarketSnapshot snapshotWithAuthority(
      ExecutableMarketSnapshot source,
      String providerCode,
      MarketSourceMode sourceMode,
      Instant asOf,
      Instant expiresAt
  ) {
    return new ExecutableMarketSnapshot(
        source.platformSymbol(),
        source.productType(),
        providerCode,
        source.providerSymbol(),
        sourceMode,
        source.bid(),
        source.ask(),
        source.last(),
        source.mark(),
        source.index(),
        asOf,
        expiresAt);
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

  private static ExecutableMarketSnapshot snapshot(
      ProductType productType,
      String bid,
      String ask,
      String last,
      String mark,
      String index
  ) {
    boolean perpetual = productType == ProductType.LINEAR_PERP;
    return new ExecutableMarketSnapshot(
        perpetual ? "BTCUSDT-PERP" : "BTCUSDT",
        productType,
        perpetual ? "binance-usdm" : "binance",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid),
        decimal(ask),
        decimal(last),
        decimal(mark),
        decimal(index),
        NOW.minusSeconds(1),
        NOW.plusSeconds(2));
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  private static DemoExecutionPolicy policy(
      String makerFeeRate,
      String takerFeeRate,
      String slippageRate
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal(makerFeeRate),
        new BigDecimal(takerFeeRate),
        new BigDecimal("0.001"),
        new BigDecimal(slippageRate),
        List.of(),
        List.of(),
        null);
  }

  private static final class MutableClock extends Clock {

    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new UnsupportedOperationException("Test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }

  private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
