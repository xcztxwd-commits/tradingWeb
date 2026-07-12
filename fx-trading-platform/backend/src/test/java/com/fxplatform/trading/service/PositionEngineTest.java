package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

  @BeforeEach
  void canonicalOneWaySlotDelegatesToExistingNetFixtures() {
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        any(UUID.class), any(String.class), eq(PositionMode.ONE_WAY), eq(PositionSide.BOTH)))
        .thenAnswer(invocation -> positionRepository.findOpenNetPosition(
            invocation.getArgument(0), invocation.getArgument(1)));
  }

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
  void linearPerpetualLongAddReduceAndCloseKeepsWeightedAverageAndRealizedPnl() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("10000.00000000"), BigDecimal.ZERO, 20);
    PositionEngine engine = engine();
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));

    when(positionRepository.findOpenNetPosition(accountId, "ETHUSDT")).thenReturn(Optional.empty());
    PositionEntity first = engine.applyFill(
        account,
        order(accountId, "ETHUSDT", OrderSide.BUY, new BigDecimal("1.00"), 20),
        fill(new BigDecimal("3000.00000000"), new BigDecimal("1.00")),
        linearWithMaintenance()).position();

    assertThat(first.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(first.getSide()).isEqualTo(OrderSide.BUY);
    assertThat(first.getLots()).isEqualByComparingTo("1.00");
    assertThat(first.getOpenPrice()).isEqualByComparingTo("3000.00000000");
    assertThat(first.getMarginHeld()).isEqualByComparingTo("150.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("150.00000000");

    when(positionRepository.findOpenNetPosition(accountId, "ETHUSDT")).thenReturn(Optional.of(first));
    PositionEntity second = engine.applyFill(
        account,
        order(accountId, "ETHUSDT", OrderSide.BUY, new BigDecimal("1.00"), 20),
        fill(new BigDecimal("3200.00000000"), new BigDecimal("1.00")),
        linearWithMaintenance()).position();

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(second.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(second.getLots()).isEqualByComparingTo("2.00");
    assertThat(second.getOpenPrice()).isEqualByComparingTo("3100.00000000");
    assertThat(second.getMarginHeld()).isEqualByComparingTo("320.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("320.00000000");

    when(positionRepository.findOpenNetPosition(accountId, "ETHUSDT")).thenReturn(Optional.of(second));
    PositionEntity third = engine.applyFill(
        account,
        order(accountId, "ETHUSDT", OrderSide.BUY, new BigDecimal("2.00"), 20),
        fill(new BigDecimal("2800.00000000"), new BigDecimal("2.00")),
        linearWithMaintenance()).position();

    assertThat(third.getId()).isEqualTo(first.getId());
    assertThat(third.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(third.getLots()).isEqualByComparingTo("4.00");
    assertThat(third.getOpenPrice()).isEqualByComparingTo("2950.00000000");
    assertThat(third.getMarkPrice()).isEqualByComparingTo("2800.00000000");
    assertThat(third.getNotional()).isEqualByComparingTo("11200.00000000");
    assertThat(third.getInitialMargin()).isEqualByComparingTo("560.00000000");
    assertThat(third.getMarginHeld()).isEqualByComparingTo("560.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("560.00000000");

    when(positionRepository.findOpenNetPosition(accountId, "ETHUSDT")).thenReturn(Optional.of(third));
    PositionEntity halfClosed = engine.applyFill(
        account,
        order(accountId, "ETHUSDT", OrderSide.SELL, new BigDecimal("2.00"), 20),
        fill(new BigDecimal("3300.00000000"), new BigDecimal("2.00")),
        linearWithMaintenance()).position();

    assertThat(halfClosed.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(halfClosed.getLots()).isEqualByComparingTo("2.00");
    assertThat(halfClosed.getOpenPrice()).isEqualByComparingTo("2950.00000000");
    assertThat(halfClosed.getRealizedPnl()).isEqualByComparingTo("700.00000000");
    assertThat(halfClosed.getMarginHeld()).isEqualByComparingTo("280.00000000");
    assertThat(halfClosed.getMarkPrice()).isEqualByComparingTo("3300.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("10700.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("280.00000000");

    when(positionRepository.findOpenNetPosition(accountId, "ETHUSDT")).thenReturn(Optional.of(halfClosed));
    PositionEntity fullyClosed = engine.applyFill(
        account,
        order(accountId, "ETHUSDT", OrderSide.SELL, new BigDecimal("2.00"), 20),
        fill(new BigDecimal("3100.00000000"), new BigDecimal("2.00")),
        linearWithMaintenance()).position();

    assertThat(fullyClosed.getId()).isEqualTo(first.getId());
    assertThat(fullyClosed.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(fullyClosed.getClosedAt()).isEqualTo(FILLED_AT);
    assertThat(fullyClosed.getLots()).isEqualByComparingTo("2.00");
    assertThat(fullyClosed.getRealizedPnl()).isEqualByComparingTo("1000.00000000");
    assertThat(fullyClosed.getFloatingPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(fullyClosed.getMarginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(fullyClosed.getNotional()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(fullyClosed.getInitialMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(fullyClosed.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(fullyClosed.getMarkPrice()).isEqualByComparingTo("3100.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("11000.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(ledgerService).recordTradePnl(account, new BigDecimal("700.00000000"), first.getId(), "Position realized PnL");
    verify(ledgerService).recordTradePnl(account, new BigDecimal("300.00000000"), first.getId(), "Position realized PnL");
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

  @Test
  void oneWayPerpetualWritesCanonicalBothSlotAndOrderSnapshots() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000"), BigDecimal.ZERO, 10);
    account.setPositionMode(PositionMode.ONE_WAY);
    OrderEntity order = perpetualOrder(
        accountId, "BTCUSDT-PERP", OrderSide.BUY, "0.1000",
        PositionMode.ONE_WAY, PositionSide.BOTH, MarginMode.ISOLATED, 20, false);
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.empty());
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    org.mockito.Mockito.clearInvocations(positionRepository);

    PositionEntity opened = engine().applyFill(
        account, order, fill(new BigDecimal("50000"), new BigDecimal("0.1000")),
        canonicalLinear("0.01", "10")).position();

    assertThat(opened.getProductType()).isEqualTo(ProductType.LINEAR_PERP);
    assertThat(opened.getPositionMode()).isEqualTo(PositionMode.ONE_WAY);
    assertThat(opened.getPositionSide()).isEqualTo(PositionSide.BOTH);
    assertThat(opened.getMarginMode()).isEqualTo(MarginMode.ISOLATED);
    assertThat(opened.getLeverage()).isEqualTo(20);
    assertThat(opened.getLots()).isEqualByComparingTo("0.1000");
    assertThat(opened.getNotional()).isEqualByComparingTo("5000.00000000");
    verify(positionRepository).findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH);
  }

  @Test
  void oneWayReduceOnlyRejectsIncreaseAndOverCloseBeforeMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000"), new BigDecimal("5000"), 10);
    account.setPositionMode(PositionMode.ONE_WAY);
    PositionEntity existing = canonicalPosition(
        accountId, "BTCUSDT-PERP", OrderSide.BUY, "1", "50000", "5000",
        PositionMode.ONE_WAY, PositionSide.BOTH);
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(existing));
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(existing));

    assertCode("REDUCE_ONLY_EXCEEDS_POSITION", () -> engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.SELL, "2", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, true),
        fill(new BigDecimal("51000"), new BigDecimal("2")), linearWithMaintenance()));
    assertCode("REDUCE_ONLY_WOULD_INCREASE", () -> engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.BUY, "0.1", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, true),
        fill(new BigDecimal("51000"), new BigDecimal("0.1")), linearWithMaintenance()));

    assertThat(existing.getLots()).isEqualByComparingTo("1");
    assertThat(existing.getStatus()).isEqualTo(PositionStatus.OPEN);
    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    org.mockito.Mockito.verifyNoInteractions(ledgerService);
  }

  @Test
  void oneWayExactOppositeFillClosesTheBothSlot() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000"), new BigDecimal("5000"), 10);
    account.setPositionMode(PositionMode.ONE_WAY);
    PositionEntity existing = canonicalPosition(
        accountId, "BTCUSDT-PERP", OrderSide.BUY, "1", "50000", "5000",
        PositionMode.ONE_WAY, PositionSide.BOTH);
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(existing));
    when(positionRepository.save(existing)).thenReturn(existing);

    PositionEntity closed = engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.SELL, "1", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, true),
        fill(new BigDecimal("51000"), BigDecimal.ONE), linearWithMaintenance()).position();

    assertThat(closed.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(closed.getMarginHeld()).isZero();
    assertThat(closed.getRealizedPnl()).isEqualByComparingTo("1000.00000000");
  }

  @Test
  void reduceOnlyCannotOpenAnEmptyOneWaySlot() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000"), BigDecimal.ZERO, 10);
    account.setPositionMode(PositionMode.ONE_WAY);
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());

    assertCode("REDUCE_ONLY_WOULD_INCREASE", () -> engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.BUY, "0.1", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, true),
        fill(new BigDecimal("50000"), new BigDecimal("0.1")), linearWithMaintenance()));
    verify(positionRepository, never()).save(any(PositionEntity.class));
  }

  @Test
  void oneWayPerpetualIncreasesReducesAndReversesTheBothSlot() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("20000"), new BigDecimal("10"), 10);
    account.setPositionMode(PositionMode.ONE_WAY);
    PositionEntity existing = canonicalPosition(
        accountId, "ETHUSDT-PERP", OrderSide.BUY, "1", "100", "10",
        PositionMode.ONE_WAY, PositionSide.BOTH);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "ETHUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(existing));
    lenient().when(positionRepository.findOpenNetPosition(accountId, "ETHUSDT-PERP"))
        .thenReturn(Optional.of(existing));
    org.mockito.Mockito.clearInvocations(positionRepository);

    PositionEntity increased = engine().applyFill(
        account,
        perpetualOrder(accountId, "ETHUSDT-PERP", OrderSide.BUY, "1", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, false),
        fill(new BigDecimal("120"), BigDecimal.ONE), linearWithMaintenance()).position();
    assertThat(increased.getLots()).isEqualByComparingTo("2");
    assertThat(increased.getOpenPrice()).isEqualByComparingTo("110.00000000");

    PositionEngine.PositionUpdateResult reduction = engine().applyFill(
        account,
        perpetualOrder(accountId, "ETHUSDT-PERP", OrderSide.SELL, "0.5", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, false),
        fill(new BigDecimal("130"), new BigDecimal("0.5")), linearWithMaintenance());
    PositionEntity reduced = reduction.position();
    assertThat(reduced.getLots()).isEqualByComparingTo("1.5");
    assertThat(reduced.getOpenPrice()).isEqualByComparingTo("110.00000000");
    assertThat(reduction.reducedPositionId()).isEqualTo(existing.getId());
    assertThat(reduction.fromQuantity()).isEqualByComparingTo("2");
    assertThat(reduction.toQuantity()).isEqualByComparingTo("1.5");

    PositionEngine.PositionUpdateResult reversal = engine().applyFill(
        account,
        perpetualOrder(accountId, "ETHUSDT-PERP", OrderSide.SELL, "2", PositionMode.ONE_WAY,
            PositionSide.BOTH, MarginMode.CROSS, 10, false),
        fill(new BigDecimal("90"), new BigDecimal("2")), linearWithMaintenance());
    PositionEntity reversed = reversal.position();
    assertThat(existing.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(reversed.getId()).isNotEqualTo(existing.getId());
    assertThat(reversed.getSide()).isEqualTo(OrderSide.SELL);
    assertThat(reversed.getLots()).isEqualByComparingTo("0.5");
    assertThat(reversed.getPositionSide()).isEqualTo(PositionSide.BOTH);
    assertThat(reversal.reducedPositionId()).isEqualTo(existing.getId());
    assertThat(reversal.fromQuantity()).isEqualByComparingTo("1.5");
    assertThat(reversal.toQuantity()).isZero();
    verify(positionRepository, org.mockito.Mockito.times(3)).findOpenPerpetualSlotForUpdate(
        accountId, "ETHUSDT-PERP", PositionMode.ONE_WAY, PositionSide.BOTH);
  }

  @Test
  void hedgePerpetualMaintainsIndependentLongAndShortSlotsAndCloseDirections() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("30000"), BigDecimal.ZERO, 10);
    account.setPositionMode(PositionMode.HEDGE);
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.empty());
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.HEDGE, PositionSide.LONG))
        .thenReturn(Optional.empty());

    PositionEntity longLeg = engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.BUY, "1", PositionMode.HEDGE,
            PositionSide.LONG, MarginMode.ISOLATED, 20, false),
        fill(new BigDecimal("50000"), BigDecimal.ONE), linearWithMaintenance()).position();

    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.HEDGE, PositionSide.SHORT))
        .thenReturn(Optional.empty());
    PositionEntity shortLeg = engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.SELL, "2", PositionMode.HEDGE,
            PositionSide.SHORT, MarginMode.ISOLATED, 20, false),
        fill(new BigDecimal("50010"), new BigDecimal("2")), linearWithMaintenance()).position();

    assertThat(longLeg.getPositionSide()).isEqualTo(PositionSide.LONG);
    assertThat(longLeg.getSide()).isEqualTo(OrderSide.BUY);
    assertThat(shortLeg.getPositionSide()).isEqualTo(PositionSide.SHORT);
    assertThat(shortLeg.getSide()).isEqualTo(OrderSide.SELL);
    assertThat(longLeg.getId()).isNotEqualTo(shortLeg.getId());
    assertThat(longLeg.getLeverage()).isEqualTo(shortLeg.getLeverage()).isEqualTo(20);
    assertThat(longLeg.getMarginMode()).isEqualTo(shortLeg.getMarginMode()).isEqualTo(MarginMode.ISOLATED);

    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.HEDGE, PositionSide.LONG))
        .thenReturn(Optional.of(longLeg));
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(longLeg));
    PositionEntity reducedLong = engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.SELL, "0.4", PositionMode.HEDGE,
            PositionSide.LONG, MarginMode.ISOLATED, 20, true),
        fill(new BigDecimal("50100"), new BigDecimal("0.4")), linearWithMaintenance()).position();

    assertThat(reducedLong.getLots()).isEqualByComparingTo("0.6");
    assertThat(shortLeg.getLots()).isEqualByComparingTo("2");

    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.HEDGE, PositionSide.SHORT))
        .thenReturn(Optional.of(shortLeg));
    PositionEntity reducedShort = engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.BUY, "0.5", PositionMode.HEDGE,
            PositionSide.SHORT, MarginMode.ISOLATED, 20, true),
        fill(new BigDecimal("49900"), new BigDecimal("0.5")), linearWithMaintenance()).position();
    assertThat(reducedShort.getLots()).isEqualByComparingTo("1.5");
    assertThat(reducedLong.getLots()).isEqualByComparingTo("0.6");
  }

  @Test
  void hedgeRejectsBothSlotBeforeOpeningAnyPosition() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("30000"), BigDecimal.ZERO, 10);
    account.setPositionMode(PositionMode.HEDGE);
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.empty());

    assertCode("INVALID_POSITION_SIDE", () -> engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.BUY, "1", PositionMode.HEDGE,
            PositionSide.BOTH, MarginMode.CROSS, 10, false),
        fill(new BigDecimal("50000"), BigDecimal.ONE), linearWithMaintenance()));
  }

  @Test
  void hedgeMalformedExistingSlotDirectionRejectsBeforeMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(
        accountId, new BigDecimal("30000"), new BigDecimal("5000"), 10);
    account.setPositionMode(PositionMode.HEDGE);
    PositionEntity malformedLong = canonicalPosition(
        accountId,
        "BTCUSDT-PERP",
        OrderSide.SELL,
        "1",
        "50000",
        "5000",
        PositionMode.HEDGE,
        PositionSide.LONG);
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.HEDGE, PositionSide.LONG))
        .thenReturn(Optional.of(malformedLong));

    assertCode("INVALID_POSITION_SIDE", () -> engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.BUY, "0.1", PositionMode.HEDGE,
            PositionSide.LONG, MarginMode.CROSS, 10, false),
        fill(new BigDecimal("50100"), new BigDecimal("0.1")),
        linearWithMaintenance()));

    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    org.mockito.Mockito.verifyNoInteractions(ledgerService);
  }

  @Test
  void hedgeOverCloseRejectsWithoutClampingOrOpeningTheOppositeLeg() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, new BigDecimal("30000"), new BigDecimal("5000"), 10);
    account.setPositionMode(PositionMode.HEDGE);
    PositionEntity longLeg = canonicalPosition(
        accountId, "BTCUSDT-PERP", OrderSide.BUY, "1", "50000", "5000",
        PositionMode.HEDGE, PositionSide.LONG);
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT-PERP", PositionMode.HEDGE, PositionSide.LONG))
        .thenReturn(Optional.of(longLeg));
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(longLeg));

    assertCode("REDUCE_ONLY_EXCEEDS_POSITION", () -> engine().applyFill(
        account,
        perpetualOrder(accountId, "BTCUSDT-PERP", OrderSide.SELL, "2", PositionMode.HEDGE,
            PositionSide.LONG, MarginMode.CROSS, 10, false),
        fill(new BigDecimal("51000"), new BigDecimal("2")), linearWithMaintenance()));

    assertThat(longLeg.getLots()).isEqualByComparingTo("1");
    assertThat(longLeg.getStatus()).isEqualTo(PositionStatus.OPEN);
    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    org.mockito.Mockito.verifyNoInteractions(ledgerService);
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
    if (!"EURUSD".equals(symbol)) {
      order.setProductType(ProductType.LINEAR_PERP);
    }
    return order;
  }

  private static OrderEntity perpetualOrder(
      UUID accountId,
      String symbol,
      OrderSide side,
      String quantity,
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      int leverage,
      boolean reduceOnly
  ) {
    OrderEntity order = order(accountId, symbol, side, new BigDecimal(quantity), leverage);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(positionMode);
    order.setPositionSide(positionSide);
    order.setMarginMode(marginMode);
    order.setReduceOnly(reduceOnly);
    order.setBaseQuantity(new BigDecimal(quantity));
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

  private static InstrumentProfile canonicalLinear(String contractSize, String contractMultiplier) {
    return new InstrumentProfile(
        InstrumentKind.LINEAR_PERPETUAL,
        new BigDecimal(contractSize).multiply(new BigDecimal(contractMultiplier)),
        "SWAP",
        "CONTRACT",
        new BigDecimal(contractSize),
        new BigDecimal(contractMultiplier),
        new BigDecimal("0.005"),
        "USDT",
        "USDT");
  }

  private static PositionEntity canonicalPosition(
      UUID accountId,
      String symbol,
      OrderSide side,
      String quantity,
      String openPrice,
      String margin,
      PositionMode positionMode,
      PositionSide positionSide
  ) {
    PositionEntity position = openPosition(
        accountId, symbol, side, quantity, openPrice, margin, 10);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(positionMode);
    position.setPositionSide(positionSide);
    position.setMarginMode(MarginMode.CROSS);
    position.setNotional(new BigDecimal(quantity).multiply(new BigDecimal(openPrice)));
    position.setInitialMargin(new BigDecimal(margin));
    position.setMaintenanceMargin(new BigDecimal("1"));
    position.setMarkPrice(new BigDecimal(openPrice));
    return position;
  }

  private static void assertCode(
      String code,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action
  ) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private static PositionEntity withId(PositionEntity position) {
    if (position.getId() == null) {
      position.setId(UUID.randomUUID());
    }
    return position;
  }
}
