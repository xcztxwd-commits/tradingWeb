package com.fxplatform.market.adapter.binance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record BinanceMarketOverviewSourceResponse(
    List<JsonNode> products,
    BinanceFearGreedResponse fearGreed
) {
}
