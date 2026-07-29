package com.fxplatform.engagement.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.repository.MessagePublicationRepository;
import com.fxplatform.engagement.persistence.repository.MessageTargetRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class CampaignMessageSyncTest {

  private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");
  private static final Instant END_AT = NOW.plusSeconds(3_600);
  private static final UUID CAMPAIGN_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID CONTENT_ITEM_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID PUBLICATION_ID =
      UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID ACTOR_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000004");

  @Mock private MessagePublicationRepository publicationRepository;
  @Mock private MessageTargetRepository targetRepository;

  private CampaignMessageSyncService service;

  @BeforeEach
  void setUp() {
    service = new CampaignMessageSyncService(publicationRepository, targetRepository);
  }

  @Test
  void syncDisabledIsAnAbsoluteNoOp() {
    PopupCampaignEntity campaign = campaign(PopupCampaignLifecycleStatus.ACTIVE, AudienceType.ALL);
    campaign.setSyncToInbox(false);

    service.synchronize(campaign, ACTOR_ID, NOW);

    verifyNoInteractions(publicationRepository, targetRepository);
  }

  @Test
  void firstScheduledPublicationDoesNotCreateAnInboxMessageUntilActuallyActive() {
    PopupCampaignEntity scheduled = campaign(
        PopupCampaignLifecycleStatus.SCHEDULED, AudienceType.ALL);
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.empty());

    service.synchronize(scheduled, ACTOR_ID, NOW);

    verify(publicationRepository, never()).insert(any(MessagePublicationEntity.class));
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
    verifyNoInteractions(targetRepository);
  }

  @Test
  void firstActiveAllCampaignCreatesOneSentPublicationSharingContentAndEndCutoff() {
    PopupCampaignEntity active = campaign(PopupCampaignLifecycleStatus.ACTIVE, AudienceType.ALL);
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.empty());
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(1);

    service.synchronize(active, ACTOR_ID, NOW);

    ArgumentCaptor<MessagePublicationEntity> inserted =
        ArgumentCaptor.forClass(MessagePublicationEntity.class);
    verify(publicationRepository).insert(inserted.capture());
    MessagePublicationEntity publication = inserted.getValue();
    assertThat(publication.getId()).isNotNull();
    assertThat(publication.getContentItemId()).isEqualTo(CONTENT_ITEM_ID);
    assertThat(publication.getSourceType()).isEqualTo(MessageSourceType.CAMPAIGN);
    assertThat(publication.getSourceCampaignId()).isEqualTo(CAMPAIGN_ID);
    assertThat(publication.getAudienceType()).isEqualTo(AudienceType.ALL);
    assertThat(publication.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(publication.getCategory()).isEqualTo("CAMPAIGN");
    assertThat(publication.getSentAt()).isEqualTo(NOW);
    assertThat(publication.getAudienceCutoffAt()).isEqualTo(END_AT);
    assertThat(publication.getCreatedBy()).isEqualTo(ACTOR_ID);
    assertThat(publication.getUpdatedBy()).isEqualTo(ACTOR_ID);
    assertThat(publication.getCreatedAt()).isEqualTo(NOW);
    assertThat(publication.getUpdatedAt()).isEqualTo(NOW);
    verifyNoInteractions(targetRepository);
  }

  @Test
  void firstActiveSelectedCampaignFreezesTargetsWithOneInsertSelect() {
    PopupCampaignEntity active = campaign(
        PopupCampaignLifecycleStatus.ACTIVE, AudienceType.SELECTED);
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.empty());
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(1);
    when(targetRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(3);
    when(targetRepository.insertFromCampaign(any(UUID.class), any(UUID.class))).thenReturn(3);

    service.synchronize(active, ACTOR_ID, NOW);

    ArgumentCaptor<MessagePublicationEntity> inserted =
        ArgumentCaptor.forClass(MessagePublicationEntity.class);
    InOrder writes = inOrder(publicationRepository, targetRepository);
    writes.verify(publicationRepository).insert(inserted.capture());
    writes.verify(targetRepository).countByCampaignId(CAMPAIGN_ID);
    writes.verify(targetRepository).insertFromCampaign(inserted.getValue().getId(), CAMPAIGN_ID);
    assertThat(inserted.getValue().getAudienceType()).isEqualTo(AudienceType.SELECTED);
  }

  @Test
  void repeatedActiveSyncReusesTheUniqueCanonicalPublication() {
    PopupCampaignEntity active = campaign(PopupCampaignLifecycleStatus.ACTIVE, AudienceType.ALL);
    MessagePublicationEntity existing = publication(MessageLifecycleStatus.SENT);
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.of(existing));

    service.synchronize(active, ACTOR_ID, NOW);

    verify(publicationRepository, never()).insert(any(MessagePublicationEntity.class));
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
    verifyNoInteractions(targetRepository);
  }

  @Test
  void canonicalPublicationMustKeepCampaignContentAudienceAndOwnership() {
    PopupCampaignEntity active = campaign(PopupCampaignLifecycleStatus.ACTIVE, AudienceType.ALL);
    MessagePublicationEntity wrongContent = publication(MessageLifecycleStatus.SENT);
    wrongContent.setContentItemId(UUID.randomUUID());
    MessagePublicationEntity wrongAudience = publication(MessageLifecycleStatus.SENT);
    wrongAudience.setAudienceType(AudienceType.SELECTED);
    MessagePublicationEntity wrongSource = publication(MessageLifecycleStatus.SENT);
    wrongSource.setSourceType(MessageSourceType.MANUAL);
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID)).thenReturn(
        Optional.of(wrongContent), Optional.of(wrongAudience), Optional.of(wrongSource));

    assertThatIllegalStateException()
        .isThrownBy(() -> service.synchronize(active, ACTOR_ID, NOW));
    assertThatIllegalStateException()
        .isThrownBy(() -> service.synchronize(active, ACTOR_ID, NOW));
    assertThatIllegalStateException()
        .isThrownBy(() -> service.synchronize(active, ACTOR_ID, NOW));

    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
    verifyNoInteractions(targetRepository);
  }

  @Test
  void pauseAndNormalEndKeepTheExistingMessageSentAndVisible() {
    MessagePublicationEntity pausedPublication = publication(MessageLifecycleStatus.SENT);
    MessagePublicationEntity endedPublication = publication(MessageLifecycleStatus.SENT);
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID)).thenReturn(
        Optional.of(pausedPublication), Optional.of(endedPublication));

    service.synchronize(
        campaign(PopupCampaignLifecycleStatus.PAUSED, AudienceType.ALL), ACTOR_ID, NOW);
    service.synchronize(
        campaign(PopupCampaignLifecycleStatus.ENDED, AudienceType.ALL), ACTOR_ID, NOW);

    assertThat(pausedPublication.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(endedPublication.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
  }

  @Test
  void campaignDeleteHidesTheLinkedMessageAndIsIdempotent() {
    PopupCampaignEntity deleted = campaign(
        PopupCampaignLifecycleStatus.DELETED, AudienceType.ALL);
    MessagePublicationEntity existing = publication(MessageLifecycleStatus.SENT);
    Instant originalSentAt = existing.getSentAt();
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.of(existing), Optional.of(existing));
    when(publicationRepository.updateById(existing)).thenReturn(1);

    service.synchronize(deleted, ACTOR_ID, NOW);
    service.synchronize(deleted, ACTOR_ID, NOW);

    assertThat(existing.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.DELETED);
    assertThat(existing.getDeletedAt()).isEqualTo(NOW);
    assertThat(existing.getSentAt()).isEqualTo(originalSentAt);
    verify(publicationRepository).updateById(existing);
  }

  @Test
  void restoreToPausedKeepsTheLinkedMessageHidden() {
    MessagePublicationEntity hidden = publication(MessageLifecycleStatus.DELETED);
    hidden.setDeletedAt(NOW.minusSeconds(1));
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.of(hidden));

    service.synchronize(
        campaign(PopupCampaignLifecycleStatus.PAUSED, AudienceType.ALL), ACTOR_ID, NOW);

    assertThat(hidden.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.DELETED);
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
  }

  @Test
  void republishToActiveRestoresTheSameMessageAndPreservesOriginalSentAt() {
    PopupCampaignEntity active = campaign(PopupCampaignLifecycleStatus.ACTIVE, AudienceType.ALL);
    MessagePublicationEntity hidden = publication(MessageLifecycleStatus.DELETED);
    Instant originalSentAt = NOW.minusSeconds(600);
    hidden.setSentAt(originalSentAt);
    hidden.setAudienceCutoffAt(NOW.plusSeconds(60));
    hidden.setDeletedAt(NOW.minusSeconds(1));
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.of(hidden));
    when(publicationRepository.updateById(hidden)).thenReturn(1);

    service.synchronize(active, ACTOR_ID, NOW);

    assertThat(hidden.getId()).isEqualTo(PUBLICATION_ID);
    assertThat(hidden.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(hidden.getDeletedAt()).isNull();
    assertThat(hidden.getSentAt()).isEqualTo(originalSentAt);
    assertThat(hidden.getAudienceCutoffAt()).isEqualTo(END_AT);
    verify(publicationRepository, never()).insert(any(MessagePublicationEntity.class));
    verifyNoInteractions(targetRepository);
  }

  @Test
  void reconfigureSynchronizesEndCutoffWithoutChangingSentAt() {
    PopupCampaignEntity paused = campaign(PopupCampaignLifecycleStatus.PAUSED, AudienceType.ALL);
    MessagePublicationEntity existing = publication(MessageLifecycleStatus.SENT);
    Instant originalSentAt = existing.getSentAt();
    existing.setAudienceCutoffAt(NOW.plusSeconds(10));
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID))
        .thenReturn(Optional.of(existing));
    when(publicationRepository.updateById(existing)).thenReturn(1);

    service.synchronize(paused, ACTOR_ID, NOW);

    assertThat(existing.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(existing.getSentAt()).isEqualTo(originalSentAt);
    assertThat(existing.getAudienceCutoffAt()).isEqualTo(END_AT);
    assertThat(existing.getUpdatedBy()).isEqualTo(ACTOR_ID);
    assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void zeroRowInsertUpdateOrPartialTargetCopyFailsClosed() {
    PopupCampaignEntity activeAll = campaign(
        PopupCampaignLifecycleStatus.ACTIVE, AudienceType.ALL);
    PopupCampaignEntity activeSelected = campaign(
        PopupCampaignLifecycleStatus.ACTIVE, AudienceType.SELECTED);
    MessagePublicationEntity hidden = publication(MessageLifecycleStatus.DELETED);
    hidden.setDeletedAt(NOW.minusSeconds(1));
    when(publicationRepository.findBySourceCampaignIdForUpdate(CAMPAIGN_ID)).thenReturn(
        Optional.empty(), Optional.empty(), Optional.of(hidden));
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(0, 1);
    when(targetRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2);
    when(targetRepository.insertFromCampaign(any(UUID.class), any(UUID.class))).thenReturn(1);
    when(publicationRepository.updateById(hidden)).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.synchronize(activeAll, ACTOR_ID, NOW));
    assertThatIllegalStateException()
        .isThrownBy(() -> service.synchronize(activeSelected, ACTOR_ID, NOW));
    assertThatIllegalStateException()
        .isThrownBy(() -> service.synchronize(activeAll, ACTOR_ID, NOW));
  }

  @Test
  void repositoriesDeclareCanonicalLockAndSingleStatementTargetFreeze() throws Exception {
    Select linked = MessagePublicationRepository.class
        .getMethod("findBySourceCampaignIdForUpdate", UUID.class)
        .getAnnotation(Select.class);
    Insert copy = MessageTargetRepository.class
        .getMethod("insertFromCampaign", UUID.class, UUID.class)
        .getAnnotation(Insert.class);
    String copySql = String.join(" ", copy.value()).replaceAll("\\s+", " ").toUpperCase();

    assertThat(String.join(" ", linked.value()).toUpperCase())
        .contains("SOURCE_CAMPAIGN_ID", "FOR UPDATE");
    assertThat(copySql).contains(
        "INSERT INTO CONTENT.MESSAGE_TARGETS",
        "SELECT #{PUBLICATIONID}, TARGET.USER_ID",
        "FROM CONTENT.POPUP_CAMPAIGN_TARGETS TARGET",
        "TARGET.CAMPAIGN_ID = #{CAMPAIGNID}",
        "ON CONFLICT (PUBLICATION_ID, USER_ID) DO NOTHING");
    assertThat(CampaignMessageSyncService.class).hasAnnotation(Transactional.class);
  }

  private static PopupCampaignEntity campaign(
      PopupCampaignLifecycleStatus status,
      AudienceType audienceType) {
    PopupCampaignEntity campaign = new PopupCampaignEntity();
    campaign.setId(CAMPAIGN_ID);
    campaign.setContentItemId(CONTENT_ITEM_ID);
    campaign.setLifecycleStatus(status);
    campaign.setAudienceType(audienceType);
    campaign.setSyncToInbox(true);
    campaign.setEndAt(END_AT);
    return campaign;
  }

  private static MessagePublicationEntity publication(MessageLifecycleStatus status) {
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(PUBLICATION_ID);
    publication.setContentItemId(CONTENT_ITEM_ID);
    publication.setSourceType(MessageSourceType.CAMPAIGN);
    publication.setSourceCampaignId(CAMPAIGN_ID);
    publication.setAudienceType(AudienceType.ALL);
    publication.setLifecycleStatus(status);
    publication.setCategory("CAMPAIGN");
    publication.setSentAt(NOW.minusSeconds(300));
    publication.setAudienceCutoffAt(END_AT);
    publication.setCreatedBy(ACTOR_ID);
    publication.setUpdatedBy(ACTOR_ID);
    publication.setCreatedAt(NOW.minusSeconds(300));
    publication.setUpdatedAt(NOW.minusSeconds(300));
    return publication;
  }
}
