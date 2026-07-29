package com.fxplatform.engagement.admin.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignDetailRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignStatsRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignUserRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
class CampaignAdminQueryPersistenceTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired CampaignAdminQueryRepository repository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Clock clock;

  @Test
  void postgresMapsCampaignDetailStatsAndAudienceUsersWithoutExposingDeliverySecrets() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID actorId = insertUser("ADMIN", UserStatus.ACTIVE, now.minus(Duration.ofDays(3)));
    UUID targetUserId = insertUser("USER", UserStatus.FROZEN, now.minus(Duration.ofDays(2)));
    insertUser("USER", UserStatus.ACTIVE, now.minus(Duration.ofDays(1)));
    ContentFixture content = insertContent(actorId, now);
    UUID campaignId = insertCampaign(actorId, content.itemId(), "SELECTED", now);
    jdbcTemplate.update(
        "insert into content.popup_campaign_targets (campaign_id, user_id) values (?, ?)",
        campaignId,
        targetUserId);
    jdbcTemplate.update("""
        insert into content.popup_campaign_user_states (
          campaign_id, user_id, total_impressions, daily_bucket,
          daily_impressions, last_impression_at, last_clicked_at, version
        ) values (?, ?, 3, ?, 1, ?, ?, 2)
        """, campaignId, targetUserId, now.atZone(ZoneOffset.UTC).toLocalDate(),
        timestamp(now.minus(Duration.ofMinutes(2))),
        timestamp(now.minus(Duration.ofMinutes(1))));
    insertShownDelivery(campaignId, targetUserId, content.revisionId(), now);

    CampaignDetailRow detail = repository.findCampaign(campaignId).orElseThrow();
    CampaignStatsRow stats = repository.findStats(campaignId).orElseThrow();
    CampaignUserRow user = repository.findUsers(campaignId, 20, 0).getFirst();

    assertThat(repository.findCampaigns(null, null, null, null, null, null, 20, 0))
        .extracting(row -> row.id())
        .contains(campaignId);
    assertThat(detail.revisionId()).isEqualTo(content.revisionId());
    assertThat(detail.title()).isEqualTo("Campaign title");
    assertThat(detail.targetCount()).isEqualTo(1);
    assertThat(stats.targetCount()).isEqualTo(1);
    assertThat(stats.usersWithState()).isEqualTo(1);
    assertThat(stats.totalImpressions()).isEqualTo(3);
    assertThat(stats.shownDeliveries()).isEqualTo(1);
    assertThat(repository.countUsers(campaignId)).isEqualTo(1);
    assertThat(user.userId()).isEqualTo(targetUserId);
    assertThat(user.status()).isEqualTo(UserStatus.FROZEN);
    assertThat(user.totalImpressions()).isEqualTo(3);
    assertThat(user.shownDeliveries()).isEqualTo(1);
  }

  @Test
  void allAudienceIncludesBusinessUsersCreatedDuringTheWindowAndExcludesAdminsAndLateUsers() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID actorId = insertUser("ADMIN", UserStatus.ACTIVE, now.minus(Duration.ofDays(2)));
    UUID inWindowUserId = insertUser("USER", UserStatus.ACTIVE, now);
    insertUser("USER", UserStatus.ACTIVE, now.plus(Duration.ofDays(2)));
    ContentFixture content = insertContent(actorId, now);
    UUID campaignId = insertCampaign(actorId, content.itemId(), "ALL", now);

    assertThat(repository.countUsers(campaignId)).isEqualTo(1);
    assertThat(repository.findCampaign(campaignId).orElseThrow().targetCount()).isEqualTo(1);
    assertThat(repository.findUsers(campaignId, 20, 0))
        .extracting(CampaignUserRow::userId)
        .containsExactly(inWindowUserId);
  }

  @Test
  void campaignPageAndTotalShareAudienceSyncAndHalfOpenEffectiveWindowFilters() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID actorId = insertUser("ADMIN", UserStatus.ACTIVE, now.minus(Duration.ofDays(2)));
    Instant effectiveFrom = now.minus(Duration.ofHours(1));
    Instant effectiveTo = now.plus(Duration.ofHours(1));
    Instant firstPublishedAt = now.minus(Duration.ofMinutes(30));

    UUID matching = insertCampaign(
        actorId, insertContent(actorId, now).itemId(), "Matching", "SELECTED", true,
        effectiveFrom.minusSeconds(1), effectiveTo.plusSeconds(1), firstPublishedAt, now);
    insertCampaign(
        actorId, insertContent(actorId, now).itemId(), "Wrong audience", "ALL", true,
        effectiveFrom.minusSeconds(1), effectiveTo.plusSeconds(1), null, now);
    insertCampaign(
        actorId, insertContent(actorId, now).itemId(), "Wrong sync", "SELECTED", false,
        effectiveFrom.minusSeconds(1), effectiveTo.plusSeconds(1), null, now);
    insertCampaign(
        actorId, insertContent(actorId, now).itemId(), "Ends at from", "SELECTED", true,
        effectiveFrom.minus(Duration.ofHours(1)), effectiveFrom, null, now);
    insertCampaign(
        actorId, insertContent(actorId, now).itemId(), "Starts at to", "SELECTED", true,
        effectiveTo, effectiveTo.plus(Duration.ofHours(1)), null, now);

    var rows = repository.findCampaigns(
        null, null, "SELECTED", true, effectiveFrom, effectiveTo, 20, 0);

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.id()).isEqualTo(matching);
      assertThat(row.firstPublishedAt()).isEqualTo(firstPublishedAt);
    });
    assertThat(repository.countCampaigns(
        null, null, "SELECTED", true, effectiveFrom, effectiveTo)).isEqualTo(1);
  }

  private UUID insertUser(String role, UserStatus status, Instant createdAt) {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', ?, ?, ?, ?)
        """, userId, "campaign-admin-" + userId + "@example.test", status.name(), role,
        timestamp(createdAt), timestamp(createdAt));
    return userId;
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
        ) values (?, ?, 1, 'Campaign title', '{"type":"doc","content":[]}'::jsonb,
          '<p>Campaign body</p>', ?, ?)
        """, revisionId, itemId, actorId, timestamp(now));
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        revisionId,
        itemId);
    return new ContentFixture(itemId, revisionId);
  }

  private UUID insertCampaign(UUID actorId, UUID itemId, String audienceType, Instant now) {
    return insertCampaign(
        actorId,
        itemId,
        "Admin query smoke",
        audienceType,
        false,
        now.minus(Duration.ofHours(1)),
        now.plus(Duration.ofDays(1)),
        null,
        now);
  }

  private UUID insertCampaign(
      UUID actorId,
      UUID itemId,
      String name,
      String audienceType,
      boolean syncToInbox,
      Instant startAt,
      Instant endAt,
      Instant firstPublishedAt,
      Instant now) {
    UUID campaignId = UUID.randomUUID();
    String lifecycleStatus = firstPublishedAt == null ? "DRAFT" : "ACTIVE";
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, sync_to_inbox,
          start_at, end_at, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, campaignId, name, itemId, lifecycleStatus, audienceType, syncToInbox,
        timestamp(startAt), timestamp(endAt),
        firstPublishedAt == null ? null : timestamp(firstPublishedAt),
        firstPublishedAt == null ? null : timestamp(firstPublishedAt), actorId, actorId,
        timestamp(now.minus(Duration.ofHours(1))), timestamp(now));
    return campaignId;
  }

  private void insertShownDelivery(
      UUID campaignId,
      UUID userId,
      UUID revisionId,
      Instant now) {
    UUID queueId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.popup_queue_sessions (
          id, user_id, trigger_type, surface_page_key, device_class,
          max_items, issued_count, created_at, expires_at
        ) values (?, ?, 'NEW_SESSION', 'HOME', 'PC', 1, 1, ?, ?)
        """, queueId, userId, timestamp(now.minus(Duration.ofMinutes(3))),
        timestamp(now.plus(Duration.ofMinutes(30))));
    jdbcTemplate.update("""
        insert into content.popup_deliveries (
          id, queue_session_id, campaign_id, user_id, revision_id,
          token_hash, status, page_key, device_class,
          issued_at, expires_at, shown_at
        ) values (?, ?, ?, ?, ?, ?, 'SHOWN', 'HOME', 'PC', ?, ?, ?)
        """, UUID.randomUUID(), queueId, campaignId, userId, revisionId,
        "a".repeat(64), timestamp(now.minus(Duration.ofMinutes(2))),
        timestamp(now.plus(Duration.ofMinutes(30))),
        timestamp(now.minus(Duration.ofMinutes(1))));
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private record ContentFixture(UUID itemId, UUID revisionId) {
  }
}
