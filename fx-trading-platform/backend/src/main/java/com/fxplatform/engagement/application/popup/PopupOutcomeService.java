package com.fxplatform.engagement.application.popup;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.domain.popup.PopupOutcome;
import com.fxplatform.engagement.domain.popup.PopupQueuePolicy;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignUserStateEntity;
import com.fxplatform.engagement.persistence.entity.PopupDeliveryEntity;
import com.fxplatform.engagement.persistence.entity.PopupQueueSessionEntity;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.PopupDeliveryStatus;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignUserStateRepository;
import com.fxplatform.engagement.persistence.repository.PopupDeliveryRepository;
import com.fxplatform.engagement.persistence.repository.PopupQueueSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction authority for delivery-token outcomes and campaign-wide counter reset. */
@Service
public class PopupOutcomeService {

  static final String CLOSE_REASON = "CLOSE";
  static final String OPT_OUT_REASON = "OPT_OUT";
  static final String CTA_CLICK_REASON = "CTA_CLICK";

  private static final Pattern DELIVERY_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  static final String INVALID_CREDENTIAL_CODE = "POPUP_DELIVERY_CREDENTIAL_INVALID";
  private static final String INVALID_CREDENTIAL = "Popup delivery credential is invalid";

  private final EngagementUserLockRepository userLockRepository;
  private final PopupDeliveryRepository deliveryRepository;
  private final PopupQueueSessionRepository sessionRepository;
  private final PopupCampaignRepository campaignRepository;
  private final PopupCampaignUserStateRepository stateRepository;
  private final PopupDeliveryTokenCodec tokenCodec;
  private final Clock clock;
  private final List<PopupShownHandler> shownHandlers;
  private final PopupQueuePolicy queuePolicy = new PopupQueuePolicy();

  public PopupOutcomeService(
      EngagementUserLockRepository userLockRepository,
      PopupDeliveryRepository deliveryRepository,
      PopupQueueSessionRepository sessionRepository,
      PopupCampaignRepository campaignRepository,
      PopupCampaignUserStateRepository stateRepository,
      PopupDeliveryTokenCodec tokenCodec,
      Clock clock,
      List<PopupShownHandler> shownHandlers) {
    this.userLockRepository = Objects.requireNonNull(userLockRepository, "userLockRepository");
    this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository");
    this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
    this.campaignRepository = Objects.requireNonNull(campaignRepository, "campaignRepository");
    this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
    this.tokenCodec = Objects.requireNonNull(tokenCodec, "tokenCodec");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.shownHandlers = List.copyOf(Objects.requireNonNull(shownHandlers, "shownHandlers"));
  }

