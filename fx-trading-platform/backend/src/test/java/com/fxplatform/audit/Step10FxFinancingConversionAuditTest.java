package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.entity.ForexConversionRateEntity;
import com.fxplatform.risk.repository.ForexConversionRateRepository;
import com.fxplatform.risk.service.ForexConversionService;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.trading.entity.ForexFinancingRateEntity;
import com.fxplatform.trading.entity.ForexFinancingSettlementEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.ForexFinancingRateRepository;
import com.fxplatform.trading.repository.ForexFinancingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.ForexFinancingService;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step10FxFinancingConversionAuditTest {

  @Mock
  private ForexConversionRateRepository conversionRateRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private ForexFinancingRateRepository financingRateRepository;

  @Mock
  private ForexFinancingSettlementRepository financingSettlementRepository;

  @Mock
  private LedgerService ledgerService;

  @Test
  void pnlCalculatorConvertsJpyPnlToUsdWithConfiguredRate() {
    when(conversionRateRepository.findLatest("JPY", "USD"))
        .thenReturn(Optional.of(conversionRate("JPY", "USD", "0.0066442")));

    BigDecimal pnl = new PnLCalculator(
        new TradingAlgorithmEngine(),
        new ForexConversionService(conversionRateRepository))
        .floatingPnl("USDJPY", "USD", OrderSide.BUY, BigDecimal.ONE, new BigDecimal("150.005"), new BigDecimal("150.505"));

    AuditAssertions.assertFxConversionClose(pnl, "332.21");
  }

  @Test
  void accountSnapshotConvertsUsdJpyFloatingPnlToAccountCurrency() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000", "1000.00000000");
    PositionEntity position = openForex(accountId, "USDJPY", "1.00", "150.005", "1000.00000000");

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("USDJPY")).thenReturn(Optional.of(forexSymbol("USDJPY")));
    when(quoteService.freshQuote("USDJPY")).thenReturn(quote("USDJPY", "150.505", "150.510"));
    when(conversionRateRepository.findLatest("JPY", "USD"))
        .thenReturn(Optional.of(conversionRate("JPY", "USD", "0.0066442")));

    AccountSnapshot snapshot = new AccountSnapshotService(
        accountRepository,
        positionRepository,
        quoteService,
        new PnLCalculator(new TradingAlgorithmEngine(), new ForexConversionService(conversionRateRepository)),
        symbolRepository)
        .snapshot(account);

    AuditAssertions.assertFxConversionClose(snapshot.openFloatingPnl(), "332.21");
  }

  @Test
  void missingConversionRateFailsClosed() {
    when(conversionRateRepository.findLatest("JPY", "USD")).thenReturn(Optional.empty());
    when(conversionRateRepository.findLatest("USD", "JPY")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> new ForexConversionService(conversionRateRepository)
        .convert(new BigDecimal("50000"), "JPY", "USD"))
        .hasMessageContaining("FX conversion rate not found");
  }

  @Test
  void dailyForexFinancingUpdatesAccountPositionAndLedger() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000", "1000.00000000");
    PositionEntity position = openForex(accountId, "EURUSD", "1.00", "1.10000", "1000.00000000");
    position.setNotional(new BigDecimal("100000.00000000"));
    ForexFinancingRateEntity rate = financingRate("EURUSD", "-0.0365", "0.01825", 365);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol("EURUSD")));
    when(financingRateRepository.findLatestBySymbol("EURUSD")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class))).thenReturn(true);

    BigDecimal settled = financingService().settleDailyFinancing(accountId);

    AuditAssertions.assertAmountClose(settled, "-10.00000000");
    AuditAssertions.assertAmountClose(account.getBalance(), "9990.00000000");
    AuditAssertions.assertAmountClose(position.getFinancingAccrued(), "-10.00000000");
    verify(ledgerService).recordFinancingSettlement(
        eq(account),
        eq(new BigDecimal("-10.00000000")),
        any(UUID.class),
        eq("FX rollover financing"));
  }

  @Test
  void dailyForexFinancingConvertsJpyBasisToUsdLedgerAmount() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000", "1000.00000000");
    PositionEntity position = openForex(accountId, "USDJPY", "1.00", "100.00000", "1000.00000000");
    position.setNotional(new BigDecimal("10000000.00000000"));
    ForexFinancingRateEntity rate = financingRate("USDJPY", "-0.036", "0.018", 360);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("USDJPY")).thenReturn(Optional.of(forexSymbol("USDJPY")));
    when(financingRateRepository.findLatestBySymbol("USDJPY")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(conversionRateRepository.findLatest("JPY", "USD"))
        .thenReturn(Optional.of(conversionRate("JPY", "USD", "0.01000000")));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class))).thenReturn(true);

    BigDecimal settled = financingService().settleDailyFinancing(accountId);

    AuditAssertions.assertAmountClose(settled, "-10.00000000");
    AuditAssertions.assertAmountClose(account.getBalance(), "9990.00000000");
    AuditAssertions.assertAmountClose(position.getFinancingAccrued(), "-10.00000000");
    verify(ledgerService).recordFinancingSettlement(
        eq(account),
        eq(new BigDecimal("-10.00000000")),
        any(UUID.class),
        eq("FX rollover financing"));
  }

  private ForexFinancingService financingService() {
    try {
      var constructor = ForexFinancingService.class.getDeclaredConstructor(
          ForexFinancingRateRepository.class,
          ForexFinancingSettlementRepository.class,
          PositionRepository.class,
          TradingAccountRepository.class,
          LedgerService.class,
          SymbolRepository.class,
          TradingInstrumentClassifier.class,
          ForexConversionService.class,
          Clock.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          financingRateRepository,
          financingSettlementRepository,
          positionRepository,
          accountRepository,
          ledgerService,
          symbolRepository,
          new TradingInstrumentClassifier(),
          new ForexConversionService(conversionRateRepository),
          Clock.fixed(Instant.parse("2026-06-16T12:00:00Z"), ZoneOffset.UTC));
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Unable to construct ForexFinancingService with fixed audit clock", ex);
    }
  }

  private static TradingAccountEntity account(UUID accountId, String balance, String usedMargin) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal(balance));
    account.setEquity(new BigDecimal(balance));
    account.setUsedMargin(new BigDecimal(usedMargin));
    account.setFreeMargin(new BigDecimal(balance).subtract(new BigDecimal(usedMargin)));
    account.setLeverage(100);
    return account;
  }

  private static PositionEntity openForex(
      UUID accountId,
      String symbol,
      String lots,
      String openPrice,
      String marginHeld
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal(lots));
    position.setOpenPrice(new BigDecimal(openPrice));
    position.setCurrentPrice(new BigDecimal(openPrice));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setFinancingAccrued(BigDecimal.ZERO);
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static SymbolEntity forexSymbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("FOREX");
    symbol.setProductType(ProductType.FX_MARGIN);
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency(symbolCode.substring(3));
    symbol.setLotSize(new BigDecimal("100000"));
    return symbol;
  }

  private static QuoteResponse quote(String symbol, String bid, String ask) {
    BigDecimal bidPrice = new BigDecimal(bid);
    BigDecimal askPrice = new BigDecimal(ask);
    return new QuoteResponse(
        "quote",
        symbol,
        bidPrice,
        askPrice,
        bidPrice.add(askPrice).divide(new BigDecimal("2")),
        askPrice.subtract(bidPrice),
        "audit",
        1781667600000L);
  }

  private static ForexConversionRateEntity conversionRate(String from, String to, String rateValue) {
    ForexConversionRateEntity rate = new ForexConversionRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setFromCurrency(from);
    rate.setToCurrency(to);
    rate.setRate(new BigDecimal(rateValue));
    rate.setEffectiveAt(Instant.parse("2026-06-16T00:00:00Z"));
    rate.setSource("audit");
    return rate;
  }

  private static ForexFinancingRateEntity financingRate(
      String symbol,
      String longRate,
      String shortRate,
      int dayCount
  ) {
    ForexFinancingRateEntity rate = new ForexFinancingRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setSymbol(symbol);
    rate.setLongRateAnnual(new BigDecimal(longRate));
    rate.setShortRateAnnual(new BigDecimal(shortRate));
    rate.setDayCount(dayCount);
    rate.setEffectiveDate(LocalDate.parse("2026-06-16"));
    rate.setSource("audit");
    return rate;
  }
}
