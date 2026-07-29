package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.engagement.persistence.entity.PopupCampaignTargetEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PopupCampaignTargetRepository {

  @Delete("""
      DELETE FROM content.popup_campaign_targets
      WHERE campaign_id = #{campaignId}
      """)
  int deleteByCampaignId(@Param("campaignId") UUID campaignId);

  @Insert("""
      INSERT INTO content.popup_campaign_targets (campaign_id, user_id)
      VALUES (#{campaignId}, #{userId})
      ON CONFLICT (campaign_id, user_id) DO NOTHING
      """)
  int insertIfAbsent(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId);

  @Select("""
      SELECT campaign_id, user_id, created_at
      FROM content.popup_campaign_targets
      WHERE campaign_id = #{campaignId}
        AND user_id = #{userId}
      """)
  Optional<PopupCampaignTargetEntity> findByKey(
      @Param("campaignId") UUID campaignId,
      @Param("userId") UUID userId);

  @Select("""
      SELECT user_id
      FROM content.popup_campaign_targets
      WHERE campaign_id = #{campaignId}
      ORDER BY user_id
      """)
  List<UUID> findUserIdsByCampaignId(@Param("campaignId") UUID campaignId);

  @Select("""
      SELECT COUNT(*)
      FROM content.popup_campaign_targets target
      JOIN auth.users user_row
        ON user_row.id = target.user_id
       AND user_row.role = 'USER'
      WHERE target.campaign_id = #{campaignId}
      """)
  int countBusinessUsersByCampaignId(@Param("campaignId") UUID campaignId);

  @Select("""
      SELECT COUNT(*)
      FROM content.popup_campaign_targets
      WHERE campaign_id = #{campaignId}
      """)
  int countByCampaignId(@Param("campaignId") UUID campaignId);
}
