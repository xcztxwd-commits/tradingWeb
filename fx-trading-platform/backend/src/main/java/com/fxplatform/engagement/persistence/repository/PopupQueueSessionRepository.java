package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.engagement.persistence.entity.PopupQueueSessionEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PopupQueueSessionRepository extends FxBaseMapper<PopupQueueSessionEntity> {

  @Insert("""
      INSERT INTO content.popup_queue_sessions (
        id, user_id, trigger_type, surface_page_key, device_class,
        max_items, issued_count, created_at, expires_at
      ) VALUES (
        #{id}, #{userId}, #{triggerType}, #{surfacePageKey}, #{deviceClass},
        #{maxItems}, #{issuedCount}, #{createdAt}, #{expiresAt}
      )
      ON CONFLICT (id) DO NOTHING
      """)
  int insertIfAbsent(PopupQueueSessionEntity session);

  @Select("""
      SELECT *
      FROM content.popup_queue_sessions
      WHERE id = #{id}
      FOR UPDATE
      """)
  Optional<PopupQueueSessionEntity> findByIdForUpdate(@Param("id") UUID id);

  @Update("""
      UPDATE content.popup_queue_sessions
      SET terminated_at = #{now}, terminated_reason = #{reason}
      WHERE id = #{id}
        AND user_id = #{userId}
        AND terminated_at IS NULL
        AND terminated_reason IS NULL
      """)
  int terminate(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("reason") String reason,
      @Param("now") Instant now);

  @Update("""
      UPDATE content.popup_queue_sessions
      SET issued_count = issued_count + 1
      WHERE id = #{id}
        AND user_id = #{userId}
        AND issued_count = #{expectedIssuedCount}
        AND issued_count < max_items
        AND terminated_at IS NULL
        AND expires_at > #{now}
      """)
  int incrementIssued(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("expectedIssuedCount") int expectedIssuedCount,
      @Param("now") Instant now);
}
