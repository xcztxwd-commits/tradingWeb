package com.fxplatform.engagement.admin.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageActionRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageContentRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSaveRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSendRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageUpdateRequest;
import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository;
import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository.MessageDetailRow;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentDraft;
import com.fxplatform.engagement.application.content.ContentRevisionService.SavedContentRevision;
import com.fxplatform.engagement.application.message.MessagePublicationService;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.repository.MessageTargetRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class EngagementMessageAdminServiceTest {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID PUBLICATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID CONTENT_ITEM_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID NEXT_REVISION_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
  private static final UUID USER_ONE = UUID.fromString("60000000-0000-0000-0000-000000000006");
  private static final UUID USER_TWO = UUID.fromString("70000000-0000-0000-0000-000000000007");
  private static final Instant NOW = Instant.parse("2026-07-20T04:00:00Z");

  @Mock private MessagePublicationService publicationService;
  @Mock private MessageTargetRepository targetRepository;
  @Mock private MessageAdminQueryRepository queryRepository;
  @Mock private EngagementAuditService auditService;

  private EngagementMessageAdminService service;

  @BeforeEach
  void setUp() {
    service = new EngagementMessageAdminService(
        publicationService, targetRepository, queryRepository, auditService);
  }

  @Test
  void createDelegatesToTheManualLifecycleAuthorityAndAuditsExactlyOnceAfterSuccess() {
    MessagePublicationEntity created = publication(MessageLifecycleStatus.DRAFT);
    when(publicationService.create(eq(ACTOR_ID), any())).thenReturn(created);
    when(queryRepository.findMessage(PUBLICATION_ID)).thenReturn(Optional.of(row(
        MessageLifecycleStatus.DRAFT, AudienceType.SELECTED, REVISION_ID, 2L)));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(2);
    when(targetRepository.findUserIdsByPublicationId(PUBLICATION_ID))
        .thenReturn(List.of(USER_ONE, USER_TWO));

    var response = service.create(ACTOR_ID, saveRequest());

    assertThat(response.id()).isEqualTo(PUBLICATION_ID);
    verify(publicationService).create(eq(ACTOR_ID), any(MessagePublicationService.CreateCommand.class));
    verify(auditService).record(
        ACTOR_ID,
        Action.MESSAGE_CREATE,
        TargetType.MESSAGE,
        PUBLICATION_ID.toString(),
        new Metadata(REVISION_ID, AudienceType.SELECTED, 2, null, "DRAFT", "create reason"));
  }

  @Test
  void draftUpdateChangesAudienceThenRevisionAndAuditsOneSafeMetadataRecord() {
    MessageDetailRow before = row(
        MessageLifecycleStatus.DRAFT, AudienceType.ALL, REVISION_ID, 0L);
    MessageDetailRow after = row(
        MessageLifecycleStatus.DRAFT, AudienceType.SELECTED, NEXT_REVISION_ID, 2L);
    when(queryRepository.findMessage(PUBLICATION_ID))
        .thenReturn(Optional.of(before), Optional.of(after));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(0, 2);
    when(targetRepository.findUserIdsByPublicationId(PUBLICATION_ID))
        .thenReturn(List.of(USER_ONE, USER_TWO));
    when(publicationService.reviseContent(eq(PUBLICATION_ID), any(), eq(ACTOR_ID)))
        .thenReturn(new SavedContentRevision(
            CONTENT_ITEM_ID, NEXT_REVISION_ID, 2, "Updated", "<p>safe</p>"));

    service.update(ACTOR_ID, PUBLICATION_ID, updateRequest(AudienceType.SELECTED));

    verify(publicationService).replaceAudience(
        PUBLICATION_ID, AudienceType.SELECTED, Set.of(USER_ONE, USER_TWO), ACTOR_ID);
    verify(publicationService).reviseContent(eq(PUBLICATION_ID), any(ContentDraft.class), eq(ACTOR_ID));
    verify(auditService).record(
        ACTOR_ID,
        Action.MESSAGE_EDIT,
        TargetType.MESSAGE,
        PUBLICATION_ID.toString(),
        new Metadata(
            NEXT_REVISION_ID,
            AudienceType.SELECTED,
            2,
            "status=DRAFT,revisionId=" + REVISION_ID,
            "status=DRAFT,revisionId=" + NEXT_REVISION_ID,
            "edit reason"));
  }

  @Test
  void sentMessageAllowsContentRevisionOnlyWhenTheFrozenAudienceIsIdentical() {
    MessageDetailRow sent = row(
        MessageLifecycleStatus.SENT, AudienceType.SELECTED, REVISION_ID, 2L);
    MessageDetailRow revised = row(
        MessageLifecycleStatus.SENT, AudienceType.SELECTED, NEXT_REVISION_ID, 2L);
    when(queryRepository.findMessage(PUBLICATION_ID))
        .thenReturn(Optional.of(sent), Optional.of(revised));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(2, 2);
    when(targetRepository.findUserIdsByPublicationId(PUBLICATION_ID))
        .thenReturn(List.of(USER_ONE, USER_TWO), List.of(USER_ONE, USER_TWO));
    when(publicationService.reviseContent(eq(PUBLICATION_ID), any(), eq(ACTOR_ID)))
        .thenReturn(new SavedContentRevision(
            CONTENT_ITEM_ID, NEXT_REVISION_ID, 2, "Updated", "<p>safe</p>"));

    service.update(ACTOR_ID, PUBLICATION_ID, updateRequest(AudienceType.SELECTED));

    verify(publicationService, never()).replaceAudience(any(), any(), any(), any());
    verify(publicationService).reviseContent(eq(PUBLICATION_ID), any(), eq(ACTOR_ID));
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.MESSAGE_EDIT), eq(TargetType.MESSAGE),
        eq(PUBLICATION_ID.toString()), any(Metadata.class));

    clearInvocations(publicationService, auditService, targetRepository, queryRepository);
    when(queryRepository.findMessage(PUBLICATION_ID)).thenReturn(Optional.of(sent));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(2);
    when(targetRepository.findUserIdsByPublicationId(PUBLICATION_ID))
        .thenReturn(List.of(USER_ONE, USER_TWO));

    assertThatThrownBy(() -> service.update(
        ACTOR_ID,
        PUBLICATION_ID,
        new MessageUpdateRequest(
            AudienceType.ALL, Set.of(), content(), "illegal audience change")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("audience");
    verify(publicationService, never()).reviseContent(any(), any(), any());
    verifyNoInteractions(auditService);
  }

  @Test
  void sentContentOnlyUpdateMayOmitTheFrozenAudiencePayload() {
    MessageDetailRow sent = row(
        MessageLifecycleStatus.SENT, AudienceType.SELECTED, REVISION_ID, 2L);
    MessageDetailRow revised = row(
        MessageLifecycleStatus.SENT, AudienceType.SELECTED, NEXT_REVISION_ID, 2L);
    when(queryRepository.findMessage(PUBLICATION_ID))
        .thenReturn(Optional.of(sent), Optional.of(revised));
    when(targetRepository.countByPublicationId(PUBLICATION_ID)).thenReturn(2, 2);
    when(targetRepository.findUserIdsByPublicationId(PUBLICATION_ID))
        .thenReturn(List.of(USER_ONE, USER_TWO), List.of(USER_ONE, USER_TWO));
    when(publicationService.reviseContent(eq(PUBLICATION_ID), any(), eq(ACTOR_ID)))
        .thenReturn(new SavedContentRevision(
            CONTENT_ITEM_ID, NEXT_REVISION_ID, 2, "Updated", "<p>safe</p>"));

    service.update(
        ACTOR_ID,
        PUBLICATION_ID,
        new MessageUpdateRequest(null, null, content(), "content only"));

    verify(publicationService, never()).replaceAudience(any(), any(), any(), any());
    verify(publicationService).reviseContent(eq(PUBLICATION_ID), any(), eq(ACTOR_ID));
    verify(auditService).record(
        eq(ACTOR_ID), eq(Action.MESSAGE_EDIT), eq(TargetType.MESSAGE),
        eq(PUBLICATION_ID.toString()), any(Metadata.class));
  }

  @Test
  void sendChoosesScheduleOrSendAuditFromTheAuthoritativeResult() {
    MessageDetailRow draft = row(
        MessageLifecycleStatus.DRAFT, AudienceType.ALL, REVISION_ID, 0L);
    MessageDetailRow scheduled = row(
        MessageLifecycleStatus.SCHEDULED, AudienceType.ALL, REVISION_ID, 0L);
    MessagePublicationEntity scheduledEntity = publication(MessageLifecycleStatus.SCHEDULED);
    when(queryRepository.findMessage(PUBLICATION_ID))
        .thenReturn(Optional.of(draft), Optional.of(scheduled));
    when(publicationService.send(PUBLICATION_ID, NOW.plusSeconds(60), ACTOR_ID))
        .thenReturn(scheduledEntity);

    service.send(
        ACTOR_ID, PUBLICATION_ID, new MessageSendRequest(NOW.plusSeconds(60), "schedule reason"));

    verify(auditService).record(
        ACTOR_ID,
        Action.MESSAGE_SCHEDULE,
        TargetType.MESSAGE,
        PUBLICATION_ID.toString(),
        new Metadata(REVISION_ID, AudienceType.ALL, 0, "DRAFT", "SCHEDULED", "schedule reason"));

    clearInvocations(publicationService, auditService, queryRepository);
    MessageDetailRow sent = row(
        MessageLifecycleStatus.SENT, AudienceType.ALL, REVISION_ID, 0L);
    when(queryRepository.findMessage(PUBLICATION_ID))
        .thenReturn(Optional.of(draft), Optional.of(sent));
    when(publicationService.send(PUBLICATION_ID, null, ACTOR_ID))
        .thenReturn(publication(MessageLifecycleStatus.SENT));

    service.send(ACTOR_ID, PUBLICATION_ID, new MessageSendRequest(null, "send reason"));

    verify(auditService).record(
        ACTOR_ID,
        Action.MESSAGE_SEND,
        TargetType.MESSAGE,
        PUBLICATION_ID.toString(),
        new Metadata(REVISION_ID, AudienceType.ALL, 0, "DRAFT", "SENT", "send reason"));
  }

  @Test
  void cancelDeleteAndRestoreEachProduceExactlyTheirTypedAuditAfterDomainSuccess() {
    assertAction(
        Action.MESSAGE_CANCEL_SCHEDULE,
        MessageLifecycleStatus.SCHEDULED,
        MessageLifecycleStatus.DRAFT,
        () -> service.cancelSchedule(ACTOR_ID, PUBLICATION_ID, new MessageActionRequest("cancel")));
    assertAction(
        Action.MESSAGE_DELETE,
        MessageLifecycleStatus.SENT,
        MessageLifecycleStatus.DELETED,
        () -> service.delete(ACTOR_ID, PUBLICATION_ID, new MessageActionRequest("delete")));
    assertAction(
        Action.MESSAGE_RESTORE,
        MessageLifecycleStatus.DELETED,
        MessageLifecycleStatus.SENT,
        () -> service.restore(ACTOR_ID, PUBLICATION_ID, new MessageActionRequest("restore")));
  }

  @Test
  void failedDomainCommandWritesNoAuditAndFacadeOwnsTheTransaction() throws Exception {
    when(queryRepository.findMessage(PUBLICATION_ID)).thenReturn(Optional.of(row(
        MessageLifecycleStatus.DRAFT, AudienceType.ALL, REVISION_ID, 0L)));
    when(publicationService.send(PUBLICATION_ID, null, ACTOR_ID))
        .thenThrow(new IllegalStateException("domain rejected"));

    assertThatThrownBy(() -> service.send(
        ACTOR_ID, PUBLICATION_ID, new MessageSendRequest(null, "failed")))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(auditService);

    Transactional transactional = EngagementMessageAdminService.class.getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
  }

  @Test
  void adminQueriesAreManualOnlyUseCurrentSanitizedRevisionAndNeverProjectRawHtml()
      throws Exception {
    for (String methodName : List.of("findMessages", "countMessages", "findMessage")) {
      String sql = normalizedQuerySql(methodName);
      assertThat(sql).contains("SOURCE_TYPE = 'MANUAL'", "SOURCE_CAMPAIGN_ID IS NULL");
      assertThat(sql).doesNotContain("RAW_HTML");
      if (methodName.equals("findMessage")) {
        assertThat(sql).contains(
            "CURRENT_REVISION_ID",
            "SANITIZED_HTML",
            "BODY_DOCUMENT::TEXT AS BODY_DOCUMENT");
      }
      if (methodName.equals("findMessages") || methodName.equals("findMessage")) {
        assertThat(sql).contains(
            "TARGET_USER.ROLE = 'USER'",
            "TARGET_USER.CREATED_AT <= PUBLICATION.AUDIENCE_CUTOFF_AT");
      }
    }
  }

  @Test
  void adminQueryFragmentsKeepSqlTokenBoundaries() throws Exception {
    assertThat(normalizedQuerySql("findMessages"))
        .contains("WHERE PUBLICATION.SOURCE_TYPE");
    assertThat(normalizedQuerySql("countMessages"))
        .contains("WHERE PUBLICATION.SOURCE_TYPE");
    assertThat(normalizedQuerySql("findMessage"))
        .contains("AND PUBLICATION.SOURCE_TYPE");
  }

  private static String normalizedQuerySql(String methodName) throws Exception {
    Method method = List.of(MessageAdminQueryRepository.class.getMethods()).stream()
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    return String.join(" ", method.getAnnotation(Select.class).value())
        .replaceAll("\\s+", " ")
        .toUpperCase();
  }

  private void assertAction(
      Action action,
      MessageLifecycleStatus beforeStatus,
      MessageLifecycleStatus afterStatus,
      Runnable adminCall) {
    clearInvocations(publicationService, auditService, queryRepository, targetRepository);
    MessageDetailRow before = row(beforeStatus, AudienceType.ALL, REVISION_ID, 0L);
    MessageDetailRow after = row(afterStatus, AudienceType.ALL, REVISION_ID, 0L);
    when(queryRepository.findMessage(PUBLICATION_ID))
        .thenReturn(Optional.of(before), Optional.of(after));
    switch (action) {
      case MESSAGE_CANCEL_SCHEDULE -> when(
          publicationService.cancelSchedule(PUBLICATION_ID, ACTOR_ID))
          .thenReturn(publication(afterStatus));
      case MESSAGE_DELETE -> when(publicationService.delete(PUBLICATION_ID, ACTOR_ID))
          .thenReturn(publication(afterStatus));
      case MESSAGE_RESTORE -> when(publicationService.restore(PUBLICATION_ID, ACTOR_ID))
          .thenReturn(publication(afterStatus));
      default -> throw new AssertionError(action);
    }

    adminCall.run();

    verify(auditService).record(
        eq(ACTOR_ID), eq(action), eq(TargetType.MESSAGE), eq(PUBLICATION_ID.toString()),
        any(Metadata.class));
  }

  private static MessageSaveRequest saveRequest() {
    return new MessageSaveRequest(
        "NOTICE",
        AudienceType.SELECTED,
        Set.of(USER_ONE, USER_TWO),
        content(),
        "create reason");
  }

  private static MessageUpdateRequest updateRequest(AudienceType audienceType) {
    return new MessageUpdateRequest(
        audienceType, Set.of(USER_ONE, USER_TWO), content(), "edit reason");
  }

  private static MessageContentRequest content() {
    return new MessageContentRequest(
        "Updated", "{\"type\":\"doc\",\"content\":[]}", null, null);
  }

  private static MessagePublicationEntity publication(MessageLifecycleStatus status) {
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(PUBLICATION_ID);
    publication.setContentItemId(CONTENT_ITEM_ID);
    publication.setSourceType(MessageSourceType.MANUAL);
    publication.setAudienceType(AudienceType.ALL);
    publication.setLifecycleStatus(status);
    return publication;
  }

  private static MessageDetailRow row(
      MessageLifecycleStatus status,
      AudienceType audience,
      UUID revisionId,
      Long targetCount) {
    return new MessageDetailRow(
        PUBLICATION_ID,
        CONTENT_ITEM_ID,
        MessageSourceType.MANUAL,
        audience,
        status,
        "NOTICE",
        null,
        status == MessageLifecycleStatus.SENT ? NOW : null,
        status == MessageLifecycleStatus.SENT ? NOW : null,
        null,
        revisionId,
        revisionId.equals(REVISION_ID) ? 1 : 2,
        "Title",
        "{\"type\":\"doc\",\"content\":[]}",
        "<p>safe</p>",
        null,
        null,
        null,
        null,
        targetCount,
        NOW,
        NOW);
  }
}
