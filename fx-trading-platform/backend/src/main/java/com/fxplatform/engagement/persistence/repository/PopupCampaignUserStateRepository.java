package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.engagement.persistence.entity.PopupCampaignUserStateEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PopupCampaignUserStateRepository {

  @Insert("""
      INSERT INTO content.popup_campaign_user_states (campaign_id, user_id)
      VALUES (#{campaignId}, #{userId})
      ON CONFLICT (campaign_id, user_id) DO NOTHING
      """)
  int insertIfAbsent(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId);

  @Select("""
      SELECT *
      FROM content.popup_campaign_user_states
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
      """)
  Optional<PopupCampaignUserStateEntity> findByKey(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId);

  @Select("""
      SELECT *
      FROM content.popup_campaign_user_states
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
      FOR UPDATE
      """)
  Optional<PopupCampaignUserStateEntity> findByKeyForUpdate(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId);

  @Update("""
      UPDATE content.popup_campaign_user_states
      SET active_delivery_id = #{deliveryId},
          active_delivery_expires_at = #{expiresAt},
          version = version + 1
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
        AND version = #{expectedVersion}
        AND (
          active_delivery_id IS NULL
          OR active_delivery_expires_at <= #{now}
        )
      """)
  int setActiveDelivery(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId,
      @Param("deliveryId") UUID deliveryId,
      @Param("expiresAt") Instant expiresAt,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_campaign_user_states
      SET active_delivery_id = NULL,
          active_delivery_expires_at = NULL,
          version = version + 1
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
        AND active_delivery_id = #{deliveryId}
        AND version = #{expectedVersion}
      """)
  int clearActiveDelivery(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId,
      @Param("deliveryId") UUID deliveryId,
      @Param("expectedVersion") long expectedVersion);

  @Update("""
      UPDATE content.popup_campaign_user_states
      SET total_impressions = total_impressions + 1,
          daily_bucket = #{dailyBucket},
          daily_impressions = CASE
            WHEN daily_bucket = #{dailyBucket} THEN daily_impressions + 1
            ELSE 1
          END,
          last_impression_at = #{now},
          version = version + 1
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
        AND active_delivery_id = #{deliveryId}
        AND active_delivery_expires_at > #{now}
        AND opted_out_at IS NULL
        AND version = #{expectedVersion}
      """)
  int recordShown(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId,
      @Param("deliveryId") UUID deliveryId,
      @Param("expectedVersion") long expectedVersion,
      @Param("dailyBucket") LocalDate dailyBucket,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_campaign_user_states
      SET opted_out_at = COALESCE(opted_out_at, #{now}),
          active_delivery_id = NULL,
          active_delivery_expires_at = NULL,
          version = version + 1
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
        AND active_delivery_id = #{deliveryId}
        AND active_delivery_expires_at > #{now}
        AND version = #{expectedVersion}
      """)
  int recordOptOutAndClear(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId,
      @Param("deliveryId") UUID deliveryId,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_campaign_user_states
      SET last_clicked_at = #{now},
          active_delivery_id = NULL,
          active_delivery_expires_at = NULL,
          version = version + 1
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
        AND active_delivery_id = #{deliveryId}
        AND active_delivery_expires_at > #{now}
        AND version = #{expectedVersion}
      """)
  int recordClickAndClear(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId,
      @Param("deliveryId") UUID deliveryId,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_campaign_user_states
      SET total_impressions = 0,
          daily_bucket = NULL,
          daily_impressions = 0,
          last_impression_at = NULL,
          version = version + 1
      WHERE campaign_id = #{campaignId}
      """)
  int resetDeliveryCounters(@Param("campaignId") UUID campaignId);
}
