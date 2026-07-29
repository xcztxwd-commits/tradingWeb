package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.engagement.persistence.entity.MessageReceiptEntity;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.query.UserMessageRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MessageReceiptRepository {

  String VISIBLE_MESSAGES = """
      FROM auth.users recipient
      JOIN content.message_publications publication
        ON publication.lifecycle_status = 'SENT'
       AND publication.deleted_at IS NULL
       AND publication.sent_at <= #{now}
      JOIN content.content_items content_item
        ON content_item.id = publication.content_item_id
       AND content_item.current_revision_id IS NOT NULL
      JOIN content.content_revisions revision
        ON revision.id = content_item.current_revision_id
       AND revision.content_item_id = content_item.id
      LEFT JOIN content.message_receipts receipt
        ON receipt.publication_id = publication.id
       AND receipt.user_id = #{userId}
      WHERE recipient.id = #{userId}
        AND recipient.role = 'USER'
        AND (
          (
            publication.audience_type = 'ALL'
            AND recipient.created_at <= publication.audience_cutoff_at
          )
          OR (
            publication.audience_type = 'SELECTED'
            AND EXISTS (
              SELECT 1
              FROM content.message_targets target
              WHERE target.publication_id = publication.id
                AND target.user_id = #{userId}
            )
          )
        )
        AND receipt.hidden_at IS NULL
      """;

  String ELIGIBLE_PUBLICATION = """
      FROM auth.users recipient
      JOIN content.message_publications publication
        ON publication.id = #{publicationId}
       AND publication.lifecycle_status = 'SENT'
       AND publication.deleted_at IS NULL
       AND publication.sent_at <= #{now}
      JOIN content.content_items content_item
        ON content_item.id = publication.content_item_id
       AND content_item.current_revision_id IS NOT NULL
      JOIN content.content_revisions revision
        ON revision.id = content_item.current_revision_id
       AND revision.content_item_id = content_item.id
      WHERE recipient.id = #{userId}
        AND recipient.role = 'USER'
        AND (
          (
            publication.audience_type = 'ALL'
            AND recipient.created_at <= publication.audience_cutoff_at
          )
          OR (
            publication.audience_type = 'SELECTED'
            AND EXISTS (
              SELECT 1
              FROM content.message_targets target
              WHERE target.publication_id = publication.id
                AND target.user_id = #{userId}
            )
          )
        )
      """;

  @Select("""
      SELECT
        publication.id AS publication_id,
        content_item.id AS content_item_id,
        revision.id AS revision_id,
        publication.source_type,
        publication.category,
        publication.sent_at,
        COALESCE(receipt.delivered_at, publication.sent_at) AS delivered_at,
        revision.title,
        revision.sanitized_html,
        revision.cover_asset_id,
        revision.cta_label,
        revision.cta_route_key,
        revision.cta_params::text AS cta_params,
        receipt.read_at,
        receipt.read_source
      """ + VISIBLE_MESSAGES + """
      AND (#{unreadOnly} = FALSE OR receipt.read_at IS NULL)
      ORDER BY publication.sent_at DESC, publication.id DESC
      LIMIT #{limit}
      OFFSET #{offset}
      """)
  List<UserMessageRow> findVisibleMessages(
      @Param("userId") UUID userId,
      @Param("now") Instant now,
      @Param("unreadOnly") boolean unreadOnly,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select("""
      SELECT COUNT(*)
      """ + VISIBLE_MESSAGES + """
      AND (#{unreadOnly} = FALSE OR receipt.read_at IS NULL)
      """)
  long countVisibleMessages(
      @Param("userId") UUID userId,
      @Param("now") Instant now,
      @Param("unreadOnly") boolean unreadOnly);

  @Select(value = """
      WITH eligible AS MATERIALIZED (
        SELECT publication.id, publication.sent_at
        """ + ELIGIBLE_PUBLICATION + """
          AND NOT EXISTS (
            SELECT 1
            FROM content.message_receipts hidden_receipt
            WHERE hidden_receipt.publication_id = publication.id
              AND hidden_receipt.user_id = #{userId}
              AND hidden_receipt.hidden_at IS NOT NULL
          )
      ), mutated AS (
        INSERT INTO content.message_receipts AS existing (
          publication_id, user_id, delivered_at, read_at, read_source, updated_at
        )
        SELECT id, #{userId}, sent_at, #{now}, 'USER', #{now}
        FROM eligible
        ON CONFLICT (publication_id, user_id) DO UPDATE SET
          read_at = EXCLUDED.read_at,
          read_source = EXCLUDED.read_source,
          updated_at = EXCLUDED.updated_at
        WHERE existing.read_at IS NULL
          AND existing.hidden_at IS NULL
        RETURNING 1
      )
      SELECT
        EXISTS (SELECT 1 FROM eligible) AS visible,
        EXISTS (SELECT 1 FROM mutated) AS changed
      """, affectData = true)
  ReceiptMutation markReadIfVisible(
      @Param("userId") UUID userId,
      @Param("publicationId") UUID publicationId,
      @Param("now") Instant now);

  @Select(value = """
      WITH eligible AS MATERIALIZED (
        SELECT publication.id
        """ + ELIGIBLE_PUBLICATION + """
          AND NOT EXISTS (
            SELECT 1
            FROM content.message_receipts hidden_receipt
            WHERE hidden_receipt.publication_id = publication.id
              AND hidden_receipt.user_id = #{userId}
              AND hidden_receipt.hidden_at IS NOT NULL
          )
      ), mutated AS (
        UPDATE content.message_receipts AS existing
        SET read_at = NULL,
            read_source = NULL,
            updated_at = #{now}
        FROM eligible
        WHERE existing.publication_id = eligible.id
          AND existing.user_id = #{userId}
          AND existing.read_at IS NOT NULL
        RETURNING 1
      )
      SELECT
        EXISTS (SELECT 1 FROM eligible) AS visible,
        EXISTS (SELECT 1 FROM mutated) AS changed
      """, affectData = true)
  ReceiptMutation markUnreadIfVisible(
      @Param("userId") UUID userId,
      @Param("publicationId") UUID publicationId,
      @Param("now") Instant now);

  @Select(value = """
      WITH eligible AS MATERIALIZED (
        SELECT publication.id, publication.sent_at
        """ + ELIGIBLE_PUBLICATION + """
      ), mutated AS (
        INSERT INTO content.message_receipts AS existing (
          publication_id, user_id, delivered_at, hidden_at, updated_at
        )
        SELECT id, #{userId}, sent_at, #{now}, #{now}
        FROM eligible
        ON CONFLICT (publication_id, user_id) DO UPDATE SET
          hidden_at = EXCLUDED.hidden_at,
          updated_at = EXCLUDED.updated_at
        WHERE existing.hidden_at IS NULL
        RETURNING 1
      )
      SELECT
        EXISTS (SELECT 1 FROM eligible) AS visible,
        EXISTS (SELECT 1 FROM mutated) AS changed
      """, affectData = true)
  ReceiptMutation hideIfEligible(
      @Param("userId") UUID userId,
      @Param("publicationId") UUID publicationId,
      @Param("now") Instant now);

  @Insert("""
      INSERT INTO content.message_receipts AS existing (
        publication_id, user_id, delivered_at, read_at, read_source, updated_at
      )
      SELECT
        publication.id,
        #{userId},
        publication.sent_at,
        #{now},
        'READ_ALL',
        #{now}
      """ + VISIBLE_MESSAGES + """
      AND receipt.read_at IS NULL
      ON CONFLICT (publication_id, user_id) DO UPDATE SET
        read_at = EXCLUDED.read_at,
        read_source = EXCLUDED.read_source,
        updated_at = EXCLUDED.updated_at
      WHERE existing.hidden_at IS NULL
        AND existing.read_at IS NULL
      """)
  int markAllVisibleRead(
      @Param("userId") UUID userId,
      @Param("now") Instant now);

  @Select("""
      SELECT *
      FROM content.message_publications
      WHERE source_campaign_id = #{campaignId}
        AND source_type = 'CAMPAIGN'
        AND lifecycle_status = 'SENT'
        AND deleted_at IS NULL
        AND sent_at <= #{shownAt}
      """)
  Optional<MessagePublicationEntity> findLinkedSentPublication(
      @Param("campaignId") UUID campaignId,
      @Param("shownAt") Instant shownAt);

  @Insert("""
      INSERT INTO content.message_receipts AS existing (
        publication_id, user_id, delivered_at, read_at, read_source, updated_at
      )
      VALUES (#{publicationId}, #{userId}, #{deliveredAt}, #{readAt}, 'POPUP', #{readAt})
      ON CONFLICT (publication_id, user_id) DO UPDATE SET
        read_at = EXCLUDED.read_at,
        read_source = EXCLUDED.read_source,
        updated_at = EXCLUDED.updated_at
      WHERE existing.read_at IS NULL
      """)
  int markReadFromPopup(
      @Param("publicationId") UUID publicationId,
      @Param("userId") UUID userId,
      @Param("deliveredAt") Instant deliveredAt,
      @Param("readAt") Instant readAt);

  @Insert("""
      INSERT INTO content.message_receipts (publication_id, user_id, delivered_at)
      VALUES (#{publicationId}, #{userId}, #{deliveredAt})
      ON CONFLICT (publication_id, user_id) DO NOTHING
      """)
  int insertIfAbsent(
      @Param("publicationId") UUID publicationId,
      @Param("userId") UUID userId,
      @Param("deliveredAt") Instant deliveredAt);

  @Select("""
      SELECT publication_id, user_id, delivered_at, read_at, read_source, hidden_at, updated_at
      FROM content.message_receipts
      WHERE publication_id = #{publicationId}
        AND user_id = #{userId}
      """)
  Optional<MessageReceiptEntity> findByKey(
      @Param("publicationId") UUID publicationId,
      @Param("userId") UUID userId);

  record ReceiptMutation(boolean visible, boolean changed) {
  }
}
