package com.fxplatform.market.realtime;

import com.fxplatform.common.response.ApiResponse;
import java.util.OptionalLong;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/market/realtime")
@PreAuthorize("hasRole('ADMIN')")
public class MarketRealtimeStatusController {

  private final MarketRealtimeProperties properties;
  private final BinanceRealtimeClient client;
  private final BinanceSubscriptionManager subscriptionManager;
  private final RealtimeQuoteSink quoteSink;
  private final RealtimeBackfillService backfillService;

  public MarketRealtimeStatusController(
      MarketRealtimeProperties properties,
      BinanceRealtimeClient client,
      BinanceSubscriptionManager subscriptionManager,
      RealtimeQuoteSink quoteSink,
      RealtimeBackfillService backfillService) {
    this.properties = properties;
    this.client = client;
    this.subscriptionManager = subscriptionManager;
    this.quoteSink = quoteSink;
    this.backfillService = backfillService;
  }

  @GetMapping("/status")
  public ApiResponse<MarketRealtimeStatus> status() {
    return ApiResponse.success(new MarketRealtimeStatus(
        properties.enabled(),
        properties.provider(),
        client.connected(),
        boxed(client.connectedAt()),
        boxed(client.lastMessageAt()),
        subscriptionManager.activeSymbols(),
        subscriptionManager.desiredStreams().size(),
        quoteSink.processedCount(),
        quoteSink.cacheFailureCount(),
        quoteSink.candleFailureCount(),
        quoteSink.publishFailureCount(),
        backfillService.successCount(),
        backfillService.failureCount(),
        client.reconnectAttempt(),
        subscriptionManager.rejectedSymbolCount()));
  }

  private Long boxed(OptionalLong value) {
    return value.isPresent() ? value.getAsLong() : null;
  }
}
