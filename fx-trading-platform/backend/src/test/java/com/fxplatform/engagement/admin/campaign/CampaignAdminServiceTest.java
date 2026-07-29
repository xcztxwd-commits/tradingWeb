package com.fxplatform.engagement.admin.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignActionRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignContentRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignPreviewResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSaveRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignStatsResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSummaryResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignUserResponse;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignDetailRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignStatsRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignSummaryRow;
import com.fxplatform.engagement.admin.campaign.repository.CampaignAdminQueryRepository.CampaignUserRow;
import com.fxplatform.engagement.application.campaign.PopupAudienceService;
import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.application.campaign.PopupCampaignService.ConfiguredCampaign;
import com.fxplatform.engagement.application.content.ContentRevisionService;
import com.fxplatform.engagement.application.content.ContentRevisionService.SavedContentRevision;
import com.fxplatform.engagement.application.popup.PopupOutcomeService;
import com.fxplatform.engagement.domain.campaign.PopupCampaign;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.ContentKind;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import com.fxplatform.engagement.persistence.repository.ContentItemRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignTargetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");
  private static final UUID CAMPAIGN_ID = UUID.fromString(
      "10000000-0000-0000-0000-000000000001");
  private static final UUID ITEM_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000002");
  private static final UUID OLD_REVISION_ID = UUID.fromString(
      "30000000-0000-0000-0000-000000000003");
  private static final UUID NEW_REVISION_ID = UUID.fromString(
      "40000000-0000-0000-0000-000000000004");
  private static final UUID ACTOR_ID = UUID.fromString(
      "50000000-0000-0000-0000-000000000005");
  private static final UUID USER_ID = UUID.fromString(
      "60000000-0000-0000-0000-000000000006");

  @Mock private PopupCampaignRepository campaignRepository;
  @Mock private PopupCampaignTargetRepository targetRepository;
  @Mock private ContentItemRepository contentItemRepository;
  @Mock private ContentRevisionService contentRevisionService;
  @Mock private PopupAudienceService audienceService;
  @Mock private PopupCampaignService campaignService;
  @Mock private PopupOutcomeService outcomeService;
  @Mock private CampaignAdminQueryRepository queryRepository;
  @Mock private EngagementAuditService auditService;

  private CampaignAdminService service;

  @BeforeEach
  void setUp() {
    service = new CampaignAdminService(
        campaignRepository,
        targetRepository,
        contentItemRepository,
        contentRevisionService,
        audienceService,
        campaignService,
        outcomeService,
        queryRepository,
        auditService,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void campaignListUsesIdenticalServerSideFiltersForRowsAndTotalAndReturnsPublishHistory() {
    Instant effectiveFrom = NOW.minus(Duration.ofDays(1));
    Instant effectiveTo = NOW.plus(Duration.ofDays(1));
    Instant firstPublishedAt = NOW.minus(Duration.ofHours(2));
    CampaignSummaryRow row = new CampaignSummaryRow(
        CAMPAIGN_ID,
        "Summer",
        PopupCampaignLifecycleStatus.ACTIVE,
        AudienceType.SELECTED,
        true,
        10,
        NOW.minus(Duration.ofHours(1)),
        NOW.plus(Duration.ofHours(1)),
        firstPublishedAt,
        NEW_REVISION_ID,
        "Campaign title",
        1L,
        NOW);
    when(queryRepository.findCampaigns(
        null, "Summer", "SELECTED", true, effectiveFrom, effectiveTo, 25, 50L))
        .thenReturn(List.of(row));
    when(queryRepository.countCampaigns(
        null, "Summer", "SELECTED", true, effectiveFrom, effectiveTo)).thenReturn(1L);

    var page = service.campaigns(
        2, 25, null, " Summer ", AudienceType.SELECTED, true,
        effectiveFrom, effectiveTo);

    assertThat(page.total()).isEqualTo(1L);
    assertThat(page.items()).singleElement()
        .extracting(CampaignSummaryResponse::firstPublishedAt)
        .isEqualTo(firstPublishedAt);
    verify(queryRepository).findCampaigns(
        null, "Summer", "SELECTED", true, effectiveFrom, effectiveTo, 25, 50L);
    verify(queryRepository).countCampaigns(
        null, "Summer", "SELECTED", true, effectiveFrom, effectiveTo);
  }

  @Test
  void campaignListRejectsEmptyEffectiveWindowBeforeQuerying() {
    for (Instant effectiveTo : List.of(NOW, NOW.minusSeconds(1))) {
      assertThatThrownBy(() -> service.campaigns(
          0, 20, null, null, null, null, NOW, effectiveTo))
          .isInstanceOf(BusinessException.class)
          .extracting("code")
          .isEqualTo("CAMPAIGN_CONFIG_INVALID");
    }

    verifyNoInteractions(queryRepository);
  }

  @Test
  void publishedEditRejectsAudienceTypeChangeBeforeCampaignContentOrOutboxWrites() {
    PopupCampaignEntity published = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    ContentItemEntity currentItem = new ContentItemEntity();
    currentItem.setId(ITEM_ID);
    currentItem.setCurrentRevisionId(OLD_REVISION_ID);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(published);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(currentItem);

    assertThatThrownBy(() -> service.update(
        ACTOR_ID, CAMPAIGN_ID, request(AudienceType.ALL, Set.of(), true)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CAMPAIGN_AUDIENCE_FROZEN");

    verify(campaignService, never()).configure(any(), any(), any());
    verifyNoInteractions(contentRevisionService, audienceService, auditService);
    verify(campaignRepository).selectByIdForUpdate(CAMPAIGN_ID);
    verifyNoMoreInteractions(campaignRepository);
  }

  @Test
  void publishedEditRejectsSyncChangeBeforeCampaignContentOrOutboxWrites() {
    PopupCampaignEntity published = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    ContentItemEntity currentItem = new ContentItemEntity();
    currentItem.setId(ITEM_ID);
    currentItem.setCurrentRevisionId(OLD_REVISION_ID);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(published);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(currentItem);
    assertThatThrownBy(() -> service.update(
        ACTOR_ID, CAMPAIGN_ID, request(AudienceType.SELECTED, Set.of(USER_ID), false)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CAMPAIGN_AUDIENCE_FROZEN");

    verify(campaignService, never()).configure(any(), any(), any());
    verifyNoInteractions(contentRevisionService, audienceService, auditService);
    verify(campaignRepository).selectByIdForUpdate(CAMPAIGN_ID);
    verifyNoMoreInteractions(campaignRepository);
  }

  @Test
  void publishedEditMapsEveryOtherFrozenConfigurationChangeToStableBusinessError() {
    PopupCampaignEntity published = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    CampaignSaveRequest base = request(Set.of(USER_ID));
    ContentItemEntity currentItem = new ContentItemEntity();
    currentItem.setId(ITEM_ID);
    currentItem.setCurrentRevisionId(OLD_REVISION_ID);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(published);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(currentItem);
    List<CampaignSaveRequest> changed = List.of(
        frozenCopy(base, "Renamed", base.timeZone(), base.startAt(), base.templateSize()),
        frozenCopy(base, base.name(), "UTC", base.startAt(), base.templateSize()),
        frozenCopy(base, base.name(), base.timeZone(),
            base.startAt().plusSeconds(1), base.templateSize()),
        frozenCopy(base, base.name(), base.timeZone(), base.startAt(), TemplateSize.LARGE));

    for (CampaignSaveRequest request : changed) {
      assertThatThrownBy(() -> service.update(ACTOR_ID, CAMPAIGN_ID, request))
          .isInstanceOf(BusinessException.class)
          .extracting("code")
          .isEqualTo("CAMPAIGN_CONFIG_FROZEN");
    }

    verify(campaignService, never()).configure(any(), any(), any());
    verifyNoInteractions(contentRevisionService, audienceService, auditService);
    verify(campaignRepository, times(changed.size())).selectByIdForUpdate(CAMPAIGN_ID);
    verifyNoMoreInteractions(campaignRepository);
  }

  @Test
  void publishedSelectedEditOnlyComparesTargetsAndDoesNotAttemptToReplaceFrozenRows() {
    PopupCampaignEntity before = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    PopupCampaignEntity after = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(before, after);
    when(targetRepository.findUserIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(USER_ID));
    ContentItemEntity currentItem = new ContentItemEntity();
    currentItem.setId(ITEM_ID);
    currentItem.setCurrentRevisionId(OLD_REVISION_ID);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(currentItem);
    when(campaignService.configure(eq(CAMPAIGN_ID), any(), eq(ACTOR_ID)))
        .thenReturn(new ConfiguredCampaign(domain(after), ITEM_ID));
    when(contentRevisionService.revise(eq(ITEM_ID), eq(ACTOR_ID), any()))
        .thenReturn(new SavedContentRevision(
            ITEM_ID, NEW_REVISION_ID, 2, "新版", "<p>新版</p>"));
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(1L);
    when(queryRepository.findCampaign(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(detail(after)));

    service.update(ACTOR_ID, CAMPAIGN_ID, request(Set.of(USER_ID)));

    verify(audienceService, never()).replaceTargets(any(), any());
    verify(targetRepository, never()).deleteByCampaignId(any());
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.CAMPAIGN_EDIT), eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()), any(Metadata.class));
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void publishedSelectedEditRejectsAnyTargetListChangeBeforeContentOrCampaignWrites() {
    PopupCampaignEntity published = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    UUID replacementUserId = UUID.fromString("70000000-0000-0000-0000-000000000007");
    ContentItemEntity currentItem = new ContentItemEntity();
    currentItem.setId(ITEM_ID);
    currentItem.setCurrentRevisionId(OLD_REVISION_ID);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(published);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(currentItem);
    when(targetRepository.findUserIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(USER_ID));

    assertThatThrownBy(() -> service.update(
        ACTOR_ID, CAMPAIGN_ID, request(Set.of(replacementUserId))))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CAMPAIGN_AUDIENCE_FROZEN");

    verifyNoInteractions(campaignService, contentRevisionService, audienceService, auditService);
  }

  @Test
  void lifecycleAuditCapturesBeforeStatusBeforeNestedMapperCanMutateTheEntity() {
    PopupCampaignEntity before = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(before);
    when(campaignService.pause(CAMPAIGN_ID, ACTOR_ID)).thenAnswer(invocation -> {
      before.setLifecycleStatus(PopupCampaignLifecycleStatus.PAUSED);
      before.setPausedAt(NOW);
      return domain(before);
    });
    ContentItemEntity item = new ContentItemEntity();
    item.setId(ITEM_ID);
    item.setCurrentRevisionId(OLD_REVISION_ID);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(item);
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(1L);
    when(queryRepository.findCampaign(CAMPAIGN_ID))
        .thenReturn(java.util.Optional.of(detail(before)));

    service.pause(ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("运营暂停"));

    ArgumentCaptor<Metadata> metadata = ArgumentCaptor.forClass(Metadata.class);
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.CAMPAIGN_PAUSE), eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()), metadata.capture());
    assertThat(metadata.getValue().before()).isEqualTo("ACTIVE");
    assertThat(metadata.getValue().after()).isEqualTo("PAUSED");
  }

  @Test
  void selectedPublishFailsClosedWhenAnyFrozenTargetIsNotABusinessUser() {
    PopupCampaignEntity draft = campaign(PopupCampaignLifecycleStatus.DRAFT);
    draft.setFirstPublishedAt(null);
    draft.setLastPublishedAt(null);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(draft);
    when(targetRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2);
    when(targetRepository.countBusinessUsersByCampaignId(CAMPAIGN_ID)).thenReturn(1);

    assertThatThrownBy(() -> service.publish(
            ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("发布")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CAMPAIGN_TARGETS_INVALID");

    verify(campaignService, never()).publish(any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void selectedPublishRequiresAtLeastOneAuthoritativePersistedTarget() {
    PopupCampaignEntity draft = campaign(PopupCampaignLifecycleStatus.DRAFT);
    draft.setFirstPublishedAt(null);
    draft.setLastPublishedAt(null);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(draft);
    when(targetRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(0);
    when(targetRepository.countBusinessUsersByCampaignId(CAMPAIGN_ID)).thenReturn(0);

    assertThatThrownBy(() -> service.publish(
        ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("发布")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CAMPAIGN_TARGETS_REQUIRED");

    verify(campaignService, never()).publish(any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void userDetailSqlJoinsStateBeforeWhereAndNeverReadsDeliveryCredentials() throws Exception {
    Select select = CampaignAdminQueryRepository.class
        .getMethod("findUsers", UUID.class, int.class, long.class)
        .getAnnotation(Select.class);
    String sql = String.join(" ", select.value()).toLowerCase();

    assertThat(sql.indexOf("left join content.popup_campaign_user_states"))
        .isLessThan(sql.indexOf("where campaign.id"));
    assertThat(sql).doesNotContain("token_hash", "token hash", "delivery_token");
  }

  @Test
  void allAudienceCreateWritesOneRevisionAndAuditButNoTargetRows() {
    SavedContentRevision saved = new SavedContentRevision(
        ITEM_ID, NEW_REVISION_ID, 1, "新版", "<p>新版</p>");
    when(contentRevisionService.create(eq(ContentKind.POPUP_CAMPAIGN), eq(ACTOR_ID), any()))
        .thenReturn(saved);
    PopupCampaignEntity[] inserted = new PopupCampaignEntity[1];
    when(campaignRepository.insert(any(PopupCampaignEntity.class))).thenAnswer(invocation -> {
      inserted[0] = invocation.getArgument(0);
      return 1;
    });
    when(queryRepository.countUsers(any(UUID.class))).thenReturn(42L);
    when(queryRepository.findCampaign(any(UUID.class))).thenAnswer(invocation ->
        Optional.of(detail(inserted[0])));

    var response = service.create(ACTOR_ID, allRequest());

    assertThat(response.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.DRAFT);
    assertThat(response.revisionId()).isEqualTo(NEW_REVISION_ID);
    verifyNoInteractions(audienceService);
    verify(targetRepository, never()).deleteByCampaignId(any());
    verify(targetRepository, never()).insertIfAbsent(any(), any());
    ArgumentCaptor<Metadata> metadata = ArgumentCaptor.forClass(Metadata.class);
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.CAMPAIGN_CREATE), eq(TargetType.CAMPAIGN),
        eq(inserted[0].getId().toString()), metadata.capture());
    assertThat(metadata.getValue().revisionId()).isEqualTo(NEW_REVISION_ID);
    assertThat(metadata.getValue().audienceType()).isEqualTo(AudienceType.ALL);
    assertThat(metadata.getValue().targetCount()).isEqualTo(42);
    assertThat(metadata.getValue().before()).isNull();
    assertThat(metadata.getValue().after())
        .doesNotContain("新版", "<p>", "bodyDocument", "password", "token", "hash");
    assertThat(metadata.getValue().reason()).isEqualTo("创建活动");
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void previewReturnsOnlySafeCurrentContentAndWritesNoFormalDeliveryState() {
    PopupCampaignEntity campaign = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    when(queryRepository.findCampaign(CAMPAIGN_ID)).thenReturn(Optional.of(detail(campaign)));
    when(targetRepository.findUserIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(USER_ID));

    CampaignPreviewResponse response = service.testPopup(
        ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("运营预览"));

    assertThat(response.preview()).isTrue();
    assertThat(response.revisionId()).isEqualTo(NEW_REVISION_ID);
    assertThat(response.sanitizedHtml()).isEqualTo("<p>新版</p>");
    assertThat(CampaignPreviewResponse.class.getRecordComponents())
        .extracting(component -> component.getName().toLowerCase())
        .noneMatch(name -> name.contains("token") || name.contains("hash")
            || name.contains("document"));
    verifyNoInteractions(outcomeService, campaignService, contentRevisionService, audienceService);
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.CAMPAIGN_TEST_POPUP), eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()), any(Metadata.class));
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void resetDelegatesToAuthoritativeOutcomeServiceAndRepositorySqlPreservesOptOutAndLease()
      throws Exception {
    PopupCampaignEntity campaign = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(campaign);
    when(outcomeService.resetDeliveryCounters(CAMPAIGN_ID)).thenReturn(7);
    ContentItemEntity item = new ContentItemEntity();
    item.setId(ITEM_ID);
    item.setCurrentRevisionId(OLD_REVISION_ID);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(item);
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(9L);

    var response = service.resetDelivery(
        ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("重新投放"));

    assertThat(response.affectedUsers()).isEqualTo(7);
    verify(outcomeService).resetDeliveryCounters(CAMPAIGN_ID);
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.CAMPAIGN_RESET_DELIVERY), eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()), any(Metadata.class));
    verifyNoMoreInteractions(auditService);

    org.apache.ibatis.annotations.Update update =
        com.fxplatform.engagement.persistence.repository.PopupCampaignUserStateRepository.class
            .getMethod("resetDeliveryCounters", UUID.class)
            .getAnnotation(org.apache.ibatis.annotations.Update.class);
    String sql = String.join(" ", update.value()).toLowerCase();
    assertThat(sql)
        .contains("total_impressions = 0", "daily_bucket = null",
            "daily_impressions = 0", "last_impression_at = null")
        .doesNotContain("opted_out_at", "active_delivery_id", "last_clicked_at");
  }

  @Test
  void statsAndUserDetailExposeNoCredentialFieldsAndUserDetailAuditsExactlyOnce() {
    when(queryRepository.findStats(CAMPAIGN_ID)).thenReturn(Optional.of(
        new CampaignStatsRow(CAMPAIGN_ID, 5L, 4L, 9L, 1L, 2L,
            1L, 2L, 3L, 4L, 5L, 6L)));
    CampaignStatsResponse stats = service.stats(CAMPAIGN_ID);
    assertThat(stats.totalImpressions()).isEqualTo(9);

    PopupCampaignEntity campaign = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    when(queryRepository.findCampaign(CAMPAIGN_ID)).thenReturn(Optional.of(detail(campaign)));
    when(targetRepository.findUserIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(USER_ID));
    when(queryRepository.findUsers(CAMPAIGN_ID, 20, 0L)).thenReturn(List.of(
        new CampaignUserRow(
            USER_ID, "user@example.com", UserStatus.FROZEN, 3, null, 0,
            NOW.minusSeconds(10), NOW.minusSeconds(5), null,
            0L, 0L, 2L, 0L, 0L, 0L)));
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(1L);

    CampaignUserResponse user = service.users(
        ACTOR_ID, CAMPAIGN_ID, 0, 20, "查看投放明细").items().getFirst();

    assertThat(user.userId()).isEqualTo(USER_ID);
    assertThat(user.status()).isEqualTo(UserStatus.FROZEN);
    assertThat(CampaignStatsResponse.class.getRecordComponents())
        .extracting(component -> component.getName().toLowerCase())
        .noneMatch(CampaignAdminServiceTest::isCredentialField);
    assertThat(CampaignUserResponse.class.getRecordComponents())
        .extracting(component -> component.getName().toLowerCase())
        .noneMatch(CampaignAdminServiceTest::isCredentialField);
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.CAMPAIGN_USER_DETAIL_VIEW), eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()), any(Metadata.class));
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void everySuccessfulCommandRunsInsideTheTransactionRequiredByAudit() {
    org.springframework.transaction.annotation.Transactional transactional =
        CampaignAdminService.class.getAnnotation(
            org.springframework.transaction.annotation.Transactional.class);
    assertThat(transactional).isNotNull();
  }

  @ParameterizedTest
  @EnumSource(LifecycleCase.class)
  void everyNonPublishLifecycleCommandWritesExactlyItsOneTypedAudit(LifecycleCase operation) {
    PopupCampaignEntity before = campaign(operation.beforeStatus);
    PopupCampaignEntity after = campaign(operation.afterStatus);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(before);
    ContentItemEntity item = new ContentItemEntity();
    item.setId(ITEM_ID);
    item.setCurrentRevisionId(OLD_REVISION_ID);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(item);
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(1L);
    when(queryRepository.findCampaign(CAMPAIGN_ID)).thenReturn(Optional.of(detail(after)));
    when(targetRepository.findUserIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(USER_ID));
    PopupCampaign result = domain(after);
    switch (operation) {
      case PAUSE -> when(campaignService.pause(CAMPAIGN_ID, ACTOR_ID)).thenReturn(result);
      case RESUME -> when(campaignService.resume(CAMPAIGN_ID, ACTOR_ID)).thenReturn(result);
      case END -> when(campaignService.end(CAMPAIGN_ID, ACTOR_ID)).thenReturn(result);
      case DELETE -> when(campaignService.delete(CAMPAIGN_ID, ACTOR_ID)).thenReturn(result);
      case RESTORE -> when(campaignService.restore(CAMPAIGN_ID, ACTOR_ID)).thenReturn(result);
    }

    CampaignActionRequest request = new CampaignActionRequest("生命周期变更");
    switch (operation) {
      case PAUSE -> service.pause(ACTOR_ID, CAMPAIGN_ID, request);
      case RESUME -> service.resume(ACTOR_ID, CAMPAIGN_ID, request);
      case END -> service.end(ACTOR_ID, CAMPAIGN_ID, request);
      case DELETE -> service.delete(ACTOR_ID, CAMPAIGN_ID, request);
      case RESTORE -> service.restore(ACTOR_ID, CAMPAIGN_ID, request);
    }

    verify(auditService).record(
        eq(ACTOR_ID), eq(operation.auditAction), eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()), any(Metadata.class));
    verifyNoMoreInteractions(auditService);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void immediateAndScheduledPublishEachWriteExactlyOneCorrectAudit(boolean scheduled) {
    PopupCampaignEntity before = campaign(PopupCampaignLifecycleStatus.DRAFT);
    before.setFirstPublishedAt(null);
    before.setLastPublishedAt(null);
    before.setStartAt(scheduled ? NOW.plusSeconds(60) : NOW.minusSeconds(60));
    PopupCampaignEntity after = campaign(
        scheduled ? PopupCampaignLifecycleStatus.SCHEDULED
            : PopupCampaignLifecycleStatus.ACTIVE);
    after.setStartAt(before.getStartAt());
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(before);
    when(targetRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(1);
    when(targetRepository.countBusinessUsersByCampaignId(CAMPAIGN_ID)).thenReturn(1);
    ContentItemEntity item = new ContentItemEntity();
    item.setId(ITEM_ID);
    item.setCurrentRevisionId(OLD_REVISION_ID);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(item);
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(1L);
    when(campaignService.publish(CAMPAIGN_ID, ACTOR_ID)).thenReturn(domain(after));
    when(queryRepository.findCampaign(CAMPAIGN_ID)).thenReturn(Optional.of(detail(after)));
    when(targetRepository.findUserIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(USER_ID));

    service.publish(ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("发布活动"));

    verify(auditService).record(
        eq(ACTOR_ID),
        eq(scheduled ? Action.CAMPAIGN_SCHEDULE : Action.CAMPAIGN_PUBLISH),
        eq(TargetType.CAMPAIGN),
        eq(CAMPAIGN_ID.toString()),
        any(Metadata.class));
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void failedLifecycleCommandWritesNoAuditRecord() {
    PopupCampaignEntity before = campaign(PopupCampaignLifecycleStatus.ACTIVE);
    when(campaignRepository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(before);
    ContentItemEntity item = new ContentItemEntity();
    item.setId(ITEM_ID);
    item.setCurrentRevisionId(OLD_REVISION_ID);
    when(contentItemRepository.selectById(ITEM_ID)).thenReturn(item);
    when(queryRepository.countUsers(CAMPAIGN_ID)).thenReturn(1L);
    when(campaignService.pause(CAMPAIGN_ID, ACTOR_ID))
        .thenThrow(new IllegalStateException("write conflict"));

    assertThatThrownBy(() -> service.pause(
        ACTOR_ID, CAMPAIGN_ID, new CampaignActionRequest("生命周期变更")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflict");

    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  private static CampaignSaveRequest request(Set<UUID> targets) {
    return request(AudienceType.SELECTED, targets, true);
  }

  private static CampaignSaveRequest request(
      AudienceType audienceType, Set<UUID> targets, boolean syncToInbox) {
    return new CampaignSaveRequest(
        "活动",
        new CampaignContentRequest(
            "新版", "{\"type\":\"doc\",\"content\":[]}", null, null),
        audienceType,
        targets,
        syncToInbox,
        10,
        DisplayScope.ALL_BUSINESS_PAGES,
        Set.of(),
        DeviceScope.ALL,
        TemplateSize.MEDIUM,
        "Asia/Shanghai",
        NOW.minusSeconds(60),
        NOW.plusSeconds(3600),
        3,
        1,
        60,
        "更新活动");
  }

  private static CampaignSaveRequest allRequest() {
    CampaignSaveRequest selected = request(Set.of());
    return new CampaignSaveRequest(
        selected.name(),
        selected.content(),
        AudienceType.ALL,
        Set.of(),
        selected.syncToInbox(),
        selected.priority(),
        selected.displayScope(),
        selected.pageKeys(),
        selected.deviceScope(),
        selected.templateSize(),
        selected.timeZone(),
        selected.startAt(),
        selected.endAt(),
        selected.maxTotalImpressions(),
        selected.maxDailyImpressions(),
        selected.minIntervalSeconds(),
        "创建活动");
  }

  private static CampaignSaveRequest frozenCopy(
      CampaignSaveRequest source,
      String name,
      String timeZone,
      Instant startAt,
      TemplateSize templateSize) {
    return new CampaignSaveRequest(
        name,
        source.content(),
        source.audienceType(),
        source.targetUserIds(),
        source.syncToInbox(),
        source.priority(),
        source.displayScope(),
        source.pageKeys(),
        source.deviceScope(),
        templateSize,
        timeZone,
        startAt,
        source.endAt(),
        source.maxTotalImpressions(),
        source.maxDailyImpressions(),
        source.minIntervalSeconds(),
        source.reason());
  }

  private static boolean isCredentialField(String name) {
    return name.contains("password") || name.contains("secret")
        || name.contains("token") || name.contains("hash");
  }

  private static PopupCampaignEntity campaign(PopupCampaignLifecycleStatus status) {
    PopupCampaignEntity entity = new PopupCampaignEntity();
    entity.setId(CAMPAIGN_ID);
    entity.setName("活动");
    entity.setContentItemId(ITEM_ID);
    entity.setLifecycleStatus(status);
    entity.setAudienceType(AudienceType.SELECTED);
    entity.setSyncToInbox(true);
    entity.setPriority(10);
    entity.setDisplayScope(DisplayScope.ALL_BUSINESS_PAGES);
    entity.setPageKeys("[]");
    entity.setDeviceScope(DeviceScope.ALL);
    entity.setTemplateSize(TemplateSize.MEDIUM);
    entity.setTimeZone("Asia/Shanghai");
    entity.setStartAt(NOW.minusSeconds(60));
    entity.setEndAt(NOW.plusSeconds(3600));
    entity.setMaxTotalImpressions(3);
    entity.setMaxDailyImpressions(1);
    entity.setMinIntervalSeconds(60);
    if (status != PopupCampaignLifecycleStatus.DRAFT) {
      entity.setFirstPublishedAt(NOW.minusSeconds(30));
      entity.setLastPublishedAt(NOW.minusSeconds(30));
    }
    if (status == PopupCampaignLifecycleStatus.PAUSED) {
      entity.setPausedAt(NOW.minusSeconds(5));
    } else if (status == PopupCampaignLifecycleStatus.ENDED) {
      entity.setEndedAt(NOW.minusSeconds(5));
    } else if (status == PopupCampaignLifecycleStatus.DELETED) {
      entity.setDeletedAt(NOW.minusSeconds(5));
    }
    entity.setCreatedAt(NOW.minusSeconds(120));
    entity.setUpdatedAt(NOW.minusSeconds(30));
    return entity;
  }

  private static PopupCampaign domain(PopupCampaignEntity entity) {
    return PopupCampaign.rehydrate(
        entity.getId(),
        entity.getLifecycleStatus(),
        entity.getAudienceType(),
        entity.getSyncToInbox(),
        ZoneId.of(entity.getTimeZone()),
        entity.getStartAt(),
        entity.getEndAt(),
        entity.getMaxTotalImpressions(),
        entity.getMaxDailyImpressions(),
        Duration.ofSeconds(entity.getMinIntervalSeconds()),
        entity.getFirstPublishedAt(),
        entity.getLastPublishedAt(),
        entity.getPausedAt(),
        entity.getEndedAt(),
        entity.getDeletedAt());
  }

  private static CampaignDetailRow detail(PopupCampaignEntity entity) {
    return new CampaignDetailRow(
        entity.getId(),
        entity.getName(),
        entity.getLifecycleStatus(),
        entity.getAudienceType(),
        entity.getSyncToInbox(),
        entity.getPriority(),
        entity.getDisplayScope(),
        entity.getPageKeys(),
        entity.getDeviceScope(),
        entity.getTemplateSize(),
        entity.getTimeZone(),
        entity.getStartAt(),
        entity.getEndAt(),
        entity.getMaxTotalImpressions(),
        entity.getMaxDailyImpressions(),
        entity.getMinIntervalSeconds(),
        entity.getFirstPublishedAt(),
        entity.getLastPublishedAt(),
        entity.getPausedAt(),
        entity.getEndedAt(),
        entity.getDeletedAt(),
        ITEM_ID,
        NEW_REVISION_ID,
        2,
        "新版",
        "{\"type\":\"doc\",\"content\":[]}",
        "<p>新版</p>",
        null,
        null,
        null,
        null,
        1L,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private enum LifecycleCase {
    PAUSE(
        PopupCampaignLifecycleStatus.ACTIVE,
        PopupCampaignLifecycleStatus.PAUSED,
        Action.CAMPAIGN_PAUSE),
    RESUME(
        PopupCampaignLifecycleStatus.PAUSED,
        PopupCampaignLifecycleStatus.ACTIVE,
        Action.CAMPAIGN_RESUME),
    END(
        PopupCampaignLifecycleStatus.ACTIVE,
        PopupCampaignLifecycleStatus.ENDED,
        Action.CAMPAIGN_END),
    DELETE(
        PopupCampaignLifecycleStatus.ACTIVE,
        PopupCampaignLifecycleStatus.DELETED,
        Action.CAMPAIGN_DELETE),
    RESTORE(
        PopupCampaignLifecycleStatus.DELETED,
        PopupCampaignLifecycleStatus.PAUSED,
        Action.CAMPAIGN_RESTORE);

    private final PopupCampaignLifecycleStatus beforeStatus;
    private final PopupCampaignLifecycleStatus afterStatus;
    private final Action auditAction;

    LifecycleCase(
        PopupCampaignLifecycleStatus beforeStatus,
        PopupCampaignLifecycleStatus afterStatus,
        Action auditAction) {
      this.beforeStatus = beforeStatus;
      this.afterStatus = afterStatus;
      this.auditAction = auditAction;
    }
  }
}
