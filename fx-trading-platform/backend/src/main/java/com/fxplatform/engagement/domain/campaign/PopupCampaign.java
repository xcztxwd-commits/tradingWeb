package com.fxplatform.engagement.domain.campaign;

import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** The single domain model that owns popup campaign lifecycle invariants. */
public final class PopupCampaign {

  private final UUID id;
  private final PopupCampaignLifecycleStatus lifecycleStatus;
  private final AudienceType audienceType;
  private final boolean syncToInbox;
  private final ZoneId timeZone;
  private final Instant startAt;
  private final Instant endAt;
  private final int maxTotalImpressions;
  private final int maxDailyImpressions;
  private final Duration minInterval;
  private final Instant firstPublishedAt;
  private final Instant lastPublishedAt;
  private final Instant pausedAt;
  private final Instant endedAt;
  private final Instant deletedAt;

  private PopupCampaign(
      UUID id,
      PopupCampaignLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      boolean syncToInbox,
      ZoneId timeZone,
      Instant startAt,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      Duration minInterval,
      Instant firstPublishedAt,
      Instant lastPublishedAt,
      Instant pausedAt,
      Instant endedAt,
      Instant deletedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.lifecycleStatus = Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
    this.audienceType = Objects.requireNonNull(audienceType, "audienceType");
    this.syncToInbox = syncToInbox;
    this.timeZone = Objects.requireNonNull(timeZone, "timeZone");
    this.startAt = Objects.requireNonNull(startAt, "startAt");
    this.endAt = Objects.requireNonNull(endAt, "endAt");
    this.minInterval = Objects.requireNonNull(minInterval, "minInterval");
    validateWindowAndFrequency(
        startAt, endAt, maxTotalImpressions, maxDailyImpressions, minInterval);
    this.maxTotalImpressions = maxTotalImpressions;
    this.maxDailyImpressions = maxDailyImpressions;
    this.firstPublishedAt = firstPublishedAt;
    this.lastPublishedAt = lastPublishedAt;
    this.pausedAt = pausedAt;
    this.endedAt = endedAt;
    this.deletedAt = deletedAt;
    validatePersistedState(
        lifecycleStatus, firstPublishedAt, lastPublishedAt, deletedAt);
  }

  public static PopupCampaign draft(
      UUID id,
      AudienceType audienceType,
      boolean syncToInbox,
      ZoneId timeZone,
      Instant startAt,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      Duration minInterval) {
    return new PopupCampaign(
        id,
        PopupCampaignLifecycleStatus.DRAFT,
        audienceType,
        syncToInbox,
        timeZone,
        startAt,
        endAt,
        maxTotalImpressions,
        maxDailyImpressions,
        minInterval,
        null,
        null,
        null,
        null,
        null);
  }

  public static PopupCampaign rehydrate(
      UUID id,
      PopupCampaignLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      boolean syncToInbox,
      ZoneId timeZone,
      Instant startAt,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      Duration minInterval,
      Instant firstPublishedAt,
      Instant lastPublishedAt,
      Instant pausedAt,
      Instant endedAt,
      Instant deletedAt) {
    return new PopupCampaign(
        id,
        lifecycleStatus,
        audienceType,
        syncToInbox,
        timeZone,
        startAt,
        endAt,
        maxTotalImpressions,
        maxDailyImpressions,
        minInterval,
        firstPublishedAt,
        lastPublishedAt,
        pausedAt,
        endedAt,
        deletedAt);
  }

  public PopupCampaign reconfigure(
      AudienceType newAudienceType,
      boolean newSyncToInbox,
      Instant newEndAt,
      int newMaxTotalImpressions,
      int newMaxDailyImpressions,
      Duration newMinInterval) {
    Objects.requireNonNull(newAudienceType, "newAudienceType");
    if (firstPublishedAt != null
        && (newAudienceType != audienceType || newSyncToInbox != syncToInbox)) {
      throw new IllegalStateException("Published campaign audience and syncToInbox are frozen");
    }
    if (newAudienceType == audienceType
        && newSyncToInbox == syncToInbox
        && Objects.equals(newEndAt, endAt)
        && newMaxTotalImpressions == maxTotalImpressions
        && newMaxDailyImpressions == maxDailyImpressions
        && Objects.equals(newMinInterval, minInterval)) {
      return this;
    }
    return new PopupCampaign(
        id,
        lifecycleStatus,
        newAudienceType,
        newSyncToInbox,
        timeZone,
        startAt,
        newEndAt,
        newMaxTotalImpressions,
        newMaxDailyImpressions,
        newMinInterval,
        firstPublishedAt,
        lastPublishedAt,
        pausedAt,
        endedAt,
        deletedAt);
  }

