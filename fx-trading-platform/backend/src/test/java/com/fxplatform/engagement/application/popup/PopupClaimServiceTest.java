package com.fxplatform.engagement.application.popup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.TokenHashService;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupClaim;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupSurface;
import com.fxplatform.engagement.domain.popup.PopupQueuePolicy;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import com.fxplatform.engagement.persistence.entity.ContentRevisionEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignUserStateEntity;
import com.fxplatform.engagement.persistence.entity.PopupDeliveryEntity;
import com.fxplatform.engagement.persistence.entity.PopupQueueSessionEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.PopupDeliveryStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import com.fxplatform.engagement.persistence.repository.ContentItemRepository;
import com.fxplatform.engagement.persistence.repository.ContentRevisionRepository;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignUserStateRepository;
import com.fxplatform.engagement.persistence.repository.PopupDeliveryRepository;
import com.fxplatform.engagement.persistence.repository.PopupQueueSessionRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PopupClaimServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");
  private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SESSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID CAMPAIGN_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID CONTENT_ITEM_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID REVISION_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
  private static final UUID COVER_ASSET_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000006");
  private static final PopupSurface SURFACE =
      new PopupSurface("LOGIN", "dashboard", DeviceClass.PC);

  @Mock private EngagementUserLockRepository userLockRepository;
  @Mock private SystemSettingRepository settingRepository;
  @Mock private PopupQueueSessionRepository sessionRepository;
  @Mock private PopupCampaignRepository campaignRepository;
  @Mock private PopupDeliveryRepository deliveryRepository;
  @Mock private PopupCampaignUserStateRepository stateRepository;
  @Mock private ContentItemRepository contentItemRepository;
  @Mock private ContentRevisionRepository contentRevisionRepository;

  private PopupDeliveryTokenCodec tokenCodec;
  private PopupClaimService service;

  @BeforeEach
  void setUp() {
    tokenCodec = new PopupDeliveryTokenCodec(new TokenHashService());
    service = new PopupClaimService(
        userLockRepository,
        settingRepository,
        sessionRepository,
        campaignRepository,
        deliveryRepository,
        stateRepository,
        contentItemRepository,
        contentRevisionRepository,
        tokenCodec,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void exposesTheSingleTransactionalClaimSeam() throws Exception {
    Method claim = PopupClaimService.class.getMethod(
        "claimNextPopup", UUID.class, PopupSurface.class, UUID.class);

    assertThat(claim.getReturnType()).isEqualTo(Optional.class);
    assertThat(PopupClaimService.class).hasAnnotation(Transactional.class);
  }

  @Test
  void issuesThirtyTwoByteTokensWithADomainSeparatedHash() {
    String plaintext = tokenCodec.issuePlaintext();

    assertThat(plaintext).matches("^[A-Za-z0-9_-]{43}$");
    assertThat(Base64.getUrlDecoder().decode(plaintext)).hasSize(32);
    assertThat(tokenCodec.hash(plaintext))
        .isEqualTo(new TokenHashService().hash("popup-delivery:" + plaintext))
        .matches("^[0-9a-f]{64}$")
        .isNotEqualTo(plaintext);
    assertThat(new TokenHashService().hash(plaintext)).isNotEqualTo(tokenCodec.hash(plaintext));
  }

  @Test
  void successfulClaimSnapshotsSettingPinsRevisionAndWritesInLockOrder() {
    stubNewSessionSetting("4");
    stubEligibleClaim();
    when(sessionRepository.insertIfAbsent(any(PopupQueueSessionEntity.class))).thenReturn(1);
    when(deliveryRepository.insertIfAbsent(any(PopupDeliveryEntity.class))).thenReturn(1);
    when(stateRepository.setActiveDelivery(
        any(UUID.class), any(UUID.class), any(UUID.class), any(Instant.class), anyLong(), any(Instant.class)))
        .thenReturn(1);
    when(sessionRepository.incrementIssued(any(UUID.class), any(UUID.class), anyInt(), any(Instant.class)))
        .thenReturn(1);

    PopupClaim claim = service.claimNextPopup(USER_ID, SURFACE, null).orElseThrow();

    ArgumentCaptor<PopupQueueSessionEntity> session =
        ArgumentCaptor.forClass(PopupQueueSessionEntity.class);
    verify(sessionRepository).insertIfAbsent(session.capture());
    assertThat(session.getValue().getId()).isEqualTo(claim.queueSessionId());
    assertThat(session.getValue().getMaxItems()).isEqualTo(4);
    assertThat(session.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

    ArgumentCaptor<PopupDeliveryEntity> delivery =
        ArgumentCaptor.forClass(PopupDeliveryEntity.class);
    verify(deliveryRepository).insertIfAbsent(delivery.capture());
    assertThat(delivery.getValue().getRevisionId()).isEqualTo(REVISION_ID);
    assertThat(delivery.getValue().getTokenHash())
        .isEqualTo(tokenCodec.hash(claim.deliveryToken()))
        .isNotEqualTo(claim.deliveryToken());
    assertThat(claim.revisionId()).isEqualTo(REVISION_ID);
    assertThat(claim.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(claim.toString()).contains("deliveryToken=<redacted>").doesNotContain(claim.deliveryToken());

    InOrder order = inOrder(
        userLockRepository,
        sessionRepository,
        deliveryRepository,
        campaignRepository,
        stateRepository,
        contentItemRepository,
        contentRevisionRepository);
    order.verify(userLockRepository).findActiveBusinessUserForUpdate(USER_ID);
    order.verify(sessionRepository).findByIdForUpdate(any(UUID.class));
    order.verify(deliveryRepository).findActiveByUserId(USER_ID);
    order.verify(campaignRepository).findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class));
    order.verify(stateRepository).insertIfAbsent(CAMPAIGN_ID, USER_ID);
    order.verify(stateRepository).findByKeyForUpdate(CAMPAIGN_ID, USER_ID);
    order.verify(contentItemRepository).selectById(CONTENT_ITEM_ID);
    order.verify(contentRevisionRepository).selectById(REVISION_ID);
    order.verify(sessionRepository).insertIfAbsent(any(PopupQueueSessionEntity.class));
    order.verify(deliveryRepository).insertIfAbsent(any(PopupDeliveryEntity.class));
    order.verify(stateRepository).setActiveDelivery(
        any(UUID.class), any(UUID.class), any(UUID.class), any(Instant.class), anyLong(), any(Instant.class));
    order.verify(sessionRepository).incrementIssued(
        any(UUID.class), any(UUID.class), anyInt(), any(Instant.class));
  }

  @Test
  void claimSnapshotsThePinnedRevisionPayloadAndNeverLeaksItsPlaintextTokenFromToString() {
    stubNewSessionSetting("3");
    stubEligibleClaim();
    stubSuccessfulWrites();

    PopupClaim claimed = service.claimNextPopup(USER_ID, SURFACE, null).orElseThrow();

    assertThat(claimed.revisionId()).isEqualTo(REVISION_ID);
    assertThat(claimed.templateSize()).isEqualTo(TemplateSize.MEDIUM);
    assertThat(claimed.title()).isEqualTo("Pinned campaign title");
    assertThat(claimed.sanitizedHtml()).isEqualTo("<p>Pinned safe body</p>");
    assertThat(claimed.coverAssetId()).isEqualTo(COVER_ASSET_ID);
    assertThat(claimed.ctaLabel()).isEqualTo("Open dashboard");
    assertThat(claimed.ctaRouteKey()).isEqualTo("DASHBOARD");
    assertThat(claimed.ctaParams()).isEqualTo("{\"tab\":\"overview\"}");
    assertThat(claimed.toString())
        .contains("deliveryToken=<redacted>")
        .doesNotContain(claimed.deliveryToken(), "Pinned safe body");

    ContentRevisionEntity replacement = contentRevision();
    replacement.setId(UUID.fromString("70000000-0000-0000-0000-000000000007"));
    replacement.setTitle("Later revision title");
    replacement.setSanitizedHtml("<p>Later body</p>");
    ContentItemEntity movedItem = new ContentItemEntity();
    movedItem.setId(CONTENT_ITEM_ID);
    movedItem.setCurrentRevisionId(replacement.getId());
    when(contentItemRepository.selectById(CONTENT_ITEM_ID)).thenReturn(movedItem);
    when(contentRevisionRepository.selectById(replacement.getId())).thenReturn(replacement);
    PopupClaim later = service.claimNextPopup(USER_ID, SURFACE, null).orElseThrow();

    // A later claim sees the new current revision, while the already-issued payload stays pinned.
    assertThat(later.revisionId()).isEqualTo(replacement.getId());
    assertThat(later.title()).isEqualTo("Later revision title");
    assertThat(later.sanitizedHtml()).isEqualTo("<p>Later body</p>");
    assertThat(claimed.revisionId()).isEqualTo(REVISION_ID);
    assertThat(claimed.title()).isEqualTo("Pinned campaign title");
    assertThat(claimed.sanitizedHtml()).isEqualTo("<p>Pinned safe body</p>");
    verify(contentRevisionRepository).selectById(REVISION_ID);
    verify(contentRevisionRepository).selectById(replacement.getId());
  }

  @Test
  void missingSettingUsesDefaultAndDeliveryLeaseIsCappedByCampaignEnd() {
    stubNewSessionSetting(null);
    PopupCampaignEntity campaign = campaign();
    campaign.setEndAt(NOW.plusSeconds(30));
    stubEligibleClaim(campaign);
    stubSuccessfulWrites();

    PopupClaim claim = service.claimNextPopup(USER_ID, SURFACE, null).orElseThrow();

    ArgumentCaptor<PopupQueueSessionEntity> session =
        ArgumentCaptor.forClass(PopupQueueSessionEntity.class);
    verify(sessionRepository).insertIfAbsent(session.capture());
    assertThat(session.getValue().getMaxItems())
        .isEqualTo(PopupQueuePolicy.DEFAULT_MAX_SEQUENTIAL_POPUPS);
    assertThat(claim.expiresAt()).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void invalidSettingFailsClosedBeforeCandidateOrSessionWrite() {
    stubNewSessionSetting("0");

    assertThatIllegalStateException()
        .isThrownBy(() -> service.claimNextPopup(USER_ID, SURFACE, null))
        .withMessageContaining(PopupQueuePolicy.MAX_SEQUENTIAL_POPUPS_SETTING_KEY);

    verify(campaignRepository, never()).findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class));
    verify(sessionRepository, never()).insertIfAbsent(any(PopupQueueSessionEntity.class));
  }

  @Test
  void noCandidateDoesNotPersistAnOrphanSession() {
    stubNewSessionSetting(null);
    when(deliveryRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
    when(campaignRepository.findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class)))
        .thenReturn(Optional.empty());

    assertThat(service.claimNextPopup(USER_ID, SURFACE, null)).isEmpty();

    verify(sessionRepository, never()).insertIfAbsent(any(PopupQueueSessionEntity.class));
    verify(deliveryRepository, never()).insertIfAbsent(any(PopupDeliveryEntity.class));
  }

  @Test
  void callerSuppliedMissingSessionIsRejectedWithoutCreatingIt() {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(user()));
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.empty());

    assertInvalidQueueSession(() -> service.claimNextPopup(USER_ID, SURFACE, SESSION_ID));

    verify(settingRepository, never()).findBySettingKey(any(String.class));
    verify(sessionRepository, never()).insertIfAbsent(any(PopupQueueSessionEntity.class));
    verify(deliveryRepository, never()).findActiveByUserId(any(UUID.class));
    verify(campaignRepository, never()).findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class));
  }

  @Test
  void unexpiredActiveDeliveryStopsBeforeCandidateAndLeavesNewSessionUnwritten() {
    stubNewSessionSetting(null);
    PopupDeliveryEntity active = activeDelivery(NOW.plusSeconds(1));
    when(deliveryRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(active));
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(campaign());
    when(deliveryRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));

    assertThat(service.claimNextPopup(USER_ID, SURFACE, null)).isEmpty();

    verify(campaignRepository, never()).findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class));
    verify(sessionRepository, never()).insertIfAbsent(any(PopupQueueSessionEntity.class));
  }

  @Test
  void expiredActiveDeliveryAndExactStatePointerAreClearedBeforeSearching() {
    stubNewSessionSetting(null);
    PopupDeliveryEntity active = activeDelivery(NOW);
    PopupCampaignUserStateEntity state = state();
    state.setActiveDeliveryId(active.getId());
    state.setActiveDeliveryExpiresAt(NOW);
    when(deliveryRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(active));
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(campaign());
    when(deliveryRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
    when(stateRepository.findByKeyForUpdate(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(state));
    when(deliveryRepository.expireActive(active.getId(), USER_ID, NOW)).thenReturn(1);
    when(stateRepository.clearActiveDelivery(CAMPAIGN_ID, USER_ID, active.getId(), 0L)).thenReturn(1);
    when(campaignRepository.findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class)))
        .thenReturn(Optional.empty());

    assertThat(service.claimNextPopup(USER_ID, SURFACE, null)).isEmpty();

    verify(deliveryRepository).expireActive(active.getId(), USER_ID, NOW);
    verify(stateRepository).clearActiveDelivery(CAMPAIGN_ID, USER_ID, active.getId(), 0L);
  }

  @Test
  void capReachedTerminatesExistingSessionWithoutReadingCurrentSetting() {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(user()));
    PopupQueueSessionEntity capped = session(USER_ID, 2, 2);
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(capped));
    when(sessionRepository.terminate(
        SESSION_ID, USER_ID, PopupClaimService.CAP_REACHED_REASON, NOW)).thenReturn(1);

    assertThat(service.claimNextPopup(USER_ID, SURFACE, SESSION_ID)).isEmpty();

    verify(sessionRepository).terminate(
        SESSION_ID, USER_ID, PopupClaimService.CAP_REACHED_REASON, NOW);
    verify(settingRepository, never()).findBySettingKey(any(String.class));
    verify(campaignRepository, never()).findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class));
  }

  @Test
  void expiredExistingSessionIsTerminatedAtomically() {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(user()));
    PopupQueueSessionEntity expired = session(USER_ID, 2, 0);
    expired.setExpiresAt(NOW);
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(expired));
    when(sessionRepository.terminate(
        SESSION_ID, USER_ID, PopupClaimService.EXPIRED_REASON, NOW)).thenReturn(1);

    assertThat(service.claimNextPopup(USER_ID, SURFACE, SESSION_ID)).isEmpty();

    verify(sessionRepository).terminate(
        SESSION_ID, USER_ID, PopupClaimService.EXPIRED_REASON, NOW);
    verify(campaignRepository, never()).findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class));
  }

  @Test
  void ownerAndSurfaceMismatchFailClosedWithoutTerminatingAnotherSession() {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(user()));
    PopupQueueSessionEntity existing = session(UUID.randomUUID(), 2, 0);
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(existing));

    assertInvalidQueueSession(() -> service.claimNextPopup(USER_ID, SURFACE, SESSION_ID));

    existing.setUserId(USER_ID);
    existing.setSurfacePageKey("orders");
    assertInvalidQueueSession(() -> service.claimNextPopup(USER_ID, SURFACE, SESSION_ID));

    verify(sessionRepository, never()).terminate(
        any(UUID.class), any(UUID.class), any(String.class), any(Instant.class));
  }

  @Test
  void deliveryInsertConflictFailsClosedBeforePointerOrSessionCount() {
    stubNewSessionSetting(null);
    stubEligibleClaim();
    when(sessionRepository.insertIfAbsent(any(PopupQueueSessionEntity.class))).thenReturn(1);
    when(deliveryRepository.insertIfAbsent(any(PopupDeliveryEntity.class))).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.claimNextPopup(USER_ID, SURFACE, null))
        .withMessageContaining("conflict");

    verify(stateRepository, never()).setActiveDelivery(
        any(UUID.class), any(UUID.class), any(UUID.class), any(Instant.class), anyLong(), any(Instant.class));
    verify(sessionRepository, never()).incrementIssued(
        any(UUID.class), any(UUID.class), anyInt(), any(Instant.class));
  }

  @Test
  void candidateSqlContainsEveryAuthoritativeFilterAndExactOrdering() throws Exception {
    Method method = PopupCampaignRepository.class.getMethod(
        "findNextEligibleForUpdate",
        UUID.class, UUID.class, String.class, DeviceClass.class, Instant.class);
    String sql = String.join(" ", method.getAnnotation(Select.class).value())
        .replaceAll("\\s+", " ").toUpperCase();

    assertThat(sql).contains(
        "CAMPAIGN.LIFECYCLE_STATUS = 'ACTIVE'",
        "USER_ROW.STATUS = 'ACTIVE'",
        "USER_ROW.ROLE = 'USER'",
        "POPUP_CAMPAIGN_TARGETS",
        "JSONB_EXISTS",
        "DEVICE_SCOPE",
        "OPTED_OUT_AT IS NULL",
        "TOTAL_IMPRESSIONS",
        "DAILY_BUCKET",
        "MIN_INTERVAL_SECONDS",
        "ACTIVE_DELIVERY_EXPIRES_AT",
        "POPUP_DELIVERIES",
        "ORDER BY CAMPAIGN.PRIORITY DESC, CAMPAIGN.FIRST_PUBLISHED_AT ASC, CAMPAIGN.ID ASC",
        "LIMIT 1",
        "FOR UPDATE OF CAMPAIGN");

    Select userLock = EngagementUserLockRepository.class
        .getMethod("findActiveBusinessUserForUpdate", UUID.class)
        .getAnnotation(Select.class);
    assertThat(String.join(" ", userLock.value()).toUpperCase())
        .contains("AUTH.USERS", "STATUS = 'ACTIVE'", "ROLE = 'USER'", "FOR UPDATE");

    Update terminate = PopupQueueSessionRepository.class
        .getMethod("terminate", UUID.class, UUID.class, String.class, Instant.class)
        .getAnnotation(Update.class);
    assertThat(String.join(" ", terminate.value()).replaceAll("\\s+", " ").toUpperCase())
        .contains(
            "SET TERMINATED_AT = #{NOW}, TERMINATED_REASON = #{REASON}",
            "ID = #{ID}",
            "USER_ID = #{USERID}",
            "TERMINATED_AT IS NULL",
            "TERMINATED_REASON IS NULL");
  }

  private void stubNewSessionSetting(String configured) {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(user()));
    when(sessionRepository.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());
    if (configured == null) {
      when(settingRepository.findBySettingKey(PopupQueuePolicy.MAX_SEQUENTIAL_POPUPS_SETTING_KEY))
          .thenReturn(Optional.empty());
    } else {
      SystemSettingEntity setting = new SystemSettingEntity();
      setting.setSettingValue(configured);
      when(settingRepository.findBySettingKey(PopupQueuePolicy.MAX_SEQUENTIAL_POPUPS_SETTING_KEY))
          .thenReturn(Optional.of(setting));
    }
  }

  private static void assertInvalidQueueSession(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("POPUP_QUEUE_SESSION_INVALID");
          assertThat(exception.getMessage()).isEqualTo("Popup queue session is invalid");
        });
  }

  private void stubEligibleClaim() {
    stubEligibleClaim(campaign());
  }

  private void stubEligibleClaim(PopupCampaignEntity campaign) {
    when(deliveryRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
    when(campaignRepository.findNextEligibleForUpdate(
        any(UUID.class), any(UUID.class), any(String.class), any(DeviceClass.class), any(Instant.class)))
        .thenReturn(Optional.of(campaign));
    when(stateRepository.insertIfAbsent(CAMPAIGN_ID, USER_ID)).thenReturn(1);
    when(stateRepository.findByKeyForUpdate(CAMPAIGN_ID, USER_ID))
        .thenReturn(Optional.of(state()));
    ContentItemEntity item = new ContentItemEntity();
    item.setId(CONTENT_ITEM_ID);
    item.setCurrentRevisionId(REVISION_ID);
    when(contentItemRepository.selectById(CONTENT_ITEM_ID)).thenReturn(item);
    when(contentRevisionRepository.selectById(REVISION_ID)).thenReturn(contentRevision());
  }

  private static ContentRevisionEntity contentRevision() {
    ContentRevisionEntity revision = new ContentRevisionEntity();
    revision.setId(REVISION_ID);
    revision.setContentItemId(CONTENT_ITEM_ID);
    revision.setRevisionNo(1);
    revision.setTitle("Pinned campaign title");
    revision.setSanitizedHtml("<p>Pinned safe body</p>");
    revision.setCoverAssetId(COVER_ASSET_ID);
    revision.setCtaLabel("Open dashboard");
    revision.setCtaRouteKey("DASHBOARD");
    revision.setCtaParams("{\"tab\":\"overview\"}");
    return revision;
  }

  private void stubSuccessfulWrites() {
    when(sessionRepository.insertIfAbsent(any(PopupQueueSessionEntity.class))).thenReturn(1);
    when(deliveryRepository.insertIfAbsent(any(PopupDeliveryEntity.class))).thenReturn(1);
    when(stateRepository.setActiveDelivery(
        any(UUID.class), any(UUID.class), any(UUID.class), any(Instant.class), anyLong(), any(Instant.class)))
        .thenReturn(1);
    when(sessionRepository.incrementIssued(
        any(UUID.class), any(UUID.class), anyInt(), any(Instant.class))).thenReturn(1);
  }

  private static UserEntity user() {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setStatus(UserStatus.ACTIVE);
    user.setRole(UserRole.USER);
    user.setCreatedAt(NOW.minus(Duration.ofDays(30)));
    return user;
  }

  private static PopupQueueSessionEntity session(UUID userId, int maxItems, int issuedCount) {
    PopupQueueSessionEntity session = new PopupQueueSessionEntity();
    session.setId(SESSION_ID);
    session.setUserId(userId);
    session.setTriggerType(SURFACE.triggerType());
    session.setSurfacePageKey(SURFACE.pageKey());
    session.setDeviceClass(SURFACE.deviceClass());
    session.setMaxItems(maxItems);
    session.setIssuedCount(issuedCount);
    session.setCreatedAt(NOW.minusSeconds(1));
    session.setExpiresAt(NOW.plus(Duration.ofMinutes(30)));
    return session;
  }

  private static PopupCampaignEntity campaign() {
    PopupCampaignEntity campaign = new PopupCampaignEntity();
    campaign.setId(CAMPAIGN_ID);
    campaign.setContentItemId(CONTENT_ITEM_ID);
    campaign.setLifecycleStatus(PopupCampaignLifecycleStatus.ACTIVE);
    campaign.setAudienceType(AudienceType.ALL);
    campaign.setSyncToInbox(false);
    campaign.setPriority(10);
    campaign.setDisplayScope(DisplayScope.ALL_BUSINESS_PAGES);
    campaign.setPageKeys("[]");
    campaign.setDeviceScope(DeviceScope.ALL);
    campaign.setTemplateSize(TemplateSize.MEDIUM);
    campaign.setTimeZone("Asia/Shanghai");
    campaign.setStartAt(NOW.minusSeconds(60));
    campaign.setEndAt(NOW.plus(Duration.ofDays(1)));
    campaign.setMaxTotalImpressions(3);
    campaign.setMaxDailyImpressions(1);
    campaign.setMinIntervalSeconds(0);
    campaign.setFirstPublishedAt(NOW.minusSeconds(30));
    campaign.setLastPublishedAt(NOW.minusSeconds(30));
    return campaign;
  }

  private static PopupCampaignUserStateEntity state() {
    PopupCampaignUserStateEntity state = new PopupCampaignUserStateEntity();
    state.setCampaignId(CAMPAIGN_ID);
    state.setUserId(USER_ID);
    state.setTotalImpressions(0);
    state.setDailyImpressions(0);
    state.setVersion(0L);
    return state;
  }

  private static PopupDeliveryEntity activeDelivery(Instant expiresAt) {
    PopupDeliveryEntity delivery = new PopupDeliveryEntity();
    delivery.setId(UUID.randomUUID());
    delivery.setCampaignId(CAMPAIGN_ID);
    delivery.setUserId(USER_ID);
    delivery.setStatus(PopupDeliveryStatus.ISSUED);
    delivery.setExpiresAt(expiresAt);
    return delivery;
  }
}
