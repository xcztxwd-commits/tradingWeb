package com.fxplatform.engagement.admin.campaign.repository;

import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CampaignAdminQueryRepository {

  String TARGET_COUNT = """
      CASE campaign.audience_type
        WHEN 'SELECTED' THEN (
          SELECT COUNT(*)
          FROM content.popup_campaign_targets target
          JOIN auth.users target_user
            ON target_user.id = target.user_id
           AND target_user.role = 'USER'
          WHERE target.campaign_id = campaign.id
        )
        ELSE (
          SELECT COUNT(*)
          FROM auth.users target_user
          WHERE target_user.role = 'USER'
            AND target_user.created_at <= campaign.end_at
        )
      END
      """;

  String FILTERS = """
      WHERE (CAST(#{lifecycleStatus} AS varchar) IS NULL
             OR campaign.lifecycle_status = CAST(#{lifecycleStatus} AS varchar))
        AND (CAST(#{nameQuery} AS varchar) IS NULL
             OR LOWER(campaign.name) LIKE '%' || LOWER(CAST(#{nameQuery} AS varchar)) || '%')
        AND (CAST(#{audienceType} AS varchar) IS NULL
             OR campaign.audience_type = CAST(#{audienceType} AS varchar))
        AND (CAST(#{syncToInbox} AS boolean) IS NULL
             OR campaign.sync_to_inbox = CAST(#{syncToInbox} AS boolean))
        AND (CAST(#{effectiveFrom} AS timestamptz) IS NULL
             OR campaign.end_at > CAST(#{effectiveFrom} AS timestamptz))
        AND (CAST(#{effectiveTo} AS timestamptz) IS NULL
             OR campaign.start_at < CAST(#{effectiveTo} AS timestamptz))
      """;

  @Select("""
      SELECT
        campaign.id,
        campaign.name,
        campaign.lifecycle_status,
        campaign.audience_type,
        campaign.sync_to_inbox,
        campaign.priority,
        campaign.start_at,
        campaign.end_at,
        campaign.first_published_at,
        revision.id AS revision_id,
        revision.title,
        """ + TARGET_COUNT + """
          AS target_count,
        campaign.updated_at
      FROM content.popup_campaigns campaign
      JOIN content.content_items item
        ON item.id = campaign.content_item_id
      JOIN content.content_revisions revision
        ON revision.id = item.current_revision_id
       AND revision.content_item_id = item.id
      """ + FILTERS + """
      ORDER BY campaign.updated_at DESC, campaign.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<CampaignSummaryRow> findCampaigns(
      @Param("lifecycleStatus") String lifecycleStatus,
      @Param("nameQuery") String nameQuery,
      @Param("audienceType") String audienceType,
      @Param("syncToInbox") Boolean syncToInbox,
      @Param("effectiveFrom") Instant effectiveFrom,
      @Param("effectiveTo") Instant effectiveTo,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select("""
      SELECT COUNT(*)
      FROM content.popup_campaigns campaign
      """ + FILTERS)
  long countCampaigns(
      @Param("lifecycleStatus") String lifecycleStatus,
      @Param("nameQuery") String nameQuery,
      @Param("audienceType") String audienceType,
      @Param("syncToInbox") Boolean syncToInbox,
      @Param("effectiveFrom") Instant effectiveFrom,
      @Param("effectiveTo") Instant effectiveTo);

  @Select("""
      SELECT
        campaign.id,
        campaign.name,
        campaign.lifecycle_status,
        campaign.audience_type,
        campaign.sync_to_inbox,
        campaign.priority,
        campaign.display_scope,
        campaign.page_keys::text AS page_keys,
        campaign.device_scope,
        campaign.template_size,
        campaign.time_zone,
        campaign.start_at,
        campaign.end_at,
        campaign.max_total_impressions,
        campaign.max_daily_impressions,
        campaign.min_interval_seconds,
        campaign.first_published_at,
        campaign.last_published_at,
        campaign.paused_at,
        campaign.ended_at,
        campaign.deleted_at,
        campaign.content_item_id,
        revision.id AS revision_id,
        revision.revision_no,
        revision.title,
        revision.body_document::text AS body_document,
        revision.sanitized_html,
        revision.cover_asset_id,
        revision.cta_label,
        revision.cta_route_key,
        revision.cta_params::text AS cta_params,
        """ + TARGET_COUNT + """
          AS target_count,
        campaign.created_at,
        campaign.updated_at
      FROM content.popup_campaigns campaign
      JOIN content.content_items item
        ON item.id = campaign.content_item_id
      JOIN content.content_revisions revision
        ON revision.id = item.current_revision_id
       AND revision.content_item_id = item.id
      WHERE campaign.id = #{campaignId}
      """)
  Optional<CampaignDetailRow> findCampaign(@Param("campaignId") UUID campaignId);

  @Select("""
      SELECT
        campaign.id AS campaign_id,
        """ + TARGET_COUNT + """
          AS target_count,
        (SELECT COUNT(*)
         FROM content.popup_campaign_user_states state
         WHERE state.campaign_id = campaign.id) AS users_with_state,
        COALESCE((SELECT SUM(state.total_impressions)
                  FROM content.popup_campaign_user_states state
                  WHERE state.campaign_id = campaign.id), 0) AS total_impressions,
        (SELECT COUNT(*)
         FROM content.popup_campaign_user_states state
         WHERE state.campaign_id = campaign.id
           AND state.opted_out_at IS NOT NULL) AS opted_out_users,
        (SELECT COUNT(*)
         FROM content.popup_campaign_user_states state
         WHERE state.campaign_id = campaign.id
           AND state.last_clicked_at IS NOT NULL) AS clicked_users,
        (SELECT COUNT(*) FROM content.popup_deliveries delivery
         WHERE delivery.campaign_id = campaign.id AND delivery.status = 'ISSUED')
          AS issued_deliveries,
        (SELECT COUNT(*) FROM content.popup_deliveries delivery
         WHERE delivery.campaign_id = campaign.id AND delivery.status = 'SHOWN')
          AS shown_deliveries,
        (SELECT COUNT(*) FROM content.popup_deliveries delivery
         WHERE delivery.campaign_id = campaign.id AND delivery.status = 'CLOSED')
          AS closed_deliveries,
        (SELECT COUNT(*) FROM content.popup_deliveries delivery
         WHERE delivery.campaign_id = campaign.id AND delivery.status = 'CLICKED')
          AS clicked_deliveries,
        (SELECT COUNT(*) FROM content.popup_deliveries delivery
         WHERE delivery.campaign_id = campaign.id AND delivery.status = 'INVALIDATED')
          AS invalidated_deliveries,
        (SELECT COUNT(*) FROM content.popup_deliveries delivery
         WHERE delivery.campaign_id = campaign.id AND delivery.status = 'EXPIRED')
          AS expired_deliveries
      FROM content.popup_campaigns campaign
      WHERE campaign.id = #{campaignId}
      """)
  Optional<CampaignStatsRow> findStats(@Param("campaignId") UUID campaignId);

  String AUDIENCE_USERS = """
      FROM content.popup_campaigns campaign
      JOIN auth.users audience_user
        ON audience_user.role = 'USER'
       AND (
         (campaign.audience_type = 'ALL' AND audience_user.created_at <= campaign.end_at)
         OR (
           campaign.audience_type = 'SELECTED'
           AND EXISTS (
             SELECT 1
             FROM content.popup_campaign_targets target
             WHERE target.campaign_id = campaign.id
               AND target.user_id = audience_user.id
           )
         )
       )
      """;

  String CAMPAIGN_ID_FILTER = """
      WHERE campaign.id = #{campaignId}
      """;

  @Select("""
      SELECT
        audience_user.id AS user_id,
        audience_user.email,
        audience_user.status,
        COALESCE(state.total_impressions, 0) AS total_impressions,
        state.daily_bucket,
        COALESCE(state.daily_impressions, 0) AS daily_impressions,
        state.last_impression_at,
        state.opted_out_at,
        state.last_clicked_at,
        COUNT(delivery.id) FILTER (WHERE delivery.status = 'ISSUED') AS issued_deliveries,
        COUNT(delivery.id) FILTER (WHERE delivery.status = 'SHOWN') AS shown_deliveries,
        COUNT(delivery.id) FILTER (WHERE delivery.status = 'CLOSED') AS closed_deliveries,
        COUNT(delivery.id) FILTER (WHERE delivery.status = 'CLICKED') AS clicked_deliveries,
        COUNT(delivery.id) FILTER (WHERE delivery.status = 'INVALIDATED') AS invalidated_deliveries,
        COUNT(delivery.id) FILTER (WHERE delivery.status = 'EXPIRED') AS expired_deliveries
      """ + AUDIENCE_USERS + """
      LEFT JOIN content.popup_campaign_user_states state
        ON state.campaign_id = campaign.id
       AND state.user_id = audience_user.id
      LEFT JOIN content.popup_deliveries delivery
        ON delivery.campaign_id = campaign.id
       AND delivery.user_id = audience_user.id
      """ + CAMPAIGN_ID_FILTER + """
      GROUP BY audience_user.id, audience_user.email, audience_user.status,
        audience_user.created_at, state.campaign_id, state.user_id,
        state.total_impressions, state.daily_bucket, state.daily_impressions,
        state.last_impression_at, state.opted_out_at, state.last_clicked_at
      ORDER BY audience_user.created_at DESC, audience_user.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<CampaignUserRow> findUsers(
      @Param("campaignId") UUID campaignId,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select("SELECT COUNT(*) " + AUDIENCE_USERS + CAMPAIGN_ID_FILTER)
  long countUsers(@Param("campaignId") UUID campaignId);

  record CampaignSummaryRow(
      UUID id,
      String name,
      PopupCampaignLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      Boolean syncToInbox,
      Integer priority,
      Instant startAt,
      Instant endAt,
      Instant firstPublishedAt,
      UUID revisionId,
      String title,
      Long targetCount,
      Instant updatedAt
  ) {
  }

  record CampaignDetailRow(
      UUID id,
      String name,
      PopupCampaignLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      Boolean syncToInbox,
      Integer priority,
      DisplayScope displayScope,
      String pageKeys,
      DeviceScope deviceScope,
      TemplateSize templateSize,
      String timeZone,
      Instant startAt,
      Instant endAt,
      Integer maxTotalImpressions,
      Integer maxDailyImpressions,
      Integer minIntervalSeconds,
      Instant firstPublishedAt,
      Instant lastPublishedAt,
      Instant pausedAt,
      Instant endedAt,
      Instant deletedAt,
      UUID contentItemId,
      UUID revisionId,
      Integer revisionNo,
      String title,
      String bodyDocument,
      String sanitizedHtml,
      UUID coverAssetId,
      String ctaLabel,
      String ctaRouteKey,
      String ctaParams,
      Long targetCount,
      Instant createdAt,
      Instant updatedAt
  ) {
  }

  record CampaignStatsRow(
      UUID campaignId,
      Long targetCount,
      Long usersWithState,
      Long totalImpressions,
      Long optedOutUsers,
      Long clickedUsers,
      Long issuedDeliveries,
      Long shownDeliveries,
      Long closedDeliveries,
      Long clickedDeliveries,
      Long invalidatedDeliveries,
      Long expiredDeliveries
  ) {
  }

  record CampaignUserRow(
      UUID userId,
      String email,
      UserStatus status,
      Integer totalImpressions,
      LocalDate dailyBucket,
      Integer dailyImpressions,
      Instant lastImpressionAt,
      Instant optedOutAt,
      Instant lastClickedAt,
      Long issuedDeliveries,
      Long shownDeliveries,
      Long closedDeliveries,
      Long clickedDeliveries,
      Long invalidatedDeliveries,
      Long expiredDeliveries
  ) {
  }
}
