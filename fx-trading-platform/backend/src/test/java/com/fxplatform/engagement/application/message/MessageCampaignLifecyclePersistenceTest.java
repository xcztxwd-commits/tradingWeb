package com.fxplatform.engagement.application.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
class MessageCampaignLifecyclePersistenceTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MessagePublicationService publicationService;
  @Autowired PopupCampaignService campaignService;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Clock clock;

  @Test
  void cancellingScheduleReallyClearsScheduledAtInPostgres() {
    UUID actorId = insertUser();
    UUID contentItemId = insertContent(actorId, "MESSAGE");
    UUID publicationId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, audience_type, lifecycle_status,
          category, scheduled_at, created_by, updated_by, created_at, updated_at
        ) values (?, ?, 'MANUAL', 'ALL', 'SCHEDULED', 'NOTICE', ?, ?, ?, ?, ?)
        """, publicationId, contentItemId, timestamp(now.plus(Duration.ofHours(1))),
        actorId, actorId, timestamp(now.minusSeconds(1)), timestamp(now.minusSeconds(1)));

    publicationService.cancelSchedule(publicationId, actorId);

    assertThat(queryStatus(
        "select lifecycle_status from content.message_publications where id = ?",
        MessageLifecycleStatus.class,
        publicationId)).isEqualTo(MessageLifecycleStatus.DRAFT);
    assertThat(jdbcTemplate.queryForObject(
        "select scheduled_at is null from content.message_publications where id = ?",
        Boolean.class,
        publicationId)).isTrue();
  }

  @Test
  void restoringSentMessageReallyClearsDeletedAtInPostgres() {
    UUID actorId = insertUser();
    UUID contentItemId = insertContent(actorId, "MESSAGE");
    UUID publicationId = UUID.randomUUID();
    Instant now = clock.instant();
    Instant sentAt = now.minus(Duration.ofMinutes(5));
    jdbcTemplate.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, audience_type, lifecycle_status,
          category, sent_at, audience_cutoff_at, created_by, updated_by,
          created_at, updated_at, deleted_at
        ) values (?, ?, 'MANUAL', 'ALL', 'DELETED', 'NOTICE', ?, ?, ?, ?, ?, ?, ?)
        """, publicationId, contentItemId, timestamp(sentAt), timestamp(sentAt),
        actorId, actorId, timestamp(sentAt), timestamp(sentAt), timestamp(now.minusSeconds(1)));

    publicationService.restore(publicationId, actorId);

    assertThat(queryStatus(
        "select lifecycle_status from content.message_publications where id = ?",
        MessageLifecycleStatus.class,
        publicationId)).isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(jdbcTemplate.queryForObject(
        "select deleted_at is null from content.message_publications where id = ?",
        Boolean.class,
        publicationId)).isTrue();
  }

  @Test
  void campaignRestorePublishResumeAndEndReallyClearNullableLifecycleTimes() {
    UUID actorId = insertUser();
    UUID contentItemId = insertContent(actorId, "POPUP_CAMPAIGN");
    UUID campaignId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, sync_to_inbox,
          start_at, end_at, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at,
          paused_at, ended_at, deleted_at
        ) values (?, 'Lifecycle persistence', ?, 'DELETED', 'ALL', false,
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, campaignId, contentItemId,
        timestamp(now.minus(Duration.ofDays(1))), timestamp(now.plus(Duration.ofDays(1))),
        timestamp(now.minus(Duration.ofHours(2))), timestamp(now.minus(Duration.ofHours(1))),
        actorId, actorId, timestamp(now.minus(Duration.ofDays(1))), timestamp(now.minusSeconds(1)),
        timestamp(now.minus(Duration.ofMinutes(3))), timestamp(now.minus(Duration.ofMinutes(2))),
        timestamp(now.minusSeconds(1)));

    campaignService.restore(campaignId, actorId);
    assertCampaignTimes(campaignId, PopupCampaignLifecycleStatus.PAUSED, false, true, true);

    campaignService.publish(campaignId, actorId);
    assertCampaignTimes(campaignId, PopupCampaignLifecycleStatus.ACTIVE, true, true, true);

    campaignService.pause(campaignId, actorId);
    assertCampaignTimes(campaignId, PopupCampaignLifecycleStatus.PAUSED, false, true, true);

    campaignService.resume(campaignId, actorId);
    assertCampaignTimes(campaignId, PopupCampaignLifecycleStatus.ACTIVE, true, true, true);

    campaignService.end(campaignId, actorId);
    assertCampaignTimes(campaignId, PopupCampaignLifecycleStatus.ENDED, true, false, true);

    campaignService.delete(campaignId, actorId);
    campaignService.restore(campaignId, actorId);
    assertCampaignTimes(campaignId, PopupCampaignLifecycleStatus.PAUSED, false, true, true);
  }

  private void assertCampaignTimes(
      UUID campaignId,
      PopupCampaignLifecycleStatus status,
      boolean pausedAtNull,
      boolean endedAtNull,
      boolean deletedAtNull) {
    assertThat(queryStatus(
        "select lifecycle_status from content.popup_campaigns where id = ?",
        PopupCampaignLifecycleStatus.class,
        campaignId)).isEqualTo(status);
    Boolean timesMatch = jdbcTemplate.queryForObject("""
        select (paused_at is null) = ?,
               (ended_at is null) = ?,
               (deleted_at is null) = ?
        from content.popup_campaigns where id = ?
        """, (row, index) -> row.getBoolean(1) && row.getBoolean(2) && row.getBoolean(3),
        pausedAtNull, endedAtNull, deletedAtNull, campaignId);
    assertThat(timesMatch).isTrue();
  }

  private UUID insertUser() {
    UUID userId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', 'ACTIVE', 'USER', ?, ?)
        """, userId, "message-lifecycle-" + userId + "@example.test",
        timestamp(now), timestamp(now));
    return userId;
  }

  private UUID insertContent(UUID actorId, String kind) {
    UUID itemId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.content_items (id, content_kind, created_at, updated_at)
        values (?, ?, ?, ?)
        """, itemId, kind, timestamp(now), timestamp(now));
    jdbcTemplate.update("""
        insert into content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) values (?, ?, 1, 'Lifecycle', '{}'::jsonb, '<p>Lifecycle</p>', ?, ?)
        """, revisionId, itemId, actorId, timestamp(now));
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        revisionId,
        itemId);
    return itemId;
  }

  private <E extends Enum<E>> E queryStatus(
      String sql,
      Class<E> enumType,
      Object... args) {
    String value = jdbcTemplate.queryForObject(sql, String.class, args);
    return Enum.valueOf(enumType, value);
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }
}
