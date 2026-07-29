package com.fxplatform.engagement.application.message;

import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.repository.MessagePublicationRepository;
import com.fxplatform.engagement.persistence.repository.MessageTargetRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the one campaign-owned inbox publication in the campaign transaction. */
@Service
@Transactional
public class CampaignMessageSyncService {

  private static final String CAMPAIGN_CATEGORY = "CAMPAIGN";

  private final MessagePublicationRepository publicationRepository;
  private final MessageTargetRepository targetRepository;

  public CampaignMessageSyncService(
      MessagePublicationRepository publicationRepository,
      MessageTargetRepository targetRepository) {
    this.publicationRepository = Objects.requireNonNull(publicationRepository);
    this.targetRepository = Objects.requireNonNull(targetRepository);
  }

  public void synchronize(PopupCampaignEntity campaign, UUID actorId, Instant now) {
    Objects.requireNonNull(campaign, "campaign");
    if (!Boolean.TRUE.equals(campaign.getSyncToInbox())) {
      return;
    }
    UUID campaignId = required(campaign.getId(), "campaign.id");
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    Instant canonicalNow = Objects.requireNonNull(now, "now");
    Optional<MessagePublicationEntity> linked =
        publicationRepository.findBySourceCampaignIdForUpdate(campaignId);
    if (linked.isEmpty()) {
      if (campaign.getLifecycleStatus() == PopupCampaignLifecycleStatus.ACTIVE) {
        create(campaign, actor, canonicalNow);
      }
      return;
    }

    MessagePublicationEntity publication = linked.orElseThrow();
    validateCanonical(campaign, publication);
    PopupCampaignLifecycleStatus status = required(
        campaign.getLifecycleStatus(), "campaign.lifecycleStatus");
    if (status == PopupCampaignLifecycleStatus.DELETED) {
      hide(publication, actor, canonicalNow);
      return;
    }
    if (status == PopupCampaignLifecycleStatus.ACTIVE
        && publication.getLifecycleStatus() == MessageLifecycleStatus.DELETED) {
      required(publication.getSentAt(), "publication.sentAt");
      publication.setLifecycleStatus(MessageLifecycleStatus.SENT);
      publication.setDeletedAt(null);
      publication.setAudienceCutoffAt(required(campaign.getEndAt(), "campaign.endAt"));
      touch(publication, actor, canonicalNow);
      requireSingleWrite(publicationRepository.updateById(publication), "restore");
      return;
    }
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.DELETED) {
      return;
    }

    Instant endAt = required(campaign.getEndAt(), "campaign.endAt");
    if (!endAt.equals(publication.getAudienceCutoffAt())) {
      publication.setAudienceCutoffAt(endAt);
      touch(publication, actor, canonicalNow);
      requireSingleWrite(publicationRepository.updateById(publication), "cutoff");
    }
  }

  private void create(PopupCampaignEntity campaign, UUID actorId, Instant now) {
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(UUID.randomUUID());
    publication.setContentItemId(required(campaign.getContentItemId(), "campaign.contentItemId"));
    publication.setSourceType(MessageSourceType.CAMPAIGN);
    publication.setSourceCampaignId(required(campaign.getId(), "campaign.id"));
    publication.setAudienceType(required(campaign.getAudienceType(), "campaign.audienceType"));
    publication.setLifecycleStatus(MessageLifecycleStatus.SENT);
    publication.setCategory(CAMPAIGN_CATEGORY);
    publication.setSentAt(now);
    publication.setAudienceCutoffAt(required(campaign.getEndAt(), "campaign.endAt"));
    publication.setCreatedBy(actorId);
    publication.setUpdatedBy(actorId);
    publication.setCreatedAt(now);
    publication.setUpdatedAt(now);
    requireSingleWrite(publicationRepository.insert(publication), "create");

    if (publication.getAudienceType() == AudienceType.SELECTED) {
      int expected = targetRepository.countByCampaignId(campaign.getId());
      int copied = targetRepository.insertFromCampaign(publication.getId(), campaign.getId());
      if (copied != expected) {
        throw new IllegalStateException("Campaign message target copy conflict");
      }
    }
  }

  private void hide(MessagePublicationEntity publication, UUID actorId, Instant now) {
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.DELETED) {
      return;
    }
    publication.setLifecycleStatus(MessageLifecycleStatus.DELETED);
    publication.setDeletedAt(now);
    touch(publication, actorId, now);
    requireSingleWrite(publicationRepository.updateById(publication), "delete");
  }

  private static void validateCanonical(
      PopupCampaignEntity campaign,
      MessagePublicationEntity publication) {
    if (publication.getSourceType() != MessageSourceType.CAMPAIGN
        || !Objects.equals(publication.getSourceCampaignId(), campaign.getId())) {
      throw new IllegalStateException("Linked message is not owned by the campaign");
    }
    if (!Objects.equals(publication.getContentItemId(), campaign.getContentItemId())) {
      throw new IllegalStateException("Campaign message content item changed");
    }
    if (publication.getAudienceType() != campaign.getAudienceType()) {
      throw new IllegalStateException("Campaign message audience changed");
    }
    if (publication.getLifecycleStatus() != MessageLifecycleStatus.SENT
        && publication.getLifecycleStatus() != MessageLifecycleStatus.DELETED) {
      throw new IllegalStateException("Campaign message lifecycle is invalid");
    }
  }

  private static void touch(
      MessagePublicationEntity publication,
      UUID actorId,
      Instant now) {
    publication.setUpdatedBy(actorId);
    publication.setUpdatedAt(now);
  }

  private static void requireSingleWrite(int rows, String operation) {
    if (rows != 1) {
      throw new IllegalStateException("Campaign message " + operation + " write conflict");
    }
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalStateException(field + " is required");
    }
    return value;
  }
}