  @Transactional
  public PopupOutcomeResult recordPopupOutcome(
      UUID userId,
      String plaintextToken,
      PopupOutcome outcome) {
    validateToken(plaintextToken);
    UUID canonicalUserId = requireCredentialValue(userId);
    PopupOutcome canonicalOutcome = Objects.requireNonNull(outcome, "outcome");
    String tokenHash = tokenCodec.hash(plaintextToken);

    if (userLockRepository.findActiveBusinessUserForUpdate(canonicalUserId).isEmpty()) {
      throw invalidCredential();
    }
    PopupDeliveryEntity identity = deliveryRepository
        .findIdentityByUserAndTokenHash(canonicalUserId, tokenHash)
        .orElseThrow(PopupOutcomeService::invalidCredential);
    UUID deliveryId = requireCredentialValue(identity.getId());
    UUID sessionId = requireCredentialValue(identity.getQueueSessionId());
    UUID campaignId = requireCredentialValue(identity.getCampaignId());

    PopupQueueSessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
        .orElseThrow(PopupOutcomeService::invalidCredential);
    PopupCampaignEntity campaign = campaignRepository.selectByIdForUpdate(campaignId);
    if (campaign == null) {
      throw invalidCredential();
    }
    PopupDeliveryEntity delivery = deliveryRepository
        .findByIdAndCredentialForUpdate(deliveryId, canonicalUserId, tokenHash)
        .orElseThrow(PopupOutcomeService::invalidCredential);
    PopupCampaignUserStateEntity state = stateRepository
        .findByKeyForUpdate(campaignId, canonicalUserId)
        .orElseThrow(PopupOutcomeService::invalidCredential);

    validateCanonicalIdentity(
        canonicalUserId, tokenHash, identity, session, campaign, delivery, state);
    PopupOutcomeResult replay = exactTerminalReplay(delivery, canonicalOutcome);
    if (replay != null) {
      return replay;
    }
    if (isFinal(delivery.getStatus())) {
      throw new IllegalStateException("Popup delivery outcome is already finalized");
    }

    Instant now = clock.instant();
    long version = requiredVersion(state);
    validatePointer(delivery, state);

    if (!queueIsActive(session, now)) {
      return delivery.getStatus() == PopupDeliveryStatus.ISSUED
              && !now.isBefore(required(delivery.getExpiresAt(), "delivery.expiresAt"))
          ? expireIssued(delivery, state, tokenHash, version, now)
          : invalidate(delivery, state, tokenHash, version, now);
    }
    if (!campaignIsActive(campaign, now)) {
      return invalidate(delivery, state, tokenHash, version, now);
    }
    if (!now.isBefore(required(delivery.getExpiresAt(), "delivery.expiresAt"))) {
      return delivery.getStatus() == PopupDeliveryStatus.ISSUED
          ? expireIssued(delivery, state, tokenHash, version, now)
          : invalidate(delivery, state, tokenHash, version, now);
    }
    if (!now.isBefore(required(state.getActiveDeliveryExpiresAt(),
        "state.activeDeliveryExpiresAt"))) {
      return invalidate(delivery, state, tokenHash, version, now);
    }
    if (isExactShownReplay(delivery, canonicalOutcome)) {
      return result(true, PopupDeliveryStatus.SHOWN, PopupOutcome.SHOWN);
    }

    return switch (canonicalOutcome) {
      case SHOWN -> recordShown(delivery, campaign, state, tokenHash, version, now);
      case CLOSE -> recordClose(delivery, state, tokenHash, version, now);
      case OPT_OUT -> recordOptOut(delivery, state, tokenHash, version, now);
      case CTA_CLICK -> recordClick(delivery, session, state, tokenHash, version, now);
    };
  }

  @Transactional
  public int resetDeliveryCounters(UUID campaignId) {
    UUID canonicalCampaignId = Objects.requireNonNull(campaignId, "campaignId");
    if (campaignRepository.selectByIdForUpdate(canonicalCampaignId) == null) {
      throw new IllegalArgumentException("Popup campaign was not found");
    }
    return stateRepository.resetDeliveryCounters(canonicalCampaignId);
  }

  private PopupOutcomeResult recordShown(
      PopupDeliveryEntity delivery,
      PopupCampaignEntity campaign,
      PopupCampaignUserStateEntity state,
      String tokenHash,
      long version,
      Instant now) {
    if (delivery.getStatus() != PopupDeliveryStatus.ISSUED) {
      throw new IllegalStateException("Popup delivery cannot be shown from its current state");
    }
    UUID deliveryId = delivery.getId();
    UUID userId = delivery.getUserId();
    UUID campaignId = delivery.getCampaignId();
    if (deliveryRepository.markShown(deliveryId, userId, tokenHash, now) != 1) {
      throw conflict("shown delivery");
    }
    LocalDate dailyBucket = LocalDate.ofInstant(
        now, ZoneId.of(required(campaign.getTimeZone(), "campaign.timeZone")));
    if (stateRepository.recordShown(
        campaignId, userId, deliveryId, version, dailyBucket, now) != 1) {
      throw conflict("shown state");
    }
    shownHandlers.forEach(handler -> handler.onShown(userId, campaignId, deliveryId, now));
    return result(true, PopupDeliveryStatus.SHOWN, PopupOutcome.SHOWN);
  }

  private PopupOutcomeResult recordClose(
      PopupDeliveryEntity delivery,
      PopupCampaignUserStateEntity state,
      String tokenHash,
      long version,
      Instant now) {
    requireShown(delivery);
    if (deliveryRepository.markClosed(
        delivery.getId(), delivery.getUserId(), tokenHash, CLOSE_REASON, now) != 1) {
      throw conflict("closed delivery");
    }
    if (stateRepository.clearActiveDelivery(
        delivery.getCampaignId(), delivery.getUserId(), delivery.getId(), version) != 1) {
      throw conflict("closed state");
    }
    return result(true, PopupDeliveryStatus.CLOSED, PopupOutcome.CLOSE);
  }

