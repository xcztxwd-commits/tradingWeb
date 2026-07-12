package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
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
class AccountSnapshotServiceTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private SymbolRepository symbolRepository;

  @Test
  void snapshotIncludesPositiveOpenFloatingPnlInEquityAndFreeMargin() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    PositionEntity position = openForexPosition(accountId, OrderSide.BUY, "1.10020", "110.02000000");
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.10120", "1.10124"));

    AccountSnapshot snapshot = service().snapshot(account);

    assertThat(snapshot.openFloatingPnl()).isEqualByComparingTo("10.00000000");
    assertThat(snapshot.equity()).isGreaterThan(snapshot.balance());
    assertThat(snapshot.equity()).isEqualByComparingTo("10010.00000000");
    assertThat(snapshot.usedMargin()).isEqualByComparingTo("110.02000000");
    assertThat(snapshot.maintenanceMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(snapshot.freeMargin()).isEqualByComparingTo("9899.98000000");
    assertThat(snapshot.marginLevel()).isEqualByComparingTo("9098.34575532");
    assertThat(snapshot.warning()).contains("usedMargin");
    verify(positionRepository).findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN);
  }

  @Test
  void snapshotIncludesNegativeOpenFloatingPnlInEquityAndFreeMargin() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    PositionEntity position = openForexPosition(accountId, OrderSide.BUY, "1.10020", "110.02000000");
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.09920", "1.09924"));

    AccountSnapshot snapshot = service().snapshot(account);

    assertThat(snapshot.openFloatingPnl()).isEqualByComparingTo("-10.00000000");
    assertThat(snapshot.equity()).isLessThan(snapshot.balance());
    assertThat(snapshot.equity()).isEqualByComparingTo("9990.00000000");
    assertThat(snapshot.usedMargin()).isEqualByComparingTo("110.02000000");
    assertThat(snapshot.maintenanceMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(snapshot.freeMargin()).isEqualByComparingTo("9879.98000000");
    assertThat(snapshot.marginLevel()).isEqualByComparingTo("9080.16724232");
  }

  @Test
  void snapshotByAccountIdLoadsAccountBeforeCalculatingOpenPositions() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of());

    AccountSnapshot snapshot = service().snapshot(accountId);

    assertThat(snapshot.accountId()).isEqualTo(accountId);
    assertThat(snapshot.openFloatingPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(snapshot.equity()).isEqualByComparingTo("10000.00000000");
    assertThat(snapshot.usedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(snapshot.maintenanceMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(snapshot.freeMargin()).isEqualByComparingTo("10000.00000000");
    assertThat(snapshot.marginLevel()).isNull();
  }

  @Test
  void snapshotIncludesPerpetualMaintenanceMargin() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    PositionEntity position = openForexPosition(accountId, OrderSide.BUY, "50000.00000000", "5000.00000000");
    position.setSymbol("BTCUSDT");
    position.setLots(BigDecimal.ONE);
    position.setLeverage(10);
    position.setInitialMargin(new BigDecimal("5000.00000000"));
    position.setMaintenanceMargin(new BigDecimal("250.00000000"));
    position.setMarkPrice(new BigDecimal("50000.00000000"));
    position.setNotional(new BigDecimal("50000.00000000"));

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(linearSymbol()));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "50100.00000000", "50102.00000000"));

    AccountSnapshot snapshot = service().snapshot(account);

    assertThat(snapshot.maintenanceMargin()).isEqualByComparingTo("250.50500000");
    assertThat(snapshot.usedMargin()).isEqualByComparingTo("5000.00000000");
  }

  @Test
  void linearPerpetualSnapshotUsesCanonicalBaseWithoutApplyingContractFactorTwice() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    PositionEntity position = openForexPosition(
        accountId,
        OrderSide.BUY,
        "50000.00000000",
        "5000.00000000");
    position.setSymbol("BTCUSDT-PERP");
    position.setLots(BigDecimal.ONE);
    position.setLeverage(10);
    position.setInitialMargin(new BigDecimal("5000.00000000"));
    position.setMaintenanceMargin(new BigDecimal("250.00000000"));
    position.setMarkPrice(new BigDecimal("50000.00000000"));
    position.setNotional(new BigDecimal("50000.00000000"));

    SymbolEntity symbol = symbol(
        "BTCUSDT-PERP",
        ProductType.LINEAR_PERP,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        new BigDecimal("0.01"),
        100);
    symbol.setContractMultiplier(new BigDecimal("10"));
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(
        quote("BTCUSDT-PERP", "50100.00000000", "50102.00000000"));

    AccountSnapshot snapshot = service().snapshot(account);

    assertThat(snapshot.openFloatingPnl()).isEqualByComparingTo("101.00000000");
    assertThat(snapshot.equity()).isEqualByComparingTo("10101.00000000");
    assertThat(snapshot.positionValue()).isEqualByComparingTo("50101.00000000");
    assertThat(snapshot.maintenanceMargin()).isEqualByComparingTo("250.50500000");
    assertThat(snapshot.usedMargin()).isEqualByComparingTo("5000.00000000");
    assertThat(snapshot.freeMargin()).isEqualByComparingTo("5101.00000000");
  }

  @Test
  void snapshotUsesOpenPositionMarginWhenPersistedUsedMarginIsStale() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUsedMargin(new BigDecimal("999.00000000"));
    PositionEntity position = openForexPosition(accountId, OrderSide.BUY, "1.10020", "0.00000000");
    position.setMarginHeld(null);
    position.setInitialMargin(new BigDecimal("110.02000000"));

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.10120", "1.10124"));

    AccountSnapshot snapshot = service().snapshot(account);

    assertThat(snapshot.usedMargin()).isEqualByComparingTo("110.02000000");
    assertThat(snapshot.freeMargin()).isEqualByComparingTo("9899.98000000");
    assertThat(snapshot.warning()).contains("usedMargin");
  }

  @Test
  void snapshotUsesExplicitCryptoProductTypesWithoutLeverageOrUsdGuessing() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    PositionEntity spot = openForexPosition(accountId, OrderSide.BUY, "100.00000000", "10.00000000");
    spot.setSymbol("BTCUSDT");
    spot.setLots(new BigDecimal("0.10"));
    spot.setLeverage(50);
    PositionEntity linear = openForexPosition(accountId, OrderSide.BUY, "1000.00000000", "100.00000000");
    linear.setSymbol("ETHUSD");
    linear.setLots(new BigDecimal("2.00"));
    linear.setLeverage(1);
    PositionEntity inverse = openForexPosition(accountId, OrderSide.BUY, "50.00000000", "20.00000000");
    inverse.setSymbol("SOLUSDT");
    inverse.setLots(new BigDecimal("100"));
    inverse.setLeverage(1);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(spot, linear, inverse));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        1)));
    when(symbolRepository.findBySymbol("ETHUSD")).thenReturn(Optional.of(symbol(
        "ETHUSD",
        ProductType.LINEAR_PERP,
        "ETH",
        "USD",
        BigDecimal.ONE,
        BigDecimal.ONE,
        20)));
    when(symbolRepository.findBySymbol("SOLUSDT")).thenReturn(Optional.of(symbol(
        "SOLUSDT",
        ProductType.INVERSE_PERP,
        "SOL",
        "USDT",
        BigDecimal.ONE,
        new BigDecimal("100"),
        10)));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "110.00000000", "111.00000000"));
    when(quoteService.freshQuote("ETHUSD")).thenReturn(quote("ETHUSD", "1010.00000000", "1011.00000000"));
    when(quoteService.freshQuote("SOLUSDT")).thenReturn(quote("SOLUSDT", "55.00000000", "56.00000000"));

    AccountSnapshot snapshot = service().snapshot(account);

    assertThat(snapshot.openFloatingPnl()).isEqualByComparingTo("41.81981982");
  }

  @Test
  void snapshotRejectsSymbolWithoutExplicitProductType() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    PositionEntity position = openForexPosition(accountId, OrderSide.BUY, "63874.80000000", "100.00000000");
    position.setSymbol("BTCUSDT");
    position.setLeverage(1);

    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol(
        "BTCUSDT",
        null,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        20)));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "63876.80000000", "63877.00000000"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().snapshot(account))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("product type");
  }

  private AccountSnapshotService service() {
    return new AccountSnapshotService(
        accountRepository,
        positionRepository,
        quoteService,
        new PnLCalculator(new TradingAlgorithmEngine()),
        symbolRepository);
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
    account.setLeverage(100);
    return account;
  }

  private static PositionEntity openForexPosition(
      UUID accountId,
      OrderSide side,
      String openPrice,
      String marginHeld
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(side);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal(openPrice));
    position.setCurrentPrice(new BigDecimal(openPrice));
    position.setMarginHeld(new BigDecimal(marginHeld));
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

  private static SymbolEntity linearSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("BTCUSDT");
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("LINEAR_PERPETUAL");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLeverage(10);
    return symbol;
  }

  private static SymbolEntity symbol(
      String code,
      ProductType productType,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal lotSize,
      BigDecimal contractSize,
      int leverage
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(code);
    symbol.setProductType(productType);
    symbol.setAssetClass(assetClass(productType));
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize(lotSize);
    symbol.setContractSize(contractSize);
    symbol.setLeverage(leverage);
    return symbol;
  }

  private static String assetClass(ProductType productType) {
    if (productType == ProductType.CRYPTO_SPOT) {
      return "CRYPTO";
    }
    if (productType == ProductType.LINEAR_PERP) {
      return "LINEAR_PERPETUAL";
    }
    if (productType == ProductType.INVERSE_PERP) {
      return "INVERSE_PERPETUAL";
    }
    return "FOREX";
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
