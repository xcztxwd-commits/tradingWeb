package com.fxplatform.engagement.admin.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignActionRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignContentRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignDetailResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignPreviewResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignResetResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSaveRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignStatsResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSummaryResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignUserResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.PopupPageKey;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignDetailRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignStatsRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignSummaryRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignUserRow;
import com.fxplatform.engagement.application.campaign.PopupAudienceService;
import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.application.campaign.PopupCampaignService.CampaignConfiguration;
import com.fxplatform.engagement.application.content.ContentRevisionService;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentCta;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentDraft;
import com.fxplatform.engagement.application.content.ContentRevisionService.SavedContentRevision;
import com.fxplatform.engagement.application.popup.PopupOutcomeService;
import com.fxplatform.engagement.domain.campaign.PopupCampaign;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.ContentKind;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.repository.ContentItemRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignTargetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CampaignAdminService {

  private static final int MAX_PAGE_SIZE = 100;
  private static final TypeReference<List<PopupPageKey>> PAGE_KEYS_TYPE =
      new TypeReference<>() { };

  private final PopupCampaignRepository campaignRepository;
  private final PopupCampaignTargetRepository targetRepository;
  private final ContentItemRepository contentItemRepository;
  private final ContentRevisionService contentRevisionService;
  private final PopupAudienceService audienceService;
  private final PopupCampaignService campaignService;
  private final PopupOutcomeService outcomeService;
  private final CampaignAdminQueryRepository queryRepository;
  private final EngagementAuditService auditService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public CampaignAdminService(
      PopupCampaignRepository campaignRepository,
      PopupCampaignTargetRepository targetRepository,
      ContentItemRepository contentItemRepository,
      ContentRevisionService contentRevisionService,
      PopupAudienceService audienceService,
      PopupCampaignService campaignService,
      PopupOutcomeService outcomeService,
      CampaignAdminQueryRepository queryRepository,
      EngagementAuditService auditService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.campaignRepository = Objects.requireNonNull(campaignRepository);
    this.targetRepository = Objects.requireNonNull(targetRepository);
    this.contentItemRepository = Objects.requireNonNull(contentItemRepository);
    this.contentRevisionService = Objects.requireNonNull(contentRevisionService);
    this.audienceService = Objects.requireNonNull(audienceService);
    this.campaignService = Objects.requireNonNull(campaignService);
    this.outcomeService = Objects.requireNonNull(outcomeService);
    this.queryRepository = Objects.requireNonNull(queryRepository);
    this.auditService = Objects.requireNonNull(auditService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.clock = Objects.requireNonNull(clock);
  }

  @Transactional(readOnly = true)
  public AdminPageResponse<CampaignSummaryResponse> campaigns(
      int page,
      int size,
      PopupCampaignLifecycleStatus lifecycleStatus,
      String nameQuery,
      AudienceType audienceType,
      Boolean syncToInbox,
      Instant effectiveFrom,
      Instant effectiveTo) {
    PageWindow window = pageWindow(page, size);
    if (effectiveFrom != null && effectiveTo != null
        && !effectiveFrom.isBefore(effectiveTo)) {
      throw invalid("Campaign effective window is invalid");
    }
    String normalizedName = normalizeFilter(nameQuery);
    String lifecycle = lifecycleStatus == null ? null : lifecycleStatus.name();
    String audience = audienceType == null ? null : audienceType.name();
    List<CampaignSummaryResponse> items = queryRepository.findCampaigns(
            lifecycle, normalizedName, audience, syncToInbox, effectiveFrom, effectiveTo,
            window.size(), window.offset()).stream()
        .map(CampaignAdminService::summary)
        .toList();
    long total = queryRepository.countCampaigns(
        lifecycle, normalizedName, audience, syncToInbox, effectiveFrom, effectiveTo);
    return page(items, window, total);
  }

  @Transactional(readOnly = true)
  public CampaignDetailResponse campaign(UUID campaignId) {
    return detail(queryRepository.findCampaign(requireId(campaignId))
        .orElseThrow(() -> notFound(campaignId)));
  }

  public CampaignDetailResponse create(
      UUID actorUserId,
      CampaignSaveRequest request) {
    UUID actor = Objects.requireNonNull(actorUserId, "actorUserId");
    PreparedRequest prepared = prepare(request);
    PopupCampaign draft = PopupCampaign.draft(
        UUID.randomUUID(),
        prepared.request().audienceType(),
        prepared.request().syncToInbox(),
        prepared.zoneId(),
        prepared.request().startAt(),
        prepared.request().endAt(),
        prepared.request().maxTotalImpressions(),
        prepared.request().maxDailyImpressions(),
        Duration.ofSeconds(prepared.request().minIntervalSeconds()));
    SavedContentRevision content = contentRevisionService.create(
        ContentKind.POPUP_CAMPAIGN, actor, contentDraft(prepared.request().content()));
    Instant now = clock.instant();
    PopupCampaignEntity entity = new PopupCampaignEntity();
    entity.setId(draft.id());
    entity.setName(prepared.name());
    entity.setContentItemId(content.contentItemId());
    entity.setLifecycleStatus(draft.lifecycleStatus());
    applyConfiguration(entity, prepared);
    entity.setCreatedBy(actor);
    entity.setUpdatedBy(actor);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    requireSingleWrite(campaignRepository.insert(entity), "create");
    if (prepared.request().audienceType() == AudienceType.SELECTED) {
      audienceService.replaceTargets(entity.getId(), prepared.targetUserIds());
    }

    int targetCount = prepared.request().audienceType() == AudienceType.SELECTED
        ? prepared.targetUserIds().size()
        : toAuditCount(queryRepository.countUsers(entity.getId()));
    auditService.record(
        actor,
        Action.CAMPAIGN_CREATE,
        TargetType.CAMPAIGN,
        entity.getId().toString(),
        metadata(content.revisionId(), entity.getAudienceType(), targetCount,
            null, safeSnapshot(entity), prepared.request().reason()));
    return campaign(entity.getId());
  }

  public CampaignDetailResponse update(
      UUID actorUserId,
      UUID campaignId,
      CampaignSaveRequest request) {
    UUID actor = Objects.requireNonNull(actorUserId, "actorUserId");
    UUID id = requireId(campaignId);
    PreparedRequest prepared = prepare(request);
    PopupCampaignEntity before = lockedCampaign(id);
    String beforeSnapshot = safeSnapshot(before);
    UUID beforeRevisionId = currentRevisionId(before);
    validatePublishedFreeze(before, prepared);

    PopupCampaignService.ConfiguredCampaign configured = campaignService.configure(
        id, configuration(prepared), actor);
    if (before.getFirstPublishedAt() == null) {
      replaceDraftTargets(id, prepared);
    }
    SavedContentRevision content = contentRevisionService.revise(
        configured.contentItemId(), actor, contentDraft(prepared.request().content()));
    PopupCampaignEntity after = lockedCampaign(id);
    int targetCount = toAuditCount(queryRepository.countUsers(id));
    auditService.record(
        actor,
        Action.CAMPAIGN_EDIT,
        TargetType.CAMPAIGN,
        id.toString(),
        metadata(content.revisionId(), after.getAudienceType(), targetCount,
            beforeSnapshot + ",revisionId=" + beforeRevisionId,
            safeSnapshot(after) + ",revisionId=" + content.revisionId(),
            prepared.request().reason()));
    return campaign(id);
  }

  public CampaignDetailResponse publish(
      UUID actorUserId,
      UUID campaignId,
      CampaignActionRequest request) {
    UUID id = requireId(campaignId);
    PopupCampaignEntity before = lockedCampaign(id);
    if (before.getAudienceType() == AudienceType.SELECTED) {
      int targetRows = targetRepository.countByCampaignId(id);
      int businessUsers = targetRepository.countBusinessUsersByCampaignId(id);
      if (targetRows == 0) {
        throw new BusinessException(
            "CAMPAIGN_TARGETS_REQUIRED", "SELECTED campaign requires at least one business user");
      }
      if (businessUsers != targetRows) {
        throw new BusinessException(
            "CAMPAIGN_TARGETS_INVALID", "SELECTED campaign contains a non-business user");
      }
    }
    return lifecycle(
        actorUserId,
        before,
        request,
        (ignored, actor) -> campaignService.publish(id, actor),
        null);
  }

  public CampaignDetailResponse pause(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    return lifecycle(actorUserId, lockedCampaign(requireId(campaignId)), request,
        (id, actor) -> campaignService.pause(id, actor), Action.CAMPAIGN_PAUSE);
  }

  public CampaignDetailResponse resume(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    return lifecycle(actorUserId, lockedCampaign(requireId(campaignId)), request,
        (id, actor) -> campaignService.resume(id, actor), Action.CAMPAIGN_RESUME);
  }

  public CampaignDetailResponse end(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    return lifecycle(actorUserId, lockedCampaign(requireId(campaignId)), request,
        (id, actor) -> campaignService.end(id, actor), Action.CAMPAIGN_END);
  }

  public CampaignDetailResponse delete(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    return lifecycle(actorUserId, lockedCampaign(requireId(campaignId)), request,
        (id, actor) -> campaignService.delete(id, actor), Action.CAMPAIGN_DELETE);
  }

  public CampaignDetailResponse restore(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    return lifecycle(actorUserId, lockedCampaign(requireId(campaignId)), request,
        (id, actor) -> campaignService.restore(id, actor), Action.CAMPAIGN_RESTORE);
  }

  public CampaignResetResponse resetDelivery(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    UUID actor = Objects.requireNonNull(actorUserId, "actorUserId");
    UUID id = requireId(campaignId);
    CampaignActionRequest action = requireAction(request);
    PopupCampaignEntity campaign = lockedCampaign(id);
    int affected = outcomeService.resetDeliveryCounters(id);
    auditService.record(
        actor,
        Action.CAMPAIGN_RESET_DELIVERY,
        TargetType.CAMPAIGN,
        id.toString(),
        metadata(currentRevisionId(campaign), campaign.getAudienceType(),
            toAuditCount(queryRepository.countUsers(id)), "DELIVERY_COUNTERS", "RESET",
            action.reason()));
    return new CampaignResetResponse(affected);
  }

  public CampaignPreviewResponse testPopup(
      UUID actorUserId, UUID campaignId, CampaignActionRequest request) {
    UUID actor = Objects.requireNonNull(actorUserId, "actorUserId");
    CampaignActionRequest action = requireAction(request);
    CampaignDetailResponse campaign = campaign(requireId(campaignId));
    auditService.record(
        actor,
        Action.CAMPAIGN_TEST_POPUP,
        TargetType.CAMPAIGN,
        campaign.id().toString(),
        metadata(campaign.revisionId(), campaign.audienceType(),
            toAuditCount(campaign.targetCount()), null, "PREVIEW", action.reason()));
    return new CampaignPreviewResponse(
        true,
        campaign.id(),
        campaign.revisionId(),
        campaign.templateSize(),
        campaign.title(),
        campaign.sanitizedHtml(),
        campaign.coverAssetId(),
        campaign.ctaLabel(),
        campaign.ctaRouteKey(),
        campaign.ctaParams());
  }

  @Transactional(readOnly = true)
  public CampaignStatsResponse stats(UUID campaignId) {
    CampaignStatsRow row = queryRepository.findStats(requireId(campaignId))
        .orElseThrow(() -> notFound(campaignId));
    return new CampaignStatsResponse(
        required(row.campaignId(), "campaignId"),
        number(row.targetCount()),
        number(row.usersWithState()),
        number(row.totalImpressions()),
        number(row.optedOutUsers()),
        number(row.clickedUsers()),
        number(row.issuedDeliveries()),
        number(row.shownDeliveries()),
        number(row.closedDeliveries()),
        number(row.clickedDeliveries()),
        number(row.invalidatedDeliveries()),
        number(row.expiredDeliveries()));
  }

  public AdminPageResponse<CampaignUserResponse> users(
      UUID actorUserId,
      UUID campaignId,
      int page,
      int size,
      String reason) {
    UUID actor = Objects.requireNonNull(actorUserId, "actorUserId");
    UUID id = requireId(campaignId);
    String auditReason = requireReason(reason);
    PageWindow window = pageWindow(page, size);
    CampaignDetailResponse campaign = campaign(id);
    List<CampaignUserResponse> items = queryRepository
        .findUsers(id, window.size(), window.offset()).stream()
        .map(CampaignAdminService::user)
        .toList();
    long total = queryRepository.countUsers(id);
    auditService.record(
        actor,
        Action.CAMPAIGN_USER_DETAIL_VIEW,
        TargetType.CAMPAIGN,
        id.toString(),
        metadata(campaign.revisionId(), campaign.audienceType(), toAuditCount(total),
            null, "PAGE=" + page, auditReason));
    return page(items, window, total);
  }

  private CampaignDetailResponse lifecycle(
      UUID actorUserId,
      PopupCampaignEntity before,
      CampaignActionRequest request,
      BiFunction<UUID, UUID, PopupCampaign> command,
      Action fixedAction) {
    UUID actor = Objects.requireNonNull(actorUserId, "actorUserId");
    CampaignActionRequest actionRequest = requireAction(request);
    UUID campaignId = required(before.getId(), "id");
    PopupCampaignLifecycleStatus beforeStatus = required(
        before.getLifecycleStatus(), "lifecycleStatus");
    AudienceType audienceType = required(before.getAudienceType(), "audienceType");
    UUID revisionId = currentRevisionId(before);
    int targetCount = toAuditCount(queryRepository.countUsers(campaignId));
    PopupCampaign updated = command.apply(campaignId, actor);
    Action action = fixedAction != null
        ? fixedAction
        : updated.lifecycleStatus() == PopupCampaignLifecycleStatus.SCHEDULED
            ? Action.CAMPAIGN_SCHEDULE
            : Action.CAMPAIGN_PUBLISH;
    auditService.record(
        actor,
        action,
        TargetType.CAMPAIGN,
        campaignId.toString(),
        metadata(revisionId, audienceType, targetCount,
            beforeStatus.name(), updated.lifecycleStatus().name(),
            actionRequest.reason()));
    return campaign(campaignId);
  }

  private void validatePublishedFreeze(PopupCampaignEntity campaign, PreparedRequest prepared) {
    if (campaign.getFirstPublishedAt() == null) {
      return;
    }
    CampaignSaveRequest request = prepared.request();
    if (campaign.getAudienceType() != request.audienceType()
        || campaign.getSyncToInbox() != request.syncToInbox()) {
      throw new BusinessException(
          "CAMPAIGN_AUDIENCE_FROZEN", "Published campaign audience is frozen");
    }
    if (!Objects.equals(campaign.getName(), prepared.name())
        || !Objects.equals(campaign.getStartAt(), request.startAt())
        || !Objects.equals(campaign.getTimeZone(), prepared.zoneId().getId())
        || campaign.getTemplateSize() != request.templateSize()) {
      throw new BusinessException(
          "CAMPAIGN_CONFIG_FROZEN", "Published campaign configuration is frozen");
    }
    Set<UUID> persisted = new LinkedHashSet<>(
        targetRepository.findUserIdsByCampaignId(campaign.getId()));
    if (!persisted.equals(prepared.targetUserIds())) {
      throw new BusinessException(
          "CAMPAIGN_AUDIENCE_FROZEN", "Published campaign target users are frozen");
    }
  }

  private void replaceDraftTargets(UUID campaignId, PreparedRequest prepared) {
    if (prepared.request().audienceType() == AudienceType.SELECTED) {
      audienceService.replaceTargets(campaignId, prepared.targetUserIds());
    } else {
      targetRepository.deleteByCampaignId(campaignId);
    }
  }

  private PreparedRequest prepare(CampaignSaveRequest request) {
    if (request == null) {
      throw invalid("Campaign request is required");
    }
    String name = request.name() == null ? "" : request.name().trim();
    if (name.isEmpty() || name.length() > 200) {
      throw invalid("Campaign name is invalid");
    }
    Objects.requireNonNull(request.audienceType(), "audienceType");
    Objects.requireNonNull(request.displayScope(), "displayScope");
    Objects.requireNonNull(request.deviceScope(), "deviceScope");
    Objects.requireNonNull(request.templateSize(), "templateSize");
    Objects.requireNonNull(request.startAt(), "startAt");
    Objects.requireNonNull(request.endAt(), "endAt");
    requireReason(request.reason());
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(request.timeZone());
    } catch (NullPointerException | ZoneRulesException ex) {
      throw new BusinessException("CAMPAIGN_CONFIG_INVALID", "Campaign timeZone is invalid", ex);
    }
    Set<UUID> targets = normalizeIds(request.targetUserIds());
    if (request.audienceType() == AudienceType.ALL && !targets.isEmpty()) {
      throw invalid("ALL campaign must not store target users");
    }
    List<PopupPageKey> pageKeys = normalizePageKeys(request.pageKeys());
    if (request.displayScope() == DisplayScope.SELECTED_PAGES && pageKeys.isEmpty()) {
      throw invalid("SELECTED_PAGES campaign requires page keys");
    }
    if (request.displayScope() == DisplayScope.ALL_BUSINESS_PAGES && !pageKeys.isEmpty()) {
      throw invalid("ALL_BUSINESS_PAGES campaign must not store page keys");
    }
    try {
      PopupCampaign.draft(
          UUID.randomUUID(), request.audienceType(), request.syncToInbox(), zoneId,
          request.startAt(), request.endAt(), request.maxTotalImpressions(),
          request.maxDailyImpressions(), Duration.ofSeconds(request.minIntervalSeconds()));
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("CAMPAIGN_CONFIG_INVALID", ex.getMessage(), ex);
    }
    if (request.content() == null) {
      throw invalid("Campaign content is required");
    }
    return new PreparedRequest(request, name, zoneId, targets, pageKeys, json(pageKeys));
  }

  private static Set<UUID> normalizeIds(Collection<UUID> ids) {
    if (ids == null) {
      return Set.of();
    }
    LinkedHashSet<UUID> result = new LinkedHashSet<>();
    for (UUID id : ids) {
      if (id == null) {
        throw invalid("Campaign target user id is required");
      }
      result.add(id);
    }
    return Set.copyOf(result);
  }

  private static List<PopupPageKey> normalizePageKeys(Collection<PopupPageKey> keys) {
    if (keys == null) {
      return List.of();
    }
    LinkedHashSet<PopupPageKey> unique = new LinkedHashSet<>();
    for (PopupPageKey key : keys) {
      unique.add(Objects.requireNonNull(key, "pageKey"));
    }
    ArrayList<PopupPageKey> sorted = new ArrayList<>(unique);
    sorted.sort(Comparator.comparing(Enum::name));
    return List.copyOf(sorted);
  }

  private String json(List<PopupPageKey> pageKeys) {
    try {
      return objectMapper.writeValueAsString(pageKeys);
    } catch (JsonProcessingException ex) {
      throw new BusinessException("CAMPAIGN_CONFIG_INVALID", "Page keys are invalid", ex);
    }
  }

  private List<PopupPageKey> pageKeys(String json) {
    try {
      return List.copyOf(objectMapper.readValue(required(json, "pageKeys"), PAGE_KEYS_TYPE));
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new BusinessException(
          "CAMPAIGN_DATA_INVALID", "Persisted campaign page keys are invalid", ex);
    }
  }

  private static ContentDraft contentDraft(CampaignContentRequest content) {
    CampaignContentRequest canonical = Objects.requireNonNull(content, "content");
    ContentCta cta = canonical.cta() == null ? null : new ContentCta(
        canonical.cta().label(), canonical.cta().routeKey(), canonical.cta().paramsJson());
    return new ContentDraft(
        canonical.title(), canonical.bodyDocument(), canonical.coverAssetId(), cta);
  }

  private static CampaignConfiguration configuration(PreparedRequest prepared) {
    CampaignSaveRequest request = prepared.request();
    return new CampaignConfiguration(
        prepared.name(),
        request.audienceType(),
        request.syncToInbox(),
        request.priority(),
        request.displayScope(),
        prepared.pageKeysJson(),
        request.deviceScope(),
        request.templateSize(),
        prepared.zoneId().getId(),
        request.startAt(),
        request.endAt(),
        request.maxTotalImpressions(),
        request.maxDailyImpressions(),
        request.minIntervalSeconds());
  }

  private static void applyConfiguration(PopupCampaignEntity entity, PreparedRequest prepared) {
    CampaignSaveRequest request = prepared.request();
    entity.setAudienceType(request.audienceType());
    entity.setSyncToInbox(request.syncToInbox());
    entity.setPriority(request.priority());
    entity.setDisplayScope(request.displayScope());
    entity.setPageKeys(prepared.pageKeysJson());
    entity.setDeviceScope(request.deviceScope());
    entity.setTemplateSize(request.templateSize());
    entity.setTimeZone(prepared.zoneId().getId());
    entity.setStartAt(request.startAt());
    entity.setEndAt(request.endAt());
    entity.setMaxTotalImpressions(request.maxTotalImpressions());
    entity.setMaxDailyImpressions(request.maxDailyImpressions());
    entity.setMinIntervalSeconds(request.minIntervalSeconds());
  }

  private static CampaignSummaryResponse summary(CampaignSummaryRow row) {
    return new CampaignSummaryResponse(
        required(row.id(), "id"),
        required(row.name(), "name"),
        required(row.lifecycleStatus(), "lifecycleStatus"),
        required(row.audienceType(), "audienceType"),
        required(row.syncToInbox(), "syncToInbox"),
        required(row.priority(), "priority"),
        required(row.startAt(), "startAt"),
        required(row.endAt(), "endAt"),
        row.firstPublishedAt(),
        required(row.revisionId(), "revisionId"),
        required(row.title(), "title"),
        number(row.targetCount()),
        required(row.updatedAt(), "updatedAt"));
  }

  private CampaignDetailResponse detail(CampaignDetailRow row) {
    List<UUID> targets = row.audienceType() == AudienceType.SELECTED
        ? List.copyOf(targetRepository.findUserIdsByCampaignId(row.id()))
        : List.of();
    return new CampaignDetailResponse(
        required(row.id(), "id"),
        required(row.name(), "name"),
        required(row.lifecycleStatus(), "lifecycleStatus"),
        required(row.audienceType(), "audienceType"),
        targets,
        required(row.syncToInbox(), "syncToInbox"),
        required(row.priority(), "priority"),
        required(row.displayScope(), "displayScope"),
        pageKeys(row.pageKeys()),
        required(row.deviceScope(), "deviceScope"),
        required(row.templateSize(), "templateSize"),
        required(row.timeZone(), "timeZone"),
        required(row.startAt(), "startAt"),
        required(row.endAt(), "endAt"),
        required(row.maxTotalImpressions(), "maxTotalImpressions"),
        required(row.maxDailyImpressions(), "maxDailyImpressions"),
        required(row.minIntervalSeconds(), "minIntervalSeconds"),
        row.firstPublishedAt(),
        row.lastPublishedAt(),
        row.pausedAt(),
        row.endedAt(),
        row.deletedAt(),
        required(row.contentItemId(), "contentItemId"),
        required(row.revisionId(), "revisionId"),
        required(row.revisionNo(), "revisionNo"),
        required(row.title(), "title"),
        required(row.bodyDocument(), "bodyDocument"),
        required(row.sanitizedHtml(), "sanitizedHtml"),
        row.coverAssetId(),
        row.ctaLabel(),
        row.ctaRouteKey(),
        row.ctaParams(),
        number(row.targetCount()),
        required(row.createdAt(), "createdAt"),
        required(row.updatedAt(), "updatedAt"));
  }

  private static CampaignUserResponse user(CampaignUserRow row) {
    return new CampaignUserResponse(
        required(row.userId(), "userId"),
        row.email(),
        required(row.status(), "status"),
        required(row.totalImpressions(), "totalImpressions"),
        row.dailyBucket(),
        required(row.dailyImpressions(), "dailyImpressions"),
        row.lastImpressionAt(),
        row.optedOutAt(),
        row.lastClickedAt(),
        number(row.issuedDeliveries()),
        number(row.shownDeliveries()),
        number(row.closedDeliveries()),
        number(row.clickedDeliveries()),
        number(row.invalidatedDeliveries()),
        number(row.expiredDeliveries()));
  }

  private PopupCampaignEntity lockedCampaign(UUID campaignId) {
    PopupCampaignEntity campaign = campaignRepository.selectByIdForUpdate(campaignId);
    if (campaign == null) {
      throw notFound(campaignId);
    }
    return campaign;
  }

  private UUID currentRevisionId(PopupCampaignEntity campaign) {
    ContentItemEntity item = contentItemRepository.selectById(
        required(campaign.getContentItemId(), "contentItemId"));
    if (item == null || item.getCurrentRevisionId() == null) {
      throw new BusinessException("CAMPAIGN_DATA_INVALID", "Campaign content is invalid");
    }
    return item.getCurrentRevisionId();
  }

  private static CampaignActionRequest requireAction(CampaignActionRequest request) {
    if (request == null) {
      throw invalid("Campaign action is required");
    }
    requireReason(request.reason());
    return request;
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
      throw invalid("Campaign audit reason is required");
    }
    return reason.trim();
  }

  private static String normalizeFilter(String filter) {
    if (filter == null || filter.isBlank()) {
      return null;
    }
    String normalized = filter.trim();
    if (normalized.length() > 200) {
      throw invalid("Campaign name filter is too long");
    }
    return normalized;
  }

  private static PageWindow pageWindow(int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw invalid("Campaign pagination is invalid");
    }
    return new PageWindow(page, size, Math.multiplyExact((long) page, size));
  }

  private static <T> AdminPageResponse<T> page(
      List<T> items, PageWindow window, long total) {
    int pages = total == 0 ? 0 : Math.toIntExact((total + window.size() - 1) / window.size());
    return new AdminPageResponse<>(items, window.page(), window.size(), total, pages);
  }

  private static Metadata metadata(
      UUID revisionId,
      AudienceType audienceType,
      int targetCount,
      String before,
      String after,
      String reason) {
    return new Metadata(revisionId, audienceType, targetCount, before, after, reason);
  }

  private static String safeSnapshot(PopupCampaignEntity campaign) {
    return "status=" + campaign.getLifecycleStatus()
        + ",audience=" + campaign.getAudienceType()
        + ",sync=" + campaign.getSyncToInbox()
        + ",priority=" + campaign.getPriority()
        + ",display=" + campaign.getDisplayScope()
        + ",device=" + campaign.getDeviceScope()
        + ",template=" + campaign.getTemplateSize()
        + ",timeZone=" + campaign.getTimeZone()
        + ",startAt=" + campaign.getStartAt()
        + ",endAt=" + campaign.getEndAt()
        + ",total=" + campaign.getMaxTotalImpressions()
        + ",daily=" + campaign.getMaxDailyImpressions()
        + ",interval=" + campaign.getMinIntervalSeconds();
  }

  private static void requireSingleWrite(int rows, String operation) {
    if (rows != 1) {
      throw new BusinessException(
          "CAMPAIGN_WRITE_CONFLICT", "Campaign " + operation + " did not affect one row");
    }
  }

  private static UUID requireId(UUID campaignId) {
    return Objects.requireNonNull(campaignId, "campaignId");
  }

  private static long number(Long value) {
    return required(value, "aggregate");
  }

  private static int toAuditCount(long value) {
    return Math.toIntExact(value);
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("CAMPAIGN_CONFIG_INVALID", message);
  }

  private static BusinessException notFound(UUID campaignId) {
    return new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + campaignId);
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new BusinessException("CAMPAIGN_DATA_INVALID", "Campaign " + field + " is invalid");
    }
    return value;
  }

  private record PreparedRequest(
      CampaignSaveRequest request,
      String name,
      ZoneId zoneId,
      Set<UUID> targetUserIds,
      List<PopupPageKey> pageKeys,
      String pageKeysJson
  ) {
  }

  private record PageWindow(int page, int size, long offset) {
  }
}
