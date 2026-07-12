package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

class SimulatedExecutionAdapterFeeTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withPropertyValues("execution.mode=demo")
      .withUserConfiguration(AdapterTestConfiguration.class);

  @Test
  void demoAdapterReturnsOnlyTheExplicitFullQuantityIntent() {
    contextRunner.run(context -> {
      ExecutionResult result = context.getBean(SimulatedExecutionAdapter.class)
          .execute(marketOrder("BTCUSDT", "2.00"));

      assertThat(result.filledQuantity()).isEqualByComparingTo("2.00");
      assertThat(result.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(result.filledPrice()).isNull();
      assertThat(result.fee()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(result.feeAsset()).isNull();
      assertThat(result.slippage()).isEqualByComparingTo(BigDecimal.ZERO);
    });
  }

  @Test
  void demoAdapterDoesNotRequireAQuoteOrSymbolRepository() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(SimulatedExecutionAdapter.class);
      assertThat(context).doesNotHaveBean(com.fxplatform.market.service.QuoteService.class);
      assertThat(context).doesNotHaveBean(com.fxplatform.market.repository.SymbolRepository.class);
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
        "intent-test-" + symbol,
        null,
        new BigDecimal(quantity),
        null);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Import(SimulatedExecutionAdapter.class)
  static class AdapterTestConfiguration {
  }
}
