package com.fxplatform.engagement.domain.campaign;

import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Computes popup eligibility from campaign configuration and persisted user state. */
public final class PopupEligibilityPolicy {

  private final Clock clock;

  public PopupEligibilityPolicy(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Decision evaluate(PopupCampaign campaign, PopupImpressionState state) {
    Objects.requireNonNull(campaign, "campaign");
    Objects.requireNonNull(state, "state");
    Instant now = clock.instant();

    if (campaign.lifecycleStatus() != PopupCampaignLifecycleStatus.ACTIVE) {
      return Decision.CAMPAIGN_NOT_ACTIVE;
    }
    if (now.isBefore(campaign.startAt()) || !now.isBefore(campaign.endAt())) {
      return Decision.OUTSIDE_TIME_WINDOW;
    }
    if (state.optedOutAt() != null) {
      return Decision.OPTED_OUT;
    }
    if (state.totalImpressions() >= campaign.maxTotalImpressions()) {
      return Decision.TOTAL_LIMIT_REACHED;
    }

    LocalDate currentBucket = LocalDate.ofInstant(now, campaign.timeZone());
    int currentDailyImpressions = currentBucket.equals(state.dailyBucket())
        ? state.dailyImpressions()
        : 0;
    if (currentDailyImpressions >= campaign.maxDailyImpressions()) {
      return Decision.DAILY_LIMIT_REACHED;
    }
    if (state.lastImpressionAt() != null
        && now.isBefore(state.lastImpressionAt().plus(campaign.minInterval()))) {
      return Decision.MIN_INTERVAL_NOT_ELAPSED;
    }
    return Decision.ELIGIBLE;
  }

  public enum Decision {
    ELIGIBLE,
    CAMPAIGN_NOT_ACTIVE,
    OUTSIDE_TIME_WINDOW,
    OPTED_OUT,
    TOTAL_LIMIT_REACHED,
    DAILY_LIMIT_REACHED,
    MIN_INTERVAL_NOT_ELAPSED
  }
}
