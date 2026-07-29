package com.fxplatform.engagement.domain.campaign;

import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.CAMPAIGN_NOT_ACTIVE;
import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.DAILY_LIMIT_REACHED;
import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.ELIGIBLE;
import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.MIN_INTERVAL_NOT_ELAPSED;
import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.OPTED_OUT;
import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.OUTSIDE_TIME_WINDOW;
import static com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy.Decision.TOTAL_LIMIT_REACHED;
import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PopupEligibilityPolicyTest {

  @Test
  void resetsTheDailyCountAtShanghaiMidnight() {
    Instant beforeMidnight = Instant.parse("2026-07-19T15:59:00Z");
    PopupCampaign campaign = activeCampaign(
        beforeMidnight, ZoneId.of("Asia/Shanghai"), 3, 1, Duration.ZERO);
    PopupImpressionState reachedToday = new PopupImpressionState(
        1, LocalDate.of(2026, 7, 19), 1, beforeMidnight.minusSeconds(60), null);

    assertThat(policyAt(beforeMidnight).evaluate(campaign, reachedToday))
        .isEqualTo(DAILY_LIMIT_REACHED);
    assertThat(policyAt(Instant.parse("2026-07-19T16:00:00Z")).evaluate(campaign, reachedToday))
        .isEqualTo(ELIGIBLE);
  }

  @Test
  void aNewNaturalDayDoesNotBypassTheMinimumInterval() {
    Instant afterMidnight = Instant.parse("2026-07-19T16:00:00Z");
    PopupCampaign campaign = activeCampaign(
        afterMidnight, ZoneId.of("Asia/Shanghai"), 3, 1, Duration.ofHours(4));
    PopupImpressionState previousDay = new PopupImpressionState(
        1,
        LocalDate.of(2026, 7, 19),
        1,
        afterMidnight.minusSeconds(60),
        null);

    assertThat(policyAt(afterMidnight).evaluate(campaign, previousDay))
        .isEqualTo(MIN_INTERVAL_NOT_ELAPSED);
  }

  @Test
  void usesZoneIdRulesAcrossADstTransition() {
    ZoneId newYork = ZoneId.of("America/New_York");
    Instant beforeSpringForward = Instant.parse("2026-03-08T06:59:00Z");
    Instant afterSpringForward = Instant.parse("2026-03-08T07:00:00Z");
    PopupCampaign campaign = activeCampaign(
        beforeSpringForward, newYork, 3, 1, Duration.ZERO);
    PopupImpressionState reachedSameLocalDay = new PopupImpressionState(
        1, LocalDate.of(2026, 3, 8), 1, beforeSpringForward.minusSeconds(60), null);

    assertThat(policyAt(afterSpringForward).evaluate(campaign, reachedSameLocalDay))
        .isEqualTo(DAILY_LIMIT_REACHED);
  }

  @Test
  void enforcesTotalCountOptOutAndMinimumIntervalIndependently() {
    Instant now = Instant.parse("2026-07-19T04:00:00Z");
    PopupCampaign campaign = activeCampaign(
        now, ZoneId.of("Asia/Shanghai"), 3, 2, Duration.ofHours(4));

    assertThat(policyAt(now).evaluate(campaign, new PopupImpressionState(
        3, LocalDate.of(2026, 7, 19), 1, now.minus(Duration.ofDays(1)), null)))
        .isEqualTo(TOTAL_LIMIT_REACHED);
    assertThat(policyAt(now).evaluate(campaign, new PopupImpressionState(
        1, LocalDate.of(2026, 7, 19), 1, now.minus(Duration.ofDays(1)), now.minusSeconds(1))))
        .isEqualTo(OPTED_OUT);
    assertThat(policyAt(now).evaluate(campaign, new PopupImpressionState(
        1, LocalDate.of(2026, 7, 19), 1, now.minus(Duration.ofHours(3)), null)))
        .isEqualTo(MIN_INTERVAL_NOT_ELAPSED);
    assertThat(policyAt(now).evaluate(campaign, new PopupImpressionState(
        1, LocalDate.of(2026, 7, 19), 1, now.minus(Duration.ofHours(4)), null)))
        .isEqualTo(ELIGIBLE);
  }

  @Test
  void neverShowsBeforeStartAtEndOrWhilePausedOrEnded() {
    Instant now = Instant.parse("2026-07-19T04:00:00Z");
    PopupCampaign future = draft(
        ZoneId.of("Asia/Shanghai"), now.plusSeconds(1), now.plusSeconds(600), 3, 1, Duration.ZERO);
    PopupCampaign scheduled = future.publishAt(now);

    assertThat(policyAt(now).evaluate(scheduled, PopupImpressionState.empty()))
        .isEqualTo(CAMPAIGN_NOT_ACTIVE);

    PopupCampaign active = activeCampaign(
        now, ZoneId.of("Asia/Shanghai"), 3, 1, Duration.ZERO);
    PopupCampaign paused = active.pauseAt(now.plusSeconds(1));
    PopupCampaign ended = active.endAt(now.plusSeconds(2));

    assertThat(policyAt(now.plusSeconds(1)).evaluate(paused, PopupImpressionState.empty()))
        .isEqualTo(CAMPAIGN_NOT_ACTIVE);
    assertThat(policyAt(now.plusSeconds(2)).evaluate(ended, PopupImpressionState.empty()))
        .isEqualTo(CAMPAIGN_NOT_ACTIVE);
    assertThat(policyAt(active.endAt()).evaluate(active, PopupImpressionState.empty()))
        .isEqualTo(OUTSIDE_TIME_WINDOW);
  }

  private static PopupEligibilityPolicy policyAt(Instant instant) {
    return new PopupEligibilityPolicy(fixed(instant));
  }

  private static PopupCampaign activeCampaign(
      Instant now,
      ZoneId timeZone,
      int maxTotal,
      int maxDaily,
      Duration minInterval) {
    PopupCampaign draft = draft(
        timeZone,
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(2)),
        maxTotal,
        maxDaily,
        minInterval);
    return draft.publishAt(now);
  }

  private static PopupCampaign draft(
      ZoneId timeZone,
      Instant startAt,
      Instant endAt,
      int maxTotal,
      int maxDaily,
      Duration minInterval) {
    return PopupCampaign.draft(
        UUID.randomUUID(), AudienceType.ALL, false, timeZone, startAt, endAt,
        maxTotal, maxDaily, minInterval);
  }

  private static Clock fixed(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