  private PopupOutcomeResult recordOptOut(
      PopupDeliveryEntity delivery,
      PopupCampaignUserStateEntity state,
      String tokenHash,
      long version,
      Instant now) {
    requireShown(delivery);
    if (deliveryRepository.markClosed(
        delivery.getId(), delivery.getUserId(), tokenHash, OPT_OUT_REASON, now) != 1) {
      throw conflict("opt-out delivery");
    }
    if (stateRepository.recordOptOutAndClear(
        delivery.getCampaignId(), delivery.getUserId(), delivery.getId(), version, now) != 1) {
      throw conflict("opt-out state");
    }
    return result(true, PopupDeliveryStatus.CLOSED, PopupOutcome.OPT_OUT);
  }

  private PopupOutcomeResult recordClick(
      PopupDeliveryEntity delivery,
      PopupQueueSessionEntity session,
      PopupCampaignUserStateEntity state,
      String tokenHash,
      long version,
      Instant now) {
    requireShown(delivery);
    if (deliveryRepository.markClicked(
        delivery.getId(), delivery.getUserId(), tokenHash, now) != 1) {
      throw conflict("clicked delivery");
    }
    if (stateRepository.recordClickAndClear(
        delivery.getCampaignId(), delivery.getUserId(), delivery.getId(), version, now) != 1) {
      throw conflict("clicked state");
    }
    if (sessionRepository.terminate(
        session.getId(), delivery.getUserId(), CTA_CLICK_REASON, now) != 1) {
      throw conflict("clicked queue session");
    }
    return result(true, PopupDeliveryStatus.CLICKED, PopupOutcome.CTA_CLICK);
  }

  private PopupOutcomeResult expireIssued(
      PopupDeliveryEntity delivery,
      PopupCampaignUserStateEntity state,
      String tokenHash,
      long version,
      Instant now) {
    if (deliveryRepository.expireIssued(
        delivery.getId(), delivery.getUserId(), tokenHash, now) != 1) {
      throw conflict("expired delivery");
    }
    clearPointer(delivery, version, "expired state");
    return new PopupOutcomeResult(
        false, PopupDeliveryStatus.EXPIRED, PopupQueuePolicy.OutcomeDecision.CONTINUE);
  }

  private PopupOutcomeResult invalidate(
      PopupDeliveryEntity delivery,
      PopupCampaignUserStateEntity state,
      String tokenHash,
      long version,
      Instant now) {
    if (deliveryRepository.invalidateActive(
        delivery.getId(), delivery.getUserId(), tokenHash, now) != 1) {
      throw conflict("invalidated delivery");
    }
    clearPointer(delivery, version, "invalidated state");
    return new PopupOutcomeResult(
        false, PopupDeliveryStatus.INVALIDATED, PopupQueuePolicy.OutcomeDecision.CONTINUE);
  }

  private void clearPointer(PopupDeliveryEntity delivery, long version, String target) {
    if (stateRepository.clearActiveDelivery(
        delivery.getCampaignId(), delivery.getUserId(), delivery.getId(), version) != 1) {
      throw conflict(target);
    }
  }

  private PopupOutcomeResult exactTerminalReplay(
      PopupDeliveryEntity delivery,
      PopupOutcome outcome) {
    if (outcome == PopupOutcome.CLOSE
        && delivery.getStatus() == PopupDeliveryStatus.CLOSED
        && CLOSE_REASON.equals(delivery.getCloseReason())
        && delivery.getClosedAt() != null) {
      return result(true, PopupDeliveryStatus.CLOSED, outcome);
    }
    if (outcome == PopupOutcome.OPT_OUT
        && delivery.getStatus() == PopupDeliveryStatus.CLOSED
        && OPT_OUT_REASON.equals(delivery.getCloseReason())
        && delivery.getClosedAt() != null) {
      return result(true, PopupDeliveryStatus.CLOSED, outcome);
    }
    if (outcome == PopupOutcome.CTA_CLICK
        && delivery.getStatus() == PopupDeliveryStatus.CLICKED
        && delivery.getClickedAt() != null) {
      return result(true, PopupDeliveryStatus.CLICKED, outcome);
    }
    return null;
  }

