package com.fxplatform.engagement.scheduling;

import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.application.message.MessagePublicationService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Explicitly enabled timer that delegates to transactional due-work authorities. */
@Service
@ConditionalOnProperty(
    prefix = "app.engagement.scheduler",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class EngagementScheduledDispatcher {

  private final PopupCampaignService campaignService;
  private final MessagePublicationService messageService;

  public EngagementScheduledDispatcher(
      PopupCampaignService campaignService,
      MessagePublicationService messageService) {
    this.campaignService = Objects.requireNonNull(campaignService, "campaignService");
    this.messageService = Objects.requireNonNull(messageService, "messageService");
  }

  @Scheduled(fixedDelayString = "${app.engagement.scheduler.fixed-delay:PT1M}")
  public int dispatchDue() {
    return campaignService.activateDue() + messageService.dispatchDue();
  }
}
