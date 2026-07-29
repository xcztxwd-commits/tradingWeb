package com.fxplatform.engagement.admin.message.repository;

import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MessageAdminQueryRepository {

  String MANUAL_ONLY = """
      publication.source_type = 'MANUAL'
      AND publication.source_campaign_id IS NULL
      """;

  String TARGET_COUNT = """
      CASE publication.audience_type
        WHEN 'SELECTED' THEN (
          SELECT COUNT(*)
          FROM content.message_targets target
          JOIN auth.users target_user
            ON target_user.id = target.user_id
           AND target_user.role = 'USER'
          WHERE target.publication_id = publication.id
        )
        ELSE (
          SELECT COUNT(*)
          FROM auth.users target_user
          WHERE target_user.role = 'USER'
            AND (
              publication.audience_cutoff_at IS NULL
              OR target_user.created_at <= publication.audience_cutoff_at
            )
        )
      END
      """;

  String FILTERS = """
      WHERE
      """ + MANUAL_ONLY + """
        AND (CAST(#{lifecycleStatus} AS varchar) IS NULL
             OR publication.lifecycle_status = CAST(#{lifecycleStatus} AS varchar))
        AND (CAST(#{titleQuery} AS varchar) IS NULL
             OR LOWER(revision.title) LIKE '%' || LOWER(CAST(#{titleQuery} AS varchar)) || '%')
      """;

  @Select("""
      SELECT
        publication.id,
        publication.lifecycle_status,
        publication.audience_type,
        publication.category,
        publication.scheduled_at,
        publication.sent_at,
        revision.id AS revision_id,
        revision.title,
        """ + TARGET_COUNT + """
          AS target_count,
        publication.updated_at
      FROM content.message_publications publication
      JOIN content.content_items item
        ON item.id = publication.content_item_id
       AND item.current_revision_id IS NOT NULL
      JOIN content.content_revisions revision
        ON revision.id = item.current_revision_id
       AND revision.content_item_id = item.id
      """ + FILTERS + """
      ORDER BY publication.updated_at DESC, publication.id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<MessageSummaryRow> findMessages(
      @Param("lifecycleStatus") String lifecycleStatus,
      @Param("titleQuery") String titleQuery,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select("""
      SELECT COUNT(*)
      FROM content.message_publications publication
      JOIN content.content_items item
        ON item.id = publication.content_item_id
       AND item.current_revision_id IS NOT NULL
      JOIN content.content_revisions revision
        ON revision.id = item.current_revision_id
       AND revision.content_item_id = item.id
      """ + FILTERS)
  long countMessages(
      @Param("lifecycleStatus") String lifecycleStatus,
      @Param("titleQuery") String titleQuery);

  @Select("""
      SELECT
        publication.id,
        publication.content_item_id,
        publication.source_type,
        publication.audience_type,
        publication.lifecycle_status,
        publication.category,
        publication.scheduled_at,
        publication.sent_at,
        publication.audience_cutoff_at,
        publication.deleted_at,
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
        publication.created_at,
        publication.updated_at
      FROM content.message_publications publication
      JOIN content.content_items item
        ON item.id = publication.content_item_id
       AND item.current_revision_id IS NOT NULL
      JOIN content.content_revisions revision
        ON revision.id = item.current_revision_id
       AND revision.content_item_id = item.id
      WHERE publication.id = #{publicationId}
        AND
      """ + MANUAL_ONLY)
  Optional<MessageDetailRow> findMessage(@Param("publicationId") UUID publicationId);

  record MessageSummaryRow(
      UUID id,
      MessageLifecycleStatus lifecycleStatus,
      AudienceType audienceType,
      String category,
      Instant scheduledAt,
      Instant sentAt,
      UUID revisionId,
      String title,
      Long targetCount,
      Instant updatedAt
  ) {
  }

  record MessageDetailRow(
      UUID id,
      UUID contentItemId,
      MessageSourceType sourceType,
      AudienceType audienceType,
      MessageLifecycleStatus lifecycleStatus,
      String category,
      Instant scheduledAt,
      Instant sentAt,
      Instant audienceCutoffAt,
      Instant deletedAt,
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
}
