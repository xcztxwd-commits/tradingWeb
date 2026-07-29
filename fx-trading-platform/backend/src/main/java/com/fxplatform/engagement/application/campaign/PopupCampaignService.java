package com.fxplatform.engagement.application.campaign;

import com.fxplatform.engagement.application.message.CampaignMessageSyncService;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.AggregateType;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.UpdateType;
import com.fxplatform.engagement.domain.campaign.PopupCampaign;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The transactional command/query seam for popup campaign lifecycle state. */
@Service
@Transactional
public class PopupCampaignService {

  private final PopupCampaignRepository repository;
  private final Clock clock;
  private final CampaignMessageSyncService messageSyncService;
  private final EngagementOutboxService outboxService;

  public PopupCampaignService(
      PopupCampaignRepository repository,
      Clock clock,
      CampaignMessageSyncService messageSyncService,
      EngagementOutboxService outboxService) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.messageSyncService = Objects.requireNonNull(messageSyncService, "messageSyncService");
    this.outboxService = Objects.requireNonNull(outboxService, "outboxService");
  }

  @Transactional(readOnly = true)
  public PopupCampaign get(UUID campaignId) {
    return hydrate(repository.selectById(requireId(campaignId)));
  }

  public PopupCampaign publish(UUID campaignId, UUID actorId) {
    return mutate(campaignId, actorId, PopupCampaign::publishAt, UpdateType.CAMPAIGN_UPDATED);
  }

  /** Applies scheduled lifecycle transitions under ordered database locks. */
  public int activateDue() {
    Instant now = clock.instant();
    List<PopupCampaignEntity> due = repository.findDueForUpdate(now);
    for (PopupCampaignEntity entity : due) {
      PopupCampaign current = hydrate(entity);
      PopupCampaign updated = current.activateScheduledAt(now);
      if (updated == current) {
        throw new IllegalStateException("Due campaign query returned a non-due campaign");
      }
      apply(updated, entity);
      entity.setUpdatedAt(now);
      if (repository.updateById(entity) != 1) {
        throw new IllegalStateException("Popup campaign write conflict: " + current.id());
      }
      messageSyncService.synchronize(
          entity,
          required(entity.getUpdatedBy(), "updatedBy"),
          now);
      append(UpdateType.CAMPAIGN_UPDATED, entity, now);
    }
    return due.size();
  }

  public PopupCampaign pause(UUID campaignId, UUID actorId) {
    return mutate(campaignId, actorId, PopupCampaign::pauseAt, UpdateType.CAMPAIGN_INVALIDATED);
  }

  public PopupCampaign resume(UUID campaignId, UUID actorId) {
    return mutate(campaignId, actorId, PopupCampaign::resumeAt, UpdateType.CAMPAIGN_UPDATED);
  }

  public PopupCampaign end(UUID campaignId, UUID actorId) {
    return mutate(campaignId, actorId, PopupCampaign::endAt, UpdateType.CAMPAIGN_INVALIDATED);
  }

  public PopupCampaign delete(UUID campaignId, UUID actorId) {
    return mutate(campaignId, actorId, PopupCampaign::deleteAt, UpdateType.CAMPAIGN_INVALIDATED);
  }

  public PopupCampaign restore(UUID campaignId, UUID actorId) {
    return mutate(campaignId, actorId, PopupCampaign::restoreAt, UpdateType.CAMPAIGN_UPDATED);
  }

  public PopupCampaign reconfigure(
      UUID campaignId,
      AudienceType audienceType,
      boolean syncToInbox,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      Duration minInterval,
      UUID actorId) {
    return mutate(
        campaignId,
        actorId,
        (campaign, ignored) -> campaign.reconfigure(
            audienceType,
            syncToInbox,
            endAt,
            maxTotalImpressions,
            maxDailyImpressions,
            minInterval),
        UpdateType.CAMPAIGN_UPDATED);
  }

  /** Updates the complete editable campaign configuration under the canonical campaign lock. */
  public ConfiguredCampaign configure(
      UUID campaignId,
      CampaignConfiguration configuration,
      UUID actorId) {
    UUID canonicalId = requireId(campaignId);
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    CampaignConfiguration config = Objects.requireNonNull(configuration, "configuration");
    PopupCampaignEntity entity = repository.selectByIdForUpdate(canonicalId);
    PopupCampaign current = hydrate(entity);
    validateFrozenConfiguration(current, entity, config);

    PopupCampaign updated = PopupCampaign.rehydrate(
        current.id(),
        current.lifecycleStatus(),
        required(config.audienceType(), "audienceType"),
        config.syncToInbox(),
        ZoneId.of(required(config.timeZone(), "timeZone")),
        required(config.startAt(), "startAt"),
        required(config.endAt(), "endAt"),
        config.maxTotalImpressions(),
        config.maxDailyImpressions(),
        Duration.ofSeconds(config.minIntervalSeconds()),
        current.firstPublishedAt(),
        current.lastPublishedAt(),
        current.pausedAt(),
        current.endedAt(),
        current.deletedAt());

    Instant now = clock.instant();
    apply(updated, entity);
    entity.setName(requireName(config.name()));
    entity.setPriority(config.priority());
    entity.setDisplayScope(required(config.displayScope(), "displayScope"));
    entity.setPageKeys(required(config.pageKeysJson(), "pageKeysJson"));
    entity.setDeviceScope(required(config.deviceScope(), "deviceScope"));
    entity.setTemplateSize(required(config.templateSize(), "templateSize"));
    entity.setUpdatedBy(actor);
    entity.setUpdatedAt(now);
    if (repository.updateById(entity) != 1) {
      throw new IllegalStateException("Popup campaign write conflict: " + canonicalId);
    }
    messageSyncService.synchronize(entity, actor, now);
    append(UpdateType.CAMPAIGN_UPDATED, entity, now);
    return new ConfiguredCampaign(updated, required(entity.getContentItemId(), "contentItemId"));
  }

  private PopupCampaign mutate(
      UUID campaignId,
      UUID actorId,
      BiFunction<PopupCampaign, Instant, PopupCampaign> command,
      UpdateType updateType) {
    UUID canonicalId = requireId(campaignId);
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    PopupCampaignEntity entity = repository.selectByIdForUpdate(canonicalId);
    PopupCampaign current = hydrate(entity);
    Instant now = clock.instant();
    PopupCampaign updated = command.apply(current, now);
    boolean changed = updated != current;
    if (changed) {
      apply(updated, entity);
      entity.setUpdatedBy(actor);
      entity.setUpdatedAt(now);
      if (repository.updateById(entity) != 1) {
        throw new IllegalStateException("Popup campaign write conflict: " + canonicalId);
      }
    }
    messageSyncService.synchronize(entity, actor, now);
    if (changed) {
      append(updateType, entity, now);
    }
    return updated;
  }

  private void append(UpdateType updateType, PopupCampaignEntity entity, Instant now) {
    outboxService.append(
        updateType,
        AggregateType.POPUP_CAMPAIGN,
        required(entity.getId(), "id"),
        required(entity.getAudienceType(), "audienceType"),
        now);
  }

  private static PopupCampaign hydrate(PopupCampaignEntity entity) {
    if (entity == null) {
      throw new NoSuchElementException("Popup campaign not found");
    }
    return PopupCampaign.rehydrate(
        required(entity.getId(), "id"),
        required(entity.getLifecycleStatus(), "lifecycleStatus"),
        required(entity.getAudienceType(), "audienceType"),
        required(entity.getSyncToInbox(), "syncToInbox"),
        ZoneId.of(required(entity.getTimeZone(), "timeZone")),
        required(entity.getStartAt(), "startAt"),
        required(entity.getEndAt(), "endAt"),
        required(entity.getMaxTotalImpressions(), "maxTotalImpressions"),
        required(entity.getMaxDailyImpressions(), "maxDailyImpressions"),
        Duration.ofSeconds(required(entity.getMinIntervalSeconds(), "minIntervalSeconds")),
        entity.getFirstPublishedAt(),
        entity.getLastPublishedAt(),
        entity.getPausedAt(),
        entity.getEndedAt(),
        entity.getDeletedAt());
  }

  private static void apply(PopupCampaign campaign, PopupCampaignEntity entity) {
    entity.setLifecycleStatus(campaign.lifecycleStatus());
    entity.setAudienceType(campaign.audienceType());
    entity.setSyncToInbox(campaign.syncToInbox());
    entity.setTimeZone(campaign.timeZone().getId());
    entity.setStartAt(campaign.startAt());
    entity.setEndAt(campaign.endAt());
    entity.setMaxTotalImpressions(campaign.maxTotalImpressions());
    entity.setMaxDailyImpressions(campaign.maxDailyImpressions());
    entity.setMinIntervalSeconds(Math.toIntExact(campaign.minInterval().getSeconds()));
    entity.setFirstPublishedAt(campaign.firstPublishedAt());
    entity.setLastPublishedAt(campaign.lastPublishedAt());
    entity.setPausedAt(campaign.pausedAt());
    entity.setEndedAt(campaign.endedAt());
    entity.setDeletedAt(campaign.deletedAt());
  }

  private static void validateFrozenConfiguration(
      PopupCampaign current,
      PopupCampaignEntity entity,
      CampaignConfiguration config) {
    if (current.firstPublishedAt() == null) {
      return;
    }
    boolean frozenChanged = current.audienceType() != config.audienceType()
        || current.syncToInbox() != config.syncToInbox()
        || !current.startAt().equals(config.startAt())
        || !current.timeZone().equals(ZoneId.of(required(config.timeZone(), "timeZone")))
        || !Objects.equals(entity.getName(), requireName(config.name()))
        || entity.getTemplateSize() != config.templateSize();
    if (frozenChanged) {
      throw new IllegalStateException(
          "Published campaign name, audience, sync, start, timeZone and template are frozen");
    }
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank() || name.trim().length() > 200) {
      throw new IllegalArgumentException("Campaign name is invalid");
    }
    return name.trim();
  }

  private static UUID requireId(UUID id) {
    return Objects.requireNonNull(id, "campaignId");
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalStateException("Popup campaign " + field + " is null");
    }
    return value;
  }

  public record CampaignConfiguration(
      String name,
      AudienceType audienceType,
      boolean syncToInbox,
      int priority,
      DisplayScope displayScope,
      String pageKeysJson,
      DeviceScope deviceScope,
      TemplateSize templateSize,
      String timeZone,
      Instant startAt,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      int minIntervalSeconds
  ) {
  }

  public record ConfiguredCampaign(PopupCampaign campaign, UUID contentItemId) {
  }
}
