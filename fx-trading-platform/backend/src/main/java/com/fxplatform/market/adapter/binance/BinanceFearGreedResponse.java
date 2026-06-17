package com.fxplatform.market.adapter.binance;

public record BinanceFearGreedResponse(
    int value,
    String label,
    Long updatedAt,
    String source
) {
}
