package com.fxplatform.market.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.dto.InstrumentRulesResponse;
import com.fxplatform.market.dto.MarketDepthResponse;
import com.fxplatform.market.dto.MarketStatusResponse;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.RecentTradeResponse;
import com.fxplatform.market.dto.PerpetualReferenceResponse;
import com.fxplatform.market.model.MarketBundleProducts;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.dto.UserFavoriteSymbolRequest;
import com.fxplatform.market.provider.MarketDataRouter;
import com.fxplatform.market.realtime.RealtimeMarketSnapshotCache;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolService;
import com.fxplatform.market.service.UserFavoriteSymbolService;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  private final MarketDataRouter marketDataRouter;
  private final UserFavoriteSymbolService userFavoriteSymbolService;
  private final InstrumentRulesEngine instrumentRulesEngine;
  private final RealtimeMarketSnapshotCache realtimeCache;

  /**
   * 处理 symbols 查询接口请求。
   */
  @GetMapping("/symbols")
  public ApiResponse<List<SymbolResponse>> symbols(
      @RequestParam(required = false) String assetClass,
      @RequestParam(defaultValue = "2000") int limit
  ) {
    return ApiResponse.success(symbolService.enabledSymbols(assetClass, limit));
  }

  @GetMapping("/symbols/{symbol}/rules")
  public ApiResponse<InstrumentRulesResponse> symbolRules(@PathVariable String symbol) {
    return ApiResponse.success(InstrumentRulesResponse.from(instrumentRulesEngine.rules(symbol)));
  }

  @GetMapping("/symbol-rules")
  public ApiResponse<List<InstrumentRulesResponse>> symbolRulesBatch(@RequestParam String symbols) {
    List<String> normalizedSymbols = List.of(symbols.split(","))
        .stream()
        .map(String::trim)
        .map(SymbolNormalizer::normalize)
        .filter(value -> !value.isBlank())
        .toList();
    return ApiResponse.success(instrumentRulesEngine.rules(normalizedSymbols)
        .stream()
        .map(InstrumentRulesResponse::from)
        .toList());
  }

  /**
   * 处理 quote 查询接口请求。
   */
  @GetMapping("/quotes/{symbol}")
  public ApiResponse<QuoteResponse> quote(@PathVariable String symbol) {
    return ApiResponse.success(quoteService.latestQuote(symbol));
  }

  @GetMapping(value = "/quotes", params = "symbols")
  public ApiResponse<Map<String, QuoteResponse>> quotes(@RequestParam String symbols) {
    List<String> normalizedSymbols = List.of(symbols.split(","))
        .stream()
        .map(String::trim)
        .map(SymbolNormalizer::normalize)
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
    return ApiResponse.success(quoteService.latestQuotes(normalizedSymbols));
  }

  /**
   * 处理 orderBook 查询接口请求。
   */
  @GetMapping("/order-book/{symbol}")
  public ApiResponse<MarketDepthResponse> orderBook(@PathVariable String symbol) {
    if (MarketBundleProducts.isP0(symbol)) {
      return ApiResponse.success(marketDataRouter.orderBook(symbol));
    }
    return ApiResponse.success(realtimeCache.orderBook(symbol)
        .orElseGet(() -> marketDataRouter.orderBook(symbol)));
  }

  /**
   * 处理 trades 查询接口请求。
   */
  @GetMapping("/trades/{symbol}")
  public ApiResponse<List<RecentTradeResponse>> trades(
      @PathVariable String symbol,
      @RequestParam(defaultValue = "40") int limit
  ) {
    if (MarketBundleProducts.isP0(symbol)) {
      return ApiResponse.success(marketDataRouter.recentTrades(symbol, limit));
    }
    List<RecentTradeResponse> cached = realtimeCache.recentTrades(symbol, limit);
    return ApiResponse.success(cached.isEmpty() ? marketDataRouter.recentTrades(symbol, limit) : cached);
  }

  @GetMapping("/perpetuals/{symbol}/reference")
  public ApiResponse<PerpetualReferenceResponse> perpetualReference(@PathVariable String symbol) {
    return ApiResponse.success(marketDataRouter.perpetualReference(symbol));
  }

  /**
   * 处理 status 查询接口请求。
   */
  @GetMapping("/status")
  public ApiResponse<MarketStatusResponse> status() {
    return ApiResponse.success(quoteService.status());
  }

  @GetMapping("/favorites")
  public ApiResponse<List<String>> favoriteSymbols(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ApiResponse.success(List.of());
    }
    return ApiResponse.success(userFavoriteSymbolService.favoriteSymbols(principal.id()));
  }

  @PutMapping("/favorites/{symbol}")
  public ApiResponse<List<String>> setFavorite(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String symbol,
      @RequestBody UserFavoriteSymbolRequest request
  ) {
    if (principal == null) {
      throw new BusinessException("AUTH_REQUIRED", "Authentication required");
    }
    return ApiResponse.success(userFavoriteSymbolService.setFavorite(principal.id(), symbol, request.favorite()));
  }
}
