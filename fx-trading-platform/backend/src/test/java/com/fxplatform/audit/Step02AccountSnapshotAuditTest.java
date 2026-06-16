package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountSnapshotService;
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
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step02AccountSnapshotAuditTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private SymbolRepository symbolRepository;

  @Test
  void snapshotIncludesOpenFloatingPnlEquityFreeMarginAndMarginLevel() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000", "220.00000000");
    PositionEntity position = openPosition(accountId, "EURUSD", "0.10", "1.10000", "220.00000000");

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol("EURUSD")));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.10100", "1.10104"));

    AccountSnapshot snapshot = service().snapshot(account);

    AuditAssertions.assertAmountClose(snapshot.openFloatingPnl(), "10.00000000");
    AuditAssertions.assertAmountClose(snapshot.equity(), "10010.00000000");
    AuditAssertions.assertAmountClose(snapshot.usedMargin(), "220.00000000");
    AuditAssertions.assertAmountClose(snapshot.freeMargin(), "9790.00000000");
    assertThat(snapshot.marginLevel()).isNotNull();
    verify(positionRepository).findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN);
  }

  @Test
  void snapshotWithNoUsedMarginHasNoMarginLevel() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000", "0.00000000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of());

    AccountSnapshot snapshot = service().snapshot(accountId);

    AuditAssertions.assertAmountClose(snapshot.openFloatingPnl(), "0.00000000");
    AuditAssertions.assertAmountClose(snapshot.equity(), "10000.00000000");
    AuditAssertions.assertAmountClose(snapshot.freeMargin(), "10000.00000000");
    assertThat(snapshot.marginLevel()).isNull();
  }

  @Test
  void perpetualSnapshotUsesQuoteMarkPriceBeforeCloseoutForPnlValueAndMaintenanceMargin() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10000.00000000", "5000.00000000");
    PositionEntity position = openPosition(accountId, "BTCUSDT", "1.00", "50000.00000000", "5000.00000000");
    position.setLeverage(10);
    position.setInitialMargin(new BigDecimal("5000.00000000"));
    position.setNotional(new BigDecimal("51000.00000000"));
    position.setMaintenanceMargin(new BigDecimal("255.00000000"));

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(linearPerpSymbol("BTCUSDT")));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quoteWithMark("BTCUSDT", "51000.00000000", "51000.00000000", "51000.00000000", "49000.00000000"));

    AccountSnapshot snapshot = service().snapshot(account);

    AuditAssertions.assertAmountClose(snapshot.openFloatingPnl(), "-1000.00000000");
    AuditAssertions.assertAmountClose(snapshot.positionValue(), "49000.00000000");
    AuditAssertions.assertAmountClose(snapshot.maintenanceMargin(), "245.00000000");
  }

  private AccountSnapshotService service() {
    return new AccountSnapshotService(
        accountRepository,
        positionRepository,
        quoteService,
        new PnLCalculator(new TradingAlgorithmEngine()),
        symbolRepository);
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

  private static PositionEntity openPosition(
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
    position.setStatus(PositionStatus.OPEN);
    position.setLeverage(100);
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
    symbol.setLeverage(100);
    return symbol;
  }

  private static SymbolEntity linearPerpSymbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("LINEAR_PERPETUAL");
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLeverage(10);
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

  private static QuoteResponse quoteWithMark(String symbol, String bid, String ask, String mid, String markPrice) {
    BigDecimal bidPrice = new BigDecimal(bid);
    BigDecimal askPrice = new BigDecimal(ask);
    BigDecimal midPrice = new BigDecimal(mid);
    if (hasMarkPrice()) {
      try {
        Constructor<QuoteResponse> constructor = QuoteResponse.class.getDeclaredConstructor(
            String.class,
            String.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            String.class,
            long.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class);
        return constructor.newInstance(
            "quote",
            symbol,
            bidPrice,
            askPrice,
            midPrice,
            new BigDecimal(markPrice),
            askPrice.subtract(bidPrice),
            "audit",
            1781667600000L,
            null,
            null,
            null,
            null);
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError(ex);
      }
    }
    return new QuoteResponse(
        "quote",
        symbol,
        bidPrice,
        askPrice,
        midPrice,
        askPrice.subtract(bidPrice),
        "audit",
        1781667600000L);
  }

  private static boolean hasMarkPrice() {
    for (var component : QuoteResponse.class.getRecordComponents()) {
      if ("markPrice".equals(component.getName())) {
        return true;
      }
    }
    return false;
  }
}
