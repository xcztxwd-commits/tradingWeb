package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MessagePublicationRepository extends FxBaseMapper<MessagePublicationEntity> {

  @Select("""
      SELECT *
      FROM content.message_publications
      WHERE id = #{id}
      FOR UPDATE
      """)
  MessagePublicationEntity selectByIdForUpdate(@Param("id") UUID id);

  @Select("""
      SELECT *
      FROM content.message_publications
      WHERE source_campaign_id = #{campaignId}
      FOR UPDATE
      """)
  Optional<MessagePublicationEntity> findBySourceCampaignIdForUpdate(
      @Param("campaignId") UUID campaignId);

  @Select("""
      SELECT *
      FROM content.message_publications
      WHERE lifecycle_status = 'SCHEDULED'
        AND deleted_at IS NULL
        AND scheduled_at <= #{now}
      ORDER BY scheduled_at ASC, id ASC
      FOR UPDATE SKIP LOCKED
      """)
  List<MessagePublicationEntity> findDueForUpdate(@Param("now") Instant now);
}
