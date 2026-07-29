package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.engagement.persistence.entity.PopupDeliveryEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PopupDeliveryRepository extends FxBaseMapper<PopupDeliveryEntity> {

  @Update("""
      WITH expired_deliveries AS MATERIALIZED (
        SELECT delivery.id, delivery.campaign_id, delivery.user_id
        FROM content.popup_deliveries delivery
        JOIN content.popup_campaign_user_states state
          ON state.active_delivery_id = delivery.id
         AND state.campaign_id = delivery.campaign_id
         AND state.user_id = delivery.user_id
        WHERE delivery.issued_at < #{cutoff}
          AND delivery.expires_at <= #{now}
          AND state.active_delivery_expires_at <= #{now}
        ORDER BY delivery.id
        FOR UPDATE OF delivery
      )
      UPDATE content.popup_campaign_user_states state
      SET active_delivery_id = NULL,
          active_delivery_expires_at = NULL,
          version = state.version + 1
      FROM expired_deliveries delivery
      WHERE state.active_delivery_id = delivery.id
        AND state.campaign_id = delivery.campaign_id
        AND state.user_id = delivery.user_id
        AND state.active_delivery_expires_at <= #{now}
      """)
  int clearExpiredPointersForDeliveriesIssuedBefore(
      @Param("cutoff") Instant cutoff,
      @Param("now") Instant now);

  @Delete("""
      DELETE FROM content.popup_deliveries delivery
      WHERE delivery.issued_at < #{cutoff}
        AND NOT EXISTS (
          SELECT 1
          FROM content.popup_campaign_user_states state
          WHERE state.active_delivery_id = delivery.id
        )
      """)
  int deleteIssuedBefore(@Param("cutoff") Instant cutoff);

  @Select("""
      SELECT *
      FROM content.popup_deliveries
      WHERE user_id = #{userId}
        AND token_hash = #{tokenHash}
      """)
  Optional<PopupDeliveryEntity> findIdentityByUserAndTokenHash(
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash);

  @Select("""
      SELECT *
      FROM content.popup_deliveries
      WHERE id = #{id}
        AND user_id = #{userId}
        AND token_hash = #{tokenHash}
      FOR UPDATE
      """)
  Optional<PopupDeliveryEntity> findByIdAndCredentialForUpdate(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash);

  @Select("""
      SELECT *
      FROM content.popup_deliveries
      WHERE user_id = #{userId}
        AND status IN ('ISSUED', 'SHOWN')
        AND invalidated_at IS NULL
      """)
  Optional<PopupDeliveryEntity> findActiveByUserId(@Param("userId") UUID userId);

  @Select("""
      SELECT *
      FROM content.popup_deliveries
      WHERE id = #{id}
      FOR UPDATE
      """)
  Optional<PopupDeliveryEntity> findByIdForUpdate(@Param("id") UUID id);

  @Update("""
      UPDATE content.popup_deliveries
      SET status = 'EXPIRED', invalidated_at = #{now}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND status IN ('ISSUED', 'SHOWN')
        AND invalidated_at IS NULL
        AND expires_at <= #{now}
      """)
  int expireActive(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_deliveries
      SET status = 'SHOWN', shown_at = #{now}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND token_hash = #{tokenHash}
        AND status = 'ISSUED'
        AND shown_at IS NULL
        AND invalidated_at IS NULL
        AND expires_at > #{now}
      """)
  int markShown(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_deliveries
      SET status = 'CLOSED', closed_at = #{now}, close_reason = #{reason}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND token_hash = #{tokenHash}
        AND status = 'SHOWN'
        AND shown_at IS NOT NULL
        AND closed_at IS NULL
        AND invalidated_at IS NULL
        AND expires_at > #{now}
      """)
  int markClosed(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash,
      @Param("reason") String reason,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_deliveries
      SET status = 'CLICKED', clicked_at = #{now}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND token_hash = #{tokenHash}
        AND status = 'SHOWN'
        AND shown_at IS NOT NULL
        AND clicked_at IS NULL
        AND invalidated_at IS NULL
        AND expires_at > #{now}
      """)
  int markClicked(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_deliveries
      SET status = 'EXPIRED', invalidated_at = #{now}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND token_hash = #{tokenHash}
        AND status = 'ISSUED'
        AND invalidated_at IS NULL
        AND expires_at <= #{now}
      """)
  int expireIssued(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_deliveries
      SET status = 'INVALIDATED', invalidated_at = #{now}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND token_hash = #{tokenHash}
        AND status IN ('ISSUED', 'SHOWN')
        AND invalidated_at IS NULL
      """)
  int invalidateActive(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("tokenHash") String tokenHash,
      @Param("now") Instant now);

  @Insert("""
      INSERT INTO content.popup_deliveries (
        id, queue_session_id, campaign_id, user_id, revision_id,
        token_hash, status, page_key, device_class, issued_at, expires_at
      ) VALUES (
        #{id}, #{queueSessionId}, #{campaignId}, #{userId}, #{revisionId},
        #{tokenHash}, #{status}, #{pageKey}, #{deviceClass}, #{issuedAt}, #{expiresAt}
      )
      ON CONFLICT DO NOTHING
      """)
  int insertIfAbsent(PopupDeliveryEntity delivery);
}
