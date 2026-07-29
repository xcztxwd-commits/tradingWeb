package com.fxplatform.engagement.domain.campaign;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.util.Objects;

/** Decides campaign audience membership without applying lifecycle or frequency rules. */
public final class PopupAudiencePolicy {

  public boolean isMember(
      PopupCampaign campaign,
      UserEntity user,
      boolean selectedTarget
  ) {
    Objects.requireNonNull(campaign, "campaign");
    Objects.requireNonNull(user, "user");
    if (user.getRole() != UserRole.USER) {
      return false;
    }
    if (campaign.audienceType() == AudienceType.SELECTED) {
      return selectedTarget;
    }
    return !Objects.requireNonNull(user.getCreatedAt(), "user.createdAt")
        .isAfter(campaign.endAt());
  }
}
