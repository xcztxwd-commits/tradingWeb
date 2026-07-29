package com.fxplatform.engagement.application.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.engagement.web.dto.UserMessagePageResponse;
import com.fxplatform.engagement.web.dto.UserMessageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
class MessageReceiptPersistenceTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MessageReceiptService receiptService;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Clock clock;

  @Test
  void postgresAppliesAudienceAuthorityAndMapsTheCurrentSanitizedRevision() {
    Instant now = dbNow();
    UUID existingUser = insertUser(now.minus(Duration.ofDays(2)));
    UUID newerUser = insertUser(now.minus(Duration.ofHours(12)));

    ContentFixture allContent = insertContent(
        existingUser, "MESSAGE", "Old title", "<p>Old body</p>");
    UUID currentRevisionId = insertRevision(
        allContent.itemId(), existingUser, 2, "Current title", "<p>Current safe body</p>");
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        currentRevisionId,
        allContent.itemId());
    UUID allPublicationId = insertManualPublication(
        allContent.itemId(), existingUser, "ALL",
        now.minus(Duration.ofHours(2)), now.minus(Duration.ofDays(1)));

    ContentFixture selectedContent = insertContent(
        existingUser, "MESSAGE", "Selected title", "<p>Selected safe body</p>");
    UUID selectedPublicationId = insertManualPublication(
        selectedContent.itemId(), existingUser, "SELECTED",
        now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)));
    insertTarget(selectedPublicationId, newerUser);

    UserMessagePageResponse existingInbox =
        receiptService.listMessages(existingUser, 0, 100, false);
    UserMessagePageResponse newerInbox =
        receiptService.listMessages(newerUser, 0, 100, false);

    assertThat(existingInbox.items())
        .extracting(UserMessageResponse::publicationId)
        .contains(allPublicationId)
        .doesNotContain(selectedPublicationId);
    assertThat(existingInbox.items())
        .filteredOn(message -> message.publicationId().equals(allPublicationId))
        .singleElement()
        .satisfies(message -> {
          assertThat(message.revisionId()).isEqualTo(currentRevisionId);
          assertThat(message.title()).isEqualTo("Current title");
          assertThat(message.sanitizedHtml()).isEqualTo("<p>Current safe body</p>");
          assertThat(message.unread()).isTrue();
          assertThat(message.readAt()).isNull();
          assertThat(message.readSource()).isNull();
        });
    assertThat(newerInbox.items())
        .extracting(UserMessageResponse::publicationId)
        .contains(selectedPublicationId)
        .doesNotContain(allPublicationId);
    assertThat(queryInteger("""
        select count(*) from content.message_receipts
        where publication_id in (?, ?) and user_id in (?, ?)
        """, allPublicationId, selectedPublicationId, existingUser, newerUser)).isZero();
  }

  @Test
  void readIsLazyAndDuplicateStableBeforeUnreadClearsTheReadTuple() {
    Instant now = dbNow();
    UUID userId = insertUser(now.minus(Duration.ofMinutes(10)));
    ContentFixture content = insertContent(
        userId, "MESSAGE", "Read lifecycle", "<p>Read lifecycle</p>");
    UUID publicationId = insertManualPublication(
        content.itemId(), userId, "ALL",
        now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(1)));

    receiptService.markRead(userId, publicationId);
    ReceiptSnapshot initial = findReceipt(publicationId, userId);
    assertThat(initial.readAt()).isNotNull();
    assertThat(initial.readSource()).isEqualTo("USER");

    Instant originalPopupReadAt = now.minus(Duration.ofMinutes(3));
    jdbcTemplate.update("""
        update content.message_receipts
        set read_at = ?, read_source = 'POPUP', updated_at = ?
        where publication_id = ? and user_id = ?
        """, timestamp(originalPopupReadAt), timestamp(originalPopupReadAt),
        publicationId, userId);

    receiptService.markRead(userId, publicationId);

    ReceiptSnapshot duplicate = findReceipt(publicationId, userId);
    assertThat(duplicate.readAt()).isEqualTo(originalPopupReadAt);
    assertThat(duplicate.readSource()).isEqualTo("POPUP");

    receiptService.markUnread(userId, publicationId);

    ReceiptSnapshot unread = findReceipt(publicationId, userId);
    assertThat(unread.readAt()).isNull();
    assertThat(unread.readSource()).isNull();
    assertThat(unread.hiddenAt()).isNull();
  }

  @Test
  void hideIsPerUserAndReadAllMutatesOnlyVisibleUnreadRows() {
    Instant now = dbNow();
    UUID userId = insertUser(now.minus(Duration.ofMinutes(10)));
    UUID otherUserId = insertUser(now.minus(Duration.ofMinutes(10)));
    Instant sentAt = now.minus(Duration.ofMinutes(5));
    Instant cutoff = now.minus(Duration.ofMinutes(1));

    UUID unreadPublicationId = insertManualPublication(
        insertContent(userId, "MESSAGE", "Unread", "<p>Unread</p>").itemId(),
        userId, "ALL", sentAt.minusSeconds(2), cutoff);
    UUID alreadyReadPublicationId = insertManualPublication(
        insertContent(userId, "MESSAGE", "Already read", "<p>Already read</p>").itemId(),
        userId, "ALL", sentAt.minusSeconds(1), cutoff);
    UUID hiddenPublicationId = insertManualPublication(
        insertContent(userId, "MESSAGE", "Hidden", "<p>Hidden</p>").itemId(),
        userId, "ALL", sentAt, cutoff);

    receiptService.markRead(userId, alreadyReadPublicationId);
    ReceiptSnapshot alreadyRead = findReceipt(alreadyReadPublicationId, userId);
    receiptService.hide(userId, hiddenPublicationId);

    assertThat(receiptService.listMessages(userId, 0, 100, false).items())
        .extracting(UserMessageResponse::publicationId)
        .doesNotContain(hiddenPublicationId);
    assertThat(receiptService.listMessages(otherUserId, 0, 100, false).items())
        .extracting(UserMessageResponse::publicationId)
        .contains(hiddenPublicationId);
    assertThat(queryInteger("""
        select count(*) from content.message_receipts
        where publication_id = ? and user_id = ?
        """, hiddenPublicationId, otherUserId)).isZero();

    assertThat(receiptService.markAllRead(userId)).isEqualTo(1);

    ReceiptSnapshot unread = findReceipt(unreadPublicationId, userId);
    ReceiptSnapshot preserved = findReceipt(alreadyReadPublicationId, userId);
    ReceiptSnapshot hidden = findReceipt(hiddenPublicationId, userId);
    assertThat(unread.readAt()).isNotNull();
    assertThat(unread.readSource()).isEqualTo("READ_ALL");
    assertThat(preserved.readAt()).isEqualTo(alreadyRead.readAt());
    assertThat(preserved.readSource()).isEqualTo("USER");
    assertThat(hidden.hiddenAt()).isNotNull();
    assertThat(hidden.readAt()).isNull();
    assertThat(hidden.readSource()).isNull();
    assertThat(receiptService.unreadCount(userId)).isZero();
  }

  @Test
  void linkedCampaignPopupMarksPopupReadWithoutUnhidingTheMessage() {
    Instant now = dbNow();
    UUID userId = insertUser(now.minus(Duration.ofDays(1)));
    ContentFixture content = insertContent(
        userId, "POPUP_CAMPAIGN", "Campaign", "<p>Campaign</p>");
    UUID campaignId = insertCampaign(content.itemId(), userId, now);
    UUID publicationId = insertCampaignPublication(
        campaignId, content.itemId(), userId,
        now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)));
    insertTarget(publicationId, userId);

    receiptService.hide(userId, publicationId);
    Instant hiddenAt = findReceipt(publicationId, userId).hiddenAt();
    Instant shownAt = now.plusSeconds(1);

    receiptService.onShown(userId, campaignId, UUID.randomUUID(), shownAt);

    ReceiptSnapshot receipt = findReceipt(publicationId, userId);
    assertThat(receipt.readAt()).isEqualTo(shownAt);
    assertThat(receipt.readSource()).isEqualTo("POPUP");
    assertThat(receipt.hiddenAt()).isEqualTo(hiddenAt);
    assertThat(receiptService.listMessages(userId, 0, 100, false).items())
        .extracting(UserMessageResponse::publicationId)
        .doesNotContain(publicationId);
  }

  private UUID insertUser(Instant createdAt) {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', 'ACTIVE', 'USER', ?, ?)
        """, userId, "message-receipt-" + userId + "@example.test",
        timestamp(createdAt), timestamp(clock.instant()));
    return userId;
  }

  private ContentFixture insertContent(
      UUID actorId,
      String kind,
      String title,
      String sanitizedHtml) {
    UUID itemId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.content_items (id, content_kind, created_at, updated_at)
        values (?, ?, ?, ?)
        """, itemId, kind, timestamp(now), timestamp(now));
    UUID revisionId = insertRevision(itemId, actorId, 1, title, sanitizedHtml);
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        revisionId,
        itemId);
    return new ContentFixture(itemId, revisionId);
  }

  private UUID insertRevision(
      UUID itemId,
      UUID actorId,
      int revisionNo,
      String title,
      String sanitizedHtml) {
    UUID revisionId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?)
        """, revisionId, itemId, revisionNo, title, sanitizedHtml,
        actorId, timestamp(clock.instant()));
    return revisionId;
  }

  private UUID insertManualPublication(
      UUID contentItemId,
      UUID actorId,
      String audienceType,
      Instant sentAt,
      Instant cutoffAt) {
    UUID publicationId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, audience_type, lifecycle_status,
          category, sent_at, audience_cutoff_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, 'MANUAL', ?, 'SENT', 'NOTICE', ?, ?, ?, ?, ?, ?)
        """, publicationId, contentItemId, audienceType,
        timestamp(sentAt), timestamp(cutoffAt), actorId, actorId,
        timestamp(sentAt), timestamp(sentAt));
    return publicationId;
  }

  private UUID insertCampaign(UUID contentItemId, UUID actorId, Instant now) {
    UUID campaignId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, sync_to_inbox,
          start_at, end_at, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, ?, 'ACTIVE', 'SELECTED', true, ?, ?, ?, ?, ?, ?, ?, ?)
        """, campaignId, "Popup " + campaignId, contentItemId,
        timestamp(now.minus(Duration.ofHours(1))), timestamp(now.plus(Duration.ofHours(1))),
        timestamp(now.minus(Duration.ofHours(1))), timestamp(now.minus(Duration.ofHours(1))),
        actorId, actorId, timestamp(now.minus(Duration.ofHours(1))), timestamp(now));
    jdbcTemplate.update("""
        insert into content.popup_campaign_targets (campaign_id, user_id)
        values (?, ?)
        """, campaignId, actorId);
    return campaignId;
  }

  private UUID insertCampaignPublication(
      UUID campaignId,
      UUID contentItemId,
      UUID actorId,
      Instant sentAt,
      Instant cutoffAt) {
    UUID publicationId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.message_publications (
          id, content_item_id, source_type, source_campaign_id,
          audience_type, lifecycle_status, category, sent_at, audience_cutoff_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, 'CAMPAIGN', ?, 'SELECTED', 'SENT', 'CAMPAIGN', ?, ?, ?, ?, ?, ?)
        """, publicationId, contentItemId, campaignId,
        timestamp(sentAt), timestamp(cutoffAt), actorId, actorId,
        timestamp(sentAt), timestamp(sentAt));
    return publicationId;
  }

  private void insertTarget(UUID publicationId, UUID userId) {
    jdbcTemplate.update("""
        insert into content.message_targets (publication_id, user_id)
        values (?, ?)
        """, publicationId, userId);
  }

  private ReceiptSnapshot findReceipt(UUID publicationId, UUID userId) {
    return jdbcTemplate.queryForObject("""
        select read_at, read_source, hidden_at
        from content.message_receipts
        where publication_id = ? and user_id = ?
        """, (row, index) -> new ReceiptSnapshot(
        instant(row.getTimestamp("read_at")),
        row.getString("read_source"),
        instant(row.getTimestamp("hidden_at"))),
        publicationId,
        userId);
  }

  private int queryInteger(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private Instant dbNow() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private record ContentFixture(UUID itemId, UUID revisionId) {
  }

  private record ReceiptSnapshot(Instant readAt, String readSource, Instant hiddenAt) {
  }
}
