package com.fxplatform.home.dto;

import java.util.List;

public record HomeCountersResponse(
    long users,
    long activeTraders,
    long dailyTrades,
    List<HomePromoCardResponse> metricCards
) {
}
