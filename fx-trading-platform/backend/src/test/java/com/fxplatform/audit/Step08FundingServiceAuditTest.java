package com.fxplatform.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.FundingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step08FundingServiceAuditTest {

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

  @ParameterizedTest
  @CsvSource({
      "BUY,0.0001,-5.00000000,9995.00000000",
      "SELL,0.0001,5.00000000,10005.00000000"
  })
  void positiveFundingRateMeansLongPaysShortReceives(
      OrderSide side,
      String rate,
      String expectedCashflow,
      String expectedBalance
  ) {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000");
    PositionEntity position = position(accountId, "BTCUSDT", side, "1", "50000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT",
        "LINEAR_PERPETUAL",
        "BTC",
        "USDT",
        "1",
        "USDT")));
    when(fundingSettlementRepository.insertIfAbsent(any(FundingSettlementEntity.class))).thenReturn(true);
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(fundingRate("BTCUSDT", rate, "50000")));

    BigDecimal cashflow = service().settleFundingForPosition(position);

    AuditAssertions.assertAmountClose(cashflow, expectedCashflow);
    AuditAssertions.assertAmountClose(account.getBalance(), expectedBalance);
    AuditAssertions.assertAmountClose(position.getFundingPnl(), expectedCashflow);
    verify(ledgerService).recordFundingFee(account, new BigDecimal(expectedCashflow), position.getId(), "Perpetual funding fee");
  }

  @Test
  void inverseFundingUsesCoinPositionValue() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "1.00000000");
    account.setBaseCurrency("BTC");
    PositionEntity position = position(accountId, "BTCUSD", OrderSide.BUY, "100", "50000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSD")).thenReturn(Optional.of(perpSymbol(
        "BTCUSD",
        "INVERSE_PERPETUAL",
        "BTC",
        "USD",
        "100",
        "BTC")));
    when(fundingSettlementRepository.insertIfAbsent(any(FundingSettlementEntity.class))).thenReturn(true);
    when(fundingRateRepository.findLatestBySymbol("BTCUSD"))
        .thenReturn(Optional.of(fundingRate("BTCUSD", "0.0001", "50000")));

    BigDecimal cashflow = service().settleFundingForPosition(position);

    AuditAssertions.assertBtcClose(cashflow, "-0.00002000");
    AuditAssertions.assertBtcClose(account.getBalance(), "0.99998000");
    verify(ledgerService).recordFundingFee(account, new BigDecimal("-0.00002000"), position.getId(), "Perpetual funding fee");
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
      String marginAsset
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass(assetClass);
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize(new BigDecimal(contractSize));
    symbol.setContractSize(new BigDecimal(contractSize));
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setSettlementAsset(marginAsset);
    symbol.setMarginAsset(marginAsset);
    return symbol;
  }
}
