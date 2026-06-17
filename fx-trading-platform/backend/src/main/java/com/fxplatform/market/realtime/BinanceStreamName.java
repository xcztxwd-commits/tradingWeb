package com.fxplatform.market.realtime;

import java.util.Locale;

public final class BinanceStreamName {

  private BinanceStreamName() {
  }

  public static String bookTicker(String symbol) {
    return stream(symbol, "bookTicker");
  }

  public static String ticker(String symbol) {
    return stream(symbol, "ticker");
  }

  public static String aggTrade(String symbol) {
    return stream(symbol, "aggTrade");
  }

  public static String depth(String symbol, int levels, boolean fast) {
    String suffix = fast ? "depth%d@100ms".formatted(levels) : "depth%d".formatted(levels);
    return stream(symbol, suffix);
  }

  public static String kline(String symbol, String interval) {
    return stream(symbol, "kline_" + interval);
  }

  public static String symbolFromStream(String streamName) {
    int separator = streamName.indexOf('@');
    String symbol = separator >= 0 ? streamName.substring(0, separator) : streamName;
    return symbol.toUpperCase(Locale.ROOT);
  }

  private static String stream(String symbol, String suffix) {
    return symbol.toLowerCase(Locale.ROOT) + "@" + suffix;
  }
}
