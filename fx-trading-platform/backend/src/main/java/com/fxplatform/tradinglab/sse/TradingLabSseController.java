package com.fxplatform.tradinglab.sse;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/admin/trading-lab/runs")
public class TradingLabSseController {

  private final TradingLabSseService service;

  public TradingLabSseController(TradingLabSseService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize(
      "hasRole('ADMIN') and hasAuthority('"
          + AdminPermissionCatalog.TRADING_LAB_VIEW
          + "')")
  public SseEmitter events(
      @PathVariable("id") UUID runId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
  ) {
    return service.events(runId, lastEventId);
  }
}
