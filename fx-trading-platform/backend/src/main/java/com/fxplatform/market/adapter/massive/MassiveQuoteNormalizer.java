package com.fxplatform.market.adapter.massive;

import com.fxplatform.market.dto.QuoteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * MassiveQuoteNormalizer 是行情模块的外部服务适配器。
 */
@Component
public class MassiveQuoteNormalizer {

  /**
   * 执行 normalize 适配器逻辑。
   */
  public QuoteResponse normalize(String symbol, MassiveQuotePayload payload) {
    BigDecimal spread = payload.ask().subtract(payload.bid()).max(BigDecimal.ZERO);
    BigDecimal mid = payload.bid().add(payload.ask()).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);

    // Massive 原始字段只在 adapter 内出现，出站统一成平台 QuoteResponse。
    return new QuoteResponse("quote", symbol, payload.bid(), payload.ask(), mid, spread, "massive", payload.timestamp());
  }
}
