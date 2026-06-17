package com.fxplatform.market.realtime;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market.realtime")
public class MarketRealtimeProperties {

  private boolean enabled = false;
  private String provider = "binance";
  private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
  private String websocketBaseUrl = "wss://stream.binance.com:9443/ws";
  private Duration quoteStale = Duration.ofMillis(3000);
  private Duration reconnectInitial = Duration.ofMillis(1000);
  private Duration reconnectMax = Duration.ofMillis(30000);
  private Duration reconnectJitter = Duration.ofMillis(250);
  private Duration unsubscribeGrace = Duration.ofMillis(10000);
  private int binanceCommandPerSecond = 4;
  private boolean backfillEnabled = true;
  private Duration backfillLookback = Duration.ofMinutes(60);
  private List<String> klineIntervals = List.of("1s", "1m", "5m", "15m", "1h", "4h", "1d");
  private int orderBookLevels = 20;
  private boolean orderBookFast = true;
  private int maxStreamsPerConnection = 1024;
  private int maxActiveSymbols = 80;
  private boolean dynamicSymbolsEnabled = true;

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String provider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public List<String> symbols() {
    return symbols;
  }

  public void setSymbols(List<String> symbols) {
    this.symbols = symbols;
  }

  public String websocketBaseUrl() {
    return websocketBaseUrl;
  }

  public void setWebsocketBaseUrl(String websocketBaseUrl) {
    this.websocketBaseUrl = websocketBaseUrl;
  }

  public Duration quoteStale() {
    return quoteStale;
  }

  public void setQuoteStale(Duration quoteStale) {
    this.quoteStale = quoteStale;
  }

  public Duration reconnectInitial() {
    return reconnectInitial;
  }

  public void setReconnectInitial(Duration reconnectInitial) {
    this.reconnectInitial = reconnectInitial;
  }

  public Duration reconnectMax() {
    return reconnectMax;
  }

  public void setReconnectMax(Duration reconnectMax) {
    this.reconnectMax = reconnectMax;
  }

  public Duration reconnectJitter() {
    return reconnectJitter;
  }

  public void setReconnectJitter(Duration reconnectJitter) {
    this.reconnectJitter = reconnectJitter;
  }

  public Duration unsubscribeGrace() {
    return unsubscribeGrace;
  }

  public void setUnsubscribeGrace(Duration unsubscribeGrace) {
    this.unsubscribeGrace = unsubscribeGrace;
  }

  public int binanceCommandPerSecond() {
    return binanceCommandPerSecond;
  }

  public void setBinanceCommandPerSecond(int binanceCommandPerSecond) {
    this.binanceCommandPerSecond = binanceCommandPerSecond;
  }

  public boolean backfillEnabled() {
    return backfillEnabled;
  }

  public void setBackfillEnabled(boolean backfillEnabled) {
    this.backfillEnabled = backfillEnabled;
  }

  public Duration backfillLookback() {
    return backfillLookback;
  }

  public void setBackfillLookback(Duration backfillLookback) {
    this.backfillLookback = backfillLookback;
  }

  public List<String> klineIntervals() {
    return klineIntervals;
  }

  public void setKlineIntervals(List<String> klineIntervals) {
    this.klineIntervals = klineIntervals;
  }

  public int orderBookLevels() {
    return orderBookLevels;
  }

  public void setOrderBookLevels(int orderBookLevels) {
    this.orderBookLevels = orderBookLevels;
  }

  public boolean orderBookFast() {
    return orderBookFast;
  }

  public void setOrderBookFast(boolean orderBookFast) {
    this.orderBookFast = orderBookFast;
  }

  public int maxStreamsPerConnection() {
    return maxStreamsPerConnection;
  }

  public void setMaxStreamsPerConnection(int maxStreamsPerConnection) {
    this.maxStreamsPerConnection = maxStreamsPerConnection;
  }

  public int maxActiveSymbols() {
    return maxActiveSymbols;
  }

  public void setMaxActiveSymbols(int maxActiveSymbols) {
    this.maxActiveSymbols = maxActiveSymbols;
  }

  public boolean dynamicSymbolsEnabled() {
    return dynamicSymbolsEnabled;
  }

  public void setDynamicSymbolsEnabled(boolean dynamicSymbolsEnabled) {
    this.dynamicSymbolsEnabled = dynamicSymbolsEnabled;
  }

  public int effectiveStreamsPerSymbol() {
    return 4 + klineIntervals.size();
  }

  public String orderBookStreamSuffix() {
    return orderBookFast ? "depth%d@100ms".formatted(orderBookLevels) : "depth%d".formatted(orderBookLevels);
  }
}
