package com.fxplatform.engagement.admin.campaign;

import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CampaignAdminDtos {

  private CampaignAdminDtos() {
  }

  /** Fixed business surfaces; authentication URLs deliberately have no representation here. */
  public enum PopupPageKey {
    HOME,
    TRADE_SPOT,
    TRADE_PERPETUAL,
    DASHBOARD,
    MARKETS,
    ORDERS,
    POSITIONS,
    WALLET,
    ACCOUNT_OVERVIEW,
    ACCOUNT_ASSETS,
    FUNDING_RECORDS,
    TRADE_RECORDS,
    KYC,
    ACCOUNT_SETTINGS,
    SECURITY,
    SETTINGS,
    MESSAGES
  }

  public record CampaignContentRequest(
      @NotBlank @Size(max = 200) String title,
      @NotBlank String bodyDocument,
      UUID coverAssetId,
      @Valid CampaignCtaRequest cta
  ) {
  }

  public record CampaignCtaRequest(
      @NotBlank @Size(max = 80) String label,
      @NotBlank @Size(max = 64) String routeKey,
      @NotBlank String paramsJson
  ) {
  }

  public record CampaignSaveRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Valid CampaignContentRequest content,
      @NotNull AudienceType audienceType,
      Set<UUID> targetUserIds,
      boolean syncToInbox,
      @Min(-100000) @Max(100000) int priority,
      @NotNull DisplayScope displayScope,
      Set<PopupPageKey> pageKeys,
      @NotNull DeviceScope deviceScope,
      @NotNull TemplateSize templateSize,
      @NotBlank @Size(max = 64) String timeZone,
      @NotNull Instant startAt,
      @NotNull Instant endAt,
      @Min(1) int maxTotalImpressions,
      @Min(1) int maxDailyImpressions,
      @Min(0) int minIntervalSeconds,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record CampaignActionRequest(
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record CampaignSummaryResponse(
      UUID id,
      String name,
      PopupCampaignLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      boolean syncToInbox,
      int priority,
      Instant startAt,
      Instant endAt,
      Instant firstPublishedAt,
      UUID revisionId,
      String title,
      long targetCount,
      Instant updatedAt
  ) {
  }

  public record CampaignDetailResponse(
      UUID id,
      String name,
      PopupCampaignLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      List<UUID> targetUserIds,
      boolean syncToInbox,
      int priority,
      DisplayScope displayScope,
      List<PopupPageKey> pageKeys,
      DeviceScope deviceScope,
      TemplateSize templateSize,
      String timeZone,
      Instant startAt,
      Instant endAt,
      int maxTotalImpressions,
      int maxDailyImpressions,
      int minIntervalSeconds,
      Instant firstPublishedAt,
      Instant lastPublishedAt,
      Instant pausedAt,
      Instant endedAt,
      Instant deletedAt,
      UUID contentItemId,
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

  public record CampaignPreviewResponse(
      boolean preview,
      UUID campaignId,
      UUID revisionId,
      TemplateSize templateSize,
      String title,
      String sanitizedHtml,
      UUID coverAssetId,
      String ctaLabel,
      String ctaRouteKey,
      String ctaParams
  ) {
  }

  public record CampaignResetResponse(int affectedUsers) {
  }

  public record CampaignStatsResponse(
      UUID campaignId,
      long targetCount,
      long usersWithState,
      long totalImpressions,
      long optedOutUsers,
      long clickedUsers,
      long issuedDeliveries,
      long shownDeliveries,
      long closedDeliveries,
      long clickedDeliveries,
      long invalidatedDeliveries,
      long expiredDeliveries
  ) {
  }

  public record CampaignUserResponse(
      UUID userId,
      String email,
      UserStatus status,
      int totalImpressions,
      LocalDate dailyBucket,
      int dailyImpressions,
      Instant lastImpressionAt,
      Instant optedOutAt,
      Instant lastClickedAt,
      long issuedDeliveries,
      long shownDeliveries,
      long closedDeliveries,
      long clickedDeliveries,
      long invalidatedDeliveries,
      long expiredDeliveries
  ) {
  }
}