  public PopupCampaign publishAt(Instant now) {
    Objects.requireNonNull(now, "now");
    if (lifecycleStatus == PopupCampaignLifecycleStatus.ACTIVE
        || lifecycleStatus == PopupCampaignLifecycleStatus.SCHEDULED) {
      return this;
    }
    if (lifecycleStatus != PopupCampaignLifecycleStatus.DRAFT
        && lifecycleStatus != PopupCampaignLifecycleStatus.PAUSED) {
      throw invalidTransition("publish");
    }
    if (!now.isBefore(endAt)) {
      throw new IllegalStateException("Expired campaign cannot be published");
    }
    PopupCampaignLifecycleStatus publishedStatus = now.isBefore(startAt)
        ? PopupCampaignLifecycleStatus.SCHEDULED
        : PopupCampaignLifecycleStatus.ACTIVE;
    Instant firstPublication = firstPublishedAt == null ? now : firstPublishedAt;
    return copy(
        publishedStatus,
        audienceType,
        syncToInbox,
        firstPublication,
        now,
        null,
        null,
        null);
  }

  /** Activates scheduled work at its inclusive due boundary without bypassing lifecycle rules. */
  public PopupCampaign activateScheduledAt(Instant now) {
    Objects.requireNonNull(now, "now");
    requireStatus(PopupCampaignLifecycleStatus.SCHEDULED, "activate scheduled");
    if (now.isBefore(startAt)) {
      return this;
    }
    if (!now.isBefore(endAt)) {
      return endAt(now);
    }
    return copy(
        PopupCampaignLifecycleStatus.ACTIVE,
        audienceType,
        syncToInbox,
        firstPublishedAt,
        lastPublishedAt,
        null,
        null,
        null);
  }

  public PopupCampaign pauseAt(Instant now) {
    Objects.requireNonNull(now, "now");
    if (lifecycleStatus == PopupCampaignLifecycleStatus.PAUSED) {
      return this;
    }
    if (lifecycleStatus != PopupCampaignLifecycleStatus.ACTIVE
        && lifecycleStatus != PopupCampaignLifecycleStatus.SCHEDULED) {
      throw invalidTransition("pause");
    }
    return copy(
        PopupCampaignLifecycleStatus.PAUSED,
        audienceType,
        syncToInbox,
        firstPublishedAt,
        lastPublishedAt,
        now,
        null,
        null);
  }

  public PopupCampaign resumeAt(Instant now) {
    Objects.requireNonNull(now, "now");
    requireStatus(PopupCampaignLifecycleStatus.PAUSED, "resume");
    if (firstPublishedAt == null) {
      throw new IllegalStateException("Unpublished campaign must be published before it can resume");
    }
    if (!now.isBefore(endAt)) {
      return endAt(now);
    }
    PopupCampaignLifecycleStatus resumedStatus = now.isBefore(startAt)
        ? PopupCampaignLifecycleStatus.SCHEDULED
        : PopupCampaignLifecycleStatus.ACTIVE;
    return copy(
        resumedStatus,
        audienceType,
        syncToInbox,
        firstPublishedAt,
        lastPublishedAt,
        null,
        null,
        null);
  }

  public PopupCampaign endAt(Instant now) {
    Objects.requireNonNull(now, "now");
    if (lifecycleStatus == PopupCampaignLifecycleStatus.ENDED) {
      return this;
    }
    if (lifecycleStatus == PopupCampaignLifecycleStatus.DELETED) {
      throw invalidTransition("end");
    }
    return copy(
        PopupCampaignLifecycleStatus.ENDED,
        audienceType,
        syncToInbox,
        firstPublishedAt,
        lastPublishedAt,
        null,
        now,
        null);
  }

  public PopupCampaign deleteAt(Instant now) {
    Objects.requireNonNull(now, "now");
    if (lifecycleStatus == PopupCampaignLifecycleStatus.DELETED) {
      return this;
    }
    return copy(
        PopupCampaignLifecycleStatus.DELETED,
        audienceType,
        syncToInbox,
        firstPublishedAt,
        lastPublishedAt,
        pausedAt,
        endedAt,
        now);
  }

