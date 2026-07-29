package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.engagement.persistence.entity.MessageTargetEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MessageTargetRepository {

  @Select("""
      SELECT COUNT(*)
      FROM content.message_targets
      WHERE publication_id = #{publicationId}
      """)
  int countByPublicationId(@Param("publicationId") UUID publicationId);

  @Select("""
      SELECT target.user_id
      FROM content.message_targets target
      JOIN auth.users target_user
        ON target_user.id = target.user_id
       AND target_user.role = 'USER'
      WHERE target.publication_id = #{publicationId}
      ORDER BY target.user_id ASC
      """)
  List<UUID> findUserIdsByPublicationId(@Param("publicationId") UUID publicationId);

  @Delete("""
      DELETE FROM content.message_targets
      WHERE publication_id = #{publicationId}
      """)
  int deleteByPublicationId(@Param("publicationId") UUID publicationId);

  @Select("""
      SELECT COUNT(*)
      FROM content.popup_campaign_targets
      WHERE campaign_id = #{campaignId}
      """)
  int countByCampaignId(@Param("campaignId") UUID campaignId);

  @Insert("""
      INSERT INTO content.message_targets (publication_id, user_id)
      SELECT #{publicationId}, target.user_id
      FROM content.popup_campaign_targets target
      WHERE target.campaign_id = #{campaignId}
      ON CONFLICT (publication_id, user_id) DO NOTHING
      """)
  int insertFromCampaign(
      @Param("publicationId") UUID publicationId,
      @Param("campaignId") UUID campaignId);

  @Insert("""
      INSERT INTO content.message_targets (publication_id, user_id)
      VALUES (#{publicationId}, #{userId})
      ON CONFLICT (publication_id, user_id) DO NOTHING
      """)
  int insertIfAbsent(
      @Param("publicationId") UUID publicationId,
      @Param("userId") UUID userId);

  @Select("""
      SELECT publication_id, user_id
      FROM content.message_targets
      WHERE publication_id = #{publicationId}
        AND user_id = #{userId}
      """)
  Optional<MessageTargetEntity> findByKey(
      @Param("publicationId") UUID publicationId,
      @Param("userId") UUID userId);
}
