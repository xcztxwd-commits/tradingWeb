package com.fxplatform.engagement.application.campaign;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignTargetRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the complete target list for an unpublished SELECTED campaign. */
@Service
public class PopupAudienceService {

  private final PopupCampaignRepository campaignRepository;
  private final UserRepository userRepository;
  private final PopupCampaignTargetRepository targetRepository;

  public PopupAudienceService(
      PopupCampaignRepository campaignRepository,
      UserRepository userRepository,
      PopupCampaignTargetRepository targetRepository) {
    this.campaignRepository = campaignRepository;
    this.userRepository = userRepository;
    this.targetRepository = targetRepository;
  }

  @Transactional
  public void replaceTargets(UUID campaignId, Collection<UUID> userIds) {
    if (campaignId == null) {
      throw new IllegalArgumentException("Campaign id is required");
    }

    PopupCampaignEntity campaign = campaignRepository.selectByIdForUpdate(campaignId);
    if (campaign == null) {
      throw new IllegalArgumentException("Campaign not found");
    }
    if (campaign.getAudienceType() != AudienceType.SELECTED) {
      throw new IllegalStateException("Only SELECTED campaigns have an explicit target list");
    }
    if (campaign.getFirstPublishedAt() != null) {
      throw new IllegalStateException("Published campaign audience is frozen");
    }

    Set<UUID> targets = normalize(userIds);
    validateBusinessUsers(targets);

    targetRepository.deleteByCampaignId(campaignId);
    for (UUID userId : targets) {
      if (targetRepository.insertIfAbsent(campaignId, userId) != 1) {
        throw new IllegalStateException("Campaign target write conflict");
      }
    }
  }

  private void validateBusinessUsers(Set<UUID> targets) {
    if (targets.isEmpty()) {
      return;
    }

    Collection<UserEntity> users = userRepository.selectBatchIds(targets);
    Set<UUID> loadedIds = new LinkedHashSet<>();
    for (UserEntity user : users) {
      if (user == null || user.getId() == null || !targets.contains(user.getId())) {
        throw new IllegalArgumentException("Campaign target user not found");
      }
      if (user.getRole() != UserRole.USER) {
        throw new IllegalArgumentException("Campaign targets must be business users");
      }
      loadedIds.add(user.getId());
    }
    if (!loadedIds.equals(targets)) {
      throw new IllegalArgumentException("Campaign target user not found");
    }
  }

  private Set<UUID> normalize(Collection<UUID> userIds) {
    if (userIds == null) {
      throw new IllegalArgumentException("Campaign target users are required");
    }
    Set<UUID> targets = new LinkedHashSet<>();
    for (UUID userId : userIds) {
      if (userId == null) {
        throw new IllegalArgumentException("Campaign target user id is required");
      }
      targets.add(userId);
    }
    return targets;
  }
}
