package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountSummarySnapshotIntegrationTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private LedgerService ledgerService;

  @Test
  void accountSummaryEquityIsGreaterThanBalanceWhenOpenPositionHasFloatingProfit() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity position = openForexPosition(accountId);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.10120", "1.10124"));

    AccountResponse response = accountService().summary(userId, accountId);

    assertThat(response.balance()).isEqualByComparingTo("10000.00000000");
    assertThat(response.openFloatingPnl()).isEqualByComparingTo("10.00000000");
    assertThat(response.equity()).isGreaterThan(response.balance());
    assertThat(response.equity()).isEqualByComparingTo("10010.00000000");
    assertThat(response.positionValue()).isEqualByComparingTo("11002.00000000");
    assertThat(response.maintenanceMargin()).isEqualByComparingTo("12.34000000");
    assertThat(response.freeMargin()).isEqualByComparingTo(response.equity().subtract(response.usedMargin()));
    assertThat(response.marginAvailable()).isEqualByComparingTo(response.freeMargin());
    assertThat(response.lastSnapshotAt()).isNotNull();
    assertThat(response.warning()).isNull();
    verify(accountRepository, never()).save(any());
  }

  @Test
  void accountSummaryEquityIsLessThanBalanceWhenOpenPositionHasFloatingLoss() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity position = openForexPosition(accountId);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.09920", "1.09924"));

    AccountResponse response = accountService().summary(userId, accountId);

    assertThat(response.balance()).isEqualByComparingTo("10000.00000000");
    assertThat(response.equity()).isLessThan(response.balance());
    assertThat(response.equity()).isEqualByComparingTo("9990.00000000");
    assertThat(response.freeMargin()).isEqualByComparingTo(response.equity().subtract(response.usedMargin()));
    verify(accountRepository, never()).save(any());
  }

  private AccountService accountService() {
    AccountSnapshotService snapshotService = new AccountSnapshotService(
        accountRepository,
        positionRepository,
        quoteService,
        new PnLCalculator(new TradingAlgorithmEngine()),
        symbolRepository);
    return new AccountService(accountRepository, ledgerService, snapshotService);
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(new BigDecimal("110.02000000"));
    account.setFreeMargin(new BigDecimal("9889.98000000"));
    account.setLeverage(100);
    return account;
  }

  private static PositionEntity openForexPosition(UUID accountId) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10020"));
    position.setCurrentPrice(new BigDecimal("1.10020"));
    position.setMarginHeld(new BigDecimal("110.02000000"));
    position.setNotional(new BigDecimal("11002.00000000"));
    position.setMaintenanceMargin(new BigDecimal("12.34000000"));
    position.setStatus(PositionStatus.OPEN);
    position.setLeverage(100);
    return position;
  }

  private static SymbolEntity forexSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProductType(ProductType.FX_MARGIN);
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency("EUR");
    symbol.setQuoteCurrency("USD");
    symbol.setLotSize(new BigDecimal("100000"));
    symbol.setLeverage(100);
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
        "test",
        1781667600000L);
  }
}
