package com.fxplatform.database;

import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class EngagementSchemaInvariantMigrationIT {

  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 7, 19, 8, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void databaseRejectsInvalidRevisionCampaignAndDeliveryState() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, null);
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID actorId = insertUser(jdbc, "engagement-invariant-actor@test.local");

      ContentFixture revisionHistory = insertContent(jdbc, actorId, "Historical revision");
      UUID currentRevisionId = insertRevision(
          jdbc, revisionHistory.itemId(), actorId, 2, "Current revision");
      jdbc.update("update content.content_items set current_revision_id = ? where id = ?",
          currentRevisionId, revisionHistory.itemId());
      Throwable revisionUpdate = catchThrowable(() -> jdbc.update("""
          update content.content_revisions
          set sanitized_html = '<p>mutated history</p>'
          where id = ?
          """, revisionHistory.revisionId()));
      Throwable revisionDelete = catchThrowable(() -> jdbc.update(
          "delete from content.content_revisions where id = ?", revisionHistory.revisionId()));

      ContentFixture unpublishedContent =
          insertContent(jdbc, actorId, "Unpublished active campaign");
      Throwable activeWithoutPublishTimes = catchThrowable(() ->
          insertActiveCampaignWithoutPublishTimes(jdbc, unpublishedContent.itemId(), actorId));

      UUID uppercaseTokenUserId = insertUser(jdbc, "uppercase-token-user@test.local");
      ContentFixture uppercaseTokenContent =
          insertContent(jdbc, actorId, "Uppercase token campaign");
      UUID uppercaseTokenCampaignId =
          insertActiveCampaign(jdbc, uppercaseTokenContent.itemId(), actorId);
      UUID uppercaseTokenSessionId = insertQueueSession(jdbc, uppercaseTokenUserId);
      Throwable uppercaseTokenHash = catchThrowable(() -> insertDelivery(
          jdbc,
          uppercaseTokenSessionId,
          uppercaseTokenCampaignId,
          uppercaseTokenUserId,
          uppercaseTokenContent.revisionId(),
          "A".repeat(64)));

      UUID crossItemUserId = insertUser(jdbc, "cross-item-revision-user@test.local");
      ContentFixture campaignContent = insertContent(jdbc, actorId, "Delivery campaign content");
      ContentFixture otherContent = insertContent(jdbc, actorId, "Other delivery content");
      UUID crossItemCampaignId = insertActiveCampaign(jdbc, campaignContent.itemId(), actorId);
      UUID crossItemSessionId = insertQueueSession(jdbc, crossItemUserId);
      Throwable crossItemRevision = catchThrowable(() -> insertDelivery(
          jdbc,
          crossItemSessionId,
          crossItemCampaignId,
          crossItemUserId,
          otherContent.revisionId(),
          "b".repeat(64)));
      insertDelivery(
          jdbc,
          crossItemSessionId,
          crossItemCampaignId,
          crossItemUserId,
          campaignContent.revisionId(),
          "c".repeat(64));
      Throwable campaignContentRebind = catchThrowable(() -> jdbc.update("""
          update content.popup_campaigns
          set content_item_id = ?
          where id = ?
          """, otherContent.itemId(), crossItemCampaignId));

      SoftAssertions.assertSoftly(softly -> {
        softly.assertThat(revisionUpdate)
            .as("content revision UPDATE must be rejected")
            .isInstanceOf(DataAccessException.class);
        softly.assertThat(revisionDelete)
            .as("content revision DELETE must be rejected even when it is not current")
            .isInstanceOf(DataAccessException.class);
        softly.assertThat(activeWithoutPublishTimes)
            .as("ACTIVE campaign must require first_published_at and last_published_at")
            .isInstanceOf(DataAccessException.class);
        softly.assertThat(uppercaseTokenHash)
            .as("delivery token hash must use canonical lowercase hexadecimal")
            .isInstanceOf(DataAccessException.class);
        softly.assertThat(crossItemRevision)
            .as("delivery revision must belong to its campaign content item")
            .isInstanceOf(DataAccessException.class);
        softly.assertThat(campaignContentRebind)
            .as("a campaign with delivery history must not rebind to another content item")
            .isInstanceOf(DataAccessException.class);
      });
    }
  }

  private static UUID insertUser(JdbcTemplate jdbc, String email) {
    UUID userId = UUID.randomUUID();
    jdbc.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', 'ACTIVE', 'USER', ?, ?)
        """, userId, email, NOW, NOW);
    return userId;
  }

  private static ContentFixture insertContent(JdbcTemplate jdbc, UUID actorId, String title) {
    UUID itemId = UUID.randomUUID();
    jdbc.update("""
        insert into content.content_items (id, content_kind, created_at, updated_at)
        values (?, 'POPUP_CAMPAIGN', ?, ?)
        """, itemId, NOW, NOW);
    UUID revisionId = insertRevision(jdbc, itemId, actorId, 1, title);
    jdbc.update("update content.content_items set current_revision_id = ? where id = ?",
        revisionId, itemId);
    return new ContentFixture(itemId, revisionId);
  }

  private static UUID insertRevision(
      JdbcTemplate jdbc, UUID itemId, UUID actorId, int revisionNo, String title) {
    UUID revisionId = UUID.randomUUID();
    jdbc.update("""
        insert into content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) values (?, ?, ?, ?, jsonb_build_object('type', 'doc', 'text', ?), ?, ?, ?)
        """, revisionId, itemId, revisionNo, title, title, "<p>" + title + "</p>", actorId, NOW);
    return revisionId;
  }

  private static void insertActiveCampaignWithoutPublishTimes(
      JdbcTemplate jdbc, UUID contentItemId, UUID actorId) {
    jdbc.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type,
          start_at, end_at, created_by, updated_by, created_at, updated_at
        ) values (?, 'Missing publication timestamps', ?, 'ACTIVE', 'ALL',
          ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), contentItemId, NOW, NOW.plusDays(7),
        actorId, actorId, NOW, NOW);
  }

  private static UUID insertActiveCampaign(
      JdbcTemplate jdbc, UUID contentItemId, UUID actorId) {
    UUID campaignId = UUID.randomUUID();
    jdbc.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type,
          start_at, end_at, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, 'Valid active campaign', ?, 'ACTIVE', 'ALL',
          ?, ?, ?, ?, ?, ?, ?, ?)
        """, campaignId, contentItemId, NOW, NOW.plusDays(7), NOW, NOW,
        actorId, actorId, NOW, NOW);
    return campaignId;
  }

  private static UUID insertQueueSession(JdbcTemplate jdbc, UUID userId) {
    UUID sessionId = UUID.randomUUID();
    jdbc.update("""
        insert into content.popup_queue_sessions (
          id, user_id, trigger_type, surface_page_key, device_class,
          max_items, issued_count, created_at, expires_at
        ) values (?, ?, 'LOGIN', 'HOME', 'PC', 3, 0, ?, ?)
        """, sessionId, userId, NOW, NOW.plusMinutes(5));
    return sessionId;
  }

  private static void insertDelivery(
      JdbcTemplate jdbc,
      UUID queueSessionId,
      UUID campaignId,
      UUID userId,
      UUID revisionId,
      String tokenHash) {
    jdbc.update("""
        insert into content.popup_deliveries (
          id, queue_session_id, campaign_id, user_id, revision_id, token_hash,
          status, page_key, device_class, issued_at, expires_at
        ) values (?, ?, ?, ?, ?, ?, 'ISSUED', 'HOME', 'PC', ?, ?)
        """, UUID.randomUUID(), queueSessionId, campaignId, userId, revisionId, tokenHash,
        NOW, NOW.plusMinutes(5));
  }

  private record ContentFixture(UUID itemId, UUID revisionId) {
  }
}
