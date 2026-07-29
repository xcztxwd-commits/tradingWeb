package com.fxplatform.engagement.application.popup;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.engagement.application.popup.PopupClaimService.PopupClaim;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupSurface;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class PopupClaimConcurrencyTest {

  private static final PopupSurface SURFACE =
      new PopupSurface("LOGIN", "dashboard", DeviceClass.PC);

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired PopupClaimService claimService;
  @Autowired PopupDeliveryTokenCodec tokenCodec;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Clock clock;

  @Test
  void concurrentClaimsForOneUserIssueExactlyOneHashedDelivery() throws Exception {
    UUID userId = insertUser();
    ContentFixture content = insertContent(userId, "Concurrent popup");
    insertCampaign(content.itemId(), userId, UUID.randomUUID(), 10, clock.instant().minusSeconds(60));

    List<Optional<PopupClaim>> attempts = claimConcurrently(userId);

    assertThat(attempts.stream().filter(Optional::isPresent).count()).isEqualTo(1);
    PopupClaim claim = attempts.stream().flatMap(Optional::stream).findFirst().orElseThrow();
    assertThat(queryInteger("""
        select count(*) from content.popup_deliveries
        where user_id = ? and status in ('ISSUED', 'SHOWN') and invalidated_at is null
        """, userId)).isEqualTo(1);
    assertThat(queryInteger("""
        select count(*) from content.popup_campaign_user_states
        where user_id = ? and active_delivery_id is not null
        """, userId)).isEqualTo(1);
    assertThat(queryInteger("""
        select coalesce(sum(issued_count), 0)::integer
        from content.popup_queue_sessions where user_id = ?
        """, userId)).isEqualTo(1);
    assertThat(queryInteger("""
        select coalesce(sum(total_impressions), 0)::integer
        from content.popup_campaign_user_states where user_id = ?
        """, userId)).isZero();
    assertThat(queryInteger("""
        select coalesce(sum(daily_impressions), 0)::integer
        from content.popup_campaign_user_states where user_id = ?
        """, userId)).isZero();

    String storedHash = jdbcTemplate.queryForObject(
        "select token_hash from content.popup_deliveries where id = ?",
        String.class,
        claim.deliveryId());
    assertThat(storedHash).isEqualTo(tokenCodec.hash(claim.deliveryToken()));
    assertThat(claim.toString()).doesNotContain(claim.deliveryToken());
    assertPlaintextAbsentFromContentTextColumns(claim.deliveryToken());
  }

  @Test
  void postgresAppliesPriorityPublicationTimeAndIdOrdering() {
    UUID userId = insertUser();
    Instant earlier = clock.instant().minus(Duration.ofHours(2));
    Instant later = clock.instant().minus(Duration.ofHours(1));
    UUID expectedId = UUID.fromString("70000000-0000-0000-0000-000000000001");
    UUID sameRankHigherId = UUID.fromString("70000000-0000-0000-0000-000000000002");
    UUID laterPublicationLowerId = UUID.fromString("60000000-0000-0000-0000-000000000001");
    UUID lowerPriorityLowestId = UUID.fromString("50000000-0000-0000-0000-000000000001");

    insertCampaign(insertContent(userId, "Expected").itemId(), userId, expectedId, 20, earlier);
    insertCampaign(
        insertContent(userId, "ID tie loser").itemId(), userId, sameRankHigherId, 20, earlier);
    insertCampaign(
        insertContent(userId, "Publication tie loser").itemId(),
        userId,
        laterPublicationLowerId,
        20,
        later);
    insertCampaign(
        insertContent(userId, "Priority loser").itemId(),
        userId,
        lowerPriorityLowestId,
        10,
        earlier.minus(Duration.ofHours(1)));

    PopupClaim claim = claimService.claimNextPopup(userId, SURFACE, null).orElseThrow();

    assertThat(claim.campaignId()).isEqualTo(expectedId);
  }

  @Test
  void deliveryPinsRevisionWhileTheNextNaturalSessionUsesTheNewRevision() {
    UUID userId = insertUser();
    ContentFixture content = insertContent(userId, "Revision one");
    UUID campaignId = UUID.randomUUID();
    insertCampaign(content.itemId(), userId, campaignId, 10, clock.instant().minusSeconds(60));

    PopupClaim first = claimService.claimNextPopup(userId, SURFACE, null).orElseThrow();
    UUID revisionTwo = insertRevision(content.itemId(), userId, 2, "Revision two");
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ?, updated_at = ? where id = ?",
        revisionTwo,
        timestamp(clock.instant()),
        content.itemId());

    assertThat(queryUuid(
        "select revision_id from content.popup_deliveries where id = ?", first.deliveryId()))
        .isEqualTo(content.revisionId());

    Instant closedAt = clock.instant();
    jdbcTemplate.update("""
        update content.popup_deliveries
        set status = 'CLOSED', closed_at = ?, close_reason = 'TEST_CLOSE'
        where id = ?
        """, timestamp(closedAt), first.deliveryId());
    jdbcTemplate.update("""
        update content.popup_campaign_user_states
        set active_delivery_id = null,
            active_delivery_expires_at = null,
            version = version + 1
        where campaign_id = ? and user_id = ? and active_delivery_id = ?
        """, campaignId, userId, first.deliveryId());

    PopupClaim second = claimService.claimNextPopup(userId, SURFACE, null).orElseThrow();

    assertThat(second.queueSessionId()).isNotEqualTo(first.queueSessionId());
    assertThat(second.campaignId()).isEqualTo(campaignId);
    assertThat(second.revisionId()).isEqualTo(revisionTwo);
    assertThat(queryUuid(
        "select revision_id from content.popup_deliveries where id = ?", first.deliveryId()))
        .isEqualTo(content.revisionId());
    assertThat(queryUuid(
        "select revision_id from content.popup_deliveries where id = ?", second.deliveryId()))
        .isEqualTo(revisionTwo);
  }

  @Test
  void allCampaignIncludesABusinessUserRegisteredDuringItsActiveWindow() {
    Instant now = clock.instant();
    Instant firstPublishedAt = now.minus(Duration.ofHours(2));
    UUID actorId = insertUser();
    UUID userId = insertUser(now.minus(Duration.ofHours(1)), "ACTIVE");
    ContentFixture content = insertContent(actorId, "Dynamic ALL audience");
    UUID campaignId = UUID.randomUUID();
    insertAllCampaign(content.itemId(), actorId, campaignId, firstPublishedAt);

    PopupClaim claim = claimService.claimNextPopup(userId, SURFACE, null).orElseThrow();

    assertThat(claim.campaignId()).isEqualTo(campaignId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"FROZEN", "DISABLED"})
  void selectedTargetBecomesEligibleOnlyAfterTheAccountReturnsToActive(String initialStatus) {
    UUID actorId = insertUser();
    UUID userId = insertUser(clock.instant().minus(Duration.ofDays(1)), initialStatus);
    ContentFixture content = insertContent(actorId, "Selected account recovery");
    UUID campaignId = UUID.randomUUID();
    insertSelectedCampaign(
        content.itemId(), actorId, userId, campaignId, 10, clock.instant().minusSeconds(60));

    assertThat(claimService.claimNextPopup(userId, SURFACE, null)).isEmpty();

    jdbcTemplate.update(
        "update auth.users set status = 'ACTIVE', updated_at = ? where id = ?",
        timestamp(clock.instant()),
        userId);

    PopupClaim claim = claimService.claimNextPopup(userId, SURFACE, null).orElseThrow();
    assertThat(claim.campaignId()).isEqualTo(campaignId);
    assertThat(queryInteger("""
        select count(*) from content.popup_campaign_targets
        where campaign_id = ? and user_id = ?
        """, campaignId, userId)).isOne();
  }

  private List<Optional<PopupClaim>> claimConcurrently(UUID userId) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Callable<Optional<PopupClaim>> call = () -> {
      start.await(5, SECONDS);
      return claimService.claimNextPopup(userId, SURFACE, null);
    };
    try {
      Future<Optional<PopupClaim>> first = workers.submit(call);
      Future<Optional<PopupClaim>> second = workers.submit(call);
      return List.of(first.get(30, SECONDS), second.get(30, SECONDS));
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  private UUID insertUser() {
    Instant now = clock.instant();
    return insertUser(now.minus(Duration.ofDays(1)), "ACTIVE");
  }

  private UUID insertUser(Instant createdAt, String status) {
    UUID userId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role, created_at, updated_at)
        values (?, ?, 'test-hash', ?, 'USER', ?, ?)
        """, userId, "popup-claim-" + userId + "@example.test",
        status, timestamp(createdAt), timestamp(now));
    return userId;
  }

  private ContentFixture insertContent(UUID actorId, String title) {
    UUID itemId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.content_items (id, content_kind, created_at, updated_at)
        values (?, 'POPUP_CAMPAIGN', ?, ?)
        """, itemId, timestamp(now), timestamp(now));
    UUID revisionId = insertRevision(itemId, actorId, 1, title);
    jdbcTemplate.update(
        "update content.content_items set current_revision_id = ? where id = ?",
        revisionId,
        itemId);
    return new ContentFixture(itemId, revisionId);
  }

  private UUID insertRevision(UUID itemId, UUID actorId, int number, String title) {
    UUID revisionId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into content.content_revisions (
          id, content_item_id, revision_no, title, body_document,
          sanitized_html, created_by, created_at
        ) values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?)
        """, revisionId, itemId, number, title, "<p>" + title + "</p>", actorId,
        timestamp(clock.instant()));
    return revisionId;
  }

  private void insertCampaign(
      UUID contentItemId,
      UUID actorId,
      UUID campaignId,
      int priority,
      Instant firstPublishedAt) {
    insertSelectedCampaign(
        contentItemId, actorId, actorId, campaignId, priority, firstPublishedAt);
  }

  private void insertSelectedCampaign(
      UUID contentItemId,
      UUID actorId,
      UUID targetUserId,
      UUID campaignId,
      int priority,
      Instant firstPublishedAt) {
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, priority,
          start_at, end_at, max_total_impressions, max_daily_impressions,
          min_interval_seconds, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, ?, 'ACTIVE', 'SELECTED', ?, ?, ?, 10, 10, 0, ?, ?, ?, ?, ?, ?)
        """,
        campaignId,
        "Popup " + campaignId,
        contentItemId,
        priority,
        timestamp(now.minus(Duration.ofDays(1))),
        timestamp(now.plus(Duration.ofDays(1))),
        timestamp(firstPublishedAt),
        timestamp(firstPublishedAt),
        actorId,
        actorId,
        timestamp(now),
        timestamp(now));
    jdbcTemplate.update("""
        insert into content.popup_campaign_targets (campaign_id, user_id)
        values (?, ?)
        """, campaignId, targetUserId);
  }

  private void insertAllCampaign(
      UUID contentItemId,
      UUID actorId,
      UUID campaignId,
      Instant firstPublishedAt) {
    Instant now = clock.instant();
    jdbcTemplate.update("""
        insert into content.popup_campaigns (
          id, name, content_item_id, lifecycle_status, audience_type, priority,
          start_at, end_at, max_total_impressions, max_daily_impressions,
          min_interval_seconds, first_published_at, last_published_at,
          created_by, updated_by, created_at, updated_at
        ) values (?, ?, ?, 'ACTIVE', 'ALL', 10, ?, ?, 10, 10, 0, ?, ?, ?, ?, ?, ?)
        """,
        campaignId,
        "Popup " + campaignId,
        contentItemId,
        timestamp(firstPublishedAt),
        timestamp(now.plus(Duration.ofDays(1))),
        timestamp(firstPublishedAt),
        timestamp(firstPublishedAt),
        actorId,
        actorId,
        timestamp(now),
        timestamp(now));
  }

  private int queryInteger(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private UUID queryUuid(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, UUID.class, args);
  }

  private void assertPlaintextAbsentFromContentTextColumns(String plaintext) {
    List<TextColumn> columns = jdbcTemplate.query("""
        select table_name, column_name
        from information_schema.columns
        where table_schema = 'content'
          and data_type in ('character', 'character varying', 'text', 'json', 'jsonb')
        """, (row, index) -> new TextColumn(row.getString(1), row.getString(2)));
    for (TextColumn column : columns) {
      String sql = "select count(*) from content." + quote(column.table())
          + " where position(? in cast(" + quote(column.name()) + " as text)) > 0";
      assertThat(queryInteger(sql, plaintext))
          .as("plaintext leaked into content.%s.%s", column.table(), column.name())
          .isZero();
    }
  }

  private static String quote(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private record ContentFixture(UUID itemId, UUID revisionId) {
  }

  private record TextColumn(String table, String name) {
  }
}
