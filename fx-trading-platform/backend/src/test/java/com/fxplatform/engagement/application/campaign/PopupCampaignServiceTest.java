package com.fxplatform.engagement.application.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.engagement.application.message.CampaignMessageSyncService;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.AggregateType;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.UpdateType;
import com.fxplatform.engagement.domain.campaign.PopupCampaign;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PopupCampaignServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");
  private static final UUID CAMPAIGN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

  @Mock private PopupCampaignRepository repository;
  @Mock private CampaignMessageSyncService messageSyncService;
  @Mock private EngagementOutboxService outboxService;

  private PopupCampaignService service;

  @BeforeEach
  void setUp() {
    service = new PopupCampaignService(
        repository, fixed(NOW), messageSyncService, outboxService);
  }

  @Test
  void queryRehydratesEveryCampaignDomainFieldWithoutTakingAWriteLock() {
    PopupCampaignEntity entity = entity(PopupCampaignLifecycleStatus.DELETED);
    entity.setAudienceType(AudienceType.SELECTED);
    entity.setSyncToInbox(false);
    entity.setTimeZone("America/New_York");
    entity.setStartAt(NOW.minusSeconds(900));
    entity.setEndAt(NOW.plusSeconds(900));
    entity.setMaxTotalImpressions(9);
    entity.setMaxDailyImpressions(4);
    entity.setMinIntervalSeconds(37);
    entity.setFirstPublishedAt(NOW.minusSeconds(800));
    entity.setLastPublishedAt(NOW.minusSeconds(700));
    entity.setPausedAt(NOW.minusSeconds(600));
    entity.setEndedAt(NOW.minusSeconds(500));
    entity.setDeletedAt(NOW.minusSeconds(400));
    when(repository.selectById(CAMPAIGN_ID)).thenReturn(entity);

    PopupCampaign actual = service.get(CAMPAIGN_ID);

    assertThat(actual.id()).isEqualTo(CAMPAIGN_ID);
    assertThat(actual.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.DELETED);
    assertThat(actual.audienceType()).isEqualTo(AudienceType.SELECTED);
    assertThat(actual.syncToInbox()).isFalse();
    assertThat(actual.timeZone()).isEqualTo(ZoneId.of("America/New_York"));
    assertThat(actual.startAt()).isEqualTo(NOW.minusSeconds(900));
    assertThat(actual.endAt()).isEqualTo(NOW.plusSeconds(900));
    assertThat(actual.maxTotalImpressions()).isEqualTo(9);
    assertThat(actual.maxDailyImpressions()).isEqualTo(4);
    assertThat(actual.minInterval()).isEqualTo(Duration.ofSeconds(37));
    assertThat(actual.firstPublishedAt()).isEqualTo(NOW.minusSeconds(800));
    assertThat(actual.lastPublishedAt()).isEqualTo(NOW.minusSeconds(700));
    assertThat(actual.pausedAt()).isEqualTo(NOW.minusSeconds(600));
    assertThat(actual.endedAt()).isEqualTo(NOW.minusSeconds(500));
    assertThat(actual.deletedAt()).isEqualTo(NOW.minusSeconds(400));
    verify(repository, never()).selectByIdForUpdate(any(UUID.class));
  }

  @Test
  void queryFailsClosedOnImpossiblePersistedLifecycleState() {
    PopupCampaignEntity activeWithoutPublication = entity(PopupCampaignLifecycleStatus.ACTIVE);
    PopupCampaignEntity draftMarkedDeleted = entity(PopupCampaignLifecycleStatus.DRAFT);
    draftMarkedDeleted.setDeletedAt(NOW.minusSeconds(1));
    when(repository.selectById(CAMPAIGN_ID))
        .thenReturn(activeWithoutPublication, draftMarkedDeleted);

    assertThatIllegalArgumentException().isThrownBy(() -> service.get(CAMPAIGN_ID));
    assertThatIllegalArgumentException().isThrownBy(() -> service.get(CAMPAIGN_ID));
  }

  @Test
  void everyCommandLocksTheCanonicalRowAndWritesItAtMostOnce() {
    PopupCampaignEntity draft = entity(PopupCampaignLifecycleStatus.DRAFT);
    PopupCampaignEntity activeForPause = published(PopupCampaignLifecycleStatus.ACTIVE);
    PopupCampaignEntity pausedForResume = published(PopupCampaignLifecycleStatus.PAUSED);
    pausedForResume.setPausedAt(NOW.minusSeconds(1));
    PopupCampaignEntity activeForEnd = published(PopupCampaignLifecycleStatus.ACTIVE);
    PopupCampaignEntity activeForDelete = published(PopupCampaignLifecycleStatus.ACTIVE);
    PopupCampaignEntity deletedForRestore = published(PopupCampaignLifecycleStatus.DELETED);
    deletedForRestore.setDeletedAt(NOW.minusSeconds(1));
    PopupCampaignEntity draftForConfigure = entity(PopupCampaignLifecycleStatus.DRAFT);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID))
        .thenReturn(
            draft,
            activeForPause,
            pausedForResume,
            activeForEnd,
            activeForDelete,
            deletedForRestore,
            draftForConfigure);
    when(repository.updateById(any(PopupCampaignEntity.class))).thenReturn(1);

    assertThat(service.publish(CAMPAIGN_ID, ACTOR_ID).lifecycleStatus())
        .isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    assertThat(service.pause(CAMPAIGN_ID, ACTOR_ID).lifecycleStatus())
        .isEqualTo(PopupCampaignLifecycleStatus.PAUSED);
    assertThat(service.resume(CAMPAIGN_ID, ACTOR_ID).lifecycleStatus())
        .isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    assertThat(service.end(CAMPAIGN_ID, ACTOR_ID).lifecycleStatus())
        .isEqualTo(PopupCampaignLifecycleStatus.ENDED);
    assertThat(service.delete(CAMPAIGN_ID, ACTOR_ID).lifecycleStatus())
        .isEqualTo(PopupCampaignLifecycleStatus.DELETED);
    assertThat(service.restore(CAMPAIGN_ID, ACTOR_ID).lifecycleStatus())
        .isEqualTo(PopupCampaignLifecycleStatus.PAUSED);
    assertThat(service.reconfigure(
            CAMPAIGN_ID,
            AudienceType.SELECTED,
            false,
            NOW.plusSeconds(1200),
            10,
            2,
            Duration.ofMinutes(5),
            ACTOR_ID)
        .audienceType()).isEqualTo(AudienceType.SELECTED);

    verify(repository, org.mockito.Mockito.times(7)).selectByIdForUpdate(CAMPAIGN_ID);
    verify(repository, org.mockito.Mockito.times(7)).updateById(any(PopupCampaignEntity.class));
    verify(messageSyncService, org.mockito.Mockito.times(7))
        .synchronize(any(PopupCampaignEntity.class), org.mockito.ArgumentMatchers.eq(ACTOR_ID),
            org.mockito.ArgumentMatchers.eq(NOW));
    verify(outboxService, org.mockito.Mockito.times(4)).append(
        org.mockito.ArgumentMatchers.eq(UpdateType.CAMPAIGN_UPDATED),
        org.mockito.ArgumentMatchers.eq(AggregateType.POPUP_CAMPAIGN),
        org.mockito.ArgumentMatchers.eq(CAMPAIGN_ID),
        any(AudienceType.class),
        org.mockito.ArgumentMatchers.eq(NOW));
    verify(outboxService, org.mockito.Mockito.times(3)).append(
        org.mockito.ArgumentMatchers.eq(UpdateType.CAMPAIGN_INVALIDATED),
        org.mockito.ArgumentMatchers.eq(AggregateType.POPUP_CAMPAIGN),
        org.mockito.ArgumentMatchers.eq(CAMPAIGN_ID),
        any(AudienceType.class),
        org.mockito.ArgumentMatchers.eq(NOW));
  }

  @Test
  void canonicalPublishedRowFreezesAudienceAndSyncWithoutAnyUpdate() {
    PopupCampaignEntity canonical = published(PopupCampaignLifecycleStatus.ACTIVE);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(canonical);

    assertThatIllegalStateException().isThrownBy(() -> service.reconfigure(
        CAMPAIGN_ID,
        AudienceType.SELECTED,
        true,
        canonical.getEndAt(),
        canonical.getMaxTotalImpressions(),
        canonical.getMaxDailyImpressions(),
        Duration.ofSeconds(canonical.getMinIntervalSeconds()),
        ACTOR_ID));
    assertThatIllegalStateException().isThrownBy(() -> service.reconfigure(
        CAMPAIGN_ID,
        AudienceType.ALL,
        false,
        canonical.getEndAt(),
        canonical.getMaxTotalImpressions(),
        canonical.getMaxDailyImpressions(),
        Duration.ofSeconds(canonical.getMinIntervalSeconds()),
        ACTOR_ID));

    verify(repository, org.mockito.Mockito.times(2)).selectByIdForUpdate(CAMPAIGN_ID);
    verify(repository, never()).updateById(any(PopupCampaignEntity.class));
    verifyNoInteractions(messageSyncService);
  }

  @Test
  void publishedCampaignCanChangeEndAndFrequencyWithoutLosingPublicationState() {
    PopupCampaignEntity canonical = published(PopupCampaignLifecycleStatus.PAUSED);
    canonical.setPausedAt(NOW.minusSeconds(10));
    canonical.setName("untouched");
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(canonical);
    when(repository.updateById(canonical)).thenReturn(1);

    PopupCampaign updated = service.reconfigure(
        CAMPAIGN_ID,
        AudienceType.ALL,
        true,
        NOW.plusSeconds(3600),
        12,
        3,
        Duration.ofMinutes(30),
        ACTOR_ID);

    assertThat(updated.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.PAUSED);
    assertThat(updated.firstPublishedAt()).isEqualTo(NOW.minusSeconds(100));
    assertThat(updated.lastPublishedAt()).isEqualTo(NOW.minusSeconds(50));
    assertThat(updated.pausedAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(updated.endAt()).isEqualTo(NOW.plusSeconds(3600));
    assertThat(updated.maxTotalImpressions()).isEqualTo(12);
    assertThat(updated.maxDailyImpressions()).isEqualTo(3);
    assertThat(updated.minInterval()).isEqualTo(Duration.ofMinutes(30));
    assertThat(canonical.getName()).isEqualTo("untouched");
    assertThat(canonical.getUpdatedBy()).isEqualTo(ACTOR_ID);
    assertThat(canonical.getUpdatedAt()).isEqualTo(NOW);
    verify(repository).updateById(canonical);
  }

  @Test
  void completeAdminConfigurationFreezesPublishedIdentityButAllowsDeliveryPresentationFields() {
    UUID contentItemId = UUID.randomUUID();
    PopupCampaignEntity canonical = published(PopupCampaignLifecycleStatus.ACTIVE);
    canonical.setName("Frozen name");
    canonical.setContentItemId(contentItemId);
    canonical.setPriority(1);
    canonical.setDisplayScope(DisplayScope.ALL_BUSINESS_PAGES);
    canonical.setPageKeys("[]");
    canonical.setDeviceScope(DeviceScope.ALL);
    canonical.setTemplateSize(TemplateSize.MEDIUM);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(canonical);
    when(repository.updateById(canonical)).thenReturn(1);

    var result = service.configure(
        CAMPAIGN_ID,
        new PopupCampaignService.CampaignConfiguration(
            "Frozen name",
            AudienceType.ALL,
            true,
            99,
            DisplayScope.SELECTED_PAGES,
            "[\"WALLET\"]",
            DeviceScope.MOBILE,
            TemplateSize.MEDIUM,
            "Asia/Shanghai",
            canonical.getStartAt(),
            NOW.plusSeconds(7200),
            12,
            3,
            180),
        ACTOR_ID);

    assertThat(result.contentItemId()).isEqualTo(contentItemId);
    assertThat(canonical.getPriority()).isEqualTo(99);
    assertThat(canonical.getDisplayScope()).isEqualTo(DisplayScope.SELECTED_PAGES);
    assertThat(canonical.getPageKeys()).isEqualTo("[\"WALLET\"]");
    assertThat(canonical.getDeviceScope()).isEqualTo(DeviceScope.MOBILE);
    assertThat(result.campaign().endAt()).isEqualTo(NOW.plusSeconds(7200));
    assertThat(result.campaign().maxTotalImpressions()).isEqualTo(12);
    verify(repository).updateById(canonical);
    verify(messageSyncService).synchronize(canonical, ACTOR_ID, NOW);
    verify(outboxService).append(
        UpdateType.CAMPAIGN_UPDATED,
        AggregateType.POPUP_CAMPAIGN,
        CAMPAIGN_ID,
        AudienceType.ALL,
        NOW);
  }

  @Test
  void completeAdminConfigurationRejectsPublishedNameTemplateStartAndTimeZoneChanges() {
    PopupCampaignEntity canonical = published(PopupCampaignLifecycleStatus.ACTIVE);
    canonical.setName("Frozen name");
    canonical.setContentItemId(UUID.randomUUID());
    canonical.setPriority(1);
    canonical.setDisplayScope(DisplayScope.ALL_BUSINESS_PAGES);
    canonical.setPageKeys("[]");
    canonical.setDeviceScope(DeviceScope.ALL);
    canonical.setTemplateSize(TemplateSize.MEDIUM);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(canonical);

    assertThatIllegalStateException().isThrownBy(() -> service.configure(
        CAMPAIGN_ID,
        new PopupCampaignService.CampaignConfiguration(
            "Changed name",
            AudienceType.ALL,
            true,
            1,
            DisplayScope.ALL_BUSINESS_PAGES,
            "[]",
            DeviceScope.ALL,
            TemplateSize.LARGE,
            "UTC",
            canonical.getStartAt().plusSeconds(1),
            canonical.getEndAt(),
            3,
            1,
            60),
        ACTOR_ID));

    verify(repository, never()).updateById(any(PopupCampaignEntity.class));
    verifyNoInteractions(messageSyncService);
  }

  @Test
  void zeroRowUpdateFailsClosedAsAWriteConflict() {
    PopupCampaignEntity canonical = entity(PopupCampaignLifecycleStatus.DRAFT);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(canonical);
    when(repository.updateById(canonical)).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.publish(CAMPAIGN_ID, ACTOR_ID))
        .withMessageContaining("conflict");
    verifyNoInteractions(messageSyncService);
  }

  @Test
  void campaignWriteCommitsBeforeMessageSynchronizationInTheSameCommand() {
    PopupCampaignEntity draft = entity(PopupCampaignLifecycleStatus.DRAFT);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(draft);
    when(repository.updateById(draft)).thenReturn(1);

    service.publish(CAMPAIGN_ID, ACTOR_ID);

    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(repository, messageSyncService, outboxService);
    order.verify(repository).selectByIdForUpdate(CAMPAIGN_ID);
    order.verify(repository).updateById(draft);
    order.verify(messageSyncService).synchronize(draft, ACTOR_ID, NOW);
    order.verify(outboxService).append(
        UpdateType.CAMPAIGN_UPDATED,
        AggregateType.POPUP_CAMPAIGN,
        CAMPAIGN_ID,
        AudienceType.ALL,
        NOW);
  }

  @Test
  void idempotentActivePublishStillSynchronizesTheCanonicalPublication() {
    PopupCampaignEntity active = published(PopupCampaignLifecycleStatus.ACTIVE);
    when(repository.selectByIdForUpdate(CAMPAIGN_ID)).thenReturn(active);

    PopupCampaign result = service.publish(CAMPAIGN_ID, ACTOR_ID);

    assertThat(result.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    verify(repository, never()).updateById(any(PopupCampaignEntity.class));
    verify(messageSyncService).synchronize(active, ACTOR_ID, NOW);
    verifyNoInteractions(outboxService);
  }

  @Test
  void repositoryDeclaresAnExplicitForUpdateQuery() throws Exception {
    Select query = PopupCampaignRepository.class
        .getMethod("selectByIdForUpdate", UUID.class)
        .getAnnotation(Select.class);

    assertThat(query).isNotNull();
    assertThat(String.join(" ", query.value())).containsIgnoringCase("FOR UPDATE");
  }

  @Test
  void restoredUnpublishedDraftCannotResumeButCanBePublished() {
    PopupCampaign restored = draft(NOW.minusSeconds(60), NOW.plusSeconds(600))
        .deleteAt(NOW.minusSeconds(2))
        .restoreAt(NOW.minusSeconds(1));

    assertThat(restored.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.PAUSED);
    assertThat(restored.firstPublishedAt()).isNull();
    assertThatIllegalStateException().isThrownBy(() -> restored.resumeAt(NOW));

    PopupCampaign published = restored.publishAt(NOW);
    assertThat(published.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    assertThat(published.firstPublishedAt()).isEqualTo(NOW);
    assertThat(published.lastPublishedAt()).isEqualTo(NOW);
  }

  @Test
  void republishingARestoredCampaignKeepsFirstAndAdvancesLastPublication() {
    Instant first = NOW.minusSeconds(100);
    PopupCampaign initiallyPublished = draft(NOW.minusSeconds(200), NOW.plusSeconds(600))
        .publishAt(first);
    PopupCampaign restored = initiallyPublished
        .deleteAt(NOW.minusSeconds(2))
        .restoreAt(NOW.minusSeconds(1));

    PopupCampaign republished = restored.publishAt(NOW);

    assertThat(republished.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    assertThat(republished.firstPublishedAt()).isEqualTo(first);
    assertThat(republished.lastPublishedAt()).isEqualTo(NOW);
  }

  @Test
  void pauseAndResumeDoNotExtendTheConfiguredEndAndExpiryEndsTheCampaign() {
    Instant configuredEnd = NOW.plusSeconds(60);
    PopupCampaign active = draft(NOW.minusSeconds(60), configuredEnd).publishAt(NOW);
    PopupCampaign paused = active.pauseAt(NOW.plusSeconds(30));

    PopupCampaign expired = paused.resumeAt(configuredEnd);

    assertThat(paused.endAt()).isEqualTo(configuredEnd);
    assertThat(expired.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ENDED);
    assertThat(expired.endAt()).isEqualTo(configuredEnd);
    assertThat(expired.endedAt()).isEqualTo(configuredEnd);
  }

  @Test
  void expiredCampaignCannotBePublished() {
    PopupCampaign expired = draft(NOW.minusSeconds(600), NOW);

    assertThatIllegalStateException().isThrownBy(() -> expired.publishAt(NOW));
  }

  @Test
  void rejectsInvalidRequiredFrequencyAndTimeWindowsOnCreateAndReconfigure() {
    assertThatIllegalArgumentException().isThrownBy(() -> draft(NOW, NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> draft(NOW.plusSeconds(1), NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> PopupCampaign.draft(
        UUID.randomUUID(), AudienceType.ALL, true, ZoneId.of("Asia/Shanghai"),
        NOW, NOW.plusSeconds(60), 2, 3, Duration.ZERO));
    assertThatIllegalArgumentException().isThrownBy(() -> draft(NOW, NOW.plusSeconds(60))
        .reconfigure(AudienceType.ALL, true, NOW, 3, 1, Duration.ZERO));
    assertThatIllegalArgumentException().isThrownBy(() -> draft(NOW, NOW.plusSeconds(60))
        .reconfigure(AudienceType.ALL, true, NOW.plusSeconds(60), 2, 3, Duration.ZERO));
    assertThatIllegalArgumentException().isThrownBy(() -> draft(NOW, NOW.plusSeconds(60))
        .reconfigure(AudienceType.ALL, true, NOW.plusSeconds(60), 3, 1, Duration.ofSeconds(-1)));
  }

  private static PopupCampaignEntity entity(PopupCampaignLifecycleStatus status) {
    PopupCampaignEntity entity = new PopupCampaignEntity();
    entity.setId(CAMPAIGN_ID);
    entity.setLifecycleStatus(status);
    entity.setAudienceType(AudienceType.ALL);
    entity.setSyncToInbox(true);
    entity.setTimeZone("Asia/Shanghai");
    entity.setStartAt(NOW.minusSeconds(300));
    entity.setEndAt(NOW.plusSeconds(600));
    entity.setMaxTotalImpressions(3);
    entity.setMaxDailyImpressions(1);
    entity.setMinIntervalSeconds(60);
    return entity;
  }

  private static PopupCampaignEntity published(PopupCampaignLifecycleStatus status) {
    PopupCampaignEntity entity = entity(status);
    entity.setFirstPublishedAt(NOW.minusSeconds(100));
    entity.setLastPublishedAt(NOW.minusSeconds(50));
    return entity;
  }

  private static PopupCampaign draft(Instant startAt, Instant endAt) {
    return PopupCampaign.draft(
        CAMPAIGN_ID,
        AudienceType.ALL,
        true,
        ZoneId.of("Asia/Shanghai"),
        startAt,
        endAt,
        3,
        1,
        Duration.ofHours(4));
  }

  private static Clock fixed(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
