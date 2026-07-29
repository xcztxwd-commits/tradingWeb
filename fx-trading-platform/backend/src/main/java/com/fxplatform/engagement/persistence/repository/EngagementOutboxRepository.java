package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.engagement.persistence.entity.EngagementOutboxEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EngagementOutboxRepository extends FxBaseMapper<EngagementOutboxEntity> {

  @Select("""
      SELECT *
      FROM content.engagement_outbox
      WHERE published_at IS NULL
      ORDER BY created_at ASC, id ASC
      LIMIT #{limit}
      FOR UPDATE SKIP LOCKED
      """)
  List<EngagementOutboxEntity> claimUnpublished(@Param("limit") int limit);

  @Update("""
      UPDATE content.engagement_outbox
      SET published_at = #{publishedAt}, last_error = NULL
      WHERE id = #{id}
        AND published_at IS NULL
      """)
  int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

  @Update("""
      UPDATE content.engagement_outbox
      SET attempt_count = attempt_count + 1, last_error = #{lastError}
      WHERE id = #{id}
        AND published_at IS NULL
      """)
  int markFailed(@Param("id") UUID id, @Param("lastError") String lastError);

  @Select("""
      SELECT user_id
      FROM (
        SELECT target.user_id
        FROM content.popup_campaign_targets target
        WHERE #{aggregateType} = 'POPUP_CAMPAIGN'
          AND target.campaign_id = #{aggregateId}
        UNION ALL
        SELECT target.user_id
        FROM content.message_targets target
        WHERE #{aggregateType} = 'MESSAGE'
          AND target.publication_id = #{aggregateId}
      ) target_users
      ORDER BY user_id
      """)
  List<UUID> findTargetUserIds(
      @Param("aggregateType") String aggregateType,
      @Param("aggregateId") UUID aggregateId);
}
