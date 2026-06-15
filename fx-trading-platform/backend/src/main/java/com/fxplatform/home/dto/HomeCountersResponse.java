package com.fxplatform.home.dto;

public record HomeCountersResponse(
    long users,
    long activeTraders,
    long dailyTrades
) {
}
