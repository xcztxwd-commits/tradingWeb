package com.fxplatform.engagement.application.popup;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.engagement.domain.campaign.PopupCampaign;
import com.fxplatform.engagement.domain.campaign.PopupEligibilityPolicy;
import com.fxplatform.engagement.domain.campaign.PopupImpressionState;
import com.fxplatform.engagement.domain.popup.PopupQueuePolicy;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import com.fxplatform.engagement.persistence.entity.ContentRevisionEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignUserStateEntity;
import com.fxplatform.engagement.persistence.entity.PopupDeliveryEntity;
import com.fxplatform.engagement.persistence.entity.PopupQueueSessionEntity;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import com.fxplatform.engagement.persistence.enums.PopupDeliveryStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import com.fxplatform.engagement.persistence.repository.ContentItemRepository;
import com.fxplatform.engagement.persistence.repository.ContentRevisionRepository;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignUserStateRepository;
import com.fxplatform.engagement.persistence.repository.PopupDeliveryRepository;
import com.fxplatform.engagement.persistence.repository.PopupQueueSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction authority for issuing at most one active popup delivery per user. */
@Service
@Transactional
public class PopupClaimService {

  static final Duration SESSION_TTL = Duration.ofMinutes(30);
  static final Duration DELIVERY_LEASE = Duration.ofMinutes(5);
  static final String EXPIRED_REASON = "EXPIRED";
  static final String CAP_REACHED_REASON = "CAP_REACHED";
  static final String INVALID_QUEUE_SESSION_CODE = "POPUP_QUEUE_SESSION_INVALID";
  static final String INVALID_QUEUE_SESSION = "Popup queue session is invalid";

  private final EngagementUserLockRepository userLockRepository;
  private final SystemSettingRepository settingRepository;
  private final PopupQueueSessionRepository sessionRepository;
  private final PopupCampaignRepository campaignRepository;
  private final PopupDeliveryRepository deliveryRepository;
  private final PopupCampaignUserStateRepository stateRepository;
  private final ContentItemRepository contentItemRepository;
  private final ContentRevisionRepository contentRevisionRepository;
  private final PopupDeliveryTokenCodec tokenCodec;
  private final Clock clock;
  private final PopupQueuePolicy queuePolicy = new PopupQueuePolicy();

