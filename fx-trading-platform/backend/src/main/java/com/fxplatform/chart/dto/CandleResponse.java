package com.fxplatform.chart.dto;

import java.math.BigDecimal;

/**
 * CandleResponse 承载图表 K 线模块的数据结构。
 */
public record CandleResponse(
    long timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume
) {
}
