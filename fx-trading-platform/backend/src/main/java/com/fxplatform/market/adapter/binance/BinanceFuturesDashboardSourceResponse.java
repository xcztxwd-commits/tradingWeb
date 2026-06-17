package com.fxplatform.market.adapter.binance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record BinanceFuturesDashboardSourceResponse(
    JsonNode ticker,
    List<JsonNode> openInterest,
    List<JsonNode> topAccountRatio,
    List<JsonNode> topPositionRatio,
    List<JsonNode> globalLongShortRatio,
    List<JsonNode> takerBuySell,
    List<JsonNode> basis,
    List<JsonNode> fundingRates
) {
}
