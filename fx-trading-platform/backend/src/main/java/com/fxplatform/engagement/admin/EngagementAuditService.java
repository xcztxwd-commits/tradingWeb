package com.fxplatform.engagement.admin;

import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EngagementAuditService {

  private final AuditLogService auditLogService;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID actorUserId,
      Action action,
      TargetType targetType,
      String targetId,
      Metadata metadata
  ) {
    Metadata safeMetadata = Objects.requireNonNull(metadata, "metadata");
    auditLogService.record(
        actorUserId,
        "ENGAGEMENT_" + action.name(),
        targetType.name(),
        targetId,
        AuditDetailsBuilder.create()
            .put("revisionId", safeMetadata.revisionId())
            .put("audienceType", safeMetadata.audienceType())
            .put("targetCount", safeMetadata.targetCount())
            .put("before", safeMetadata.before())
            .put("after", safeMetadata.after())
            .put("reason", safeMetadata.reason())
            .toJson());
  }

  public enum Action {
    CAMPAIGN_CREATE,
    CAMPAIGN_EDIT,
    CAMPAIGN_PUBLISH,
    CAMPAIGN_SCHEDULE,
    CAMPAIGN_PAUSE,
    CAMPAIGN_RESUME,
    CAMPAIGN_END,
    CAMPAIGN_DELETE,
    CAMPAIGN_RESTORE,
    CAMPAIGN_RESET_DELIVERY,
    CAMPAIGN_TEST_POPUP,
    CAMPAIGN_USER_DETAIL_VIEW,
    MESSAGE_CREATE,
    MESSAGE_EDIT,
    MESSAGE_SEND,
    MESSAGE_SCHEDULE,
    MESSAGE_CANCEL_SCHEDULE,
    MESSAGE_DELETE,
    MESSAGE_RESTORE,
    POPUP_POLICY_UPDATE,
    CONTENT_ASSET_UPLOAD
  }

  public enum TargetType {
    CAMPAIGN,
    MESSAGE,
    USER,
    POPUP_POLICY,
    CONTENT_ASSET
  }

  public record Metadata(
      UUID revisionId,
      AudienceType audienceType,
      Integer targetCount,
      String before,
      String after,
      String reason
  ) {
  }
}
