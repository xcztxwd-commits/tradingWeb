package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.fxplatform.trading.service.PositionEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step06PositionEngineAuditTest {

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Test
  void forexOppositeFillReducesNetPositionAndWritesPnlLedger() {
    UUID accountId = UUID.randomUUID();
    PositionEntity existing = openPosition(accountId, "EURUSD", OrderSide.BUY, "0.20", "1.10000", "220.00000000", 100);
    TradingAccountEntity account = account(accountId, "20000.00000000", "220.00000000", 100);
    when(positionRepository.findOpenNetPosition(accountId, "EURUSD")).thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PositionEntity reduced = engine().applyFill(
        account,
        order(accountId, "EURUSD", OrderSide.SELL, "0.10", 100),
        fill("1.12000", "0.10"),
        forex()).position();

    assertThat(reduced.getStatus()).isEqualTo(PositionStatus.OPEN);
    AuditAssertions.assertAmountClose(reduced.getLots(), "0.10000000");
    AuditAssertions.assertAmountClose(reduced.getMarginHeld(), "110.00000000");
    AuditAssertions.assertAmountClose(reduced.getRealizedPnl(), "200.00000000");
    verify(ledgerService).recordMarginRelease(account, new BigDecimal("110.00000000"), existing.getId(), "Position margin released");
    verify(ledgerService).recordTradePnl(account, new BigDecimal("200.00000000"), existing.getId(), "Position realized PnL");
  }

  @Test
  void inversePerpetualShouldUseNetPositionEngineLikeLinearPerp() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "1.00000000", "0.00000000", 10, "BTC");
    lenient().when(positionRepository.findOpenNetPosition(accountId, "BTCUSD")).thenReturn(Optional.empty());
    lenient().when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    lenient().when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);

    assertThatCode(() -> engine().applyFill(
        account,
        order(accountId, "BTCUSD", OrderSide.BUY, "100", 10),
        fill("50000.00000000", "100"),
        inversePerp()))
        .as("Step 6 requires INVERSE_PERPETUAL net position support, not fallback one-position-per-fill mode")
        .doesNotThrowAnyException();
  }

  @Test
  void inversePerpetualSameSideFillUsesUsdNotionalHarmonicEntry() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "1.00000000", "0.00000000", 10, "BTC");
    PositionEngine engine = engine();
    when(positionRepository.findOpenNetPosition(accountId, "BTCUSD")).thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);

    PositionEntity first = engine.applyFill(
        account,
        order(accountId, "BTCUSD", OrderSide.BUY, "100", 10),
        fill("50000.00000000", "100"),
        inversePerp()).position();

    when(positionRepository.findOpenNetPosition(accountId, "BTCUSD")).thenReturn(Optional.of(first));

    PositionEntity merged = engine.applyFill(
        account,
        order(accountId, "BTCUSD", OrderSide.BUY, "100", 10),
        fill("55000.00000000", "100"),
        inversePerp()).position();

    AuditAssertions.assertAmountClose(merged.getLots(), "200.00000000");
    AuditAssertions.assertAmountClose(merged.getOpenPrice(), "52380.95238095");
    assertThat(merged.getOpenPrice()).isNotEqualByComparingTo("52500.00000000");
  }

  @Test
  void inversePerpetualOppositeFillRealizesPnlInSettlementCoinAndKeepsEntry() {
    UUID accountId = UUID.randomUUID();
    PositionEntity existing = openInversePosition(
        accountId,
        "BTCUSD",
        OrderSide.BUY,
        "100",
        "50000.00000000",
        "0.02000000",
        10);
    TradingAccountEntity account = account(accountId, "1.00000000", "0.02000000", 10, "BTC");
    when(positionRepository.findOpenNetPosition(accountId, "BTCUSD")).thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PositionEntity reduced = engine().applyFill(
        account,
        order(accountId, "BTCUSD", OrderSide.SELL, "50", 10),
        fill("55000.00000000", "50"),
        inversePerp()).position();

    assertThat(reduced.getStatus()).isEqualTo(PositionStatus.OPEN);
    AuditAssertions.assertAmountClose(reduced.getLots(), "50.00000000");
    AuditAssertions.assertAmountClose(reduced.getOpenPrice(), "50000.00000000");
    AuditAssertions.assertBtcClose(reduced.getRealizedPnl(), "0.00909091");
    AuditAssertions.assertBtcClose(account.getBalance(), "1.00909091");
    AuditAssertions.assertBtcClose(account.getUsedMargin(), "0.01000000");
    verify(ledgerService).recordMarginRelease(account, new BigDecimal("0.01000000"), existing.getId(), "Position margin released");
    verify(ledgerService).recordTradePnl(account, new BigDecimal("0.00909091"), existing.getId(), "Position realized PnL");
  }

  private PositionEngine engine() {
    return new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new MarginCalculator(),
        new PnLCalculator());
  }

  private static TradingAccountEntity account(UUID accountId, String balance, String usedMargin, int leverage) {
    return account(accountId, balance, usedMargin, leverage, "USD");
  }

  private static TradingAccountEntity account(UUID accountId, String balance, String usedMargin, int leverage, String baseCurrency) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency(baseCurrency);
    account.setBalance(new BigDecimal(balance));
    account.setEquity(new BigDecimal(balance));
    account.setUsedMargin(new BigDecimal(usedMargin));
    account.setFreeMargin(new BigDecimal(balance).subtract(new BigDecimal(usedMargin)));
    account.setLeverage(leverage);
    return account;
  }

  private static OrderEntity order(UUID accountId, String symbol, OrderSide side, String quantity, int leverage) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol(symbol);
    order.setSide(side);
    order.setLots(new BigDecimal(quantity));
    order.setQuantity(new BigDecimal(quantity));
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

  private static PositionEntity openInversePosition(
      UUID accountId,
      String symbol,
      OrderSide side,
      String lots,
      String openPrice,
      String marginHeld,
      int leverage
  ) {
    PositionEntity position = openPosition(accountId, symbol, side, lots, openPrice, marginHeld, leverage);
    BigDecimal contracts = new BigDecimal(lots);
    BigDecimal contractSize = new BigDecimal("100");
    BigDecimal price = new BigDecimal(openPrice);
    BigDecimal usdNotional = contracts.multiply(contractSize);
    position.setNotional(usdNotional);
    position.setInitialMargin(new BigDecimal(marginHeld));
    position.setMaintenanceMargin(usdNotional.multiply(new BigDecimal("0.005")).divide(price, 8, java.math.RoundingMode.HALF_UP));
    position.setMarkPrice(price);
    position.setSettlementAsset("BTC");
    position.setMarginAsset("BTC");
    return position;
  }

  private static ExecutionResult fill(String price, String quantity) {
    return new ExecutionResult(
        new BigDecimal(price),
        Instant.parse("2026-06-16T01:00:00Z"),
        new BigDecimal(quantity),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        null);
  }

  private static InstrumentProfile forex() {
    return new InstrumentProfile(InstrumentKind.FOREX, new BigDecimal("100000"), "FOREX", "LOT");
  }

  private static InstrumentProfile inversePerp() {
    return new InstrumentProfile(
        InstrumentKind.INVERSE_PERPETUAL,
        new BigDecimal("100"),
        "SWAP",
        "CONTRACT",
        new BigDecimal("100"),
        BigDecimal.ONE,
        new BigDecimal("0.005"),
        "BTC",
        "BTC");
  }

  private static PositionEntity withId(PositionEntity position) {
    if (position.getId() == null) {
      position.setId(UUID.randomUUID());
    }
    return position;
  }
}
