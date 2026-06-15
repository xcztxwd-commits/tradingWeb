package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
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

  private RiskCheckService service() {
    return new RiskCheckService(quoteService, symbolRepository, new MarginCalculator());
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

  private CreateOrderRequest marketOrder(UUID accountId, String symbol, String quantity) {
    return marketOrder(accountId, symbol, quantity, null);
  }

  private CreateOrderRequest marketOrder(UUID accountId, String symbol, String quantity, Integer leverage) {
    return new CreateOrderRequest(
        accountId,
        symbol,
        OrderSide.BUY,
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
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setLotSize(new BigDecimal(lotSize));
    entity.setLeverage(leverage);
    return entity;
  }
}
