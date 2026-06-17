package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
class MarketRealtimeStatusControllerTest {

  @Mock
  private BinanceRealtimeClient client;

  @Mock
  private BinanceSubscriptionManager subscriptionManager;

  @Mock
  private RealtimeQuoteSink quoteSink;

  @Mock
  private RealtimeBackfillService backfillService;

  @Test
  void exposesReadOnlyAdminStatusRoute() throws NoSuchMethodException {
    RequestMapping requestMapping = MarketRealtimeStatusController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = MarketRealtimeStatusController.class.getAnnotation(PreAuthorize.class);
    Method status = MarketRealtimeStatusController.class.getMethod("status");
    GetMapping getMapping = status.getAnnotation(GetMapping.class);

    assertThat(requestMapping.value()).containsExactly("/api/admin/market/realtime");
    assertThat(getMapping.value()).containsExactly("/status");
    assertThat(requestMapping.value()[0] + getMapping.value()[0])
        .isEqualTo("/api/admin/market/realtime/status");
    assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
  }

  @Test
  void statusAggregatesConnectionSubscriptionSinkAndBackfillMetrics() {
    MarketRealtimeProperties properties = new MarketRealtimeProperties();
    properties.setEnabled(true);
    properties.setProvider("binance");
    when(client.connected()).thenReturn(true);
    when(client.connectedAt()).thenReturn(OptionalLong.of(1780000000000L));
    when(client.lastMessageAt()).thenReturn(OptionalLong.of(1780000001000L));
    when(client.reconnectAttempt()).thenReturn(2);
    when(subscriptionManager.activeSymbols()).thenReturn(Set.of("BTCUSDT"));
    when(subscriptionManager.desiredStreams()).thenReturn(Set.of("btcusdt@bookTicker", "btcusdt@ticker"));
    when(subscriptionManager.rejectedSymbolCount()).thenReturn(3L);
    when(quoteSink.processedCount()).thenReturn(100L);
    when(quoteSink.cacheFailureCount()).thenReturn(1L);
    when(quoteSink.candleFailureCount()).thenReturn(2L);
    when(quoteSink.publishFailureCount()).thenReturn(4L);
    when(backfillService.successCount()).thenReturn(5L);
    when(backfillService.failureCount()).thenReturn(6L);

    MarketRealtimeStatusController controller = new MarketRealtimeStatusController(
        properties,
        client,
        subscriptionManager,
        quoteSink,
        backfillService);

    MarketRealtimeStatus status = controller.status().data();

    assertThat(status.enabled()).isTrue();
    assertThat(status.provider()).isEqualTo("binance");
    assertThat(status.connected()).isTrue();
    assertThat(status.connectedAt()).isEqualTo(1780000000000L);
    assertThat(status.lastMessageAt()).isEqualTo(1780000001000L);
    assertThat(status.activeSymbols()).containsExactly("BTCUSDT");
    assertThat(status.desiredStreamCount()).isEqualTo(2);
    assertThat(status.processedCount()).isEqualTo(100L);
    assertThat(status.cacheFailureCount()).isEqualTo(1L);
    assertThat(status.candleFailureCount()).isEqualTo(2L);
    assertThat(status.publishFailureCount()).isEqualTo(4L);
    assertThat(status.backfillSuccessCount()).isEqualTo(5L);
    assertThat(status.backfillFailureCount()).isEqualTo(6L);
    assertThat(status.reconnectAttempt()).isEqualTo(2);
    assertThat(status.rejectedSymbolCount()).isEqualTo(3L);
  }
}
