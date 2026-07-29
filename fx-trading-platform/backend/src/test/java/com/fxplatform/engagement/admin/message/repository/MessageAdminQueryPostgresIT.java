package com.fxplatform.engagement.admin.message.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository.MessageDetailRow;
import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository.MessageSummaryRow;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "trading.pending-order-execution-enabled=false",
    "trading.protective-order-execution-enabled=false",
    "trading.funding.enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageAdminQueryPostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MessageAdminQueryRepository repository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Clock clock;

  @Test
  void listCountAndDetailQueriesExecuteWithManualFilters() {
    Instant now = clock.instant();
    UUID actorId = insertUser(now);
    MessageFixture fixture = insertManualMessage(actorId, now);

    List<MessageSummaryRow> unfiltered = repository.findMessages(null, null, 20, 0);
    assertThat(unfiltered).singleElement().satisfies(row -> {
      assertThat(row.id()).isEqualTo(fixture.publicationId());
      assertThat(row.lifecycleStatus()).isEqualTo(MessageLifecycleStatus.DRAFT);
      assertThat(row.audienceType()).isEqualTo(AudienceType.SELECTED);
      assertThat(row.revisionId()).isEqualTo(fixture.revisionId());
      assertThat(row.title()).isEqualTo("Smoke admin message");
      assertThat(row.targetCount()).isOne();
    });
    assertThat(repository.countMessages(null, null)).isOne();

    assertThat(repository.findMessages("DRAFT", "ADMIN MESSAGE", 20, 0))
        .extracting(MessageSummaryRow::id)
        .containsExactly(fixture.publicationId());
    assertThat(repository.countMessages("DRAFT", "ADMIN MESSAGE")).isOne();

    MessageDetailRow detail = repository.findMessage(fixture.publicationId()).orElseThrow();
    assertThat(detail.sourceType()).isEqualTo(MessageSourceType.MANUAL);
    assertThat(detail.revisionId()).isEqualTo(fixture.revisionId());
    assertThat(detail.title()).isEqualTo("Smoke admin message");
    assertThat(detail.sanitizedHtml()).isEqualTo("<p>Smoke admin message</p>");
    assertThat(detail.targetCount()).isOne();
  }

  private UUID insertUser(Instant now) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, created_at, updated_at)
        VALUES (?, ?, 'message-admin-query-hash', 'ACTIVE', 'USER', ?, ?)
        """, id, "message-admin-query-" + id + "@example.test", timestamp(now), timestamp(now));
    return id;
  }

  private MessageFixture insertManualMessage(UUID actorId, Instant now) {
    UUID itemId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID publicationId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO content.content_items (id, content_kind, created_at, updated_at)
        VALUES (?, 'MESSAGE', ?, ?)
        """, itemId, timestamp(now), timestamp(now));
    jdbcTemplate.update("""
        INSERT INTO content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) VALUES (?, ?, 1, 'Smoke admin message', '{"type":"doc"}'::jsonb,
                  '<p>Smoke admin message</p>', ?, ?)
        """, revisionId, itemId, actorId, timestamp(now));
    jdbcTemplate.update(
        "UPDATE content.content_items SET current_revision_id = ? WHERE id = ?",
        revisionId,
        itemId);
    jdbcTemplate.update("""
        INSERT INTO content.message_publications (
          id, content_item_id, source_type, audience_type, lifecycle_status,
          category, created_by, updated_by, created_at, updated_at
        ) VALUES (?, ?, 'MANUAL', 'SELECTED', 'DRAFT', 'NOTICE', ?, ?, ?, ?)
        """, publicationId, itemId, actorId, actorId, timestamp(now), timestamp(now));
    jdbcTemplate.update("""
        INSERT INTO content.message_targets (publication_id, user_id)
        VALUES (?, ?)
        """, publicationId, actorId);
    return new MessageFixture(publicationId, revisionId);
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private record MessageFixture(UUID publicationId, UUID revisionId) {
  }
}
