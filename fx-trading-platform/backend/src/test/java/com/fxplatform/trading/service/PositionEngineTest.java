package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PositionEngineTest {

  private static final Instant FILLED_AT = Instant.parse("2026-06-16T01:00:00Z");

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Test
  void forexSameSideBuyFillsMergeLotsAndAverageEntry() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000.00000000"), BigDecimal.ZERO, 100);
    PositionEngine engine = engine();
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));

    OrderEntity firstOrder = order(accountId, "EURUSD", OrderSide.BUY, new BigDecimal("0.10"), 100);
    when(positionRepository.findOpenNetPosition(accountId, "EURUSD")).thenReturn(Optional.empty());
    PositionEntity first = engine.applyFill(
        account,
        firstOrder,
        fill(new BigDecimal("1.10000"), new BigDecimal("0.10")),
        forex()).position();

    OrderEntity secondOrder = order(accountId, "EURUSD", OrderSide.BUY, new BigDecimal("0.10"), 100);
    when(positionRepository.findOpenNetPosition(accountId, "EURUSD")).thenReturn(Optional.of(first));

    PositionEntity merged = engine.applyFill(
        account,
        secondOrder,
        fill(new BigDecimal("1.20000"), new BigDecimal("0.10")),
        forex()).position();

    assertThat(merged.getId()).isEqualTo(first.getId());
    assertThat(merged.getSide()).isEqualTo(OrderSide.BUY);
    assertThat(merged.getLots()).isEqualByComparingTo("0.20");
    assertThat(merged.getOpenPrice()).isEqualByComparingTo("1.15000000");
    assertThat(merged.getMarginHeld()).isEqualByComparingTo("230.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("230.00000000");
    verify(ledgerService).recordMarginHold(eq(account), eq(new BigDecimal("110.00000000")), eq(first.getId()), eq("Position margin held"));
    verify(ledgerService).recordMarginHold(eq(account), eq(new BigDecimal("120.00000000")), eq(first.getId()), eq("Position margin increased"));
  }

  @Test
  void forexOppositeFillReducesLongPositionAndKeepsAverageEntry() {
    UUID accountId = UUID.randomUUID();
    PositionEntity existing = openPosition(accountId, "EURUSD", OrderSide.BUY, "0.20", "1.10000", "220.00000000", 100);
    TradingAccountEntity account = account(accountId, new BigDecimal("20000.00000000"), new BigDecimal("220.00000000"), 100);
    when(positionRepository.findOpenNetPosition(accountId, "EURUSD")).thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PositionEntity reduced = engine().applyFill(
        account,
        order(accountId, "EURUSD", OrderSide.SELL, new BigDecimal("0.10"), 100),
        fill(new BigDecimal("1.12000"), new BigDecimal("0.10")),
        forex()).position();

    assertThat(reduced.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(reduced.getLots()).isEqualByComparingTo("0.10");
    assertThat(reduced.getOpenPrice()).isEqualByComparingTo("1.10000");
    assertThat(reduced.getMarginHeld()).isEqualByComparingTo("110.00000000");
    assertThat(reduced.getRealizedPnl()).isEqualByComparingTo("200.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("20200.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("110.00000000");
    verify(ledgerService).recordMarginRelease(account, new BigDecimal("110.00000000"), existing.getId(), "Position margin released");
    verify(ledgerService).recordTradePnl(account, new BigDecimal("200.00000000"), existing.getId(), "Position realized PnL");
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
  }

  @Test
  void forexLargerOppositeFillClosesLongAndOpensShortRemainder() {
    UUID accountId = UUID.randomUUID();
    PositionEntity existing = openPosition(accountId, "EURUSD", OrderSide.BUY, "0.10", "1.10000", "110.00000000", 100);
    TradingAccountEntity account = account(accountId, new BigDecimal("20000.00000000"), new BigDecimal("110.00000000"), 100);
    when(positionRepository.findOpenNetPosition(accountId, "EURUSD")).thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    when(accountRepository.reserveMarginIfAvailable(accountId, new BigDecimal("2.00000000"))).thenReturn(1);

    PositionEntity shortRemainder = engine().applyFill(
        account,
        order(accountId, "EURUSD", OrderSide.SELL, new BigDecimal("0.20"), 100),
        fill(new BigDecimal("1.12000"), new BigDecimal("0.20")),
        forex()).position();

    assertThat(existing.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(existing.getLots()).isEqualByComparingTo("0.10");
    assertThat(existing.getMarginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(existing.getRealizedPnl()).isEqualByComparingTo("200.00000000");
    assertThat(shortRemainder.getId()).isNotEqualTo(existing.getId());
    assertThat(shortRemainder.getSide()).isEqualTo(OrderSide.SELL);
    assertThat(shortRemainder.getLots()).isEqualByComparingTo("0.10");
    assertThat(shortRemainder.getOpenPrice()).isEqualByComparingTo("1.12000");
    assertThat(shortRemainder.getMarginHeld()).isEqualByComparingTo("112.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("112.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("20200.00000000");
    verify(ledgerService).recordMarginRelease(account, new BigDecimal("110.00000000"), existing.getId(), "Position margin released");
    verify(ledgerService).recordMarginHold(account, new BigDecimal("112.00000000"), shortRemainder.getId(), "Position margin held");
    verify(ledgerService).recordTradePnl(account, new BigDecimal("200.00000000"), existing.getId(), "Position realized PnL");
  }

  @Test
  void linearPerpetualSameSideFillRecalculatesAverageEntry() {
    UUID accountId = UUID.randomUUID();
    PositionEntity existing = openPosition(accountId, "BTCUSDT", OrderSide.BUY, "2.00", "100.00000000", "20.00000000", 10);
    existing.setNotional(new BigDecimal("200.00000000"));
    existing.setInitialMargin(new BigDecimal("20.00000000"));
    existing.setMaintenanceMargin(new BigDecimal("1.00000000"));
    existing.setMarkPrice(new BigDecimal("100.00000000"));
    TradingAccountEntity account = account(accountId, new BigDecimal("10000.00000000"), new BigDecimal("20.00000000"), 10);
    when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT")).thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.reserveMarginIfAvailable(accountId, new BigDecimal("13.00000000"))).thenReturn(1);

    PositionEntity merged = engine().applyFill(
        account,
        order(accountId, "BTCUSDT", OrderSide.BUY, new BigDecimal("1.00"), 10),
        fill(new BigDecimal("110.00000000"), new BigDecimal("1.00")),
        linearWithMaintenance()).position();

    assertThat(merged.getLots()).isEqualByComparingTo("3.00");
    assertThat(merged.getOpenPrice()).isEqualByComparingTo("103.33333333");
    assertThat(merged.getMarkPrice()).isEqualByComparingTo("110.00000000");
    assertThat(merged.getNotional()).isEqualByComparingTo("330.00000000");
    assertThat(merged.getInitialMargin()).isEqualByComparingTo("33.00000000");
    assertThat(merged.getMaintenanceMargin()).isEqualByComparingTo("1.65000000");
    assertThat(merged.getMarginHeld()).isEqualByComparingTo("33.00000000");
    verify(ledgerService).recordMarginHold(account, new BigDecimal("13.00000000"), existing.getId(), "Position margin increased");
  }

  @Test
  void linearPerpetualOpenWritesBaseMarginSnapshot() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000.00000000"), BigDecimal.ZERO, 10);
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT")).thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));

    PositionEntity opened = engine().applyFill(
        account,
        order(accountId, "BTCUSDT", OrderSide.BUY, new BigDecimal("1.00"), 10),
        fill(new BigDecimal("50000.00000000"), new BigDecimal("1.00")),
        linearWithMaintenance()).position();

    assertThat(opened.getMarkPrice()).isEqualByComparingTo("50000.00000000");
    assertThat(opened.getNotional()).isEqualByComparingTo("50000.00000000");
    assertThat(opened.getInitialMargin()).isEqualByComparingTo("5000.00000000");
    assertThat(opened.getMaintenanceMargin()).isEqualByComparingTo("250.00000000");
    assertThat(opened.getMarginHeld()).isEqualByComparingTo(opened.getInitialMargin());
  }

  @Test
  void linearPerpetualOppositeFillReducesPositionAndRealizesPnl() {
    UUID accountId = UUID.randomUUID();
    PositionEntity existing = openPosition(accountId, "BTCUSDT", OrderSide.BUY, "2.00", "100.00000000", "20.00000000", 10);
    TradingAccountEntity account = account(accountId, new BigDecimal("10000.00000000"), new BigDecimal("20.00000000"), 10);
    when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT")).thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PositionEntity reduced = engine().applyFill(
        account,
        order(accountId, "BTCUSDT", OrderSide.SELL, new BigDecimal("0.50"), 10),
        fill(new BigDecimal("110.00000000"), new BigDecimal("0.50")),
        linear()).position();

    assertThat(reduced.getLots()).isEqualByComparingTo("1.50");
    assertThat(reduced.getOpenPrice()).isEqualByComparingTo("100.00000000");
    assertThat(reduced.getMarginHeld()).isEqualByComparingTo("15.00000000");
    assertThat(reduced.getRealizedPnl()).isEqualByComparingTo("5.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("10005.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("15.00000000");
    verify(ledgerService).recordMarginRelease(account, new BigDecimal("5.00000000"), existing.getId(), "Position margin released");
    verify(ledgerService).recordTradePnl(account, new BigDecimal("5.00000000"), existing.getId(), "Position realized PnL");
  }

  private PositionEngine engine() {
    return new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new MarginCalculator(),
        new PnLCalculator());
  }

  private static TradingAccountEntity account(UUID accountId, BigDecimal balance, BigDecimal usedMargin, int leverage) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBalance(balance);
    account.setEquity(balance);
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(balance.subtract(usedMargin));
    account.setLeverage(leverage);
    account.setBaseCurrency("USD");
    return account;
  }

  private static OrderEntity order(UUID accountId, String symbol, OrderSide side, BigDecimal quantity, int leverage) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol(symbol);
    order.setSide(side);
    order.setLots(quantity);
    order.setQuantity(quantity);
    order.setLeverage(leverage);
    return order;
  }

  private static PositionEntity openPosition(
      UUID accountId,
      String symbol,
      OrderSide side,
      String lots,
      String openPrice,
      String marginHeld,
      int leverage
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setSide(side);
    position.setLots(new BigDecimal(lots));
    position.setOpenPrice(new BigDecimal(openPrice));
    position.setCurrentPrice(new BigDecimal(openPrice));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setRealizedPnl(BigDecimal.ZERO);
    position.setStatus(PositionStatus.OPEN);
    position.setLeverage(leverage);
    return position;
  }

  private static ExecutionResult fill(BigDecimal price, BigDecimal quantity) {
    return new ExecutionResult(price, FILLED_AT, quantity, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
  }

  private static InstrumentProfile forex() {
    return new InstrumentProfile(InstrumentKind.FOREX, new BigDecimal("100000"), "FOREX", "LOT");
  }

  private static InstrumentProfile linear() {
    return new InstrumentProfile(InstrumentKind.LINEAR_PERPETUAL, BigDecimal.ONE, "SWAP", "CONTRACT");
  }

  private static InstrumentProfile linearWithMaintenance() {
    return new InstrumentProfile(
        InstrumentKind.LINEAR_PERPETUAL,
        BigDecimal.ONE,
        "SWAP",
        "CONTRACT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        new BigDecimal("0.005"),
        "USDT",
        "USDT");
  }

  private static PositionEntity withId(PositionEntity position) {
    if (position.getId() == null) {
      position.setId(UUID.randomUUID());
    }
    return position;
  }
}
