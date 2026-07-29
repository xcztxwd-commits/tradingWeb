package com.fxplatform.engagement.admin.message;

import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MessageAdminDtos {

  private MessageAdminDtos() {
  }

  public record MessageContentRequest(
      @NotBlank @Size(max = 200) String title,
      @NotBlank String bodyDocument,
      UUID coverAssetId,
      @Valid MessageCtaRequest cta
  ) {
  }

  public record MessageCtaRequest(
      @NotBlank @Size(max = 80) String label,
      @NotBlank @Size(max = 64) String routeKey,
      @NotBlank String paramsJson
  ) {
  }

  public record MessageSaveRequest(
      @NotBlank @Size(max = 64) String category,
      @NotNull AudienceType audienceType,
      Set<UUID> targetUserIds,
      @NotNull @Valid MessageContentRequest content,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record MessageUpdateRequest(
      AudienceType audienceType,
      Set<UUID> targetUserIds,
      @NotNull @Valid MessageContentRequest content,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record MessageSendRequest(
      Instant sendAt,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record MessageActionRequest(
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record MessageSummaryResponse(
      UUID id,
      MessageLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      String category,
      Instant scheduledAt,
      Instant sentAt,
      UUID revisionId,
      String title,
      long targetCount,
      Instant updatedAt
  ) {
  }

  public record MessageDetailResponse(
      UUID id,
      UUID contentItemId,
      MessageLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      String category,
      List<UUID> targetUserIds,
      Instant scheduledAt,
      Instant sentAt,
      Instant audienceCutoffAt,
      Instant deletedAt,
      UUID revisionId,
      int revisionNo,
      String title,
      String bodyDocument,
      String sanitizedHtml,
      UUID coverAssetId,
      String ctaLabel,
      String ctaRouteKey,
      String ctaParams,
      long targetCount,
      Instant createdAt,
      Instant updatedAt
  ) {
  }
}
