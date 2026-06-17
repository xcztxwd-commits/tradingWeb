package com.fxplatform.market.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.dto.MarketDepthLevelResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class BinanceStreamMessageParser {

  private final ObjectMapper objectMapper;

  public BinanceStreamMessageParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Optional<RealtimeMarketEvent> parse(String payload) {
    try {
      return parseRequired(payload);
    } catch (RuntimeException | IOException ex) {
      return Optional.empty();
    }
  }

  Optional<RealtimeMarketEvent> parseRequired(String payload) throws IOException {
    JsonNode root = objectMapper.readTree(payload);
    String streamName = text(root, "stream", "");
    JsonNode data = root.has("data") ? root.path("data") : root;
    return parseData(data, streamName);
  }

  private Optional<RealtimeMarketEvent> parseData(JsonNode data, String streamName) {
    String eventType = text(data, "e", "");
    return switch (eventType) {
      case "aggTrade" -> Optional.of(parseTrade(data, streamName));
      case "24hrTicker" -> Optional.of(parseTickerStats(data, streamName));
      case "kline" -> Optional.of(parseCandle(data, streamName));
      case "serverShutdown" -> Optional.of(new RealtimeMarketEvent.ServerShutdown(longValue(data, "E")));
      default -> eventType.isBlank() ? parseShapeBased(data, streamName) : Optional.empty();
    };
  }

  private Optional<RealtimeMarketEvent> parseShapeBased(JsonNode data, String streamName) {
    if (data.has("u") && data.has("b") && data.has("a")) {
      return Optional.of(new RealtimeMarketEvent.Quote(
          symbol(data, streamName),
          longValue(data, "E"),
          longValue(data, "u"),
          decimal(data, "b"),
          decimal(data, "a")));
    }
    if (data.has("lastUpdateId") && data.has("bids") && data.has("asks")) {
      return Optional.of(new RealtimeMarketEvent.OrderBook(
          symbol(data, streamName),
          longValue(data, "E"),
          longValue(data, "lastUpdateId"),
          depthLevels(data.path("bids")),
          depthLevels(data.path("asks"))));
    }
    return Optional.empty();
  }

  private RealtimeMarketEvent.TickerStats parseTickerStats(JsonNode data, String streamName) {
    return new RealtimeMarketEvent.TickerStats(
        symbol(data, streamName),
        longValue(data, "E"),
        decimal(data, "P"),
        decimal(data, "h"),
        decimal(data, "l"),
        decimal(data, "v"));
  }

  private RealtimeMarketEvent.Trade parseTrade(JsonNode data, String streamName) {
    return new RealtimeMarketEvent.Trade(
        symbol(data, streamName),
        longValue(data, "E"),
        longValue(data, "a"),
        decimal(data, "p"),
        decimal(data, "q"),
        data.path("m").asBoolean(false) ? "SELL" : "BUY");
  }

  private RealtimeMarketEvent.Candle parseCandle(JsonNode data, String streamName) {
    JsonNode kline = data.path("k");
    return new RealtimeMarketEvent.Candle(
        symbol(data, streamName),
        longValue(data, "E"),
        text(kline, "i", ""),
        longValue(kline, "t"),
        longValue(kline, "T"),
        longValue(kline, "L"),
        decimal(kline, "o"),
        decimal(kline, "h"),
        decimal(kline, "l"),
        decimal(kline, "c"),
        decimal(kline, "v"),
        kline.path("x").asBoolean(false));
  }

  private List<MarketDepthLevelResponse> depthLevels(JsonNode rows) {
    List<MarketDepthLevelResponse> levels = new ArrayList<>();
    for (JsonNode row : rows) {
      if (row.isArray() && row.size() >= 2) {
        levels.add(new MarketDepthLevelResponse(new BigDecimal(row.get(0).asText()), new BigDecimal(row.get(1).asText())));
      }
    }
    return List.copyOf(levels);
  }

  private String symbol(JsonNode data, String streamName) {
    String symbol = text(data, "s", "");
    if (!symbol.isBlank()) {
      return symbol;
    }
    return streamName.isBlank() ? "" : BinanceStreamName.symbolFromStream(streamName);
  }

  private BigDecimal decimal(JsonNode data, String field) {
    return new BigDecimal(data.path(field).asText());
  }

  private long longValue(JsonNode data, String field) {
    return data.path(field).asLong(0L);
  }

  private String text(JsonNode data, String field, String fallback) {
    return data.path(field).asText(fallback);
  }
}
