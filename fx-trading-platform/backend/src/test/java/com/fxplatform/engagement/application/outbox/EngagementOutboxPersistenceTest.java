package com.fxplatform.engagement.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.application.message.MessagePublicationService;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "app.engagement.outbox.enabled=false",
    "spring.task.scheduling.enabled=false",
    "trading.pending-order-execution-enabled=false",
    "trading.protective-order-execution-enabled=false",
    "trading.funding.enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(EngagementOutboxPersistenceTest.RollbackProbeConfiguration.class)
class EngagementOutboxPersistenceTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired PopupCampaignService campaignService;
  @Autowired MessagePublicationService messageService;
  @Autowired RollbackProbe rollbackProbe;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired ObjectMapper objectMapper;
  @Autowired Clock clock;

  @Test
  void canonicalCampaignAndManualMessageMutationsAppendSafeOutboxEvents() throws Exception {
    UUID actorId = insertUser();
    Instant now = clock.instant();
    UUID campaignId = insertCampaign(
        actorId,
        PopupCampaignLifecycleStatus.DRAFT,
        now.minusSeconds(60),
        now.plusSeconds(3600));

    campaignService.publish(campaignId, actorId);
    campaignService.reconfigure(
        campaignId,
        AudienceType.ALL,
        false,
        now.plusSeconds(7200),
        5,
        2,
        Duration.ofMinutes(1),
        actorId);
    campaignService.pause(campaignId, actorId);
    campaignService.delete(campaignId, actorId);
    campaignService.restore(campaignId, actorId);

    assertThat(eventTypes(campaignId)).containsExactlyInAnyOrder(
        "CAMPAIGN_UPDATED",
        "CAMPAIGN_UPDATED",
        "CAMPAIGN_INVALIDATED",
        "CAMPAIGN_INVALIDATED",
        "CAMPAIGN_UPDATED");

    UUID scheduledId = insertCampaign(
        actorId,
        PopupCampaignLifecycleStatus.SCHEDULED,
        now.minusSeconds(1),
        now.plusSeconds(3600));
    assertThat(campaignService.activateDue()).isEqualTo(1);
    assertThat(eventTypes(scheduledId)).containsExactly("CAMPAIGN_UPDATED");

    UUID publicationId = insertManualMessage(actorId, now);
    messageService.send(publicationId, null, actorId);
    List<StoredEvent> messageEvents = events(publicationId);
    assertThat(messageEvents).singleElement().satisfies(event -> {
      assertThat(event.aggregateType()).isEqualTo("MESSAGE");
      assertThat(event.eventType()).isEqualTo("MESSAGE_UPDATED");
      assertThat(event.payload().path("updateType").asText()).isEqualTo("MESSAGE_UPDATED");
      assertThat(event.payload().toString()).doesNotContain("CAMPAIGN", "POPUP");
    });

    for (StoredEvent event : events(campaignId)) {
      assertThat(event.payload().properties().stream().map(java.util.Map.Entry::getKey).toList())
          .containsExactlyInAnyOrder(
              "updateType", "aggregateId", "occurredAt", "audienceType");
      assertThat(event.payload().toString().toLowerCase())
          .doesNotContain("body", "content", "token", "password", "credential", "hash");
    }
  }

  @Test
  void domainWriteAndOutboxAppendRollBackTogether() {
    UUID actorId = insertUser();
    Instant now = clock.instant();
    UUID campaignId = insertCampaign(
        actorId,
        PopupCampaignLifecycleStatus.DRAFT,
        now.minusSeconds(60),
        now.plusSeconds(3600));

    assertThatThrownBy(() -> rollbackProbe.publishThenFail(campaignId, actorId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("rollback probe");

    assertThat(jdbcTemplate.queryForObject(
        "select lifecycle_status from content.popup_campaigns where id = ?",
        String.class,
        campaignId)).isEqualTo("DRAFT");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from content.engagement_outbox where aggregate_id = ?",
        Integer.class,
        campaignId)).isZero();
  }

  private List<String> eventTypes(UUID aggregateId) {
    return events(aggregateId).stream().map(StoredEvent::eventType).toList();
  }

  private List<StoredEvent> events(UUID aggregateId) {
    return jdbcTemplate.query("""
        select aggregate_type, event_type, payload::text
        from content.engagement_outbox
        where aggregate_id = ?
        order by created_at, id
        """, (row, ignored) -> {
          try {
            return new StoredEvent(
                row.getString("aggregate_type"),
                row.getString("event_type"),
                objectMapper.readTree(row.getString("payload")));
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          }
        }, aggregateId);
  }

  private UUID insertUser() {
    UUID userId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', 'ACTIVE', 'USER', ?, ?)
        """, userId, "outbox-" + userId + "@example.test", timestamp(now), timestamp(now));
    return userId;
  }

  private UUID insertCampaign(
      UUID actorId,
      PopupCampaignLifecycleStatus status,
      Instant startAt,
      Instant endAt) {
    UUID campaignId = UUID.randomUUID();
    UUID contentItemId = insertContent(actorId, "POPUP_CAMPAIGN");
    Instant now = clock.instant();
    Instant publishedAt = status == PopupCampaignLifecycleStatus.SCHEDULED
        ? now.minusSeconds(60)
        : null;
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, sync_to_inbox,
          priority, display_scope, page_keys, device_scope, template_size, time_zone,
          start_at, end_at, max_total_impressions, max_daily_impressions,
          min_interval_seconds, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, 'Outbox campaign', ?, ?, 'ALL', false,
          1, 'ALL_BUSINESS_PAGES', '[]'::jsonb, 'ALL', 'MEDIUM', 'UTC',
          ?, ?, 3, 1, 60, ?, ?, ?, ?, ?, ?)
        """, campaignId, contentItemId, status.name(), timestamp(startAt), timestamp(endAt),
        timestamp(publishedAt), timestamp(publishedAt), actorId, actorId,
        timestamp(now), timestamp(now));
    return campaignId;
  }

  private UUID insertManualMessage(UUID actorId, Instant now) {
    UUID publicationId = UUID.randomUUID();
    UUID contentItemId = insertContent(actorId, "MESSAGE");
    jdbcTemplate.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, audience_type, lifecycle_status,
          category, created_by, updated_by, created_at, updated_at
        ) values (?, ?, 'MANUAL', 'ALL', 'DRAFT', 'NOTICE', ?, ?, ?, ?)
        """, publicationId, contentItemId, actorId, actorId, timestamp(now), timestamp(now));
    return publicationId;
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
        ) values (?, ?, 1, 'Outbox', '{}'::jsonb, '<p>Outbox</p>', ?, ?)
        """, revisionId, itemId, actorId, timestamp(now));
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        revisionId,
        itemId);
    return itemId;
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private record StoredEvent(String aggregateType, String eventType, JsonNode payload) {
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RollbackProbeConfiguration {

    @Bean
    RollbackProbe rollbackProbe(PopupCampaignService campaignService) {
      return new RollbackProbe(campaignService);
    }
  }

  static class RollbackProbe {

    private final PopupCampaignService campaignService;

    RollbackProbe(PopupCampaignService campaignService) {
      this.campaignService = campaignService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishThenFail(UUID campaignId, UUID actorId) {
      campaignService.publish(campaignId, actorId);
      throw new IllegalStateException("rollback probe");
    }
  }
}