  public PopupCampaign restoreAt(Instant now) {
    Objects.requireNonNull(now, "now");
    requireStatus(PopupCampaignLifecycleStatus.DELETED, "restore");
    return copy(
        PopupCampaignLifecycleStatus.PAUSED,
        audienceType,
        syncToInbox,
        firstPublishedAt,
        lastPublishedAt,
        now,
        null,
        null);
  }

  public UUID id() {
    return id;
  }

  public PopupCampaignLifecycleStatus lifecycleStatus() {
    return lifecycleStatus;
  }

  public AudienceType audienceType() {
    return audienceType;
  }

  public boolean syncToInbox() {
    return syncToInbox;
  }

  public ZoneId timeZone() {
    return timeZone;
  }

  public Instant startAt() {
    return startAt;
  }

  public Instant endAt() {
    return endAt;
  }

  public int maxTotalImpressions() {
    return maxTotalImpressions;
  }

  public int maxDailyImpressions() {
    return maxDailyImpressions;
  }

  public Duration minInterval() {
    return minInterval;
  }

  public Instant firstPublishedAt() {
    return firstPublishedAt;
  }

  public Instant lastPublishedAt() {
    return lastPublishedAt;
  }

  public Instant pausedAt() {
    return pausedAt;
  }

  public Instant endedAt() {
    return endedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }

  private PopupCampaign copy(
      PopupCampaignLifecycleStatus newStatus,
      AudienceType newAudienceType,
      boolean newSyncToInbox,
      Instant newFirstPublishedAt,
      Instant newLastPublishedAt,
      Instant newPausedAt,
      Instant newEndedAt,
      Instant newDeletedAt) {
    return new PopupCampaign(
        id,
        newStatus,
        newAudienceType,
        newSyncToInbox,
        timeZone,
        startAt,
        endAt,
        maxTotalImpressions,
        maxDailyImpressions,
        minInterval,
        newFirstPublishedAt,
        newLastPublishedAt,
        newPausedAt,
        newEndedAt,
        newDeletedAt);
  }

  private void requireStatus(PopupCampaignLifecycleStatus required, String action) {
    if (lifecycleStatus != required) {
      throw invalidTransition(action);
    }
  }

  private IllegalStateException invalidTransition(String action) {
    return new IllegalStateException("Cannot " + action + " campaign in " + lifecycleStatus);
  }

  private static void validateWindowAndFrequency(
      Instant startAt,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      Duration minInterval) {
    if (!endAt.isAfter(startAt)) {
      throw new IllegalArgumentException("endAt must be after startAt");
    }
    if (maxTotalImpressions <= 0) {
      throw new IllegalArgumentException("maxTotalImpressions must be positive");
    }
    if (maxDailyImpressions <= 0 || maxDailyImpressions > maxTotalImpressions) {
      throw new IllegalArgumentException(
          "maxDailyImpressions must be positive and no greater than maxTotalImpressions");
    }
    if (minInterval.isNegative()) {
      throw new IllegalArgumentException("minInterval must not be negative");
    }
    if (minInterval.getNano() != 0 || minInterval.getSeconds() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("minInterval must be a whole number of supported seconds");
    }
  }

  private static void validatePersistedState(
      PopupCampaignLifecycleStatus lifecycleStatus,
      Instant firstPublishedAt,
      Instant lastPublishedAt,
      Instant deletedAt) {
    if ((firstPublishedAt == null) != (lastPublishedAt == null)) {
      throw new IllegalArgumentException("Publication timestamps must both be null or both be present");
    }
    if (firstPublishedAt != null && lastPublishedAt.isBefore(firstPublishedAt)) {
      throw new IllegalArgumentException("lastPublishedAt must not be before firstPublishedAt");
    }
    if ((lifecycleStatus == PopupCampaignLifecycleStatus.ACTIVE
            || lifecycleStatus == PopupCampaignLifecycleStatus.SCHEDULED)
        && firstPublishedAt == null) {
      throw new IllegalArgumentException("Active or scheduled campaign must have publication timestamps");
    }
    if ((lifecycleStatus == PopupCampaignLifecycleStatus.DELETED) != (deletedAt != null)) {
      throw new IllegalArgumentException("Deleted campaign state and timestamp must agree");
    }
  }
}
