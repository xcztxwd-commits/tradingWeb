package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FundingServiceTest {

  @Mock
  private FundingRateRepository fundingRateRepository;

  @Mock
  private FundingSettlementRepository fundingSettlementRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private SymbolRepository symbolRepository;

  @Test
  void getCurrentFundingRateReturnsLatestNormalizedSymbol() {
    FundingRateEntity rate = fundingRate("BTCUSDT", "0.0001", "50000.00000000");
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT")).thenReturn(Optional.of(rate));

    FundingRateEntity current = service().getCurrentFundingRate(" btcusdt ");

    assertThat(current).isSameAs(rate);
    verify(fundingRateRepository).findLatestBySymbol("BTCUSDT");
  }

  @ParameterizedTest
  @CsvSource({
      "BUY,0.0001,-5.00000000,9995.00000000",
      "SELL,0.0001,5.00000000,10005.00000000",
      "BUY,-0.0001,5.00000000,10005.00000000",
      "SELL,-0.0001,-5.00000000,9995.00000000"
  })
  void linearPerpetualFundingDirectionUpdatesBalancePositionAndLedger(
      OrderSide side,
      String fundingRate,
      String expectedCashflow,
      String expectedBalance
  ) {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, "BTCUSDT", side, "1.00", "50000.00000000");
    FundingRateEntity rate = fundingRate("BTCUSDT", fundingRate, "50000.00000000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT",
        "LINEAR_PERPETUAL",
        "BTC",
        "USDT",
        "1",
        "1",
        "USDT",
        "USDT")));
    when(fundingSettlementRepository.insertIfAbsent(any(FundingSettlementEntity.class))).thenReturn(true);
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT")).thenReturn(Optional.of(rate));

    BigDecimal cashflow = service().settleFundingForPosition(position);

    assertThat(cashflow).isEqualByComparingTo(expectedCashflow);
    assertThat(account.getBalance()).isEqualByComparingTo(expectedBalance);
    assertThat(account.getEquity()).isEqualByComparingTo(expectedBalance);
    assertThat(account.getFreeMargin()).isEqualByComparingTo(new BigDecimal(expectedBalance).subtract(account.getUsedMargin()));
    assertThat(position.getFundingPnl()).isEqualByComparingTo(expectedCashflow);
    verify(accountRepository).save(account);
    verify(positionRepository).save(position);
    verify(ledgerService).recordFundingFee(
        account,
        new BigDecimal(expectedCashflow),
        position.getId(),
        "Perpetual funding fee");
  }

  @Test
  void inversePerpetualFundingUsesCoinPositionValue() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "1.00000000");
    account.setBaseCurrency("BTC");
    PositionEntity position = position(accountId, "BTCUSD", OrderSide.BUY, "100", "50000.00000000");
    FundingRateEntity rate = fundingRate("BTCUSD", "0.0001", "50000.00000000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSD")).thenReturn(Optional.of(perpSymbol(
        "BTCUSD",
        "INVERSE_PERPETUAL",
        "BTC",
        "USD",
        "100",
        "1",
        "BTC",
        "BTC")));
    when(fundingSettlementRepository.insertIfAbsent(any(FundingSettlementEntity.class))).thenReturn(true);
    when(fundingRateRepository.findLatestBySymbol("BTCUSD")).thenReturn(Optional.of(rate));

    BigDecimal cashflow = service().settleFundingForPosition(position);

    assertThat(cashflow).isEqualByComparingTo("-0.00002000");
    assertThat(account.getBalance()).isEqualByComparingTo("0.99998000");
    assertThat(position.getFundingPnl()).isEqualByComparingTo("-0.00002000");
    verify(ledgerService).recordFundingFee(account, new BigDecimal("-0.00002000"), position.getId(), "Perpetual funding fee");
  }

  @Test
  void settleFundingForAccountSettlesOnlyOpenPerpetualPositions() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity perp = position(accountId, "BTCUSDT", OrderSide.BUY, "1.00", "50000.00000000");
    PositionEntity forex = position(accountId, "EURUSD", OrderSide.BUY, "0.10", "1.10000000");

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(perp, forex));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT",
        "LINEAR_PERPETUAL",
        "BTC",
        "USDT",
        "1",
        "1",
        "USDT",
        "USDT")));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(fundingSettlementRepository.insertIfAbsent(any(FundingSettlementEntity.class))).thenReturn(true);
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(fundingRate("BTCUSDT", "0.0001", "50000.00000000")));

    BigDecimal total = service().settleFundingForAccount(accountId);

    assertThat(total).isEqualByComparingTo("-5.00000000");
    assertThat(perp.getFundingPnl()).isEqualByComparingTo("-5.00000000");
    assertThat(forex.getFundingPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(positionRepository).save(perp);
    verify(positionRepository, never()).save(forex);
  }

  @Test
  void repeatedFundingSettlementForSamePositionAndFundingTimeIsNoOpAfterRestart() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, "BTCUSDT", OrderSide.BUY, "1.00", "50000.00000000");
    FundingRateEntity rate = fundingRate("BTCUSDT", "0.0001", "50000.00000000");
    AtomicBoolean inserted = new AtomicBoolean(false);

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT",
        "LINEAR_PERPETUAL",
        "BTC",
        "USDT",
        "1",
        "1",
        "USDT",
        "USDT")));
    when(fundingSettlementRepository.insertIfAbsent(any(FundingSettlementEntity.class)))
        .thenAnswer(invocation -> inserted.compareAndSet(false, true));
    LedgerEntryEntity ledgerEntry = new LedgerEntryEntity();
    ledgerEntry.setId(UUID.randomUUID());
    when(ledgerService.recordFundingFee(
        eq(account),
        eq(new BigDecimal("-5.00000000")),
        eq(position.getId()),
        eq("Perpetual funding fee")))
        .thenReturn(ledgerEntry);

    BigDecimal first = service().settleFundingForPosition(position, rate);
    BigDecimal second = service().settleFundingForPosition(position, rate);

    assertThat(first).isEqualByComparingTo("-5.00000000");
    assertThat(second).isEqualByComparingTo("0.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("9995.00000000");
    assertThat(position.getFundingPnl()).isEqualByComparingTo("-5.00000000");
    verify(accountRepository).save(account);
    verify(positionRepository).save(position);
    verify(ledgerService).recordFundingFee(account, new BigDecimal("-5.00000000"), position.getId(), "Perpetual funding fee");
  }

  private FundingService service() {
    return new FundingService(
        fundingRateRepository,
        fundingSettlementRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        new TradingInstrumentClassifier());
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

  private static PositionEntity position(UUID accountId, String symbol, OrderSide side, String lots, String markPrice) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setSide(side);
    position.setLots(new BigDecimal(lots));
    position.setOpenPrice(new BigDecimal(markPrice));
    position.setCurrentPrice(new BigDecimal(markPrice));
    position.setMarkPrice(new BigDecimal(markPrice));
    position.setStatus(PositionStatus.OPEN);
    position.setFundingPnl(BigDecimal.ZERO);
    return position;
  }

  private static FundingRateEntity fundingRate(String symbol, String fundingRate, String markPrice) {
    FundingRateEntity rate = new FundingRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setSymbol(symbol);
    rate.setFundingRate(new BigDecimal(fundingRate));
    rate.setFundingTime(Instant.parse("2026-06-16T08:00:00Z"));
    rate.setNextFundingTime(Instant.parse("2026-06-16T16:00:00Z"));
    rate.setMarkPrice(new BigDecimal(markPrice));
    return rate;
  }

  private static SymbolEntity perpSymbol(
      String symbolCode,
      String assetClass,
      String baseCurrency,
      String quoteCurrency,
      String contractSize,
      String contractMultiplier,
      String settlementAsset,
      String marginAsset
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass(assetClass);
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize(new BigDecimal(contractSize));
    symbol.setContractSize(new BigDecimal(contractSize));
    symbol.setContractMultiplier(new BigDecimal(contractMultiplier));
    symbol.setSettlementAsset(settlementAsset);
    symbol.setMarginAsset(marginAsset);
    return symbol;
  }

  private static SymbolEntity forexSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency("EUR");
    symbol.setQuoteCurrency("USD");
    symbol.setLotSize(new BigDecimal("100000"));
    return symbol;
  }
}
