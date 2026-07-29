package com.fxplatform.engagement.scheduling;

import com.fxplatform.engagement.application.popup.PopupDeliveryRetentionService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    prefix = "app.engagement.retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class PopupDeliveryRetentionScheduler {

  private final PopupDeliveryRetentionService retentionService;

  public PopupDeliveryRetentionScheduler(PopupDeliveryRetentionService retentionService) {
    this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
  }

  @Scheduled(fixedDelayString = "${app.engagement.retention.fixed-delay:PT24H}")
  public int cleanup() {
    return retentionService.deleteExpiredRawDeliveries();
  }
}
