package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.market.dto.QuoteResponse;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class MarketTestControlControllerTest {

  @Test
  void exposesAdminOnlyTestControlRoutes() throws NoSuchMethodException {
    RequestMapping requestMapping = MarketTestControlController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = MarketTestControlController.class.getAnnotation(PreAuthorize.class);
    Method override = MarketTestControlController.class.getMethod("override", MarketTestControlRequest.class);
    Method endOverride = MarketTestControlController.class.getMethod("endOverride", String.class);

    assertThat(requestMapping.value()).containsExactly("/api/admin/market/test-control");
    assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(override.getAnnotation(PostMapping.class).value()).containsExactly("/overrides");
    assertThat(endOverride.getAnnotation(DeleteMapping.class).value()).containsExactly("/overrides/{symbol}");
  }

  @Test
  void delegatesOverrideAndEndOverrideToService() {
    MarketTestControlService service = Mockito.mock(MarketTestControlService.class);
    RealtimeQuoteSink sink = Mockito.mock(RealtimeQuoteSink.class);
    MarketTestControlController controller = new MarketTestControlController(service, sink);
    MarketTestControlRequest request = new MarketTestControlRequest(
        "BTCUSDT",
        new BigDecimal("100.00"),
        new BigDecimal("102.00"),
        Duration.ofMinutes(1));
    QuoteResponse response = new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("100.00"),
        new BigDecimal("102.00"),
        new BigDecimal("101.0000000000"),
        new BigDecimal("2.00"),
        "test-control",
        1780000000000L);
    when(service.startOverride(request)).thenReturn(response);

    assertThat(controller.override(request).data()).isSameAs(response);
    assertThat(controller.endOverride("btc-usdt").success()).isTrue();

    verify(service).startOverride(request);
    verify(sink).acceptTestControl(response);
    verify(service).endOverride("btc-usdt");
  }
}
