package com.fxplatform.market.realtime;

import com.fxplatform.common.market.SymbolNormalizer;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MarketRealtimeLifecycle implements ApplicationRunner {

  private final MarketRealtimeProperties properties;
  private final BinanceRealtimeClient client;
  private final BinanceSubscriptionManager subscriptionManager;
  private final RealtimeBackfillService backfillService;
  private final boolean testDataEnabled;
  private final String testDataSymbols;

  @Autowired
  public MarketRealtimeLifecycle(
      MarketRealtimeProperties properties,
      BinanceRealtimeClient client,
      BinanceSubscriptionManager subscriptionManager,
      RealtimeBackfillService backfillService,
      @Value("${market.test-data.enabled:false}") boolean testDataEnabled,
      @Value("${market.test-data.symbols:}") String testDataSymbols
  ) {
    this.properties = properties;
    this.client = client;
    this.subscriptionManager = subscriptionManager;
    this.backfillService = backfillService;
    this.testDataEnabled = testDataEnabled;
    this.testDataSymbols = testDataSymbols;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.enabled()) {
      return;
    }
    List<String> symbols = normalizeSymbols(properties.symbols());
    assertNoTestDataConflict(symbols);
    symbols.forEach(subscriptionManager::activateSymbol);
    if (properties.backfillEnabled()) {
      backfillService.backfill(symbols);
    }
    client.connect();
  }

  private void assertNoTestDataConflict(List<String> bootstrapSymbols) {
    if (!testDataEnabled) {
      return;
    }
    List<String> configuredTestSymbols = normalizeCsv(testDataSymbols);
    if (configuredTestSymbols.isEmpty()) {
      throw new IllegalStateException(
          "Realtime market data requires MARKET_TEST_DATA_ENABLED=false when demo test data runs for all symbols");
    }
    List<String> overlaps = bootstrapSymbols.stream()
        .filter(configuredTestSymbols::contains)
        .toList();
    if (!overlaps.isEmpty()) {
      throw new IllegalStateException(
          "Realtime market data overlaps with MARKET_TEST_DATA_SYMBOLS " + overlaps
              + "; set MARKET_TEST_DATA_ENABLED=false or use non-overlapping test data symbols");
    }
  }

  private List<String> normalizeCsv(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return normalizeSymbols(List.of(value.split(",")));
  }

  private List<String> normalizeSymbols(List<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String symbol : symbols) {
      if (symbol != null && !symbol.isBlank()) {
        normalized.add(SymbolNormalizer.normalize(symbol));
      }
    }
    return List.copyOf(normalized);
  }
}
