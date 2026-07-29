package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PopupCampaignRepository extends FxBaseMapper<PopupCampaignEntity> {

  @Select("SELECT * FROM content.popup_campaigns WHERE id = #{id} FOR UPDATE")
  PopupCampaignEntity selectByIdForUpdate(@Param("id") UUID id);

  @Select("""
      SELECT *
      FROM content.popup_campaigns
      WHERE lifecycle_status = 'SCHEDULED'
        AND deleted_at IS NULL
        AND start_at <= #{now}
      ORDER BY start_at ASC, id ASC
      FOR UPDATE SKIP LOCKED
      """)
  List<PopupCampaignEntity> findDueForUpdate(@Param("now") Instant now);

  @Select("""
      SELECT campaign.*
      FROM content.popup_campaigns campaign
      JOIN auth.users user_row
        ON user_row.id = #{userId}
       AND user_row.status = 'ACTIVE'
       AND user_row.role = 'USER'
      JOIN content.content_items content_item
        ON content_item.id = campaign.content_item_id
       AND content_item.current_revision_id IS NOT NULL
      LEFT JOIN content.popup_campaign_user_states state
        ON state.campaign_id = campaign.id
       AND state.user_id = #{userId}
      WHERE campaign.lifecycle_status = 'ACTIVE'
        AND campaign.deleted_at IS NULL
        AND campaign.start_at <= #{now}
        AND campaign.end_at > #{now}
        AND (
          (campaign.audience_type = 'ALL' AND user_row.created_at <= campaign.end_at)
          OR (
            campaign.audience_type = 'SELECTED'
            AND EXISTS (
              SELECT 1
              FROM content.popup_campaign_targets target
              WHERE target.campaign_id = campaign.id
                AND target.user_id = #{userId}
            )
          )
        )
        AND (
          campaign.display_scope = 'ALL_BUSINESS_PAGES'
          OR jsonb_exists(campaign.page_keys, #{pageKey})
        )
        AND (campaign.device_scope = 'ALL' OR campaign.device_scope = #{deviceClass})
        AND state.opted_out_at IS NULL
        AND COALESCE(state.total_impressions, 0) < campaign.max_total_impressions
        AND (
          state.daily_bucket IS DISTINCT FROM (#{now} AT TIME ZONE campaign.time_zone)::date
          OR state.daily_impressions < campaign.max_daily_impressions
        )
        AND (
          state.last_impression_at IS NULL
          OR state.last_impression_at
             + campaign.min_interval_seconds * INTERVAL '1 second' <= #{now}
        )
        AND (
          state.active_delivery_id IS NULL
          OR state.active_delivery_expires_at <= #{now}
        )
        AND NOT EXISTS (
          SELECT 1
          FROM content.popup_deliveries delivered
          WHERE delivered.queue_session_id = #{queueSessionId}
            AND delivered.campaign_id = campaign.id
        )
      ORDER BY campaign.priority DESC, campaign.first_published_at ASC, campaign.id ASC
      LIMIT 1
      FOR UPDATE OF campaign
      """)
  Optional<PopupCampaignEntity> findNextEligibleForUpdate(
      @Param("userId") UUID userId,
      @Param("queueSessionId") UUID queueSessionId,
      @Param("pageKey") String pageKey,
      @Param("deviceClass") DeviceClass deviceClass,
      @Param("now") Instant now);
}
