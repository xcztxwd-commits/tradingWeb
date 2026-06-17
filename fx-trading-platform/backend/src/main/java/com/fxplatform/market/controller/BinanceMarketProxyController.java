package com.fxplatform.market.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.market.adapter.binance.BinanceFuturesDashboardSourceResponse;
import com.fxplatform.market.adapter.binance.BinanceMarketOverviewSourceResponse;
import com.fxplatform.market.adapter.binance.BinanceMarketProxyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/binance")
@RequiredArgsConstructor
public class BinanceMarketProxyController {

  private final BinanceMarketProxyClient client;

  @GetMapping("/overview-source")
  public ApiResponse<BinanceMarketOverviewSourceResponse> overviewSource() {
    return ApiResponse.success(client.fetchMarketOverviewSource());
  }

  @GetMapping("/futures-dashboard-source")
  public ApiResponse<BinanceFuturesDashboardSourceResponse> futuresDashboardSource(
      @RequestParam(defaultValue = "BTCUSDT") String symbol,
      @RequestParam(defaultValue = "5m") String period
  ) {
    return ApiResponse.success(client.fetchFuturesDashboardSource(symbol, period));
  }
}
