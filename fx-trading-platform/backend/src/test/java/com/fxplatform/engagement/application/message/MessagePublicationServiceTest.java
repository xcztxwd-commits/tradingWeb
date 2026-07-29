package com.fxplatform.engagement.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.engagement.application.content.ContentRevisionService;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentDraft;
import com.fxplatform.engagement.application.content.ContentRevisionService.SavedContentRevision;
import com.fxplatform.engagement.application.message.MessagePublicationService.CreateCommand;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.AggregateType;
import com.fxplatform.engagement.application.outbox.EngagementOutboxService.UpdateType;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.ContentKind;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.repository.MessagePublicationRepository;
import com.fxplatform.engagement.persistence.repository.MessageTargetRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class MessagePublicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");
  private static final UUID PUBLICATION_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID CONTENT_ITEM_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID REVISION_ID =
      UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID ACTOR_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID USER_ONE =
      UUID.fromString("50000000-0000-0000-0000-000000000005");
  private static final UUID USER_TWO =
      UUID.fromString("60000000-0000-0000-0000-000000000006");
  private static final ContentDraft CONTENT = new ContentDraft(
      "Maintenance",
      "{\"type\":\"doc\",\"content\":[]}",
      null,
      null);

  @Mock private ContentRevisionService contentRevisionService;
  @Mock private MessagePublicationRepository publicationRepository;
  @Mock private MessageTargetRepository targetRepository;
  @Mock private UserRepository userRepository;
  @Mock private EngagementOutboxService outboxService;

  private MessagePublicationService service;

  @BeforeEach
  void setUp() {
    service = new MessagePublicationService(
        contentRevisionService,
        publicationRepository,
        targetRepository,
        userRepository,
        Clock.fixed(NOW, ZoneOffset.UTC),
        outboxService);
  }

  @Test
  void createBuildsMessageContentThenPersistsAManualDraft() {
    stubContentCreation();
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(1);

    MessagePublicationEntity created = service.create(
        ACTOR_ID,
        new CreateCommand("SYSTEM", AudienceType.ALL, List.of(USER_ONE), CONTENT));

    assertThat(created.getId()).isNotNull();
    assertThat(created.getContentItemId()).isEqualTo(CONTENT_ITEM_ID);
    assertThat(created.getSourceType()).isEqualTo(MessageSourceType.MANUAL);
    assertThat(created.getSourceCampaignId()).isNull();
    assertThat(created.getAudienceType()).isEqualTo(AudienceType.ALL);
    assertThat(created.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.DRAFT);
    assertThat(created.getCategory()).isEqualTo("SYSTEM");
    assertThat(created.getCreatedBy()).isEqualTo(ACTOR_ID);
    assertThat(created.getUpdatedBy()).isEqualTo(ACTOR_ID);
    assertThat(created.getCreatedAt()).isEqualTo(NOW);
    assertThat(created.getUpdatedAt()).isEqualTo(NOW);
    assertThat(created.getScheduledAt()).isNull();
    assertThat(created.getSentAt()).isNull();
    assertThat(created.getAudienceCutoffAt()).isNull();

    InOrder order = inOrder(contentRevisionService, publicationRepository);
    order.verify(contentRevisionService).create(ContentKind.MESSAGE, ACTOR_ID, CONTENT);
    order.verify(publicationRepository).insert(created);
    verifyNoInteractions(targetRepository, userRepository);
  }

  @Test
  void selectedDraftDeduplicatesAndAcceptsEveryBusinessUserStatus() {
    stubContentCreation();
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(1);
    when(userRepository.selectBatchIds(anyCollection())).thenReturn(List.of(
        user(USER_ONE, UserRole.USER, UserStatus.FROZEN),
        user(USER_TWO, UserRole.USER, UserStatus.DISABLED)));
    when(targetRepository.insertIfAbsent(any(UUID.class), any(UUID.class))).thenReturn(1);

    MessagePublicationEntity created = service.create(
        ACTOR_ID,
        new CreateCommand(
            "NOTICE",
            AudienceType.SELECTED,
            List.of(USER_ONE, USER_TWO, USER_ONE),
            CONTENT));

    verify(userRepository).selectBatchIds(Set.of(USER_ONE, USER_TWO));
    verify(targetRepository).insertIfAbsent(created.getId(), USER_ONE);
    verify(targetRepository).insertIfAbsent(created.getId(), USER_TWO);
    assertThat(created.getAudienceType()).isEqualTo(AudienceType.SELECTED);
  }

  @Test
  void selectedDraftMayBeEmptyButRejectsMissingNullOrAdminTargetsBeforeWriting() {
    stubContentCreation();
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(1);

    MessagePublicationEntity empty = service.create(
        ACTOR_ID,
        new CreateCommand("NOTICE", AudienceType.SELECTED, List.of(), CONTENT));
    assertThat(empty.getAudienceType()).isEqualTo(AudienceType.SELECTED);
    verifyNoInteractions(userRepository, targetRepository);

    when(userRepository.selectBatchIds(Set.of(USER_ONE))).thenReturn(List.of());
    assertThatIllegalArgumentException().isThrownBy(() -> service.create(
        ACTOR_ID,
        new CreateCommand("NOTICE", AudienceType.SELECTED, List.of(USER_ONE), CONTENT)));

    when(userRepository.selectBatchIds(Set.of(USER_TWO))).thenReturn(List.of(
        user(USER_TWO, UserRole.ADMIN, UserStatus.ACTIVE)));
    assertThatIllegalArgumentException().isThrownBy(() -> service.create(
        ACTOR_ID,
        new CreateCommand("NOTICE", AudienceType.SELECTED, List.of(USER_TWO), CONTENT)));

    assertThatIllegalArgumentException().isThrownBy(() -> service.create(
        ACTOR_ID,
        new CreateCommand(
            "NOTICE",
            AudienceType.SELECTED,
            java.util.Arrays.asList(USER_ONE, null),
            CONTENT)));
  }

  @Test
  void futureSendSchedulesWhileBoundaryAndPastSendImmediatelyWithOneNow() {
    MessagePublicationEntity future = manual(MessageLifecycleStatus.DRAFT);
    MessagePublicationEntity boundary = manual(MessageLifecycleStatus.DRAFT);
    MessagePublicationEntity past = manual(MessageLifecycleStatus.DRAFT);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID))
        .thenReturn(future, boundary, past);
    when(publicationRepository.updateById(any(MessagePublicationEntity.class))).thenReturn(1);

    MessagePublicationEntity scheduled =
        service.send(PUBLICATION_ID, NOW.plusSeconds(1), ACTOR_ID);
    MessagePublicationEntity sentAtBoundary =
        service.send(PUBLICATION_ID, NOW, ACTOR_ID);
    MessagePublicationEntity sentFromPast =
        service.send(PUBLICATION_ID, NOW.minusSeconds(1), ACTOR_ID);

    assertThat(scheduled.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SCHEDULED);
    assertThat(scheduled.getScheduledAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(scheduled.getSentAt()).isNull();
    assertThat(scheduled.getAudienceCutoffAt()).isNull();
    for (MessagePublicationEntity sent : List.of(sentAtBoundary, sentFromPast)) {
      assertThat(sent.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
      assertThat(sent.getSentAt()).isSameAs(NOW);
      assertThat(sent.getAudienceCutoffAt()).isSameAs(sent.getSentAt());
      assertThat(sent.getUpdatedAt()).isEqualTo(NOW);
    }
    verify(outboxService, org.mockito.Mockito.times(2)).append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        PUBLICATION_ID,
        AudienceType.ALL,
        NOW);
  }

  @Test
  void dueDispatchSendsAtTheExactScheduledBoundary() {
    MessagePublicationEntity before = manual(MessageLifecycleStatus.SCHEDULED);
    before.setScheduledAt(NOW.minusSeconds(1));
    MessagePublicationEntity boundary = manual(MessageLifecycleStatus.SCHEDULED);
    boundary.setId(UUID.randomUUID());
    boundary.setScheduledAt(NOW);
    when(publicationRepository.findDueForUpdate(NOW)).thenReturn(List.of(before, boundary));
    when(publicationRepository.updateById(any(MessagePublicationEntity.class))).thenReturn(1);

    assertThat(service.dispatchDue()).isEqualTo(2);

    for (MessagePublicationEntity sent : List.of(before, boundary)) {
      assertThat(sent.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
      assertThat(sent.getSentAt()).isEqualTo(NOW);
      assertThat(sent.getAudienceCutoffAt()).isEqualTo(NOW);
    }
    verify(outboxService).append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        before.getId(),
        AudienceType.ALL,
        NOW);
    verify(outboxService).append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        boundary.getId(),
        AudienceType.ALL,
        NOW);
  }

  @Test
  void selectedAudienceCanChangeWhileScheduledButFreezesOnceSent() {
    MessagePublicationEntity scheduled = manual(MessageLifecycleStatus.SCHEDULED);
    scheduled.setAudienceType(AudienceType.SELECTED);
    scheduled.setScheduledAt(NOW.plusSeconds(60));
    MessagePublicationEntity sent = manual(MessageLifecycleStatus.SENT);
    sent.setAudienceType(AudienceType.SELECTED);
    sent.setSentAt(NOW.minusSeconds(1));
    sent.setAudienceCutoffAt(NOW.minusSeconds(1));
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID))
        .thenReturn(scheduled, sent);
    when(userRepository.selectBatchIds(Set.of(USER_TWO))).thenReturn(List.of(
        user(USER_TWO, UserRole.USER, UserStatus.DISABLED)));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(1);
    when(targetRepository.deleteByPublicationId(PUBLICATION_ID)).thenReturn(1);
    when(targetRepository.insertIfAbsent(PUBLICATION_ID, USER_TWO)).thenReturn(1);
    when(publicationRepository.updateById(scheduled)).thenReturn(1);

    MessagePublicationEntity changed = service.replaceAudience(
        PUBLICATION_ID, AudienceType.SELECTED, List.of(USER_TWO, USER_TWO), ACTOR_ID);

    assertThat(changed.getAudienceType()).isEqualTo(AudienceType.SELECTED);
    verify(targetRepository).deleteByPublicationId(PUBLICATION_ID);
    verify(targetRepository).insertIfAbsent(PUBLICATION_ID, USER_TWO);
    assertThatIllegalStateException().isThrownBy(() -> service.replaceAudience(
        PUBLICATION_ID, AudienceType.ALL, List.of(), ACTOR_ID));
  }

  @Test
  void scheduledSelectedAudienceCannotBeClearedAndBlockDueDispatch() {
    MessagePublicationEntity scheduled = manual(MessageLifecycleStatus.SCHEDULED);
    scheduled.setAudienceType(AudienceType.SELECTED);
    scheduled.setScheduledAt(NOW.plusSeconds(60));
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(scheduled);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.replaceAudience(
            PUBLICATION_ID, AudienceType.SELECTED, List.of(), ACTOR_ID))
        .withMessageContaining("target");
    verify(targetRepository, never()).deleteByPublicationId(PUBLICATION_ID);
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
  }

  @Test
  void allAudienceDeletesEveryExplicitTargetAndNeverInsertsOne() {
    MessagePublicationEntity draft = manual(MessageLifecycleStatus.DRAFT);
    draft.setAudienceType(AudienceType.SELECTED);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(draft);
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(2);
    when(targetRepository.deleteByPublicationId(PUBLICATION_ID)).thenReturn(2);
    when(publicationRepository.updateById(draft)).thenReturn(1);

    service.replaceAudience(PUBLICATION_ID, AudienceType.ALL, List.of(USER_ONE), ACTOR_ID);

    assertThat(draft.getAudienceType()).isEqualTo(AudienceType.ALL);
    verifyNoInteractions(userRepository);
    verify(targetRepository, never()).insertIfAbsent(any(UUID.class), any(UUID.class));
  }

  @Test
  void emptySelectedAudienceCannotActuallySend() {
    MessagePublicationEntity draft = manual(MessageLifecycleStatus.DRAFT);
    draft.setAudienceType(AudienceType.SELECTED);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(draft);
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.send(PUBLICATION_ID, NOW, ACTOR_ID))
        .withMessageContaining("target");
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
  }

  @Test
  void emptySelectedAudienceCannotEnterTheScheduledState() {
    MessagePublicationEntity draft = manual(MessageLifecycleStatus.DRAFT);
    draft.setAudienceType(AudienceType.SELECTED);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(draft);
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.send(PUBLICATION_ID, NOW.plusSeconds(60), ACTOR_ID))
        .withMessageContaining("target");
    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
  }

  @Test
  void cancelScheduleReturnsToDraftAndIsIdempotent() {
    MessagePublicationEntity scheduled = manual(MessageLifecycleStatus.SCHEDULED);
    scheduled.setScheduledAt(NOW.plusSeconds(60));
    MessagePublicationEntity draft = manual(MessageLifecycleStatus.DRAFT);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID))
        .thenReturn(scheduled, draft);
    when(publicationRepository.updateById(scheduled)).thenReturn(1);

    MessagePublicationEntity cancelled = service.cancelSchedule(PUBLICATION_ID, ACTOR_ID);
    MessagePublicationEntity repeated = service.cancelSchedule(PUBLICATION_ID, ACTOR_ID);

    assertThat(cancelled.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.DRAFT);
    assertThat(cancelled.getScheduledAt()).isNull();
    assertThat(repeated).isSameAs(draft);
    verify(publicationRepository).updateById(scheduled);
  }

  @Test
  void restoreInfersSentFutureScheduleOrDraftFromRetainedFields() {
    MessagePublicationEntity deletedSent = deleted();
    deletedSent.setSentAt(NOW.minusSeconds(30));
    deletedSent.setAudienceCutoffAt(NOW.minusSeconds(30));
    MessagePublicationEntity deletedFuture = deleted();
    deletedFuture.setScheduledAt(NOW.plusSeconds(30));
    MessagePublicationEntity deletedPast = deleted();
    deletedPast.setScheduledAt(NOW.minusSeconds(30));
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID))
        .thenReturn(deletedSent, deletedFuture, deletedPast);
    when(publicationRepository.updateById(any(MessagePublicationEntity.class))).thenReturn(1);

    assertThat(service.restore(PUBLICATION_ID, ACTOR_ID).getLifecycleStatus())
        .isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(service.restore(PUBLICATION_ID, ACTOR_ID).getLifecycleStatus())
        .isEqualTo(MessageLifecycleStatus.SCHEDULED);
    assertThat(service.restore(PUBLICATION_ID, ACTOR_ID).getLifecycleStatus())
        .isEqualTo(MessageLifecycleStatus.DRAFT);
    assertThat(deletedSent.getDeletedAt()).isNull();
    assertThat(deletedFuture.getDeletedAt()).isNull();
    assertThat(deletedPast.getDeletedAt()).isNull();
    verify(outboxService).append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        PUBLICATION_ID,
        AudienceType.ALL,
        NOW);
  }

  @Test
  void deletePreservesLifecycleEvidenceAndDeleteRestoreAreIdempotent() {
    MessagePublicationEntity sent = manual(MessageLifecycleStatus.SENT);
    Instant originalSentAt = NOW.minusSeconds(10);
    sent.setScheduledAt(NOW.minusSeconds(20));
    sent.setSentAt(originalSentAt);
    sent.setAudienceCutoffAt(originalSentAt);
    MessagePublicationEntity alreadyDeleted = deleted();
    alreadyDeleted.setSentAt(originalSentAt);
    alreadyDeleted.setAudienceCutoffAt(originalSentAt);
    MessagePublicationEntity alreadySent = manual(MessageLifecycleStatus.SENT);
    alreadySent.setSentAt(originalSentAt);
    alreadySent.setAudienceCutoffAt(originalSentAt);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID))
        .thenReturn(sent, alreadyDeleted, alreadySent);
    when(publicationRepository.updateById(sent)).thenReturn(1);

    MessagePublicationEntity removed = service.delete(PUBLICATION_ID, ACTOR_ID);
    MessagePublicationEntity repeatedDelete = service.delete(PUBLICATION_ID, ACTOR_ID);
    MessagePublicationEntity repeatedRestore = service.restore(PUBLICATION_ID, ACTOR_ID);

    assertThat(removed.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.DELETED);
    assertThat(removed.getDeletedAt()).isEqualTo(NOW);
    assertThat(removed.getScheduledAt()).isEqualTo(NOW.minusSeconds(20));
    assertThat(removed.getSentAt()).isEqualTo(originalSentAt);
    assertThat(repeatedDelete).isSameAs(alreadyDeleted);
    assertThat(repeatedRestore).isSameAs(alreadySent);
    verify(publicationRepository).updateById(sent);
    verify(outboxService).append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        PUBLICATION_ID,
        AudienceType.ALL,
        NOW);
  }

  @Test
  void sentContentCanBeRevisedWithoutTouchingAudienceOrReceipts() {
    MessagePublicationEntity sent = manual(MessageLifecycleStatus.SENT);
    sent.setSentAt(NOW.minusSeconds(1));
    sent.setAudienceCutoffAt(NOW.minusSeconds(1));
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(sent);
    SavedContentRevision revised = new SavedContentRevision(
        CONTENT_ITEM_ID, UUID.randomUUID(), 2, "Updated", "<p>Updated</p>");
    when(contentRevisionService.revise(CONTENT_ITEM_ID, ACTOR_ID, CONTENT)).thenReturn(revised);
    when(publicationRepository.updateById(sent)).thenReturn(1);

    assertThat(service.reviseContent(PUBLICATION_ID, CONTENT, ACTOR_ID)).isSameAs(revised);

    verify(contentRevisionService).revise(CONTENT_ITEM_ID, ACTOR_ID, CONTENT);
    verifyNoInteractions(targetRepository, userRepository);
    assertThat(sent.getUpdatedBy()).isEqualTo(ACTOR_ID);
    assertThat(sent.getUpdatedAt()).isEqualTo(NOW);
    verify(outboxService).append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        PUBLICATION_ID,
        AudienceType.ALL,
        NOW);
  }

  @Test
  void everyManualCommandRejectsCampaignOwnedPublications() {
    MessagePublicationEntity campaign = manual(MessageLifecycleStatus.DRAFT);
    campaign.setSourceType(MessageSourceType.CAMPAIGN);
    campaign.setSourceCampaignId(UUID.randomUUID());
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(
        campaign, campaign, campaign, campaign, campaign, campaign);

    assertThatIllegalStateException().isThrownBy(() -> service.replaceAudience(
        PUBLICATION_ID, AudienceType.ALL, List.of(), ACTOR_ID));
    assertThatIllegalStateException().isThrownBy(() -> service.reviseContent(
        PUBLICATION_ID, CONTENT, ACTOR_ID));
    assertThatIllegalStateException().isThrownBy(() -> service.send(
        PUBLICATION_ID, NOW, ACTOR_ID));
    assertThatIllegalStateException().isThrownBy(() -> service.cancelSchedule(
        PUBLICATION_ID, ACTOR_ID));
    assertThatIllegalStateException().isThrownBy(() -> service.delete(
        PUBLICATION_ID, ACTOR_ID));
    assertThatIllegalStateException().isThrownBy(() -> service.restore(
        PUBLICATION_ID, ACTOR_ID));

    verify(publicationRepository, never()).updateById(any(MessagePublicationEntity.class));
    verifyNoInteractions(contentRevisionService, targetRepository, userRepository);
  }

  @Test
  void everyRequiredSingleRowWriteFailsClosed() {
    stubContentCreation();
    when(publicationRepository.insert(any(MessagePublicationEntity.class))).thenReturn(0);
    assertThatIllegalStateException().isThrownBy(() -> service.create(
        ACTOR_ID, new CreateCommand("NOTICE", AudienceType.ALL, List.of(), CONTENT)));

    MessagePublicationEntity draft = manual(MessageLifecycleStatus.DRAFT);
    draft.setAudienceType(AudienceType.SELECTED);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(draft);
    when(userRepository.selectBatchIds(Set.of(USER_ONE))).thenReturn(List.of(
        user(USER_ONE, UserRole.USER, UserStatus.ACTIVE)));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(0);
    when(targetRepository.deleteByPublicationId(PUBLICATION_ID)).thenReturn(0);
    when(targetRepository.insertIfAbsent(PUBLICATION_ID, USER_ONE)).thenReturn(0);
    assertThatIllegalStateException().isThrownBy(() -> service.replaceAudience(
        PUBLICATION_ID, AudienceType.SELECTED, List.of(USER_ONE), ACTOR_ID));

    MessagePublicationEntity allDraft = manual(MessageLifecycleStatus.DRAFT);
    when(publicationRepository.selectByIdForUpdate(PUBLICATION_ID)).thenReturn(allDraft);
    when(publicationRepository.updateById(allDraft)).thenReturn(0);
    assertThatIllegalStateException().isThrownBy(() -> service.send(
        PUBLICATION_ID, NOW, ACTOR_ID));
  }

  @Test
  void repositoryExposesCanonicalAndDueRowLocksAndServiceIsTransactional() throws Exception {
    Select byId = MessagePublicationRepository.class
        .getMethod("selectByIdForUpdate", UUID.class)
        .getAnnotation(Select.class);
    Select due = MessagePublicationRepository.class
        .getMethod("findDueForUpdate", Instant.class)
        .getAnnotation(Select.class);
    String dueSql = String.join(" ", due.value()).replaceAll("\\s+", " ").toUpperCase();

    assertThat(String.join(" ", byId.value())).containsIgnoringCase("FOR UPDATE");
    assertThat(dueSql).contains(
        "LIFECYCLE_STATUS = 'SCHEDULED'",
        "SCHEDULED_AT <= #{NOW}",
        "ORDER BY SCHEDULED_AT ASC, ID ASC",
        "FOR UPDATE SKIP LOCKED");
    assertThat(MessagePublicationService.class).hasAnnotation(Transactional.class);
    Method dispatch = MessagePublicationService.class.getMethod("dispatchDue");
    assertThat(dispatch.getReturnType()).isEqualTo(int.class);
  }

  private void stubContentCreation() {
    when(contentRevisionService.create(ContentKind.MESSAGE, ACTOR_ID, CONTENT))
        .thenReturn(new SavedContentRevision(
            CONTENT_ITEM_ID, REVISION_ID, 1, "Maintenance", "<p>Maintenance</p>"));
  }

  private static MessagePublicationEntity manual(MessageLifecycleStatus status) {
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(PUBLICATION_ID);
    publication.setContentItemId(CONTENT_ITEM_ID);
    publication.setSourceType(MessageSourceType.MANUAL);
    publication.setAudienceType(AudienceType.ALL);
    publication.setLifecycleStatus(status);
    publication.setCategory("NOTICE");
    publication.setCreatedBy(ACTOR_ID);
    publication.setUpdatedBy(ACTOR_ID);
    publication.setCreatedAt(NOW.minusSeconds(100));
    publication.setUpdatedAt(NOW.minusSeconds(100));
    return publication;
  }

  private static MessagePublicationEntity deleted() {
    MessagePublicationEntity publication = manual(MessageLifecycleStatus.DELETED);
    publication.setDeletedAt(NOW.minusSeconds(1));
    return publication;
  }

  private static UserEntity user(UUID id, UserRole role, UserStatus status) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setRole(role);
    user.setStatus(status);
    return user;
  }
}
