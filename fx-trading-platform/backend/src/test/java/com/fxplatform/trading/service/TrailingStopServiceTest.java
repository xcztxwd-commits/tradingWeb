package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.money.ExactNumeric;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrailingStopServiceTest {

  private OrderRepository orderRepository;
  private SystemCloseOrderService systemCloseOrderService;
  private TradingAccountRepository accountRepository;
  private DemoExecutionGuard demoExecutionGuard;
  private TrailingStopService service;

  @BeforeEach
  void setUp() {
    orderRepository = mock(OrderRepository.class);
    systemCloseOrderService = mock(SystemCloseOrderService.class);
    accountRepository = mock(TradingAccountRepository.class);
    demoExecutionGuard = mock(DemoExecutionGuard.class);
    service = new TrailingStopService(
        orderRepository,
        systemCloseOrderService,
        accountRepository,
        demoExecutionGuard);
  }

  @Test
  void sellActivatesTracksHighestPriceAndTriggersOnInclusiveRetracement() {
    OrderEntity order = trailing(OrderSide.SELL, "100", "5", null);

    TrailingStopUpdate beforeActivation = service.evaluate(order, decimal("99"));
    assertThat(beforeActivation.activated()).isFalse();
    assertThat(beforeActivation.nextExtreme()).isNull();
    assertThat(beforeActivation.triggered()).isFalse();

    TrailingStopUpdate activated = service.evaluate(order, decimal("100"));
    assertThat(activated.activated()).isTrue();
    assertThat(activated.nextExtreme()).isEqualByComparingTo("100");
    assertThat(activated.triggered()).isFalse();

    order.setTrailingExtreme(activated.nextExtreme());
    order.setTriggerPrice(decimal("95"));
    TrailingStopUpdate advanced = service.evaluate(order, decimal("112.50"));
    assertThat(advanced.nextExtreme()).isEqualByComparingTo("112.50");

    order.setTrailingExtreme(advanced.nextExtreme());
    order.setTriggerPrice(decimal("107.50"));
    TrailingStopUpdate triggered = service.evaluate(order, decimal("107.50"));
    assertThat(triggered.nextExtreme()).isEqualByComparingTo("112.50");
    assertThat(triggered.triggered()).isTrue();
  }

  @Test
  void buyWithoutActivationPriceTracksLowestPriceAndUsesExactFractionalRate() {
    OrderEntity order = trailing(OrderSide.BUY, null, null, "0.075");

    TrailingStopUpdate activated = service.evaluate(order, decimal("100.00"));
    assertThat(activated.activated()).isTrue();
    assertThat(activated.nextExtreme()).isEqualByComparingTo("100.00");

    order.setTrailingExtreme(activated.nextExtreme());
    order.setTriggerPrice(decimal("107.50000"));
    TrailingStopUpdate lower = service.evaluate(order, decimal("80.123"));
    assertThat(lower.nextExtreme()).isEqualByComparingTo("80.123");

    order.setTrailingExtreme(lower.nextExtreme());
    order.setTriggerPrice(decimal("86.132225"));
    assertThat(service.evaluate(order, decimal("86.132225")).triggered()).isTrue();
  }

  @Test
  void activationGateDoesNotUpdateBuyExtremeBeforePriceReachesIt() {
    OrderEntity order = trailing(OrderSide.BUY, "90", "2", null);

    TrailingStopUpdate update = service.evaluate(order, decimal("90.01"));

    assertThat(update.activated()).isFalse();
    assertThat(update.nextExtreme()).isNull();
  }

  @Test
  void invalidCallbacksFailClosed() {
    OrderEntity both = trailing(OrderSide.SELL, null, "1", "0.01");
    OrderEntity nonPositiveDelta = trailing(OrderSide.SELL, null, "0", null);
    OrderEntity outOfRangeRate = trailing(OrderSide.BUY, null, null, "1");

    assertThatThrownBy(() -> service.evaluate(both, decimal("100")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.evaluate(nonPositiveDelta, decimal("100")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.evaluate(outOfRangeRate, decimal("100")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void explicitTickPersistsExtremeAndDerivedThresholdWithVersionCas() {
    OrderEntity order = trailing(OrderSide.SELL, null, "5", null);
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    BigDecimal persistedThreshold = decimal("95.0000000000");
    assertThat(persistedThreshold).isEqualByComparingTo("95");
    when(orderRepository.updateTrailingState(
        order.getId(), 0L, decimal("100"), persistedThreshold)).thenReturn(1);

    int updated = service.updateExtrema(snapshot("100"));

    assertThat(updated).isEqualTo(1);
    verify(orderRepository).updateTrailingState(
        order.getId(), 0L, decimal("100"), persistedThreshold);
    verify(systemCloseOrderService, never()).executeProtection(any(), any());
    verify(demoExecutionGuard).requireDemo(account, ProductType.LINEAR_PERP, order.getSymbol());
  }

  @Test
  void sellFirstTickPersistsThresholdBeyondLegacyNumeric24Range() {
    OrderEntity order = trailing(OrderSide.SELL, null, "100000000000050", null);
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    BigDecimal threshold = decimal("-100000000000049.0000000000");
    assertThat(ExactNumeric.fits(threshold, 24, 10)).isFalse();
    assertThat(ExactNumeric.fits(threshold, 31, 10)).isTrue();
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(orderRepository.updateTrailingState(
        order.getId(), 0L, decimal("1"), threshold)).thenReturn(1);

    assertThat(service.updateExtrema(snapshot("1"))).isEqualTo(1);

    verify(orderRepository).updateTrailingState(order.getId(), 0L, decimal("1"), threshold);
    verify(systemCloseOrderService, never()).executeProtection(any(), any());
  }

  @Test
  void buyFirstTickPersistsThresholdBeyondLegacyNumeric24Range() {
    OrderEntity order = trailing(OrderSide.BUY, null, "99999999999850", null);
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    BigDecimal threshold = decimal("100000000000050.0000000000");
    assertThat(ExactNumeric.fits(threshold, 24, 10)).isFalse();
    assertThat(ExactNumeric.fits(threshold, 31, 10)).isTrue();
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(orderRepository.updateTrailingState(
        order.getId(), 0L, decimal("200"), threshold)).thenReturn(1);

    assertThat(service.updateExtrema(snapshot("200"))).isEqualTo(1);

    verify(orderRepository).updateTrailingState(order.getId(), 0L, decimal("200"), threshold);
    verify(systemCloseOrderService, never()).executeProtection(any(), any());
  }

  @Test
  void rateThresholdKeepsExactEvaluationAndUsesDirectionalNumericScaleAtCasBoundary() {
    BigDecimal mark = decimal("100.1234567890");
    BigDecimal rate = decimal("0.123456789");
    OrderEntity sell = trailing(OrderSide.SELL, null, null, rate.toPlainString());
    OrderEntity buy = trailing(OrderSide.BUY, null, null, rate.toPlainString());
    BigDecimal sellExact = mark.multiply(BigDecimal.ONE.subtract(rate));
    BigDecimal buyExact = mark.multiply(BigDecimal.ONE.add(rate));
    BigDecimal sellPersisted = sellExact.setScale(10, RoundingMode.FLOOR);
    BigDecimal buyPersisted = buyExact.setScale(10, RoundingMode.CEILING);
    assertThat(sellExact.scale()).isGreaterThan(10);
    assertThat(buyExact.scale()).isGreaterThan(10);
    assertThat(sellPersisted).isLessThan(sellExact);
    assertThat(buyPersisted).isGreaterThan(buyExact);
    assertThat(ExactNumeric.fits(mark, 24, 10)).isTrue();
    assertThat(ExactNumeric.fits(sellPersisted, 24, 10)).isTrue();
    assertThat(ExactNumeric.fits(buyPersisted, 24, 10)).isTrue();

    TradingAccountEntity sellAccount = new TradingAccountEntity();
    sellAccount.setId(sell.getAccountId());
    TradingAccountEntity buyAccount = new TradingAccountEntity();
    buyAccount.setId(buy.getAccountId());
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(sell, buy));
    when(accountRepository.findById(sell.getAccountId())).thenReturn(Optional.of(sellAccount));
    when(accountRepository.findById(buy.getAccountId())).thenReturn(Optional.of(buyAccount));
    when(orderRepository.updateTrailingState(
        sell.getId(), 0L, mark, sellPersisted)).thenReturn(1);
    when(orderRepository.updateTrailingState(
        buy.getId(), 0L, mark, buyPersisted)).thenReturn(1);

    assertThat(service.updateExtrema(snapshot(mark.toPlainString()))).isEqualTo(2);

    sell.setTrailingExtreme(mark);
    sell.setTriggerPrice(sellPersisted);
    buy.setTrailingExtreme(mark);
    buy.setTriggerPrice(buyPersisted);
    assertThat(service.updateExtrema(snapshot(mark.toPlainString()))).isZero();

    verify(orderRepository).updateTrailingState(sell.getId(), 0L, mark, sellPersisted);
    verify(orderRepository).updateTrailingState(buy.getId(), 0L, mark, buyPersisted);
    verify(systemCloseOrderService, never()).executeProtection(any(), any());
  }

  @Test
  void tickRejectsAuthorityMarkThatCannotBeStoredAsNumeric24Scale10() {
    assertThat(ExactNumeric.fits(decimal("100.12345678901"), 24, 10)).isFalse();

    assertThatThrownBy(() -> service.onTick(snapshot("100.12345678901")))
        .isInstanceOf(BusinessException.class);

    verify(orderRepository, never()).findTrailingStopsAwaitingActivation();
  }

  @Test
  void sameValueNonTriggerTickIsANoOp() {
    OrderEntity order = trailing(OrderSide.SELL, null, "5", null);
    order.setTrailingExtreme(decimal("100"));
    order.setTriggerPrice(decimal("95.0000000000"));
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));

    assertThat(service.onTick(snapshot("99"))).isZero();

    verify(orderRepository, never()).updateTrailingState(any(), eq(0L), any(), any());
    verify(systemCloseOrderService, never()).executeProtection(any(), any());
  }

  @Test
  void triggerCasHasOneWinnerAndReplayingTerminalCarrierDoesNotCloseTwice() {
    OrderEntity order = trailing(OrderSide.SELL, null, "5", null);
    order.setTrailingExtreme(decimal("100"));
    order.setTriggerPrice(decimal("95"));
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    ExecutableMarketSnapshot snapshot = snapshot("95");
    when(orderRepository.findTrailingStopsAwaitingActivation())
        .thenReturn(List.of(order))
        .thenReturn(List.of(order))
        .thenReturn(List.of());
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(orderRepository.updateTrailingState(
        order.getId(), 0L, decimal("100"), decimal("95.0000000000")))
        .thenReturn(1);

    assertThat(service.onTick(snapshot)).isEqualTo(1);
    assertThat(service.onTick(snapshot)).isZero();

    verify(systemCloseOrderService).executeProtection(order.getId(), snapshot);
  }

  @Test
  void strictTriggerUsesTheJoinedProtectionCloseEntrypoint() {
    OrderEntity order = trailing(OrderSide.SELL, null, "5", null);
    order.setTrailingExtreme(decimal("100"));
    order.setTriggerPrice(decimal("95"));
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    ExecutableMarketSnapshot snapshot = snapshot("95");
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(order));
    when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
    when(orderRepository.updateTrailingState(
        order.getId(), 0L, decimal("100"), decimal("95.0000000000")))
        .thenReturn(1);

    assertThat(service.triggerReadyStrict(snapshot)).isEqualTo(1);

    verify(systemCloseOrderService).executeProtectionStrict(order.getId(), snapshot);
    verify(systemCloseOrderService, never()).executeProtection(order.getId(), snapshot);
  }

  @Test
  void casLoserReloadsAndReevaluatesTheSameTickAgainstTheWinningExtreme() {
    OrderEntity initial = trailing(OrderSide.SELL, null, "5", null);
    initial.setVersion(1L);
    initial.setTrailingExtreme(decimal("110"));
    initial.setTriggerPrice(decimal("105"));
    OrderEntity reloaded = trailing(OrderSide.SELL, null, "5", null);
    reloaded.setId(initial.getId());
    reloaded.setUserId(initial.getUserId());
    reloaded.setAccountId(initial.getAccountId());
    reloaded.setParentPositionId(initial.getParentPositionId());
    reloaded.setVersion(2L);
    reloaded.setTrailingExtreme(decimal("120"));
    reloaded.setTriggerPrice(decimal("115"));
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(initial.getAccountId());
    ExecutableMarketSnapshot snapshot = snapshot("104");
    when(orderRepository.findTrailingStopsAwaitingActivation()).thenReturn(List.of(initial));
    when(accountRepository.findById(initial.getAccountId())).thenReturn(Optional.of(account));
    when(orderRepository.updateTrailingState(
        initial.getId(), 1L, decimal("110"), decimal("105.0000000000")))
        .thenReturn(0);
    when(orderRepository.findById(initial.getId())).thenReturn(Optional.of(reloaded));
    when(orderRepository.updateTrailingState(
        initial.getId(), 2L, decimal("120"), decimal("115.0000000000")))
        .thenReturn(1);

    assertThat(service.onTick(snapshot)).isEqualTo(1);

    verify(orderRepository).findById(initial.getId());
    verify(systemCloseOrderService).executeProtection(initial.getId(), snapshot);
  }

  private static OrderEntity trailing(
      OrderSide closeSide,
      String activationPrice,
      String trailingDelta,
      String trailingRate
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setSymbol("BTCUSDT-PERP");
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setSide(closeSide);
    order.setOrderType(OrderType.TRAILING_STOP_MARKET);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setActivationPrice(nullableDecimal(activationPrice));
    order.setTrailingDelta(nullableDecimal(trailingDelta));
    order.setTrailingRate(nullableDecimal(trailingRate));
    order.setReduceOnly(true);
    order.setOrderOrigin(OrderOrigin.PROTECTIVE);
    order.setProtectionType(ProtectionType.STOP_LOSS);
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    order.setParentPositionId(UUID.randomUUID());
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setVersion(0L);
    return order;
  }

  private static ExecutableMarketSnapshot snapshot(String mark) {
    Instant asOf = Instant.parse("2026-07-18T00:00:00Z");
    return new ExecutableMarketSnapshot(
        "BTCUSDT-PERP",
        ProductType.LINEAR_PERP,
        "DEMO",
        "BTCUSDT",
        MarketSourceMode.LOCAL_SIMULATED,
        decimal(mark).subtract(new BigDecimal("0.5")),
        decimal(mark).add(new BigDecimal("0.5")),
        decimal(mark),
        decimal(mark),
        decimal(mark),
        asOf,
        asOf.plusSeconds(30));
  }

  private static BigDecimal nullableDecimal(String value) {
    return value == null ? null : decimal(value);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
