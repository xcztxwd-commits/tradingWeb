package com.fxplatform.engagement.application.popup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.domain.popup.PopupOutcome;
import com.fxplatform.engagement.domain.popup.PopupQueuePolicy;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignUserStateEntity;
import com.fxplatform.engagement.persistence.entity.PopupDeliveryEntity;
import com.fxplatform.engagement.persistence.entity.PopupQueueSessionEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.PopupDeliveryStatus;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignUserStateRepository;
import com.fxplatform.engagement.persistence.repository.PopupDeliveryRepository;
import com.fxplatform.engagement.persistence.repository.PopupQueueSessionRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PopupOutcomeServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final UUID CAMPAIGN_ID = UUID.randomUUID();
  private static final UUID DELIVERY_ID = UUID.randomUUID();
  private static final String TOKEN = "A".repeat(43);
  private static final String TOKEN_HASH = "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-07-19T16:00:00Z");
  private static final Instant EXPIRES_AT = NOW.plusSeconds(300);

  @Mock
  private EngagementUserLockRepository userLockRepository;
  @Mock
  private PopupDeliveryRepository deliveryRepository;
  @Mock
  private PopupQueueSessionRepository sessionRepository;
  @Mock
  private PopupCampaignRepository campaignRepository;
  @Mock
  private PopupCampaignUserStateRepository stateRepository;
  @Mock
  private PopupDeliveryTokenCodec tokenCodec;
  @Mock
  private PopupShownHandler shownHandler;

  private PopupOutcomeService service;
  private PopupDeliveryEntity delivery;
  private PopupQueueSessionEntity session;
  private PopupCampaignEntity campaign;
  private PopupCampaignUserStateEntity state;

  @BeforeEach
  void setUp() {
    service = new PopupOutcomeService(
        userLockRepository,
        deliveryRepository,
        sessionRepository,
        campaignRepository,
        stateRepository,
        tokenCodec,
        Clock.fixed(NOW, ZoneOffset.UTC),
        List.of(shownHandler));
  }

  @ParameterizedTest
  @MethodSource("invalidTokens")
  void rejectsMalformedCredentialsBeforeHashingOrDatabaseAccess(String token) {
    assertInvalidCredential(() -> service.recordPopupOutcome(USER_ID, token, PopupOutcome.SHOWN));

    verifyNoInteractions(
        tokenCodec,
        userLockRepository,
        deliveryRepository,
        sessionRepository,
        campaignRepository,
        stateRepository);
  }

  private static Stream<String> invalidTokens() {
    return Stream.of(null, "", "A".repeat(42), "A".repeat(44), "*".repeat(43));
  }

  @Test
  void inactiveOrNonBusinessUserFailsClosedAfterDomainHashWithoutTokenOracleWrites() {
    when(tokenCodec.hash(TOKEN)).thenReturn(TOKEN_HASH);
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID)).thenReturn(Optional.empty());

    assertInvalidCredential(
        () -> service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN));

    verify(deliveryRepository, never()).findIdentityByUserAndTokenHash(any(), any());
    verifyNoInteractions(sessionRepository, campaignRepository, stateRepository);
  }

  @Test
  void ownerMismatchUsesTheSameGenericFailureAndPerformsNoOutcomeWrite() {
    when(tokenCodec.hash(TOKEN)).thenReturn(TOKEN_HASH);
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(activeUser()));
    when(deliveryRepository.findIdentityByUserAndTokenHash(USER_ID, TOKEN_HASH))
        .thenReturn(Optional.empty());

    assertInvalidCredential(
        () -> service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN));

    verifyNoInteractions(sessionRepository, campaignRepository, stateRepository);
    verifyNoOutcomeWrites();
  }

  @ParameterizedTest
  @MethodSource("queueIdentityMismatches")
  void lockedQueueMustMatchBothDeliverySessionIdAndUser(boolean wrongSessionId) {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");
    if (wrongSessionId) {
      session.setId(UUID.randomUUID());
    } else {
      session.setUserId(UUID.randomUUID());
    }

    assertInvalidCredential(
        () -> service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN));

    verifyNoOutcomeWrites();
  }

  private static Stream<Boolean> queueIdentityMismatches() {
    return Stream.of(true, false);
  }

  @ParameterizedTest(name = "SHOWN bucket in {0} is {1}")
  @MethodSource("timeZoneBuckets")
  void shownLocksCanonicalRowsInOrderAndIncrementsTheCampaignTimeZoneBucketOnce(
      String timeZone,
      LocalDate expectedBucket) {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, timeZone);
    when(deliveryRepository.markShown(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.recordShown(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, expectedBucket, NOW)).thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN);

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo(PopupDeliveryStatus.SHOWN);
    assertThat(result.queueDirective())
        .isEqualTo(PopupQueuePolicy.OutcomeDecision.KEEP_CURRENT);
    InOrder locks = inOrder(
        userLockRepository,
        deliveryRepository,
        sessionRepository,
        campaignRepository,
        stateRepository);
    locks.verify(userLockRepository).findActiveBusinessUserForUpdate(USER_ID);
    locks.verify(deliveryRepository).findIdentityByUserAndTokenHash(USER_ID, TOKEN_HASH);
    locks.verify(sessionRepository).findByIdForUpdate(SESSION_ID);
    locks.verify(campaignRepository).selectByIdForUpdate(CAMPAIGN_ID);
    locks.verify(deliveryRepository)
        .findByIdAndCredentialForUpdate(DELIVERY_ID, USER_ID, TOKEN_HASH);
    locks.verify(stateRepository).findByKeyForUpdate(CAMPAIGN_ID, USER_ID);
    verify(deliveryRepository).markShown(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW);
    verify(stateRepository).recordShown(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, expectedBucket, NOW);
  }

  private static Stream<Arguments> timeZoneBuckets() {
    return Stream.of(
        Arguments.of("Asia/Shanghai", LocalDate.parse("2026-07-20")),
        Arguments.of("America/New_York", LocalDate.parse("2026-07-19")));
  }

  @Test
  void duplicateShownIsAnExactReplayWithNoSecondWrite() {
    stubLockedDelivery(PopupDeliveryStatus.SHOWN, "Asia/Shanghai");
    delivery.setShownAt(NOW.minusSeconds(1));

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN);

    assertThat(result).isEqualTo(new PopupOutcomeService.PopupOutcomeResult(
        true,
        PopupDeliveryStatus.SHOWN,
        PopupQueuePolicy.OutcomeDecision.KEEP_CURRENT));
    verifyNoOutcomeWrites();
    verify(shownHandler, never()).onShown(any(), any(), any(), any());
  }

  @Test
  void firstShownInvokesHooksOnlyAfterDeliveryAndStateWritesSucceed() {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");
    when(deliveryRepository.markShown(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.recordShown(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, LocalDate.parse("2026-07-20"), NOW))
        .thenReturn(1);

    service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN);

    InOrder writes = inOrder(deliveryRepository, stateRepository, shownHandler);
    writes.verify(deliveryRepository).markShown(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW);
    writes.verify(stateRepository).recordShown(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, LocalDate.parse("2026-07-20"), NOW);
    writes.verify(shownHandler).onShown(USER_ID, CAMPAIGN_ID, DELIVERY_ID, NOW);
  }

  @Test
  void failedShownStateWriteNeverInvokesAnyHook() {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");
    when(deliveryRepository.markShown(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.recordShown(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, LocalDate.parse("2026-07-20"), NOW))
        .thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN));

    verify(shownHandler, never()).onShown(any(), any(), any(), any());
  }

  @Test
  void expiredIssuedCredentialIsExpiredAndClearsOnlyItsExactPointerWithoutCounting() {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");
    delivery.setExpiresAt(NOW);
    state.setActiveDeliveryExpiresAt(NOW);
    when(deliveryRepository.expireIssued(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.clearActiveDelivery(CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L))
        .thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN);

    assertThat(result).isEqualTo(new PopupOutcomeService.PopupOutcomeResult(
        false,
        PopupDeliveryStatus.EXPIRED,
        PopupQueuePolicy.OutcomeDecision.CONTINUE));
    verify(stateRepository, never()).recordShown(
        any(), any(), any(), anyLong(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("inactiveQueues")
  void terminatedOrExpiredQueueCannotApplyTheRequestedOutcome(
      boolean terminated,
      PopupDeliveryStatus expectedStatus) {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");
    if (terminated) {
      session.setTerminatedAt(NOW.minusSeconds(1));
      session.setTerminatedReason("CTA_CLICK");
      when(deliveryRepository.invalidateActive(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW))
          .thenReturn(1);
    } else {
      session.setExpiresAt(NOW);
      delivery.setExpiresAt(NOW);
      state.setActiveDeliveryExpiresAt(NOW);
      when(deliveryRepository.expireIssued(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW))
          .thenReturn(1);
    }
    when(stateRepository.clearActiveDelivery(CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L))
        .thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo(expectedStatus);
    verify(stateRepository, never()).recordShown(any(), any(), any(), anyLong(), any(), any());
  }

  private static Stream<Arguments> inactiveQueues() {
    return Stream.of(
        Arguments.of(true, PopupDeliveryStatus.INVALIDATED),
        Arguments.of(false, PopupDeliveryStatus.EXPIRED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inactiveCampaigns")
  void inactiveCampaignInvalidatesIssuedOrShownDeliveryWithoutRequestedBusinessSideEffect(
      String description,
      PopupDeliveryStatus deliveryStatus,
      PopupOutcome requestedOutcome,
      PopupCampaignLifecycleStatus lifecycleStatus,
      Instant startAt,
      Instant endAt,
      Instant deletedAt) {
    stubLockedDelivery(deliveryStatus, "Asia/Shanghai");
    if (deliveryStatus == PopupDeliveryStatus.SHOWN) {
      delivery.setShownAt(NOW.minusSeconds(1));
    }
    campaign.setLifecycleStatus(lifecycleStatus);
    campaign.setStartAt(startAt);
    campaign.setEndAt(endAt);
    campaign.setDeletedAt(deletedAt);
    when(deliveryRepository.invalidateActive(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW))
        .thenReturn(1);
    when(stateRepository.clearActiveDelivery(CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L))
        .thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, requestedOutcome);

    assertThat(result.accepted()).as(description).isFalse();
    assertThat(result.status()).isEqualTo(PopupDeliveryStatus.INVALIDATED);
    assertThat(result.queueDirective()).isEqualTo(PopupQueuePolicy.OutcomeDecision.CONTINUE);
    verify(sessionRepository, never()).terminate(any(), any(), any(), any());
    verify(stateRepository, never()).recordOptOutAndClear(
        any(), any(), any(), anyLong(), any());
    verify(stateRepository, never()).recordClickAndClear(
        any(), any(), any(), anyLong(), any());
    verify(stateRepository, never()).recordShown(
        any(), any(), any(), anyLong(), any(), any());
  }

  private static Stream<Arguments> inactiveCampaigns() {
    return Stream.of(
        Arguments.of(
            "paused issued delivery",
            PopupDeliveryStatus.ISSUED,
            PopupOutcome.SHOWN,
            PopupCampaignLifecycleStatus.PAUSED,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1),
            null),
        Arguments.of(
            "paused shown replay",
            PopupDeliveryStatus.SHOWN,
            PopupOutcome.SHOWN,
            PopupCampaignLifecycleStatus.PAUSED,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1),
            null),
        Arguments.of(
            "deleted shown delivery",
            PopupDeliveryStatus.SHOWN,
            PopupOutcome.OPT_OUT,
            PopupCampaignLifecycleStatus.DELETED,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1),
            NOW.minusSeconds(1)),
        Arguments.of(
            "ended time window",
            PopupDeliveryStatus.SHOWN,
            PopupOutcome.CTA_CLICK,
            PopupCampaignLifecycleStatus.ACTIVE,
            NOW.minusSeconds(2),
            NOW,
            null));
  }

  @Test
  void closeIsAllowedOnlyFromShownClearsThePointerAndContinuesWithoutTerminating() {
    stubShown();
    when(deliveryRepository.markClosed(
        DELIVERY_ID, USER_ID, TOKEN_HASH, "CLOSE", NOW)).thenReturn(1);
    when(stateRepository.clearActiveDelivery(CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L))
        .thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CLOSE);

    assertThat(result).isEqualTo(new PopupOutcomeService.PopupOutcomeResult(
        true,
        PopupDeliveryStatus.CLOSED,
        PopupQueuePolicy.OutcomeDecision.CONTINUE));
    verify(sessionRepository, never()).terminate(any(), any(), any(), any());
  }

  @Test
  void optOutIsAllowedOnlyFromShownSetsOptOutAndContinuesWithoutTerminating() {
    stubShown();
    when(deliveryRepository.markClosed(
        DELIVERY_ID, USER_ID, TOKEN_HASH, "OPT_OUT", NOW)).thenReturn(1);
    when(stateRepository.recordOptOutAndClear(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, NOW)).thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.OPT_OUT);

    assertThat(result.queueDirective()).isEqualTo(PopupQueuePolicy.OutcomeDecision.CONTINUE);
    assertThat(result.status()).isEqualTo(PopupDeliveryStatus.CLOSED);
    verify(sessionRepository, never()).terminate(any(), any(), any(), any());
  }

  @Test
  void ctaClickRecordsBothClickTimesClearsPointerAndTerminatesOnlyItsQueue() {
    stubShown();
    when(deliveryRepository.markClicked(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.recordClickAndClear(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, NOW)).thenReturn(1);
    when(sessionRepository.terminate(SESSION_ID, USER_ID, "CTA_CLICK", NOW)).thenReturn(1);

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CTA_CLICK);

    assertThat(result).isEqualTo(new PopupOutcomeService.PopupOutcomeResult(
        true,
        PopupDeliveryStatus.CLICKED,
        PopupQueuePolicy.OutcomeDecision.TERMINATE));
    verify(sessionRepository).terminate(SESSION_ID, USER_ID, "CTA_CLICK", NOW);
  }

  @ParameterizedTest(name = "exact replay {0}")
  @MethodSource("exactTerminalReplays")
  void exactTerminalReplayIsIdempotentAndDoesNotRepeatBusinessSideEffects(
      PopupOutcome replay,
      PopupDeliveryStatus status,
      String closeReason,
      PopupQueuePolicy.OutcomeDecision directive) {
    stubLockedDelivery(status, "Asia/Shanghai");
    delivery.setCloseReason(closeReason);
    delivery.setShownAt(NOW.minusSeconds(2));
    state.setActiveDeliveryId(null);
    state.setActiveDeliveryExpiresAt(null);
    if (status == PopupDeliveryStatus.CLICKED) {
      delivery.setClickedAt(NOW.minusSeconds(1));
      session.setTerminatedAt(NOW.minusSeconds(1));
      session.setTerminatedReason("CTA_CLICK");
    } else {
      delivery.setClosedAt(NOW.minusSeconds(1));
    }

    PopupOutcomeService.PopupOutcomeResult result =
        service.recordPopupOutcome(USER_ID, TOKEN, replay);

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo(status);
    assertThat(result.queueDirective()).isEqualTo(directive);
    verifyNoOutcomeWrites();
  }

  private static Stream<Arguments> exactTerminalReplays() {
    return Stream.of(
        Arguments.of(
            PopupOutcome.CLOSE,
            PopupDeliveryStatus.CLOSED,
            "CLOSE",
            PopupQueuePolicy.OutcomeDecision.CONTINUE),
        Arguments.of(
            PopupOutcome.OPT_OUT,
            PopupDeliveryStatus.CLOSED,
            "OPT_OUT",
            PopupQueuePolicy.OutcomeDecision.CONTINUE),
        Arguments.of(
            PopupOutcome.CTA_CLICK,
            PopupDeliveryStatus.CLICKED,
            null,
            PopupQueuePolicy.OutcomeDecision.TERMINATE));
  }

  @ParameterizedTest(name = "different terminal replay {0} after {1}/{2}")
  @MethodSource("differentTerminalReplays")
  void differentTerminalReplayIsRejectedWithoutAddingAnyMissingSideEffect(
      PopupOutcome replay,
      PopupDeliveryStatus status,
      String closeReason) {
    stubLockedDelivery(status, "Asia/Shanghai");
    delivery.setCloseReason(closeReason);
    delivery.setShownAt(NOW.minusSeconds(2));
    delivery.setClosedAt(NOW.minusSeconds(1));
    if (status == PopupDeliveryStatus.CLICKED) {
      delivery.setClickedAt(NOW.minusSeconds(1));
    }

    assertThatIllegalStateException()
        .isThrownBy(() -> service.recordPopupOutcome(USER_ID, TOKEN, replay))
        .withMessageContaining("already finalized");

    verifyNoOutcomeWrites();
  }

  private static Stream<Arguments> differentTerminalReplays() {
    return Stream.of(
        Arguments.of(PopupOutcome.OPT_OUT, PopupDeliveryStatus.CLOSED, "CLOSE"),
        Arguments.of(PopupOutcome.CLOSE, PopupDeliveryStatus.CLOSED, "OPT_OUT"),
        Arguments.of(PopupOutcome.CLOSE, PopupDeliveryStatus.CLICKED, null),
        Arguments.of(PopupOutcome.CTA_CLICK, PopupDeliveryStatus.CLOSED, "CLOSE"));
  }

  @ParameterizedTest
  @MethodSource("terminalOutcomes")
  void terminalOutcomesAreRejectedBeforeShown(PopupOutcome outcome) {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");

    assertThatIllegalStateException()
        .isThrownBy(() -> service.recordPopupOutcome(USER_ID, TOKEN, outcome))
        .withMessageContaining("must be shown");

    verifyNoOutcomeWrites();
  }

  private static Stream<PopupOutcome> terminalOutcomes() {
    return Stream.of(PopupOutcome.CLOSE, PopupOutcome.OPT_OUT, PopupOutcome.CTA_CLICK);
  }

  @Test
  void everyConditionalWriteMustAffectExactlyOneRow() {
    stubLockedDelivery(PopupDeliveryStatus.ISSUED, "Asia/Shanghai");
    when(deliveryRepository.markShown(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.recordShown(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, LocalDate.parse("2026-07-20"), NOW))
        .thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN))
        .withMessageContaining("write conflict");
  }

  @Test
  void ctaSessionTerminationConflictFailsTheWholeTransactionalCommand() {
    stubShown();
    when(deliveryRepository.markClicked(DELIVERY_ID, USER_ID, TOKEN_HASH, NOW)).thenReturn(1);
    when(stateRepository.recordClickAndClear(
        CAMPAIGN_ID, USER_ID, DELIVERY_ID, 4L, NOW)).thenReturn(1);
    when(sessionRepository.terminate(SESSION_ID, USER_ID, "CTA_CLICK", NOW)).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CTA_CLICK))
        .withMessageContaining("write conflict");
  }

  @Test
  void campaignWideResetLocksCampaignAndPreservesOptOutClickAndActiveLeaseColumns() {
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(activeCampaign("Asia/Shanghai"));
    when(stateRepository.resetDeliveryCounters(CAMPAIGN_ID)).thenReturn(3);

    assertThat(service.resetDeliveryCounters(CAMPAIGN_ID)).isEqualTo(3);

    InOrder order = inOrder(campaignRepository, stateRepository);
    order.verify(campaignRepository).selectByIdForUpdate(CAMPAIGN_ID);
    order.verify(stateRepository).resetDeliveryCounters(CAMPAIGN_ID);
  }

  @Test
  void transactionAndRepositorySqlFreezeCredentialPointerAndResetBoundaries() throws Exception {
    Method outcome = PopupOutcomeService.class.getMethod(
        "recordPopupOutcome", UUID.class, String.class, PopupOutcome.class);
    assertThat(outcome.getAnnotation(Transactional.class)).isNotNull();

    String identitySql = selectSql(PopupDeliveryRepository.class.getMethod(
        "findIdentityByUserAndTokenHash", UUID.class, String.class));
    assertThat(identitySql).contains("USER_ID = #{USERID}", "TOKEN_HASH = #{TOKENHASH}")
        .doesNotContain("FOR UPDATE");

    String lockedSql = selectSql(PopupDeliveryRepository.class.getMethod(
        "findByIdAndCredentialForUpdate", UUID.class, UUID.class, String.class));
    assertThat(lockedSql).contains(
        "ID = #{ID}", "USER_ID = #{USERID}", "TOKEN_HASH = #{TOKENHASH}", "FOR UPDATE");

    String shownSql = updateSql(PopupDeliveryRepository.class.getMethod(
        "markShown", UUID.class, UUID.class, String.class, Instant.class));
    assertThat(shownSql).contains(
        "ID = #{ID}",
        "USER_ID = #{USERID}",
        "TOKEN_HASH = #{TOKENHASH}",
        "STATUS = 'ISSUED'",
        "INVALIDATED_AT IS NULL",
        "EXPIRES_AT > #{NOW}");

    String stateSql = updateSql(PopupCampaignUserStateRepository.class.getMethod(
        "recordShown",
        UUID.class,
        UUID.class,
        UUID.class,
        long.class,
        LocalDate.class,
        Instant.class));
    assertThat(stateSql).contains(
        "ACTIVE_DELIVERY_ID = #{DELIVERYID}",
        "ACTIVE_DELIVERY_EXPIRES_AT > #{NOW}",
        "VERSION = #{EXPECTEDVERSION}",
        "TOTAL_IMPRESSIONS = TOTAL_IMPRESSIONS + 1");

    String resetSql = updateSql(PopupCampaignUserStateRepository.class.getMethod(
        "resetDeliveryCounters", UUID.class));
    assertThat(resetSql).contains(
        "TOTAL_IMPRESSIONS = 0",
        "DAILY_BUCKET = NULL",
        "DAILY_IMPRESSIONS = 0",
        "LAST_IMPRESSION_AT = NULL",
        "VERSION = VERSION + 1",
        "WHERE CAMPAIGN_ID = #{CAMPAIGNID}")
        .doesNotContain(
            "OPTED_OUT_AT =",
            "LAST_CLICKED_AT =",
            "ACTIVE_DELIVERY_ID =",
            "ACTIVE_DELIVERY_EXPIRES_AT =");
  }

  private void stubShown() {
    stubLockedDelivery(PopupDeliveryStatus.SHOWN, "Asia/Shanghai");
    delivery.setShownAt(NOW.minusSeconds(1));
  }

  private void stubLockedDelivery(PopupDeliveryStatus status, String timeZone) {
    delivery = delivery(status);
    session = session();
    campaign = activeCampaign(timeZone);
    state = state();

    when(tokenCodec.hash(TOKEN)).thenReturn(TOKEN_HASH);
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(activeUser()));
    when(deliveryRepository.findIdentityByUserAndTokenHash(USER_ID, TOKEN_HASH))
        .thenReturn(Optional.of(delivery));
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(campaign);
    when(deliveryRepository.findByIdAndCredentialForUpdate(DELIVERY_ID, USER_ID, TOKEN_HASH))
        .thenReturn(Optional.of(delivery));
    when(stateRepository.findByKeyForUpdate(CAMPAIGN_ID, USER_ID))
        .thenReturn(Optional.of(state));
  }

  private void verifyNoOutcomeWrites() {
    verify(deliveryRepository, never()).markShown(any(), any(), any(), any());
    verify(deliveryRepository, never()).markClosed(any(), any(), any(), any(), any());
    verify(deliveryRepository, never()).markClicked(any(), any(), any(), any());
    verify(deliveryRepository, never()).expireIssued(any(), any(), any(), any());
    verify(deliveryRepository, never()).invalidateActive(any(), any(), any(), any());
    verify(stateRepository, never()).recordShown(any(), any(), any(), anyLong(), any(), any());
    verify(stateRepository, never()).recordOptOutAndClear(any(), any(), any(), anyLong(), any());
    verify(stateRepository, never()).recordClickAndClear(any(), any(), any(), anyLong(), any());
    verify(stateRepository, never()).clearActiveDelivery(any(), any(), any(), anyLong());
    verify(sessionRepository, never()).terminate(any(), any(), any(), any());
  }

  private static void assertInvalidCredential(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("POPUP_DELIVERY_CREDENTIAL_INVALID");
          assertThat(exception.getMessage()).isEqualTo("Popup delivery credential is invalid");
        });
  }

  private static UserEntity activeUser() {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setRole(UserRole.USER);
    user.setStatus(UserStatus.ACTIVE);
    return user;
  }

  private static PopupDeliveryEntity delivery(PopupDeliveryStatus status) {
    PopupDeliveryEntity entity = new PopupDeliveryEntity();
    entity.setId(DELIVERY_ID);
    entity.setQueueSessionId(SESSION_ID);
    entity.setCampaignId(CAMPAIGN_ID);
    entity.setUserId(USER_ID);
    entity.setTokenHash(TOKEN_HASH);
    entity.setStatus(status);
    entity.setIssuedAt(NOW.minusSeconds(1));
    entity.setExpiresAt(EXPIRES_AT);
    return entity;
  }

  private static PopupQueueSessionEntity session() {
    PopupQueueSessionEntity entity = new PopupQueueSessionEntity();
    entity.setId(SESSION_ID);
    entity.setUserId(USER_ID);
    entity.setMaxItems(3);
    entity.setIssuedCount(1);
    entity.setCreatedAt(NOW.minusSeconds(2));
    entity.setExpiresAt(EXPIRES_AT);
    return entity;
  }

  private static PopupCampaignEntity activeCampaign(String timeZone) {
    PopupCampaignEntity entity = new PopupCampaignEntity();
    entity.setId(CAMPAIGN_ID);
    entity.setLifecycleStatus(PopupCampaignLifecycleStatus.ACTIVE);
    entity.setAudienceType(AudienceType.ALL);
    entity.setSyncToInbox(false);
    entity.setTimeZone(timeZone);
    entity.setStartAt(NOW.minusSeconds(2));
    entity.setEndAt(EXPIRES_AT.plusSeconds(1));
    entity.setMaxTotalImpressions(3);
    entity.setMaxDailyImpressions(1);
    entity.setMinIntervalSeconds(0);
    entity.setFirstPublishedAt(NOW.minusSeconds(2));
    entity.setLastPublishedAt(NOW.minusSeconds(2));
    return entity;
  }

  private static PopupCampaignUserStateEntity state() {
    PopupCampaignUserStateEntity entity = new PopupCampaignUserStateEntity();
    entity.setCampaignId(CAMPAIGN_ID);
    entity.setUserId(USER_ID);
    entity.setTotalImpressions(0);
    entity.setDailyImpressions(0);
    entity.setActiveDeliveryId(DELIVERY_ID);
    entity.setActiveDeliveryExpiresAt(EXPIRES_AT);
    entity.setVersion(4L);
    return entity;
  }

  private static String selectSql(Method method) {
    return String.join(" ", method.getAnnotation(Select.class).value())
        .replaceAll("\\s+", " ")
        .toUpperCase();
  }

  private static String updateSql(Method method) {
    return String.join(" ", method.getAnnotation(Update.class).value())
        .replaceAll("\\s+", " ")
        .toUpperCase();
  }
}