  private static boolean isExactShownReplay(
      PopupDeliveryEntity delivery,
      PopupOutcome outcome) {
    return outcome == PopupOutcome.SHOWN
        && delivery.getStatus() == PopupDeliveryStatus.SHOWN
        && delivery.getShownAt() != null;
  }

  private PopupOutcomeResult result(
      boolean accepted,
      PopupDeliveryStatus status,
      PopupOutcome outcome) {
    return new PopupOutcomeResult(accepted, status, queuePolicy.afterOutcome(outcome));
  }

  private static void validateCanonicalIdentity(
      UUID userId,
      String tokenHash,
      PopupDeliveryEntity identity,
      PopupQueueSessionEntity session,
      PopupCampaignEntity campaign,
      PopupDeliveryEntity delivery,
      PopupCampaignUserStateEntity state) {
    if (!Objects.equals(identity.getId(), delivery.getId())
        || !Objects.equals(identity.getQueueSessionId(), delivery.getQueueSessionId())
        || !Objects.equals(identity.getCampaignId(), delivery.getCampaignId())
        || !Objects.equals(userId, delivery.getUserId())
        || !Objects.equals(tokenHash, delivery.getTokenHash())
        || !Objects.equals(delivery.getQueueSessionId(), session.getId())
        || !Objects.equals(userId, session.getUserId())
        || !Objects.equals(delivery.getCampaignId(), campaign.getId())
        || !Objects.equals(delivery.getCampaignId(), state.getCampaignId())
        || !Objects.equals(userId, state.getUserId())) {
      throw invalidCredential();
    }
  }

  private static void validatePointer(
      PopupDeliveryEntity delivery,
      PopupCampaignUserStateEntity state) {
    if (!Objects.equals(delivery.getId(), state.getActiveDeliveryId())
        || !Objects.equals(delivery.getExpiresAt(), state.getActiveDeliveryExpiresAt())) {
      throw new IllegalStateException("Popup delivery state is invalid");
    }
  }

  private static boolean queueIsActive(PopupQueueSessionEntity session, Instant now) {
    return session.getTerminatedAt() == null
        && now.isBefore(required(session.getExpiresAt(), "session.expiresAt"));
  }

  private static boolean campaignIsActive(PopupCampaignEntity campaign, Instant now) {
    return campaign.getLifecycleStatus() == PopupCampaignLifecycleStatus.ACTIVE
        && campaign.getDeletedAt() == null
        && !now.isBefore(required(campaign.getStartAt(), "campaign.startAt"))
        && now.isBefore(required(campaign.getEndAt(), "campaign.endAt"));
  }

  private static boolean isFinal(PopupDeliveryStatus status) {
    return status == PopupDeliveryStatus.CLOSED
        || status == PopupDeliveryStatus.CLICKED
        || status == PopupDeliveryStatus.EXPIRED
        || status == PopupDeliveryStatus.INVALIDATED;
  }

  private static void requireShown(PopupDeliveryEntity delivery) {
    if (delivery.getStatus() != PopupDeliveryStatus.SHOWN || delivery.getShownAt() == null) {
      throw new IllegalStateException("Popup delivery must be shown before a terminal outcome");
    }
  }

  private static long requiredVersion(PopupCampaignUserStateEntity state) {
    Long version = state.getVersion();
    if (version == null || version < 0) {
      throw new IllegalStateException("Popup delivery state version is invalid");
    }
    return version;
  }

  private static void validateToken(String token) {
    if (token == null || !DELIVERY_TOKEN.matcher(token).matches()) {
      throw invalidCredential();
    }
  }

  private static <T> T requireCredentialValue(T value) {
    if (value == null) {
      throw invalidCredential();
    }
    return value;
  }

  private static BusinessException invalidCredential() {
    return new BusinessException(INVALID_CREDENTIAL_CODE, INVALID_CREDENTIAL);
  }

  private static IllegalStateException conflict(String target) {
    return new IllegalStateException("Popup outcome " + target + " write conflict");
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalStateException(field + " is required");
    }
    return value;
  }

  public record PopupOutcomeResult(
      boolean accepted,
      PopupDeliveryStatus status,
      PopupQueuePolicy.OutcomeDecision queueDirective) {
  }
}
