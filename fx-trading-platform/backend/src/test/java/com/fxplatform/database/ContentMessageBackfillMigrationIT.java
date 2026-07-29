package com.fxplatform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ContentMessageBackfillMigrationIT {

  private static final OffsetDateTime CREATED_AT =
      OffsetDateTime.of(2026, 7, 1, 1, 2, 3, 0, ZoneOffset.UTC);
  private static final OffsetDateTime UPDATED_AT =
      OffsetDateTime.of(2026, 7, 3, 7, 8, 9, 0, ZoneOffset.UTC);
  private static final OffsetDateTime PUBLISHED_AT =
      OffsetDateTime.of(2026, 7, 2, 4, 5, 6, 0, ZoneOffset.UTC);
  private static final String ALL_BODY =
      "Legacy all <strong>body</strong><script>alert('legacy-xss')</script>";
  private static final String SELECTED_BODY =
      "Legacy selected <img src=x onerror=alert('legacy-xss')> body";

  @Test
  void v64KeepsLegacyMessagesSelectableButRejectsEveryMutationWithStableError() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "16");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID actorId = insertUser(jdbc, "legacy-read-only-actor@test.local");
      UUID messageId = UUID.randomUUID();
      insertLegacyMessage(
          jdbc,
          messageId,
          null,
          actorId,
          "Immutable legacy title",
          "Immutable legacy body",
          "PUBLISHED",
          PUBLISHED_AT);

      PostgresMigrationTestSupport.migrate(postgres, "63");
      LegacyMessageSnapshot before = legacyMessage(jdbc, messageId);

      PostgresMigrationTestSupport.migrate(postgres, null);

      assertThat(count(jdbc, """
          select count(*) from flyway_schema_history where version = '64' and success = true
          """)).isEqualTo(1);
      assertThat(legacyMessage(jdbc, messageId)).isEqualTo(before);

      assertLegacyMutationRejected(() -> insertLegacyMessage(
          jdbc,
          UUID.randomUUID(),
          null,
          actorId,
          "Blocked insert",
          "Blocked body",
          "DRAFT",
          null));
      assertLegacyMutationRejected(() -> jdbc.update(
          "update content.messages set title = 'Blocked update' where id = ?", messageId));
      assertLegacyMutationRejected(() -> jdbc.update(
          "delete from content.messages where id = ?", messageId));

      assertThat(legacyMessage(jdbc, messageId)).isEqualTo(before);
      assertThat(count(jdbc, "select count(*) from content.messages where id = ?", messageId))
          .isEqualTo(1);
    }
  }

  @Test
  void v16AllAndSelectedMessagesBackfillWithoutDroppingLegacyDataOrHistory() {
    try (PostgreSQLContainer<?> postgres = PostgresMigrationTestSupport.startPostgresOrAbort()) {
      PostgresMigrationTestSupport.migrate(postgres, "16");
      JdbcTemplate jdbc = PostgresMigrationTestSupport.jdbc(postgres);
      UUID actorId = insertUser(jdbc, "legacy-message-actor@test.local");
      UUID selectedUserId = insertUser(jdbc, "legacy-message-target@test.local");
      UUID allMessageId = UUID.randomUUID();
      UUID selectedMessageId = UUID.randomUUID();

      insertLegacyMessage(
          jdbc,
          allMessageId,
          null,
          actorId,
          "Task 1 legacy all",
          ALL_BODY,
          "PUBLISHED",
          PUBLISHED_AT);
      insertLegacyMessage(
          jdbc,
          selectedMessageId,
          selectedUserId,
          actorId,
          "Task 1 legacy selected",
          SELECTED_BODY,
          "DRAFT",
          null);

      PostgresMigrationTestSupport.migrate(postgres, null);

      assertThat(count(jdbc, """
          select count(*) from flyway_schema_history where version = '63' and success = true
          """)).isEqualTo(1);
      assertThat(count(jdbc, """
          select count(*) from information_schema.tables
          where table_schema = 'content' and table_name = 'messages'
          """)).isEqualTo(1);
      assertThat(count(jdbc, """
          select count(*) from content.messages where id in (?, ?)
          """, allMessageId, selectedMessageId)).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.content_items")).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.content_revisions")).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.message_publications")).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.message_targets")).isEqualTo(1);

      BackfilledMessage all = backfilledMessage(jdbc, "Task 1 legacy all");
      assertThat(all.contentKind()).isEqualTo("MESSAGE");
      assertThat(all.revisionNo()).isEqualTo(1);
      assertThat(all.currentRevisionId()).isEqualTo(all.revisionId());
      assertSafelyMigratedBody(all, ALL_BODY, "Legacy all", "body");
      assertThat(all.sourceType()).isEqualTo("MANUAL");
      assertThat(all.audienceType()).isEqualTo("ALL");
      assertThat(all.lifecycleStatus()).isEqualTo("SENT");
      assertThat(all.sentAt()).isEqualTo(PUBLISHED_AT);
      assertThat(all.audienceCutoffAt()).isEqualTo(PUBLISHED_AT);
      assertThat(all.targetCount()).isZero();
      assertLegacyTrace(jdbc, allMessageId, all);

      BackfilledMessage selected = backfilledMessage(jdbc, "Task 1 legacy selected");
      assertThat(selected.contentKind()).isEqualTo("MESSAGE");
      assertThat(selected.revisionNo()).isEqualTo(1);
      assertThat(selected.currentRevisionId()).isEqualTo(selected.revisionId());
      assertSafelyMigratedBody(selected, SELECTED_BODY, "Legacy selected", "body");
      assertThat(selected.sourceType()).isEqualTo("MANUAL");
      assertThat(selected.audienceType()).isEqualTo("SELECTED");
      assertThat(selected.lifecycleStatus()).isEqualTo("DRAFT");
      assertThat(selected.sentAt()).isNull();
      assertThat(selected.targetCount()).isEqualTo(1);
      assertThat(selected.targetUserId()).isEqualTo(selectedUserId);
      assertLegacyTrace(jdbc, selectedMessageId, selected);

      PostgresMigrationTestSupport.migrate(postgres, null);
      assertThat(backfilledMessage(jdbc, "Task 1 legacy all")).isEqualTo(all);
      assertThat(backfilledMessage(jdbc, "Task 1 legacy selected")).isEqualTo(selected);
      assertThat(count(jdbc, "select count(*) from content.content_items")).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.content_revisions")).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.message_publications")).isEqualTo(2);
      assertThat(count(jdbc, "select count(*) from content.message_targets")).isEqualTo(1);

      ContentFixture campaignContent = insertContent(jdbc, actorId, "Campaign guard content");
      ContentFixture alternateContent = insertContent(jdbc, actorId, "Alternate guard content");
      assertRejected(() -> jdbc.update("""
          insert into content.message_publications (
            id, content_item_id, source_type, audience_type, lifecycle_status,
            category, sent_at, audience_cutoff_at,
            created_by, updated_by, created_at, updated_at
          ) values (?, ?, 'MANUAL', 'ALL', 'NOT_A_LIFECYCLE',
            'SYSTEM', ?, ?, ?, ?, ?, ?)
          """, UUID.randomUUID(), alternateContent.itemId(), PUBLISHED_AT, PUBLISHED_AT,
          actorId, actorId, CREATED_AT, CREATED_AT));

      UUID campaignId = insertCampaign(jdbc, campaignContent.itemId(), actorId);
      insertUserState(jdbc, campaignId, selectedUserId);
      assertRejected(() -> insertUserState(jdbc, campaignId, selectedUserId));

      UUID queueSessionId = insertQueueSession(jdbc, selectedUserId);
      UUID alternateQueueSessionId = insertQueueSession(jdbc, actorId);
      UUID tokenSeed = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      UUID deliveryId = insertDelivery(
          jdbc, queueSessionId, campaignId, selectedUserId, campaignContent.revisionId(), tokenSeed);
      String tokenHash = "a".repeat(64);
      assertRejected(() -> insertDelivery(
          jdbc, alternateQueueSessionId, campaignId, actorId,
          campaignContent.revisionId(), tokenSeed));

      UUID campaignPublicationId = insertCampaignPublication(
          jdbc, campaignId, campaignContent.itemId(), actorId);
      assertRejected(() -> insertCampaignPublication(
          jdbc, campaignId, alternateContent.itemId(), actorId));

      assertCrossTableConsistencyGuards(jdbc, actorId, all, selected);

      UUID auditId = UUID.randomUUID();
      jdbc.update("""
          insert into content.message_receipts (
            publication_id, user_id, delivered_at, updated_at
          ) values (?, ?, ?, ?)
          """, selected.publicationId(), selectedUserId, CREATED_AT, CREATED_AT);
      assertRejected(() -> jdbc.update("""
          insert into content.message_receipts (
            publication_id, user_id, delivered_at, updated_at
          ) values (?, ?, ?, ?)
          """, selected.publicationId(), selectedUserId, CREATED_AT, CREATED_AT));
      jdbc.update("""
          insert into audit.audit_logs (
            id, actor_user_id, action, target_type, target_id, details, created_at
          ) values (?, ?, 'MESSAGE_DELETE', 'MESSAGE_PUBLICATION', ?, '{}'::jsonb, ?)
          """, auditId, actorId, selected.publicationId().toString(), CREATED_AT);
      jdbc.update("""
          update content.popup_campaigns
          set lifecycle_status = 'DELETED', deleted_at = ?, updated_at = ?
          where id = ?
          """, PUBLISHED_AT, PUBLISHED_AT, campaignId);
      jdbc.update("""
          update content.message_publications
          set lifecycle_status = 'DELETED', deleted_at = ?, updated_at = ?
          where id = ?
          """, PUBLISHED_AT, PUBLISHED_AT, selected.publicationId());

      assertThat(count(jdbc, """
          select count(*) from content.popup_campaign_user_states
          where campaign_id = ? and user_id = ?
          """, campaignId, selectedUserId)).isEqualTo(1);
      assertThat(count(jdbc, "select count(*) from content.popup_deliveries where id = ?",
          deliveryId)).isEqualTo(1);
      assertThat(count(jdbc, "select count(*) from content.popup_deliveries where token_hash = ?",
          tokenHash)).isEqualTo(1);
      assertThat(count(jdbc, """
          select count(*) from content.message_publications
          where id = ? and source_campaign_id = ?
          """, campaignPublicationId, campaignId)).isEqualTo(1);
      assertThat(count(jdbc, "select count(*) from content.content_revisions where id = ?",
          selected.revisionId())).isEqualTo(1);
      assertThat(count(jdbc, """
          select count(*) from content.message_receipts
          where publication_id = ? and user_id = ?
          """, selected.publicationId(), selectedUserId)).isEqualTo(1);
      assertThat(count(jdbc, "select count(*) from audit.audit_logs where id = ?", auditId))
          .isEqualTo(1);
    }
  }

  private static UUID insertUser(JdbcTemplate jdbc, String email) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', 'ACTIVE', 'USER', ?, ?)
        """, id, email, CREATED_AT, CREATED_AT);
    return id;
  }

  private static void insertLegacyMessage(
      JdbcTemplate jdbc,
      UUID id,
      UUID targetUserId,
      UUID actorId,
      String title,
      String body,
      String status,
      OffsetDateTime publishedAt) {
    jdbc.update("""
        insert into content.messages (
          id, target_user_id, title, body, message_type, status, sent_by,
          published_at, created_at, updated_at
        ) values (?, ?, ?, ?, 'SYSTEM', ?, ?, ?, ?, ?)
        """, id, targetUserId, title, body, status, actorId, publishedAt, CREATED_AT, UPDATED_AT);
  }

  private static BackfilledMessage backfilledMessage(JdbcTemplate jdbc, String title) {
    return jdbc.queryForObject("""
        select publication.id as publication_id,
               item.id as content_item_id,
               item.content_kind,
               item.current_revision_id,
               revision.id as revision_id,
               revision.revision_no,
               revision.created_at as revision_created_at,
               revision.body_document::text as body_document,
               revision.sanitized_html,
               publication.source_type,
               publication.audience_type,
               publication.lifecycle_status,
               publication.sent_at,
               publication.audience_cutoff_at,
               count(target.user_id) as target_count,
               min(target.user_id::text) as target_user_id
        from content.message_publications publication
        join content.content_items item on item.id = publication.content_item_id
        join content.content_revisions revision on revision.id = item.current_revision_id
        left join content.message_targets target on target.publication_id = publication.id
        where revision.title = ?
        group by publication.id, item.id, revision.id
        """, (rs, rowNum) -> new BackfilledMessage(
            rs.getObject("publication_id", UUID.class),
            rs.getObject("content_item_id", UUID.class),
            rs.getString("content_kind"),
            rs.getObject("current_revision_id", UUID.class),
            rs.getObject("revision_id", UUID.class),
            rs.getInt("revision_no"),
            rs.getObject("revision_created_at", OffsetDateTime.class),
            rs.getString("body_document"),
            rs.getString("sanitized_html"),
            rs.getString("source_type"),
            rs.getString("audience_type"),
            rs.getString("lifecycle_status"),
            rs.getObject("sent_at", OffsetDateTime.class),
            rs.getObject("audience_cutoff_at", OffsetDateTime.class),
            rs.getInt("target_count"),
            rs.getString("target_user_id") == null
                ? null
                : UUID.fromString(rs.getString("target_user_id"))),
        title);
  }

  private static void assertSafelyMigratedBody(
      BackfilledMessage message, String legacyBody, String... readableText) {
    assertThat(message.bodyDocument()).contains(legacyBody).contains(readableText);
    assertThat(message.sanitizedHtml())
        .isNotEqualTo(legacyBody)
        .contains(readableText)
        .doesNotContainIgnoringCase("<script", "</script", "<img", "onerror=");
  }

  private static void assertCrossTableConsistencyGuards(
      JdbcTemplate jdbc,
      UUID actorId,
      BackfilledMessage all,
      BackfilledMessage selected) {
    ContentFixture firstItem = insertContent(jdbc, actorId, "First revision owner");
    ContentFixture secondItem = insertContent(jdbc, actorId, "Second revision owner");
    Throwable crossItemRevision = catchThrowable(() -> jdbc.update("""
        update content.content_items set current_revision_id = ? where id = ?
        """, secondItem.revisionId(), firstItem.itemId()));

    ContentFixture publicationCampaignContent =
        insertContent(jdbc, actorId, "Publication campaign content");
    ContentFixture wrongPublicationContent =
        insertContent(jdbc, actorId, "Wrong publication content");
    UUID publicationCampaignId =
        insertCampaign(jdbc, publicationCampaignContent.itemId(), actorId);
    Throwable crossCampaignPublication = catchThrowable(() -> insertCampaignPublication(
        jdbc, publicationCampaignId, wrongPublicationContent.itemId(), actorId));

    UUID mismatchedDeliveryUser = insertUser(jdbc, "delivery-mismatch@test.local");
    ContentFixture deliveryContent = insertContent(jdbc, actorId, "Delivery campaign content");
    UUID deliveryCampaignId = insertCampaign(jdbc, deliveryContent.itemId(), actorId);
    UUID actorQueueSessionId = insertQueueSession(jdbc, actorId);
    Throwable crossUserDelivery = catchThrowable(() -> insertDelivery(
        jdbc,
        actorQueueSessionId,
        deliveryCampaignId,
        mismatchedDeliveryUser,
        deliveryContent.revisionId(),
        UUID.randomUUID()));

    UUID stateOwner = insertUser(jdbc, "state-owner@test.local");
    UUID mismatchedStateUser = insertUser(jdbc, "state-mismatch@test.local");
    ContentFixture stateContent = insertContent(jdbc, actorId, "State campaign content");
    UUID stateCampaignId = insertCampaign(jdbc, stateContent.itemId(), actorId);
    UUID stateQueueSessionId = insertQueueSession(jdbc, stateOwner);
    UUID stateDeliveryId = insertDelivery(
        jdbc,
        stateQueueSessionId,
        stateCampaignId,
        stateOwner,
        stateContent.revisionId(),
        UUID.randomUUID());
    Throwable crossUserState = catchThrowable(() -> insertActiveUserState(
        jdbc, stateCampaignId, mismatchedStateUser, stateDeliveryId));
    ContentFixture alternateStateContent =
        insertContent(jdbc, actorId, "Alternate state campaign content");
    UUID alternateStateCampaignId =
        insertCampaign(jdbc, alternateStateContent.itemId(), actorId);
    Throwable crossCampaignState = catchThrowable(() -> insertActiveUserState(
        jdbc, alternateStateCampaignId, stateOwner, stateDeliveryId));

    UUID reuseOwner = insertUser(jdbc, "reuse-owner@test.local");
    UUID reuseOtherUser = insertUser(jdbc, "reuse-other@test.local");
    ContentFixture reuseContent = insertContent(jdbc, actorId, "Reuse campaign content");
    UUID reuseCampaignId = insertCampaign(jdbc, reuseContent.itemId(), actorId);
    UUID reuseQueueSessionId = insertQueueSession(jdbc, reuseOwner);
    UUID reusedDeliveryId = insertDelivery(
        jdbc,
        reuseQueueSessionId,
        reuseCampaignId,
        reuseOwner,
        reuseContent.revisionId(),
        UUID.randomUUID());
    insertActiveUserState(jdbc, reuseCampaignId, reuseOwner, reusedDeliveryId);
    Throwable reusedBySecondState = catchThrowable(() -> insertActiveUserState(
        jdbc, reuseCampaignId, reuseOtherUser, reusedDeliveryId));

    SoftAssertions.assertSoftly(softly -> {
      softly.assertThat(crossCampaignPublication)
          .as("CAMPAIGN publication must use its source campaign content item")
          .isInstanceOf(DataAccessException.class);
      softly.assertThat(crossUserDelivery)
          .as("delivery user must own its queue session")
          .isInstanceOf(DataAccessException.class);
      softly.assertThat(crossUserState)
          .as("active delivery must belong to the state user")
          .isInstanceOf(DataAccessException.class);
      softly.assertThat(crossCampaignState)
          .as("active delivery must belong to the state campaign")
          .isInstanceOf(DataAccessException.class);
      softly.assertThat(reusedBySecondState)
          .as("one delivery must not be reused by multiple user states")
          .isInstanceOf(DataAccessException.class);
      softly.assertThat(crossItemRevision)
          .as("current revision must belong to its content item")
          .isInstanceOf(DataAccessException.class);
      softly.assertThat(List.of(all.revisionCreatedAt(), selected.revisionCreatedAt()))
          .as("legacy final revisions must use message.updated_at")
          .containsOnly(UPDATED_AT);
    });
  }

  private static ContentFixture insertContent(JdbcTemplate jdbc, UUID actorId, String title) {
    UUID itemId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    jdbc.update("""
        insert into content.content_items (id, content_kind, created_at, updated_at)
        values (?, 'MESSAGE', ?, ?)
        """, itemId, CREATED_AT, CREATED_AT);
    jdbc.update("""
        insert into content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) values (?, ?, 1, ?, jsonb_build_object('type', 'doc', 'text', ?), ?, ?, ?)
        """, revisionId, itemId, title, title, "<p>" + title + "</p>", actorId, CREATED_AT);
    jdbc.update("update content.content_items set current_revision_id = ? where id = ?",
        revisionId, itemId);
    return new ContentFixture(itemId, revisionId);
  }

  private static UUID insertCampaign(JdbcTemplate jdbc, UUID contentItemId, UUID actorId) {
    UUID campaignId = UUID.randomUUID();
    jdbc.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, sync_to_inbox,
          priority, display_scope, page_keys, device_scope, template_size, time_zone,
          start_at, end_at, max_total_impressions, max_daily_impressions,
          min_interval_seconds, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, 'Migration guard campaign', ?, 'ACTIVE', 'ALL', true,
          100, 'ALL_BUSINESS_PAGES', '[]'::jsonb, 'ALL', 'SMALL', 'Asia/Shanghai',
          ?, ?, 3, 1, 14400, ?, ?, ?, ?, ?, ?)
        """, campaignId, contentItemId, CREATED_AT, CREATED_AT.plusDays(7),
        PUBLISHED_AT, PUBLISHED_AT, actorId, actorId, CREATED_AT, CREATED_AT);
    return campaignId;
  }

  private static void insertUserState(JdbcTemplate jdbc, UUID campaignId, UUID userId) {
    jdbc.update("""
        insert into content.popup_campaign_user_states (
          campaign_id, user_id, total_impressions, daily_bucket,
          daily_impressions, version
        ) values (?, ?, 0, date '2026-07-19', 0, 0)
        """, campaignId, userId);
  }

  private static void insertActiveUserState(
      JdbcTemplate jdbc, UUID campaignId, UUID userId, UUID deliveryId) {
    jdbc.update("""
        insert into content.popup_campaign_user_states (
          campaign_id, user_id, total_impressions, daily_bucket,
          daily_impressions, active_delivery_id, active_delivery_expires_at, version
        ) values (?, ?, 0, date '2026-07-19', 0, ?, ?, 0)
        """, campaignId, userId, deliveryId, CREATED_AT.plusMinutes(5));
  }

  private static UUID insertQueueSession(JdbcTemplate jdbc, UUID userId) {
    UUID sessionId = UUID.randomUUID();
    jdbc.update("""
        insert into content.popup_queue_sessions (
          id, user_id, trigger_type, surface_page_key, device_class,
          max_items, issued_count, created_at, expires_at
        ) values (?, ?, 'LOGIN', 'HOME', 'PC', 3, 0, ?, ?)
        """, sessionId, userId, CREATED_AT, CREATED_AT.plusMinutes(5));
    return sessionId;
  }

  private static UUID insertDelivery(
      JdbcTemplate jdbc,
      UUID queueSessionId,
      UUID campaignId,
      UUID userId,
      UUID revisionId,
      UUID tokenSeed) {
    UUID deliveryId = UUID.randomUUID();
    String tokenHash = tokenSeed.toString().replace("-", "").repeat(2);
    jdbc.update("""
        insert into content.popup_deliveries (
          id, queue_session_id, campaign_id, user_id, revision_id, token_hash,
          status, page_key, device_class, issued_at, expires_at
        ) values (?, ?, ?, ?, ?, ?, 'ISSUED', 'HOME', 'PC', ?, ?)
        """, deliveryId, queueSessionId, campaignId, userId, revisionId, tokenHash,
        CREATED_AT, CREATED_AT.plusMinutes(5));
    return deliveryId;
  }

  private static UUID insertCampaignPublication(
      JdbcTemplate jdbc, UUID campaignId, UUID contentItemId, UUID actorId) {
    UUID publicationId = UUID.randomUUID();
    jdbc.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, source_campaign_id, audience_type,
          lifecycle_status, category, sent_at, audience_cutoff_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, 'CAMPAIGN', ?, 'ALL', 'SENT', 'SYSTEM', ?, ?, ?, ?, ?, ?)
        """, publicationId, contentItemId, campaignId, PUBLISHED_AT, CREATED_AT.plusDays(7),
        actorId, actorId, CREATED_AT, CREATED_AT);
    return publicationId;
  }

  private static void assertRejected(Runnable statement) {
    assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class);
  }

  private static void assertLegacyMutationRejected(Runnable statement) {
    assertThatThrownBy(statement::run)
        .isInstanceOfSatisfying(DataAccessException.class, failure -> {
          Throwable cause = failure.getMostSpecificCause();
          assertThat(cause).isInstanceOf(SQLException.class);
          assertThat(((SQLException) cause).getSQLState()).isEqualTo("55000");
          assertThat(cause).hasMessageContaining("legacy content.messages is read-only");
        });
  }

  private static LegacyMessageSnapshot legacyMessage(JdbcTemplate jdbc, UUID messageId) {
    return jdbc.queryForObject("""
        select id, target_user_id, title, body, message_type, status, sent_by,
               published_at, created_at, updated_at
        from content.messages
        where id = ?
        """, (row, index) -> new LegacyMessageSnapshot(
        row.getObject("id", UUID.class),
        row.getObject("target_user_id", UUID.class),
        row.getString("title"),
        row.getString("body"),
        row.getString("message_type"),
        row.getString("status"),
        row.getObject("sent_by", UUID.class),
        row.getObject("published_at", OffsetDateTime.class),
        row.getObject("created_at", OffsetDateTime.class),
        row.getObject("updated_at", OffsetDateTime.class)),
        messageId);
  }

  private static void assertLegacyTrace(
      JdbcTemplate jdbc, UUID legacyMessageId, BackfilledMessage backfilled) {
    if (legacyMessageId.equals(backfilled.publicationId())
        || legacyMessageId.equals(backfilled.contentItemId())) {
      return;
    }
    boolean publicationHasLegacyId = hasColumn(
        jdbc, "message_publications", "legacy_message_id");
    boolean itemHasLegacyId = hasColumn(jdbc, "content_items", "legacy_message_id");
    assertThat(publicationHasLegacyId || itemHasLegacyId)
        .as("legacy message %s must remain traceable", legacyMessageId)
        .isTrue();
    if (publicationHasLegacyId) {
      assertThat(count(jdbc, """
          select count(*) from content.message_publications
          where id = ? and legacy_message_id = ?
          """, backfilled.publicationId(), legacyMessageId)).isEqualTo(1);
    } else {
      assertThat(count(jdbc, """
          select count(*) from content.content_items
          where id = ? and legacy_message_id = ?
          """, backfilled.contentItemId(), legacyMessageId)).isEqualTo(1);
    }
  }

  private static boolean hasColumn(JdbcTemplate jdbc, String table, String column) {
    return count(jdbc, """
        select count(*) from information_schema.columns
        where table_schema = 'content' and table_name = ? and column_name = ?
        """, table, column) == 1;
  }

  private static int count(JdbcTemplate jdbc, String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private record BackfilledMessage(
      UUID publicationId,
      UUID contentItemId,
      String contentKind,
      UUID currentRevisionId,
      UUID revisionId,
      int revisionNo,
      OffsetDateTime revisionCreatedAt,
      String bodyDocument,
      String sanitizedHtml,
      String sourceType,
      String audienceType,
      String lifecycleStatus,
      OffsetDateTime sentAt,
      OffsetDateTime audienceCutoffAt,
      int targetCount,
      UUID targetUserId) {
  }

  private record ContentFixture(UUID itemId, UUID revisionId) {
  }

  private record LegacyMessageSnapshot(
      UUID id,
      UUID targetUserId,
      String title,
      String body,
      String messageType,
      String status,
      UUID sentBy,
      OffsetDateTime publishedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
  }
}
