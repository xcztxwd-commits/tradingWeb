package com.fxplatform.market.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.MarketStatusResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.service.MarketTestDataService;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MarketController 是行情模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

  private final SymbolService symbolService;
  private final QuoteService quoteService;
  private final MarketTestDataService marketTestDataService;

  /**
   * 处理 symbols 查询接口请求。
   */
  @GetMapping("/symbols")
  public ApiResponse<List<SymbolResponse>> symbols(
      @RequestParam(required = false) String assetClass,
      @RequestParam(defaultValue = "1000") int limit
  ) {
    return ApiResponse.success(symbolService.enabledSymbols(assetClass, limit));
  }

  /**
   * 处理 quote 查询接口请求。
   */
  @GetMapping("/quotes/{symbol}")
  public ApiResponse<QuoteResponse> quote(@PathVariable String symbol) {
    return ApiResponse.success(quoteService.latestQuote(symbol));
  }

  /**
   * 处理 orderBook 查询接口请求。
   */
  @GetMapping("/order-book/{symbol}")
  public ApiResponse<MarketDepthResponse> orderBook(@PathVariable String symbol) {
    return ApiResponse.success(marketTestDataService.orderBook(symbol));
  }

  /**
   * 处理 trades 查询接口请求。
   */
  @GetMapping("/trades/{symbol}")
  public ApiResponse<List<RecentTradeResponse>> trades(
      @PathVariable String symbol,
      @RequestParam(defaultValue = "40") int limit
  ) {
    return ApiResponse.success(marketTestDataService.recentTrades(symbol, limit));
  }

  /**
   * 处理 status 查询接口请求。
   */
  @GetMapping("/status")
  public ApiResponse<MarketStatusResponse> status() {
    return ApiResponse.success(quoteService.status());
  }
}
