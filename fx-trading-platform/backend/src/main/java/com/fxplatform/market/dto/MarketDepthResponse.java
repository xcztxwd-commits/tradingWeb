package com.fxplatform.market.dto;

import java.util.List;

/**
 * MarketDepthResponse 承载行情模块的数据结构。
 */
public record MarketDepthResponse(
    String symbol,
    long timestamp,
    List<MarketDepthLevelResponse> bids,
    List<MarketDepthLevelResponse> asks
) {
}
