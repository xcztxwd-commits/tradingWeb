package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class SimulatedExecutionAdapterFeeTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withPropertyValues("execution.mode=demo", "massive.quote-stale-ms=60000")
      .withUserConfiguration(AdapterTestConfiguration.class);

  @Test
  void spotFeeUsesUnitSizeOneAndKeepsExistingQuoteNotional() {
    assertFee("BTCUSDT", "SPOT", "BTC", "USDT", "1", "0.10", "50000", "50010", "5.00150010");
  }

  @Test
  void forexFeeUsesStandardLotNotional() {
    assertFee("EURUSD", "FOREX", "EUR", "USD", "100000", "0.10", "1.09990", "1.10000", "11.00110000");
  }

  @Test
  void linearPerpetualFeeUsesContractQuoteNotional() {
    assertFee("BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1.00", "24990", "25000", "25.00250000");
  }

  @Test
  void marketOrdersFillRequestedQuantityInDemoExecution() {
    contextRunner.run(context -> {
      QuoteService quoteService = context.getBean(QuoteService.class);
      SymbolRepository symbolRepository = context.getBean(SymbolRepository.class);
      when(quoteService.freshQuote("ETHUSDT")).thenReturn(quote("ETHUSDT", "2799", "2800"));
      when(symbolRepository.findBySymbol("ETHUSDT"))
          .thenReturn(Optional.of(symbol("ETHUSDT", "LINEAR_PERPETUAL", "ETH", "USDT", "1")));

      ExecutionResult result = context.getBean(SimulatedExecutionAdapter.class)
          .execute(marketOrder("ETHUSDT", "2.00"));

      assertThat(result.filledQuantity()).isEqualByComparingTo("2.00");
      assertThat(result.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    });
  }

  @Test
  void inversePerpetualFeeUsesCurrentContractSizeMarginConvention() {
    assertFee("BTCUSD", "INVERSE_PERPETUAL", "BTC", "USD", "100", "1.00", "49990", "50000", "0.00000200");
  }

  @Test
  void inversePerpetualFeeExposesSettlementAssetForWalletPosting() {
    contextRunner.run(context -> {
      QuoteService quoteService = context.getBean(QuoteService.class);
      SymbolRepository symbolRepository = context.getBean(SymbolRepository.class);
      when(quoteService.freshQuote("BTCUSD")).thenReturn(quote("BTCUSD", "49990", "50000"));
      when(symbolRepository.findBySymbol("BTCUSD"))
          .thenReturn(Optional.of(symbol("BTCUSD", "INVERSE_PERPETUAL", "BTC", "USD", "100")));

      ExecutionResult result = context.getBean(SimulatedExecutionAdapter.class)
          .execute(marketOrder("BTCUSD", "1.00"));

      assertThat(result.feeAsset()).isEqualTo("BTC");
    });
  }

  private void assertFee(
      String code,
      String assetClass,
      String baseCurrency,
      String quoteCurrency,
      String lotSize,
      String quantity,
      String bid,
      String ask,
      String expectedFee
  ) {
    contextRunner.run(context -> {
      QuoteService quoteService = context.getBean(QuoteService.class);
      SymbolRepository symbolRepository = context.getBean(SymbolRepository.class);
      when(quoteService.freshQuote(code)).thenReturn(quote(code, bid, ask));
      when(symbolRepository.findBySymbol(code))
          .thenReturn(Optional.of(symbol(code, assetClass, baseCurrency, quoteCurrency, lotSize)));

      ExecutionResult result = context.getBean(SimulatedExecutionAdapter.class)
          .execute(marketOrder(code, quantity));

      assertThat(result.fee()).isEqualByComparingTo(expectedFee);
    });
  }

  private static CreateOrderRequest marketOrder(String symbol, String quantity) {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        symbol,
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        "fee-test-" + symbol,
        null,
        new BigDecimal(quantity),
        null);
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
        1780660000000L);
  }

  private static SymbolEntity symbol(
      String code,
      String assetClass,
      String baseCurrency,
      String quoteCurrency,
      String lotSize
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(code);
    symbol.setAssetClass(assetClass);
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize(new BigDecimal(lotSize));
    return symbol;
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Import(SimulatedExecutionAdapter.class)
  static class AdapterTestConfiguration {

    @Bean
    QuoteService quoteService() {
      return org.mockito.Mockito.mock(QuoteService.class);
    }

    @Bean
    SymbolRepository symbolRepository() {
      return org.mockito.Mockito.mock(SymbolRepository.class);
    }
  }
}
