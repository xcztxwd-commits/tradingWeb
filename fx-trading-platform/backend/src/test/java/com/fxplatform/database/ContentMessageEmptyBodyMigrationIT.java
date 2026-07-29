package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ContentMessageEmptyBodyMigrationIT {

  private static final OffsetDateTime CREATED_AT =
      OffsetDateTime.of(2026, 7, 1, 1, 2, 3, 0, ZoneOffset.UTC);
  private static final OffsetDateTime UPDATED_AT =
      OffsetDateTime.of(2026, 7, 3, 7, 8, 9, 0, ZoneOffset.UTC);

  @Test
  void v16EmptyBodyBackfillsToValidEmptyParagraphAndRetainsLegacyMessage() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "16");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID actorId = insertUser(jdbc);
      UUID messageId = insertEmptyBodyMessage(jdbc, actorId);

      PostgresMigrationTestSupport.migrate(postgres, null);

      assertThat(jdbc.queryForObject(
          "select count(*) from flyway_schema_history where version = '63' and success = true",
          Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject(
          "select count(*) from content.messages where id = ?", Integer.class, messageId))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject(
          "select body from content.messages where id = ?", String.class, messageId))
          .isEmpty();

      EmptyBodyBackfill migrated = jdbc.queryForObject("""
          select
            revision.body_document #>> '{type}' as document_type,
            jsonb_array_length(revision.body_document -> 'content') as document_child_count,
            revision.body_document #>> '{content,0,type}' as paragraph_type,
            coalesce(
              jsonb_array_length(revision.body_document #> '{content,0,content}'),
              0
            ) as paragraph_child_count,
            revision.sanitized_html
          from content.content_items item
          join content.content_revisions revision
            on revision.id = item.current_revision_id
          where item.id = ?
          """, (rs, rowNum) -> new EmptyBodyBackfill(
              rs.getString("document_type"),
              rs.getInt("document_child_count"),
              rs.getString("paragraph_type"),
              rs.getInt("paragraph_child_count"),
              rs.getString("sanitized_html")), messageId);

      assertThat(migrated.documentType()).isEqualTo("doc");
      assertThat(migrated.documentChildCount()).isEqualTo(1);
      assertThat(migrated.paragraphType()).isEqualTo("paragraph");
      assertThat(migrated.paragraphChildCount())
          .as("an empty paragraph must not contain an empty text node")
          .isZero();
      assertThat(migrated.sanitizedHtml()).isEqualTo("<p></p>");
    }
  }

  private static UUID insertUser(JdbcTemplate jdbc) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, 'empty-body-migration@test.local', 'test-hash', 'ACTIVE', 'USER', ?, ?)
        """, id, CREATED_AT, CREATED_AT);
    return id;
  }

  private static UUID insertEmptyBodyMessage(JdbcTemplate jdbc, UUID actorId) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into content.messages (
          id, target_user_id, title, body, message_type, status, sent_by,
          published_at, created_at, updated_at
        ) values (?, null, 'Empty legacy message', '', 'SYSTEM', 'DRAFT', ?, null, ?, ?)
        """, id, actorId, CREATED_AT, UPDATED_AT);
    return id;
  }

  private record EmptyBodyBackfill(
      String documentType,
      int documentChildCount,
      String paragraphType,
      int paragraphChildCount,
      String sanitizedHtml) {
  }
}
