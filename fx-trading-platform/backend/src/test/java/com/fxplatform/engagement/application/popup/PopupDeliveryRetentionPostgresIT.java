package com.fxplatform.engagement.application.popup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignStatsRow;
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
class PopupDeliveryRetentionPostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired PopupDeliveryRetentionService retentionService;
  @Autowired CampaignAdminQueryRepository campaignQueryRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Clock clock;

  @Test
  void default365DayCleanupDeletesOnlyExpiredRawDeliveryDetails() {
    Instant now = clock.instant();
    UUID userId = insertUser(now);
    ContentFixture content = insertContent(userId, now);
    UUID campaignId = insertCampaign(userId, content.itemId(), now);
    UUID publicationId = insertPublication(userId, campaignId, content.itemId(), now);
    UUID oldDeliveryId = insertClosedDelivery(
        userId, campaignId, content.revisionId(), now.minus(Duration.ofDays(366)));
    UUID retainedDeliveryId = insertClosedDelivery(
        userId, campaignId, content.revisionId(), now.minus(Duration.ofDays(364)));
    insertUserState(userId, campaignId, now);
    insertReceipt(userId, publicationId, now);
    UUID auditId = insertAudit(userId, campaignId, content.revisionId(), now);

    CampaignStatsRow before = campaignQueryRepository.findStats(campaignId).orElseThrow();
    assertStateBackedSummary(before);
    assertThat(before.closedDeliveries()).isEqualTo(2);

    assertThat(retentionService.deleteExpiredRawDeliveries()).isOne();

    CampaignStatsRow after = campaignQueryRepository.findStats(campaignId).orElseThrow();
    assertStateBackedSummary(after);
    assertThat(after.closedDeliveries()).isOne();
    assertThat(count("content.popup_deliveries", "id", oldDeliveryId)).isZero();
    assertThat(count("content.popup_deliveries", "id", retainedDeliveryId)).isOne();
    assertThat(count("content.popup_campaign_user_states", "campaign_id", campaignId)).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select total_impressions
        from content.popup_campaign_user_states
        where campaign_id = ? and user_id = ?
        """, Integer.class, campaignId, userId)).isEqualTo(2);
    assertThat(count("content.message_receipts", "publication_id", publicationId)).isOne();
    assertThat(count("content.content_revisions", "id", content.revisionId())).isOne();
    assertThat(count("audit.audit_logs", "id", auditId)).isOne();
  }

  @Test
  void cleanupClearsAnExpiredOldLeaseThenDeletesOnlyItsRawDetail() {
    Instant now = clock.instant();
    UUID userId = insertUser(now);
    ContentFixture content = insertContent(userId, now);
    UUID campaignId = insertCampaign(userId, content.itemId(), now);
    Instant issuedAt = now.minus(Duration.ofDays(366));
    UUID deliveryId = insertIssuedDelivery(
        userId, campaignId, content.revisionId(), issuedAt);
    jdbcTemplate.update("""
        insert into content.popup_campaign_user_states (
          campaign_id, user_id, total_impressions, daily_bucket, daily_impressions,
          last_impression_at, opted_out_at, last_clicked_at,
          active_delivery_id, active_delivery_expires_at, version
        ) values (?, ?, 3, ?, 1, ?, ?, ?, ?, ?, 1)
        """, campaignId, userId, java.sql.Date.valueOf("2026-07-20"), timestamp(issuedAt),
        timestamp(issuedAt), timestamp(issuedAt), deliveryId,
        timestamp(issuedAt.plusSeconds(300)));

    assertThat(retentionService.deleteExpiredRawDeliveries()).isOne();

    assertThat(count("content.popup_deliveries", "id", deliveryId)).isZero();
    LeaseState state = jdbcTemplate.queryForObject("""
        select active_delivery_id, active_delivery_expires_at, version,
               total_impressions, opted_out_at is not null, last_clicked_at is not null
        from content.popup_campaign_user_states
        where campaign_id = ? and user_id = ?
        """, (row, index) -> new LeaseState(
            row.getObject(1, UUID.class),
            row.getTimestamp(2),
            row.getLong(3),
            row.getInt(4),
            row.getBoolean(5),
            row.getBoolean(6)), campaignId, userId);
    assertThat(state).isEqualTo(new LeaseState(null, null, 2L, 3, true, true));
  }

  @Test
  void cleanupPreservesAnOldDeliveryWhoseActiveLeaseHasNotExpired() {
    Instant now = clock.instant();
    UUID userId = insertUser(now);
    ContentFixture content = insertContent(userId, now);
    UUID campaignId = insertCampaign(userId, content.itemId(), now);
    Instant issuedAt = now.minus(Duration.ofDays(366));
    Instant expiresAt = now.plusSeconds(300);
    UUID deliveryId = insertIssuedDelivery(
        userId, campaignId, content.revisionId(), issuedAt, expiresAt);
    jdbcTemplate.update("""
        insert into content.popup_campaign_user_states (
          campaign_id, user_id, active_delivery_id, active_delivery_expires_at, version
        ) values (?, ?, ?, ?, 1)
        """, campaignId, userId, deliveryId, timestamp(expiresAt));

    retentionService.deleteExpiredRawDeliveries();

    assertThat(count("content.popup_deliveries", "id", deliveryId)).isOne();
    assertThat(jdbcTemplate.queryForObject("""
        select active_delivery_id
        from content.popup_campaign_user_states
        where campaign_id = ? and user_id = ?
        """, UUID.class, campaignId, userId)).isEqualTo(deliveryId);
    assertThat(jdbcTemplate.queryForObject("""
        select version
        from content.popup_campaign_user_states
        where campaign_id = ? and user_id = ?
        """, Long.class, campaignId, userId)).isOne();
  }

  private UUID insertUser(Instant now) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'retention-test-hash', 'ACTIVE', 'USER', ?, ?)
        """, id, "popup-retention-" + id + "@example.test", timestamp(now), timestamp(now));
    return id;
  }

  private ContentFixture insertContent(UUID actorId, Instant now) {
    UUID itemId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.content_items (id, content_kind, created_at, updated_at)
        values (?, 'POPUP_CAMPAIGN', ?, ?)
        """, itemId, timestamp(now), timestamp(now));
    jdbcTemplate.update("""
        insert into content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) values (?, ?, 1, 'Retention acceptance', '{}'::jsonb,
                  '<p>Retention acceptance</p>', ?, ?)
        """, revisionId, itemId, actorId, timestamp(now));
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        revisionId,
        itemId);
    return new ContentFixture(itemId, revisionId);
  }

  private UUID insertCampaign(UUID actorId, UUID contentItemId, Instant now) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, priority,
          start_at, end_at, max_total_impressions, max_daily_impressions,
          min_interval_seconds, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, ?, 'ACTIVE', 'ALL', 1, ?, ?, 10, 10, 0, ?, ?, ?, ?, ?, ?)
        """,
        id,
        "Retention " + id,
        contentItemId,
        timestamp(now.minus(Duration.ofDays(400))),
        timestamp(now.plus(Duration.ofDays(1))),
        timestamp(now.minus(Duration.ofDays(400))),
        timestamp(now.minus(Duration.ofDays(400))),
        actorId,
        actorId,
        timestamp(now),
        timestamp(now));
    return id;
  }

  private UUID insertPublication(
      UUID actorId,
      UUID campaignId,
      UUID contentItemId,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, source_campaign_id, audience_type,
          lifecycle_status, category, sent_at, audience_cutoff_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, 'CAMPAIGN', ?, 'ALL', 'SENT', 'CAMPAIGN', ?, ?, ?, ?, ?, ?)
        """, id, contentItemId, campaignId, timestamp(now.minus(Duration.ofDays(400))),
        timestamp(now.plus(Duration.ofDays(1))), actorId, actorId, timestamp(now), timestamp(now));
    return id;
  }

  private UUID insertClosedDelivery(
      UUID userId,
      UUID campaignId,
      UUID revisionId,
      Instant issuedAt) {
    UUID queueId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    Instant shownAt = issuedAt.plusSeconds(30);
    Instant closedAt = issuedAt.plusSeconds(60);
    jdbcTemplate.update("""
        insert into content.popup_queue_sessions (
          id, user_id, trigger_type, surface_page_key, device_class,
          max_items, issued_count, terminated_reason, created_at, expires_at, terminated_at
        ) values (?, ?, 'LOGIN', 'dashboard', 'PC', 3, 1, 'TEST_DONE', ?, ?, ?)
        """, queueId, userId, timestamp(issuedAt), timestamp(issuedAt.plusSeconds(300)),
        timestamp(closedAt));
    jdbcTemplate.update("""
        insert into content.popup_deliveries (
          id, queue_session_id, campaign_id, user_id, revision_id,
          token_hash, status, page_key, device_class, issued_at, expires_at,
          shown_at, closed_at, close_reason
        ) values (?, ?, ?, ?, ?, ?, 'CLOSED', 'dashboard', 'PC', ?, ?, ?, ?, 'USER_CLOSE')
        """, deliveryId, queueId, campaignId, userId, revisionId,
        randomTokenHash(), timestamp(issuedAt), timestamp(issuedAt.plusSeconds(300)),
        timestamp(shownAt), timestamp(closedAt));
    return deliveryId;
  }

  private UUID insertIssuedDelivery(
      UUID userId,
      UUID campaignId,
      UUID revisionId,
      Instant issuedAt) {
    return insertIssuedDelivery(
        userId, campaignId, revisionId, issuedAt, issuedAt.plusSeconds(300));
  }

  private UUID insertIssuedDelivery(
      UUID userId,
      UUID campaignId,
      UUID revisionId,
      Instant issuedAt,
      Instant expiresAt) {
    UUID queueId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.popup_queue_sessions (
          id, user_id, trigger_type, surface_page_key, device_class,
          max_items, issued_count, created_at, expires_at
        ) values (?, ?, 'LOGIN', 'dashboard', 'PC', 3, 1, ?, ?)
        """, queueId, userId, timestamp(issuedAt), timestamp(expiresAt));
    jdbcTemplate.update("""
        insert into content.popup_deliveries (
          id, queue_session_id, campaign_id, user_id, revision_id,
          token_hash, status, page_key, device_class, issued_at, expires_at
        ) values (?, ?, ?, ?, ?, ?, 'ISSUED', 'dashboard', 'PC', ?, ?)
        """, deliveryId, queueId, campaignId, userId, revisionId, randomTokenHash(),
        timestamp(issuedAt), timestamp(expiresAt));
    return deliveryId;
  }

  private void insertUserState(UUID userId, UUID campaignId, Instant now) {
    jdbcTemplate.update("""
        insert into content.popup_campaign_user_states (
          campaign_id, user_id, total_impressions, daily_bucket,
          daily_impressions, last_impression_at, opted_out_at, last_clicked_at, version
        ) values (?, ?, 2, ?, 2, ?, ?, ?, 1)
        """, campaignId, userId, java.sql.Date.valueOf("2026-07-20"), timestamp(now),
        timestamp(now), timestamp(now));
  }

  private void insertReceipt(UUID userId, UUID publicationId, Instant now) {
    jdbcTemplate.update("""
        insert into content.message_receipts (
          publication_id, user_id, delivered_at, read_at, read_source, updated_at
        ) values (?, ?, ?, ?, 'POPUP', ?)
        """, publicationId, userId, timestamp(now), timestamp(now), timestamp(now));
  }

  private UUID insertAudit(UUID actorId, UUID campaignId, UUID revisionId, Instant now) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into audit.audit_logs (
          id, actor_user_id, action, target_type, target_id, details, created_at
        ) values (?, ?, 'ENGAGEMENT_CAMPAIGN_PUBLISH', 'CAMPAIGN', ?,
                  jsonb_build_object('revisionId', ?), ?)
        """, id, actorId, campaignId.toString(), revisionId.toString(), timestamp(now));
    return id;
  }

  private int count(String table, String column, UUID value) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?",
        Integer.class,
        value);
  }

  private static void assertStateBackedSummary(CampaignStatsRow stats) {
    assertThat(stats.usersWithState()).isOne();
    assertThat(stats.totalImpressions()).isEqualTo(2);
    assertThat(stats.optedOutUsers()).isOne();
    assertThat(stats.clickedUsers()).isOne();
  }

  private static String randomTokenHash() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private record ContentFixture(UUID itemId, UUID revisionId) {
  }

  private record LeaseState(
      UUID activeDeliveryId,
      Timestamp activeDeliveryExpiresAt,
      long version,
      int totalImpressions,
      boolean optedOut,
      boolean clicked) {
  }
}
