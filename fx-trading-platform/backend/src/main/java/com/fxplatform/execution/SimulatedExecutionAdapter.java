package com.fxplatform.execution;

import cn.hutool.core.date.DateUtil;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "demo")
public class SimulatedExecutionAdapter implements ExecutionAdapter {

  private static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.0001");
  private static final BigDecimal FEE_RATE = new BigDecimal("0.0010");
  private static final BigDecimal PARTIAL_FILL_THRESHOLD = new BigDecimal("1.00");
  private static final BigDecimal HALF = new BigDecimal("0.5");

  private final QuoteService quoteService;

  @Override
  public ExecutionResult execute(CreateOrderRequest request) {
    QuoteResponse quote = quoteService.freshQuote(request.symbol());
    BigDecimal referencePrice = request.side() == OrderSide.BUY ? quote.ask() : quote.bid();
    BigDecimal slippage = referencePrice.multiply(SLIPPAGE_RATE).setScale(10, RoundingMode.HALF_UP);
    BigDecimal filledPrice = request.side() == OrderSide.BUY
        ? referencePrice.add(slippage)
        : referencePrice.subtract(slippage);
    BigDecimal quantity = request.quantity();
    BigDecimal filledQuantity = quantity.compareTo(PARTIAL_FILL_THRESHOLD) > 0
        ? quantity.multiply(HALF).setScale(4, RoundingMode.HALF_UP)
        : quantity;
    BigDecimal remainingQuantity = quantity.subtract(filledQuantity).max(BigDecimal.ZERO);
    BigDecimal fee = filledPrice.multiply(filledQuantity).multiply(FEE_RATE).setScale(8, RoundingMode.HALF_UP);

    return new ExecutionResult(
        filledPrice,
        DateUtil.date().toInstant(),
        filledQuantity,
        remainingQuantity,
        fee,
        slippage,
        null,
        null);
  }
}