  public PopupClaimService(
      EngagementUserLockRepository userLockRepository,
      SystemSettingRepository settingRepository,
      PopupQueueSessionRepository sessionRepository,
      PopupCampaignRepository campaignRepository,
      PopupDeliveryRepository deliveryRepository,
      PopupCampaignUserStateRepository stateRepository,
      ContentItemRepository contentItemRepository,
      ContentRevisionRepository contentRevisionRepository,
      PopupDeliveryTokenCodec tokenCodec,
      Clock clock) {
    this.userLockRepository = Objects.requireNonNull(userLockRepository, "userLockRepository");
    this.settingRepository = Objects.requireNonNull(settingRepository, "settingRepository");
    this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
    this.campaignRepository = Objects.requireNonNull(campaignRepository, "campaignRepository");
    this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository");
    this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
    this.contentItemRepository = Objects.requireNonNull(contentItemRepository, "contentItemRepository");
    this.contentRevisionRepository =
        Objects.requireNonNull(contentRevisionRepository, "contentRevisionRepository");
    this.tokenCodec = Objects.requireNonNull(tokenCodec, "tokenCodec");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Optional<PopupClaim> claimNextPopup(
      UUID userId,
      PopupSurface surface,
      UUID queueSessionId) {
    UUID canonicalUserId = Objects.requireNonNull(userId, "userId");
    PopupSurface canonicalSurface = Objects.requireNonNull(surface, "surface");
    UserEntity user = userLockRepository.findActiveBusinessUserForUpdate(canonicalUserId)
        .orElse(null);
    if (user == null) {
      return Optional.empty();
    }

    Instant now = clock.instant();
    UUID effectiveSessionId = queueSessionId == null ? UUID.randomUUID() : queueSessionId;
    Optional<PopupQueueSessionEntity> persistedSession =
        sessionRepository.findByIdForUpdate(effectiveSessionId);
    if (queueSessionId != null && persistedSession.isEmpty()) {
      throw invalidQueueSession();
    }
    PopupQueueSessionEntity session = persistedSession
        .map(existing -> validateExistingSession(existing, canonicalUserId, canonicalSurface))
        .orElseGet(() -> newSession(effectiveSessionId, canonicalUserId, canonicalSurface, now));
    PopupQueuePolicy.ClaimDecision claimDecision = queuePolicy.beforeClaim(
        required(session.getIssuedCount(), "session.issuedCount"),
        required(session.getMaxItems(), "session.maxItems"),
        required(session.getExpiresAt(), "session.expiresAt"),
        session.getTerminatedAt(),
        now);
    if (claimDecision != PopupQueuePolicy.ClaimDecision.CLAIM) {
      String reason = switch (claimDecision) {
        case EXPIRED -> EXPIRED_REASON;
        case CAP_REACHED -> CAP_REACHED_REASON;
        default -> null;
      };
      if (reason != null
          && sessionRepository.terminate(effectiveSessionId, canonicalUserId, reason, now) != 1) {
        throw conflict("queue session termination");
      }
      return Optional.empty();
    }

    if (hasLiveDeliveryOrExpireStale(canonicalUserId, now)) {
      return Optional.empty();
    }

    Optional<PopupCampaignEntity> selected = campaignRepository.findNextEligibleForUpdate(
        canonicalUserId,
        effectiveSessionId,
        canonicalSurface.pageKey(),
        canonicalSurface.deviceClass(),
        now);
    if (selected.isEmpty()) {
      return Optional.empty();
    }
    PopupCampaignEntity campaignEntity = selected.orElseThrow();

    stateRepository.insertIfAbsent(campaignEntity.getId(), canonicalUserId);
    PopupCampaignUserStateEntity state = stateRepository.findByKeyForUpdate(
        campaignEntity.getId(), canonicalUserId).orElseThrow(
            () -> new IllegalStateException("Popup campaign state was not created"));
    PopupCampaign campaign = rehydrate(campaignEntity);
    if (new PopupEligibilityPolicy(Clock.fixed(now, ZoneOffset.UTC)).evaluate(
        campaign, impressionState(state)) != PopupEligibilityPolicy.Decision.ELIGIBLE) {
      return Optional.empty();
    }
    if (state.getActiveDeliveryId() != null
        && now.isBefore(required(state.getActiveDeliveryExpiresAt(), "state.activeDeliveryExpiresAt"))) {
      return Optional.empty();
    }

    ContentItemEntity contentItem = contentItemRepository.selectById(
        required(campaignEntity.getContentItemId(), "campaign.contentItemId"));
    if (contentItem == null || contentItem.getCurrentRevisionId() == null) {
      throw new IllegalStateException("Popup campaign has no current content revision");
    }
    UUID revisionId = contentItem.getCurrentRevisionId();
    ContentRevisionEntity revision = contentRevisionRepository.selectById(revisionId);
    if (revision == null
        || !revisionId.equals(revision.getId())
        || !campaignEntity.getContentItemId().equals(revision.getContentItemId())) {
      throw new IllegalStateException("Popup campaign current content revision was not found");
    }
    Instant deliveryExpiresAt = earliest(
        now.plus(DELIVERY_LEASE), session.getExpiresAt(), campaign.endAt());
    if (!now.isBefore(deliveryExpiresAt)) {
      return Optional.empty();
    }

    String plaintextToken = tokenCodec.issuePlaintext();
    UUID deliveryId = UUID.randomUUID();
    PopupDeliveryEntity delivery = delivery(
        deliveryId,
        effectiveSessionId,
        campaign.id(),
        canonicalUserId,
        revisionId,
        tokenCodec.hash(plaintextToken),
        canonicalSurface,
        now,
        deliveryExpiresAt);

    if (persistedSession.isEmpty() && sessionRepository.insertIfAbsent(session) != 1) {
      throw conflict("queue session");
    }
    if (deliveryRepository.insertIfAbsent(delivery) != 1) {
      throw conflict("delivery");
    }
    long stateVersion = requiredVersion(state);
    if (stateRepository.setActiveDelivery(
        campaign.id(),
        canonicalUserId,
        deliveryId,
        deliveryExpiresAt,
        stateVersion,
        now) != 1) {
      throw conflict("campaign state");
    }
    if (sessionRepository.incrementIssued(
        effectiveSessionId,
        canonicalUserId,
        session.getIssuedCount(),
        now) != 1) {
      throw conflict("queue session count");
    }

    return Optional.of(new PopupClaim(
        effectiveSessionId,
        deliveryId,
        campaign.id(),
        revisionId,
        plaintextToken,
        deliveryExpiresAt,
        required(campaignEntity.getTemplateSize(), "campaign.templateSize"),
        required(revision.getTitle(), "revision.title"),
        required(revision.getSanitizedHtml(), "revision.sanitizedHtml"),
        revision.getCoverAssetId(),
        revision.getCtaLabel(),
        revision.getCtaRouteKey(),
        revision.getCtaParams()));
  }

  private PopupQueueSessionEntity validateExistingSession(
      PopupQueueSessionEntity session,
      UUID userId,
      PopupSurface surface) {
    if (!userId.equals(session.getUserId())) {
      throw invalidQueueSession();
    }
    if (!surface.triggerType().equals(session.getTriggerType())
        || !surface.pageKey().equals(session.getSurfacePageKey())
        || surface.deviceClass() != session.getDeviceClass()) {
      throw invalidQueueSession();
    }
    required(session.getExpiresAt(), "session.expiresAt");
    required(session.getMaxItems(), "session.maxItems");
    required(session.getIssuedCount(), "session.issuedCount");
    return session;
  }

  private PopupQueueSessionEntity newSession(
      UUID sessionId,
      UUID userId,
      PopupSurface surface,
      Instant now) {
    String configured = settingRepository
        .findBySettingKey(PopupQueuePolicy.MAX_SEQUENTIAL_POPUPS_SETTING_KEY)
        .map(SystemSettingEntity::getSettingValue)
        .orElse(null);
    PopupQueueSessionEntity session = new PopupQueueSessionEntity();
    session.setId(sessionId);
    session.setUserId(userId);
    session.setTriggerType(surface.triggerType());
    session.setSurfacePageKey(surface.pageKey());
    session.setDeviceClass(surface.deviceClass());
    session.setMaxItems(queuePolicy.snapshotMaxItems(configured));
    session.setIssuedCount(0);
    session.setCreatedAt(now);
    session.setExpiresAt(now.plus(SESSION_TTL));
    return session;
  }

  private boolean hasLiveDeliveryOrExpireStale(UUID userId, Instant now) {
    Optional<PopupDeliveryEntity> active = deliveryRepository.findActiveByUserId(userId);
    if (active.isEmpty()) {
      return false;
    }
    PopupDeliveryEntity snapshot = active.orElseThrow();
    UUID campaignId = required(snapshot.getCampaignId(), "delivery.campaignId");
    if (campaignRepository.selectByIdForUpdate(campaignId) == null) {
      throw new IllegalStateException("Active popup delivery campaign was not found");
    }
    PopupDeliveryEntity delivery = deliveryRepository.findByIdForUpdate(
        required(snapshot.getId(), "delivery.id")).orElseThrow(
            () -> new IllegalStateException("Active popup delivery was not found"));
    if (!isActive(delivery)) {
      return false;
    }
    Instant expiresAt = required(delivery.getExpiresAt(), "delivery.expiresAt");
    if (now.isBefore(expiresAt)) {
      return true;
    }

    PopupCampaignUserStateEntity state = stateRepository.findByKeyForUpdate(
        campaignId, userId).orElseThrow(
            () -> new IllegalStateException("Active popup delivery state was not found"));
    if (deliveryRepository.expireActive(delivery.getId(), userId, now) != 1) {
      throw conflict("expired delivery");
    }
    if (stateRepository.clearActiveDelivery(
        campaignId, userId, delivery.getId(), requiredVersion(state)) != 1) {
      throw conflict("expired delivery state");
    }
    return false;
  }

  private static boolean isActive(PopupDeliveryEntity delivery) {
    return delivery.getInvalidatedAt() == null
        && (delivery.getStatus() == PopupDeliveryStatus.ISSUED
            || delivery.getStatus() == PopupDeliveryStatus.SHOWN);
  }

  private static PopupDeliveryEntity delivery(
      UUID deliveryId,
      UUID sessionId,
      UUID campaignId,
      UUID userId,
      UUID revisionId,
      String tokenHash,
      PopupSurface surface,
      Instant issuedAt,
      Instant expiresAt) {
    PopupDeliveryEntity delivery = new PopupDeliveryEntity();
    delivery.setId(deliveryId);
    delivery.setQueueSessionId(sessionId);
    delivery.setCampaignId(campaignId);
    delivery.setUserId(userId);
    delivery.setRevisionId(revisionId);
    delivery.setTokenHash(tokenHash);
    delivery.setStatus(PopupDeliveryStatus.ISSUED);
    delivery.setPageKey(surface.pageKey());
    delivery.setDeviceClass(surface.deviceClass());
    delivery.setIssuedAt(issuedAt);
    delivery.setExpiresAt(expiresAt);
    return delivery;
  }

  private static PopupCampaign rehydrate(PopupCampaignEntity entity) {
    return PopupCampaign.rehydrate(
        required(entity.getId(), "campaign.id"),
        required(entity.getLifecycleStatus(), "campaign.lifecycleStatus"),
        required(entity.getAudienceType(), "campaign.audienceType"),
        required(entity.getSyncToInbox(), "campaign.syncToInbox"),
        ZoneId.of(required(entity.getTimeZone(), "campaign.timeZone")),
        required(entity.getStartAt(), "campaign.startAt"),
        required(entity.getEndAt(), "campaign.endAt"),
        required(entity.getMaxTotalImpressions(), "campaign.maxTotalImpressions"),
        required(entity.getMaxDailyImpressions(), "campaign.maxDailyImpressions"),
        Duration.ofSeconds(required(entity.getMinIntervalSeconds(), "campaign.minIntervalSeconds")),
        entity.getFirstPublishedAt(),
        entity.getLastPublishedAt(),
        entity.getPausedAt(),
        entity.getEndedAt(),
        entity.getDeletedAt());
  }

  private static PopupImpressionState impressionState(PopupCampaignUserStateEntity state) {
    return new PopupImpressionState(
        required(state.getTotalImpressions(), "state.totalImpressions"),
        state.getDailyBucket(),
        required(state.getDailyImpressions(), "state.dailyImpressions"),
        state.getLastImpressionAt(),
        state.getOptedOutAt());
  }

  private static long requiredVersion(PopupCampaignUserStateEntity state) {
    Long version = state.getVersion();
    if (version == null || version < 0) {
      throw new IllegalStateException("Popup campaign state version is invalid");
    }
    return version;
  }

  private static Instant earliest(Instant first, Instant second, Instant third) {
    return first.isBefore(second)
        ? (first.isBefore(third) ? first : third)
        : (second.isBefore(third) ? second : third);
  }

  private static IllegalStateException conflict(String target) {
    return new IllegalStateException("Popup claim " + target + " write conflict");
  }

  private static BusinessException invalidQueueSession() {
    return new BusinessException(INVALID_QUEUE_SESSION_CODE, INVALID_QUEUE_SESSION);
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalStateException(field + " is required");
    }
    return value;
  }

  public record PopupSurface(String triggerType, String pageKey, DeviceClass deviceClass) {
    public PopupSurface {
      if (triggerType == null
          || !triggerType.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
        throw new IllegalArgumentException("triggerType is invalid");
      }
      if (pageKey == null || pageKey.isBlank() || pageKey.length() > 64) {
        throw new IllegalArgumentException("pageKey is invalid");
      }
      Objects.requireNonNull(deviceClass, "deviceClass");
    }
  }

  public record PopupClaim(
      UUID queueSessionId,
      UUID deliveryId,
      UUID campaignId,
      UUID revisionId,
      String deliveryToken,
      Instant expiresAt,
      TemplateSize templateSize,
      String title,
      String sanitizedHtml,
      UUID coverAssetId,
      String ctaLabel,
      String ctaRouteKey,
      String ctaParams) {

    @Override
    public String toString() {
      return "PopupClaim[queueSessionId=" + queueSessionId
          + ", deliveryId=" + deliveryId
          + ", campaignId=" + campaignId
          + ", revisionId=" + revisionId
          + ", deliveryToken=<redacted>"
          + ", expiresAt=" + expiresAt + "]";
    }
  }
}
