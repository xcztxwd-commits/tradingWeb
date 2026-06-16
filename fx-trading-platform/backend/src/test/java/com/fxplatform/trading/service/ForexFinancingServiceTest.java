package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.entity.ForexConversionRateEntity;
import com.fxplatform.risk.repository.ForexConversionRateRepository;
import com.fxplatform.risk.service.ForexConversionService;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.ForexFinancingRateEntity;
import com.fxplatform.trading.entity.ForexFinancingSettlementEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.ForexFinancingRateRepository;
import com.fxplatform.trading.repository.ForexFinancingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForexFinancingServiceTest {

  @Mock
  private ForexFinancingRateRepository financingRateRepository;

  @Mock
  private ForexFinancingSettlementRepository financingSettlementRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private ForexConversionRateRepository conversionRateRepository;

  private final Clock clock = Clock.fixed(Instant.parse("2026-06-16T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void longNegativeFinancingReducesAccountBalanceAndWritesLedger() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, OrderSide.BUY);
    ForexFinancingRateEntity rate = financingRate("EURUSD", "-0.036", "0.018", 360);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(financingRateRepository.findLatestBySymbol("EURUSD")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class))).thenReturn(true);

    BigDecimal settled = service().settleDailyFinancing(accountId);

    assertThat(settled).isEqualByComparingTo("-10.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("9990.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("9990.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("8990.00000000");
    assertThat(position.getFinancingAccrued()).isEqualByComparingTo("-10.00000000");
    verifyNoInteractions(conversionRateRepository);
    verify(accountRepository).save(account);
    verify(positionRepository).save(position);
    verify(ledgerService).recordFinancing(account, new BigDecimal("-10.00000000"), position.getId(), "FX rollover financing");
  }

  @Test
  void shortPositiveFinancingIncreasesAccountBalanceAndWritesLedger() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, OrderSide.SELL);
    ForexFinancingRateEntity rate = financingRate("EURUSD", "-0.036", "0.018", 360);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(financingRateRepository.findLatestBySymbol("EURUSD")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class))).thenReturn(true);

    BigDecimal settled = service().settleDailyFinancing(accountId);

    assertThat(settled).isEqualByComparingTo("5.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("10005.00000000");
    assertThat(position.getFinancingAccrued()).isEqualByComparingTo("5.00000000");
    verify(ledgerService).recordFinancing(account, new BigDecimal("5.00000000"), position.getId(), "FX rollover financing");
  }

  @Test
  void usdJpyQuotePositionValueFinancingConvertsToUsdAccountCurrency() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, OrderSide.BUY, "USDJPY", "10000000.00000000");
    ForexFinancingRateEntity rate = financingRate("USDJPY", "-0.036", "0.018", 360);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("USDJPY")).thenReturn(Optional.of(forexSymbol("USDJPY")));
    when(financingRateRepository.findLatestBySymbol("USDJPY")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(conversionRateRepository.findLatest("JPY", "USD"))
        .thenReturn(Optional.of(conversionRate("JPY", "USD", "0.01000000")));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class))).thenReturn(true);

    BigDecimal settled = service().settleDailyFinancing(accountId);

    assertThat(settled).isEqualByComparingTo("-10.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("9990.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("9990.00000000");
    assertThat(position.getFinancingAccrued()).isEqualByComparingTo("-10.00000000");
    verify(financingSettlementRepository).insertIfAbsent(argThat(settlement ->
        settlement.getAmount().compareTo(new BigDecimal("-10.00000000")) == 0
            && "USD".equals(settlement.getAsset())));
    verify(ledgerService).recordFinancing(account, new BigDecimal("-10.00000000"), position.getId(), "FX rollover financing");
  }

  @Test
  void missingFinancingConversionRateFailsClosedWithoutLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, OrderSide.BUY, "USDJPY", "10000000.00000000");
    ForexFinancingRateEntity rate = financingRate("USDJPY", "-0.036", "0.018", 360);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("USDJPY")).thenReturn(Optional.of(forexSymbol("USDJPY")));
    when(financingRateRepository.findLatestBySymbol("USDJPY")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(conversionRateRepository.findLatest("JPY", "USD")).thenReturn(Optional.empty());
    when(conversionRateRepository.findLatest("USD", "JPY")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().settleDailyFinancing(accountId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("FX conversion rate not found");

    verify(financingSettlementRepository, never()).insertIfAbsent(any(ForexFinancingSettlementEntity.class));
    verify(accountRepository, never()).save(account);
    verify(positionRepository, never()).save(position);
    verifyNoInteractions(ledgerService);
  }

  @Test
  void repeatedDailyFinancingForSamePositionAndSettlementDateIsNoOpAfterRestart() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, OrderSide.BUY);
    ForexFinancingRateEntity rate = financingRate("EURUSD", "-0.036", "0.018", 360);
    AtomicBoolean inserted = new AtomicBoolean(false);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(financingRateRepository.findLatestBySymbol("EURUSD")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class)))
        .thenAnswer(invocation -> inserted.compareAndSet(false, true));
    LedgerEntryEntity ledgerEntry = new LedgerEntryEntity();
    ledgerEntry.setId(UUID.randomUUID());
    when(ledgerService.recordFinancing(
        eq(account),
        eq(new BigDecimal("-10.00000000")),
        eq(position.getId()),
        eq("FX rollover financing")))
        .thenReturn(ledgerEntry);

    BigDecimal first = service().settleDailyFinancing(accountId);
    BigDecimal second = service().settleDailyFinancing(accountId);

    assertThat(first).isEqualByComparingTo("-10.00000000");
    assertThat(second).isEqualByComparingTo("0.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("9990.00000000");
    assertThat(position.getFinancingAccrued()).isEqualByComparingTo("-10.00000000");
    verify(accountRepository).save(account);
    verify(positionRepository).save(position);
    verify(ledgerService).recordFinancing(account, new BigDecimal("-10.00000000"), position.getId(), "FX rollover financing");
  }

  @Test
  void wednesdayTripleSwapWritesOneSettlementWithThreeDaysCharged() {
    Clock wednesday = Clock.fixed(Instant.parse("2026-06-17T12:00:00Z"), ZoneOffset.UTC);
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, OrderSide.BUY);
    ForexFinancingRateEntity rate = financingRate("EURUSD", "-0.036", "0.018", 360);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(financingRateRepository.findLatestBySymbol("EURUSD")).thenReturn(Optional.of(rate));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(financingSettlementRepository.insertIfAbsent(any(ForexFinancingSettlementEntity.class))).thenReturn(true);

    new ForexFinancingService(
        financingRateRepository,
        financingSettlementRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        new TradingInstrumentClassifier(),
        new ForexConversionService(conversionRateRepository),
        wednesday)
        .settleDailyFinancing(accountId);

    verify(financingSettlementRepository).insertIfAbsent(argThat(settlement ->
        settlement.getPositionId().equals(position.getId())
            && settlement.getSettlementDate().equals(LocalDate.parse("2026-06-17"))
            && settlement.getDaysCharged() == 3
            && settlement.getAmount().compareTo(new BigDecimal("-30.00000000")) == 0));
  }

  private ForexFinancingService service() {
    return new ForexFinancingService(
        financingRateRepository,
        financingSettlementRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        new TradingInstrumentClassifier(),
        new ForexConversionService(conversionRateRepository),
        clock);
  }

  private static TradingAccountEntity account(UUID accountId, String balance) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal(balance));
    account.setEquity(new BigDecimal(balance));
    account.setUsedMargin(new BigDecimal("1000.00000000"));
    account.setFreeMargin(new BigDecimal(balance).subtract(account.getUsedMargin()));
    return account;
  }

  private static PositionEntity position(UUID accountId, OrderSide side) {
    return position(accountId, side, "EURUSD", "100000.00000000");
  }

  private static PositionEntity position(UUID accountId, OrderSide side, String symbol, String notional) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setSide(side);
    position.setLots(new BigDecimal("1.00"));
    position.setOpenPrice(new BigDecimal("1.10000000"));
    position.setCurrentPrice(new BigDecimal("1.10000000"));
    position.setStatus(PositionStatus.OPEN);
    position.setFinancingAccrued(BigDecimal.ZERO);
    position.setNotional(new BigDecimal(notional));
    return position;
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
    rate.setSource("test");
    return rate;
  }

  private static SymbolEntity forexSymbol() {
    return forexSymbol("EURUSD");
  }

  private static SymbolEntity forexSymbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency(symbolCode.substring(3));
    symbol.setLotSize(new BigDecimal("100000"));
    return symbol;
  }

  private static ForexConversionRateEntity conversionRate(String from, String to, String rateValue) {
    ForexConversionRateEntity rate = new ForexConversionRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setFromCurrency(from);
    rate.setToCurrency(to);
    rate.setRate(new BigDecimal(rateValue));
    rate.setEffectiveAt(Instant.parse("2026-06-16T00:00:00Z"));
    rate.setSource("test");
    return rate;
  }
}
