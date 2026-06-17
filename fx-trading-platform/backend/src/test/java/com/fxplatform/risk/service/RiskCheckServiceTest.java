package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskCheckServiceTest {

  @Mock
  private QuoteService quoteService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private WalletService walletService;

  @Mock
  private InstrumentRulesEngine instrumentRulesEngine;

  @Test
  void btcUsdtUsesSymbolLotSizeAndLeverageForMargin() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10000.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.01");

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "67240.0", "67244.2"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "1", 20)));

    BigDecimal requiredMargin = service().checkOrder(account, request);

    assertThat(requiredMargin).isEqualByComparingTo("33.62210000");
  }

  @Test
  void eurUsdKeepsStandardForexContractSizing() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10000.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "EURUSD", "0.01");

    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.08318", "1.08322"));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol("EURUSD", "100000", 100)));

    BigDecimal requiredMargin = service().checkOrder(account, request);

    assertThat(requiredMargin).isEqualByComparingTo("10.83220000");
  }

  @Test
  void spotBuyUsesQuoteWalletAvailableInsteadOfFreeMargin() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10000.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.20");

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "49999.9", "50000.0"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT", "1", 20)));
    when(walletService.getBalance(account.getId(), "USDT"))
        .thenReturn(Optional.of(wallet(account.getId(), "USDT", "10000.00000000")));

    BigDecimal requiredMargin = serviceWithWallet().checkOrder(account, request);

    assertThat(requiredMargin).isEqualByComparingTo("10000.00000000");
  }

  @Test
  void spotLimitBuyUsesRequestedPriceForQuoteWalletHold() {
    TradingAccountEntity account = demoAccount(BigDecimal.ZERO, 100);
    CreateOrderRequest request = new CreateOrderRequest(
        account.getId(),
        "BTCUSDT",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        "idem-spot-limit",
        "client-spot-limit",
        new BigDecimal("0.10"),
        new BigDecimal("49000.00000000"),
        null);

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "49999.9", "50000.0"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(cryptoSpotSymbol()));
    when(walletService.getBalance(account.getId(), "USDT"))
        .thenReturn(Optional.of(wallet(account.getId(), "USDT", "4900.00000000")));

    BigDecimal requiredHold = serviceWithWallet().checkOrder(account, request);

    assertThat(requiredHold).isEqualByComparingTo("4900.00000000");
  }

  @Test
  void spotSellUsesBaseWalletAvailableInsteadOfFreeMargin() {
    TradingAccountEntity account = demoAccount(BigDecimal.ZERO, 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.10", null, OrderSide.SELL);

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "55000.0", "55001.0"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT", "1", 20)));
    when(walletService.getBalance(account.getId(), "BTC"))
        .thenReturn(Optional.of(wallet(account.getId(), "BTC", "0.10000000")));

    BigDecimal requiredBase = serviceWithWallet().checkOrder(account, request);

    assertThat(requiredBase).isEqualByComparingTo("0.10000000");
  }

  @Test
  void rejectsSpotBuyWhenQuoteWalletAvailableIsNotEnough() {
    TradingAccountEntity account = demoAccount(new BigDecimal("999999.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.20");

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "49999.9", "50000.0"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT", "1", 20)));
    when(walletService.getBalance(account.getId(), "USDT"))
        .thenReturn(Optional.of(wallet(account.getId(), "USDT", "9999.99000000")));

    assertThatThrownBy(() -> serviceWithWallet().checkOrder(account, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Quote wallet available is not enough");
  }

  @Test
  void rejectsSpotSellWhenBaseWalletAvailableIsNotEnough() {
    TradingAccountEntity account = demoAccount(new BigDecimal("999999.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.10", null, OrderSide.SELL);

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "55000.0", "55001.0"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT", "1", 20)));
    when(walletService.getBalance(account.getId(), "BTC"))
        .thenReturn(Optional.of(wallet(account.getId(), "BTC", "0.09999999")));

    assertThatThrownBy(() -> serviceWithWallet().checkOrder(account, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Base wallet available is not enough");
  }

  @Test
  void inversePerpetualUsesCoinSettledMarginFormula() {
    TradingAccountEntity account = demoAccount(new BigDecimal("1.00000000"), 20);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSD", "100", 10);

    when(quoteService.freshQuote("BTCUSD")).thenReturn(quote("BTCUSD", "49999.0", "50000.0"));
    when(symbolRepository.findBySymbol("BTCUSD")).thenReturn(Optional.of(symbol("BTCUSD", "INVERSE_PERPETUAL", "BTC", "USD", "100", 20)));

    BigDecimal requiredMargin = service().checkOrder(account, request);

    assertThat(requiredMargin).isEqualByComparingTo("0.02000000");
  }

  @Test
  void orderLeverageOverridesAccountLeverageWithinSymbolLimit() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10000.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "EURUSD", "0.01", 20);

    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.08318", "1.08322"));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol("EURUSD", "100000", 100)));

    BigDecimal requiredMargin = service().checkOrder(account, request);

    assertThat(requiredMargin).isEqualByComparingTo("54.16100000");
  }

  @Test
  void rejectsWhenSymbolAwareMarginExceedsFreeMargin() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.01");

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "67240.0", "67244.2"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "1", 20)));

    assertThatThrownBy(() -> service().checkOrder(account, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");
  }

  @Test
  void treatsNullFreeMarginAsZeroWhenCheckingRequiredMargin() {
    TradingAccountEntity account = demoAccount(null, 100);
    CreateOrderRequest request = marketOrder(account.getId(), "BTCUSDT", "0.01");

    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "67240.0", "67244.2"));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "1", 20)));

    assertThatThrownBy(() -> service().checkOrder(account, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");
  }

  @Test
  void usesSnapshotFreeMarginForOrderMarginValidation() {
    TradingAccountEntity account = demoAccount(BigDecimal.ZERO, 100);
    CreateOrderRequest request = marketOrder(account.getId(), "EURUSD", "0.01");
    AccountSnapshot snapshot = snapshot(account, new BigDecimal("100.00000000"), BigDecimal.ZERO);

    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.08318", "1.08322"));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol("EURUSD", "100000", 100)));
    when(accountSnapshotService.snapshot(account)).thenReturn(snapshot);

    BigDecimal requiredMargin = serviceWithSnapshot().checkOrder(account, request);

    assertThat(requiredMargin).isEqualByComparingTo("10.83220000");
    verify(accountSnapshotService).snapshot(account);
  }

  @Test
  void rejectsOrderWhenSnapshotFreeMarginIsInsufficientEvenIfAccountFreeMarginIsHigh() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10000.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "EURUSD", "0.01");
    AccountSnapshot snapshot = snapshot(account, new BigDecimal("5.00000000"), BigDecimal.ZERO);

    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.08318", "1.08322"));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol("EURUSD", "100000", 100)));
    when(accountSnapshotService.snapshot(account)).thenReturn(snapshot);

    assertThatThrownBy(() -> serviceWithSnapshot().checkOrder(account, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");
    verify(accountSnapshotService).snapshot(account);
  }

  @Test
  void delegatesInstrumentRuleValidationBeforeQuoteLookup() {
    TradingAccountEntity account = demoAccount(new BigDecimal("10000.00000000"), 100);
    CreateOrderRequest request = marketOrder(account.getId(), "EURUSD", "0.01");
    SymbolEntity symbol = symbol("EURUSD", "100000", 100);

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));
    doThrow(new BusinessException("SYMBOL_NOT_TRADABLE", "Symbol is not tradable"))
        .when(instrumentRulesEngine)
        .validateOrderRules(request, symbol);

    assertThatThrownBy(() -> serviceWithRules().checkOrder(account, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Symbol is not tradable");

    verifyNoInteractions(quoteService);
  }

  private RiskCheckService service() {
    return new RiskCheckService(quoteService, symbolRepository, new MarginCalculator());
  }

  private RiskCheckService serviceWithSnapshot() {
    return new RiskCheckService(quoteService, symbolRepository, new MarginCalculator(), accountSnapshotService);
  }

  private RiskCheckService serviceWithWallet() {
    return new RiskCheckService(quoteService, symbolRepository, new MarginCalculator(), accountSnapshotService, walletService);
  }

  private RiskCheckService serviceWithRules() {
    return new RiskCheckService(
        quoteService,
        symbolRepository,
        new MarginCalculator(),
        accountSnapshotService,
        walletService,
        instrumentRulesEngine);
  }

  private TradingAccountEntity demoAccount(BigDecimal freeMargin, int leverage) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setFreeMargin(freeMargin);
    account.setUsedMargin(BigDecimal.ZERO);
    account.setLeverage(leverage);
    return account;
  }

  private AccountSnapshot snapshot(
      TradingAccountEntity account,
      BigDecimal freeMargin,
      BigDecimal openFloatingPnl
  ) {
    return new AccountSnapshot(
        account.getId(),
        account.getBalance(),
        openFloatingPnl,
        account.getBalance().add(openFloatingPnl),
        account.getUsedMargin(),
        BigDecimal.ZERO,
        freeMargin,
        null,
        account.getBaseCurrency());
  }

  private CreateOrderRequest marketOrder(UUID accountId, String symbol, String quantity) {
    return marketOrder(accountId, symbol, quantity, null);
  }

  private CreateOrderRequest marketOrder(UUID accountId, String symbol, String quantity, Integer leverage) {
    return marketOrder(accountId, symbol, quantity, leverage, OrderSide.BUY);
  }

  private CreateOrderRequest marketOrder(
      UUID accountId,
      String symbol,
      String quantity,
      Integer leverage,
      OrderSide side
  ) {
    return new CreateOrderRequest(
        accountId,
        symbol,
        side,
        OrderType.MARKET,
        new BigDecimal(quantity),
        null,
        null,
        null,
        "idem-" + symbol,
        "client-" + symbol,
        new BigDecimal(quantity),
        null,
        leverage);
  }

  private WalletBalanceEntity wallet(UUID accountId, String asset, String available) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setAccountId(accountId);
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(available));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(BigDecimal.ZERO);
    return balance;
  }

  private QuoteResponse quote(String symbol, String bid, String ask) {
    BigDecimal bidValue = new BigDecimal(bid);
    BigDecimal askValue = new BigDecimal(ask);
    BigDecimal mid = bidValue.add(askValue).divide(new BigDecimal("2"));
    return new QuoteResponse(
        "quote",
        symbol,
        bidValue,
        askValue,
        mid,
        askValue.subtract(bidValue),
        "test",
        1781265723478L);
  }

  private SymbolEntity symbol(String symbol, String lotSize, int leverage) {
    return symbol(symbol, null, null, null, lotSize, leverage);
  }

  private SymbolEntity symbol(String symbol, String assetClass, String baseCurrency, String quoteCurrency, String lotSize, int leverage) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setAssetClass(assetClass);
    entity.setBaseCurrency(baseCurrency);
    entity.setQuoteCurrency(quoteCurrency);
    entity.setLotSize(new BigDecimal(lotSize));
    entity.setLeverage(leverage);
    return entity;
  }

  private SymbolEntity cryptoSpotSymbol() {
    SymbolEntity entity = symbol("BTCUSDT", "SPOT", "BTC", "USDT", "1", 20);
    entity.setProductType(ProductType.CRYPTO_SPOT);
    return entity;
  }
}
