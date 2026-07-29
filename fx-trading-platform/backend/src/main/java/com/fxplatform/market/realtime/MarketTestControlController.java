package com.fxplatform.market.realtime;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.market.dto.QuoteResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/market/test-control")
@PreAuthorize("hasRole('ADMIN')")
@Profile("(dev | test) & !prod")
@ConditionalOnProperty(prefix = "market.test-control", name = "enabled", havingValue = "true")
public class MarketTestControlController {

  private final MarketTestControlService service;
  private final RealtimeQuoteSink sink;

  public MarketTestControlController(MarketTestControlService service, RealtimeQuoteSink sink) {
    this.service = service;
    this.sink = sink;
  }

  @PostMapping("/overrides")
  public ApiResponse<QuoteResponse> override(@RequestBody MarketTestControlRequest request) {
    QuoteResponse response = service.startOverride(request);
    sink.acceptTestControl(response);
    return ApiResponse.success(response);
  }

  @DeleteMapping("/overrides/{symbol}")
  public ApiResponse<Void> endOverride(@PathVariable String symbol) {
    service.endOverride(symbol);
    return ApiResponse.success(null);
  }
}
