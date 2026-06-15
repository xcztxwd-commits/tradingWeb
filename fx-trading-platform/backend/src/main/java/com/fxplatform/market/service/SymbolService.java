package com.fxplatform.market.service;

import cn.hutool.core.util.StrUtil;

import com.fxplatform.market.adapter.MarketDataProvider;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SymbolService 是行情模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class SymbolService {

  private final SymbolRepository symbolRepository;
  private final MarketDataProvider marketDataProvider;

  @Value("${market.provider-symbol-cache-ttl-ms:300000}")
  private long providerSymbolCacheTtlMs = 300000;

  @Value("${market.provider-snapshot-cache-ttl-ms:15000}")
  private long providerSnapshotCacheTtlMs = 15000;

  private ProviderSymbolCache forexProviderSymbolCache = ProviderSymbolCache.empty();
  private ProviderQuoteCache forexProviderQuoteCache = ProviderQuoteCache.empty();

  public List<SymbolResponse> enabledSymbols() {
    return enabledSymbols(null, 1000);
  }

  public List<SymbolResponse> enabledSymbols(String assetClass, int limit) {
    int normalizedLimit = limit > 0 ? limit : 1000;
    String normalizedAssetClass = normalizeAssetClass(assetClass);
    Map<String, SymbolResponse> symbols = new LinkedHashMap<>();
    symbolRepository.findByEnabledTrueOrderBySymbolAsc().stream()
        .filter(entity -> normalizedAssetClass == null || normalizedAssetClass.equals(normalizeAssetClass(entity.getAssetClass())))
        .map(this::toResponse)
        .forEach(symbol -> symbols.put(symbol.symbol(), symbol));

    if (marketDataProvider.isConfigured()
        && symbols.size() < normalizedLimit
        && (normalizedAssetClass == null || "FOREX".equals(normalizedAssetClass))) {
      providerForexSymbols(normalizedLimit)
          .forEach(symbol -> {
            if (symbols.size() < normalizedLimit) {
              symbols.putIfAbsent(symbol.symbol(), symbol);
            }
          });
    }

    Map<String, QuoteResponse> quotes = providerForexQuotes(normalizedAssetClass, normalizedLimit);
    return symbols.values().stream()
        .map(symbol -> enrichSymbol(symbol, quotes.get(symbol.symbol())))
        .limit(normalizedLimit)
        .toList();
  }

  private SymbolResponse toResponse(SymbolEntity entity) {
    return new SymbolResponse(
        entity.getSymbol(),
        entity.getDisplayName(),
        entity.getAssetClass(),
        entity.getBaseCurrency(),
        entity.getQuoteCurrency(),
        entity.getMinLot(),
        entity.getMaxLot(),
        entity.getLeverage(),
        entity.getEnabled(),
        entity.getProvider(),
        entity.getProviderSymbol(),
        true);
  }

  private SymbolResponse enrichSymbol(SymbolResponse symbol, QuoteResponse quote) {
    if (quote == null) {
      return symbol;
    }

    return new SymbolResponse(
        symbol.symbol(),
        symbol.displayName(),
        symbol.assetClass(),
        symbol.baseCurrency(),
        symbol.quoteCurrency(),
        symbol.minLot(),
        symbol.maxLot(),
        symbol.leverage(),
        symbol.enabled(),
        symbol.provider(),
        symbol.providerSymbol(),
        symbol.tradable(),
        quote.mid(),
        quote.changePercent(),
        quote.high24h(),
        quote.low24h(),
        quote.volume24h(),
        estimateMarketCap(quote),
        quote.spread(),
        quote.timestamp(),
        quote.source());
  }

  private BigDecimal estimateMarketCap(QuoteResponse quote) {
    if (quote.mid() == null || quote.volume24h() == null || quote.volume24h().compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return quote.mid().multiply(quote.volume24h()).multiply(new BigDecimal("8"));
  }

  private String normalizeAssetClass(String assetClass) {
    return StrUtil.isBlank(assetClass) ? null : assetClass.trim().toUpperCase(Locale.ROOT);
  }

  private synchronized List<SymbolResponse> providerForexSymbols(int limit) {
    Instant now = Instant.now();
    if (forexProviderSymbolCache.isFreshFor(limit, now)) {
      return forexProviderSymbolCache.symbols();
    }

    List<SymbolResponse> fetched = marketDataProvider.fetchSymbols("FOREX", limit);
    if (fetched.isEmpty()) {
      return forexProviderSymbolCache.symbols();
    }
    if (forexProviderSymbolCache.symbols().size() > fetched.size()) {
      forexProviderSymbolCache = forexProviderSymbolCache.withExpiresAt(expiresAt(now));
      return forexProviderSymbolCache.symbols();
    }

    forexProviderSymbolCache = new ProviderSymbolCache(List.copyOf(fetched), limit, expiresAt(now));
    return forexProviderSymbolCache.symbols();
  }

  private Map<String, QuoteResponse> providerForexQuotes(String normalizedAssetClass, int limit) {
    if (!marketDataProvider.isConfigured()
        || !(normalizedAssetClass == null || "FOREX".equals(normalizedAssetClass))) {
      return Map.of();
    }
    return providerForexSnapshots(limit);
  }

  private synchronized Map<String, QuoteResponse> providerForexSnapshots(int limit) {
    Instant now = Instant.now();
    if (forexProviderQuoteCache.isFreshFor(limit, now)) {
      return forexProviderQuoteCache.quotes();
    }

    Map<String, QuoteResponse> fetched = marketDataProvider.fetchMarketSnapshots("FOREX", limit);
    if (fetched == null || fetched.isEmpty()) {
      return forexProviderQuoteCache.quotes();
    }
    if (forexProviderQuoteCache.quotes().size() > fetched.size()) {
      forexProviderQuoteCache = forexProviderQuoteCache.withExpiresAt(snapshotExpiresAt(now));
      return forexProviderQuoteCache.quotes();
    }

    forexProviderQuoteCache = new ProviderQuoteCache(Map.copyOf(fetched), Integer.MAX_VALUE, snapshotExpiresAt(now));
    return forexProviderQuoteCache.quotes();
  }

  private Instant expiresAt(Instant now) {
    return now.plusMillis(Math.max(providerSymbolCacheTtlMs, 0));
  }

  private Instant snapshotExpiresAt(Instant now) {
    return now.plusMillis(Math.max(providerSnapshotCacheTtlMs, 0));
  }

  private record ProviderSymbolCache(List<SymbolResponse> symbols, int requestedLimit, Instant expiresAt) {

    static ProviderSymbolCache empty() {
      return new ProviderSymbolCache(List.of(), 0, Instant.EPOCH);
    }

    boolean isFreshFor(int limit, Instant now) {
      return !symbols.isEmpty()
          && requestedLimit >= limit
          && expiresAt.isAfter(now);
    }

    ProviderSymbolCache withExpiresAt(Instant nextExpiresAt) {
      return new ProviderSymbolCache(symbols, requestedLimit, nextExpiresAt);
    }
  }

  private record ProviderQuoteCache(Map<String, QuoteResponse> quotes, int requestedLimit, Instant expiresAt) {

    static ProviderQuoteCache empty() {
      return new ProviderQuoteCache(Map.of(), 0, Instant.EPOCH);
    }

    boolean isFreshFor(int limit, Instant now) {
      return !quotes.isEmpty()
          && requestedLimit >= limit
          && expiresAt.isAfter(now);
    }

    ProviderQuoteCache withExpiresAt(Instant nextExpiresAt) {
      return new ProviderQuoteCache(quotes, requestedLimit, nextExpiresAt);
    }
  }
}
