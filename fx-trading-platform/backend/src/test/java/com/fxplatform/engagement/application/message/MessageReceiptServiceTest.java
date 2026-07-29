package com.fxplatform.engagement.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.enums.MessageReadSource;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.query.UserMessageRow;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.MessageReceiptRepository;
import com.fxplatform.engagement.persistence.repository.MessageReceiptRepository.ReceiptMutation;
import com.fxplatform.engagement.web.dto.UserMessagePageResponse;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class MessageReceiptServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PUBLICATION_ID = UUID.randomUUID();
  private static final UUID CAMPAIGN_ID = UUID.randomUUID();
  private static final UUID DELIVERY_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-07-20T00:15:00Z");
  private static final Instant SENT_AT = NOW.minusSeconds(60);

  @Mock
  private EngagementUserLockRepository userLockRepository;
  @Mock
  private MessageReceiptRepository receiptRepository;

  private MessageReceiptService service;

  @BeforeEach
  void setUp() {
    service = new MessageReceiptService(
        userLockRepository,
        receiptRepository,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void listUsesOneClockInstantAndDatabasePaginationWithoutCreatingReceipts() {
    UserMessageRow row = messageRow();
    when(receiptRepository.findVisibleMessages(USER_ID, NOW, true, 25, 50L))
        .thenReturn(List.of(row));
    when(receiptRepository.countVisibleMessages(USER_ID, NOW, true)).thenReturn(51L);

    UserMessagePageResponse result = service.listMessages(USER_ID, 2, 25, true);

    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(25);
    assertThat(result.total()).isEqualTo(51);
    assertThat(result.totalPages()).isEqualTo(3);
    assertThat(result.items()).singleElement().satisfies(message -> {
      assertThat(message.publicationId()).isEqualTo(PUBLICATION_ID);
      assertThat(message.unread()).isTrue();
      assertThat(message.sanitizedHtml()).isEqualTo("<p>Safe</p>");
    });
    verifyNoInteractions(userLockRepository);
    verify(receiptRepository, never()).insertIfAbsent(any(), any(), any());
  }

  @Test
  void listBoundsPageAndSizeBeforeIssuingSql() {
    when(receiptRepository.findVisibleMessages(USER_ID, NOW, false, 100, 0L))
        .thenReturn(List.of());
    when(receiptRepository.countVisibleMessages(USER_ID, NOW, false)).thenReturn(0L);

    UserMessagePageResponse result = service.listMessages(USER_ID, -4, 500, false);

    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(100);
    assertThat(result.totalPages()).isZero();
  }

  @Test
  void unreadCountUsesTheSameDatabaseVisibilityAuthorityAtInjectedNow() {
    when(receiptRepository.countVisibleMessages(USER_ID, NOW, true)).thenReturn(7L);

    assertThat(service.unreadCount(USER_ID)).isEqualTo(7L);

    verify(receiptRepository).countVisibleMessages(USER_ID, NOW, true);
    verify(receiptRepository, never()).findVisibleMessages(
        any(), any(), any(Boolean.class), anyInt(), anyLong());
    verifyNoInteractions(userLockRepository);
  }

  @Test
  void readLocksCanonicalUserThenLazilyCreatesOnlyAVisibleReceipt() {
    stubActiveUser();
    when(receiptRepository.markReadIfVisible(USER_ID, PUBLICATION_ID, NOW))
        .thenReturn(new ReceiptMutation(true, true));

    service.markRead(USER_ID, PUBLICATION_ID);

    InOrder order = inOrder(userLockRepository, receiptRepository);
    order.verify(userLockRepository).findActiveBusinessUserForUpdate(USER_ID);
    order.verify(receiptRepository).markReadIfVisible(USER_ID, PUBLICATION_ID, NOW);
  }

  @Test
  void duplicateReadIsAcceptedWithoutRewritingItsOriginalReadMetadata() {
    stubActiveUser();
    when(receiptRepository.markReadIfVisible(USER_ID, PUBLICATION_ID, NOW))
        .thenReturn(new ReceiptMutation(true, false));

    service.markRead(USER_ID, PUBLICATION_ID);

    verify(receiptRepository).markReadIfVisible(USER_ID, PUBLICATION_ID, NOW);
  }

  @Test
  void invisiblePublicationFailsClosedAfterTheUserMutex() {
    stubActiveUser();
    when(receiptRepository.markReadIfVisible(USER_ID, PUBLICATION_ID, NOW))
        .thenReturn(new ReceiptMutation(false, false));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.markRead(USER_ID, PUBLICATION_ID))
        .withMessage("Message publication is not visible to this user");
  }

  @Test
  void inactiveOrNonBusinessUserCannotMutateAnyReceipt() {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID)).thenReturn(Optional.empty());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.markRead(USER_ID, PUBLICATION_ID))
        .withMessage("Message recipient is invalid");

    verifyNoInteractions(receiptRepository);
  }

  @Test
  void unreadIsIdempotentWhenNoReceiptExistsAndHidePreservesReadState() {
    stubActiveUser();
    when(receiptRepository.markUnreadIfVisible(USER_ID, PUBLICATION_ID, NOW))
        .thenReturn(new ReceiptMutation(true, false));
    when(receiptRepository.hideIfEligible(USER_ID, PUBLICATION_ID, NOW))
        .thenReturn(new ReceiptMutation(true, true));

    service.markUnread(USER_ID, PUBLICATION_ID);
    service.hide(USER_ID, PUBLICATION_ID);

    verify(receiptRepository).markUnreadIfVisible(USER_ID, PUBLICATION_ID, NOW);
    verify(receiptRepository).hideIfEligible(USER_ID, PUBLICATION_ID, NOW);
  }

  @Test
  void readAllUsesOneBulkStatementUnderTheSameUserMutex() {
    stubActiveUser();
    when(receiptRepository.markAllVisibleRead(USER_ID, NOW)).thenReturn(14);

    assertThat(service.markAllRead(USER_ID)).isEqualTo(14);

    InOrder order = inOrder(userLockRepository, receiptRepository);
    order.verify(userLockRepository).findActiveBusinessUserForUpdate(USER_ID);
    order.verify(receiptRepository).markAllVisibleRead(USER_ID, NOW);
    verify(receiptRepository, never()).markReadIfVisible(any(), any(), any());
  }

  @Test
  void popupShownWithoutLinkedSentPublicationIsANoOpAfterTheUserMutex() {
    stubActiveUser();
    when(receiptRepository.findLinkedSentPublication(CAMPAIGN_ID, NOW))
        .thenReturn(Optional.empty());

    service.onShown(USER_ID, CAMPAIGN_ID, DELIVERY_ID, NOW);

    verify(receiptRepository, never()).markReadFromPopup(any(), any(), any(), any());
  }

  @Test
  void popupShownUsesPublicationSentAtAndPreservesAnExistingReadOrHiddenReceipt() {
    stubActiveUser();
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(PUBLICATION_ID);
    publication.setSentAt(SENT_AT);
    when(receiptRepository.findLinkedSentPublication(CAMPAIGN_ID, NOW))
        .thenReturn(Optional.of(publication));
    when(receiptRepository.markReadFromPopup(PUBLICATION_ID, USER_ID, SENT_AT, NOW))
        .thenReturn(0);

    service.onShown(USER_ID, CAMPAIGN_ID, DELIVERY_ID, NOW);

    verify(receiptRepository).markReadFromPopup(PUBLICATION_ID, USER_ID, SENT_AT, NOW);
  }

  @Test
  void impossiblePopupReceiptRowCountFailsClosed() {
    stubActiveUser();
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(PUBLICATION_ID);
    publication.setSentAt(SENT_AT);
    when(receiptRepository.findLinkedSentPublication(CAMPAIGN_ID, NOW))
        .thenReturn(Optional.of(publication));
    when(receiptRepository.markReadFromPopup(PUBLICATION_ID, USER_ID, SENT_AT, NOW))
        .thenReturn(2);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.onShown(USER_ID, CAMPAIGN_ID, DELIVERY_ID, NOW))
        .withMessage("Popup message receipt write conflicted");
  }

  @Test
  void repositoryListAndCountSqlAreTheVisibilityAuthority() throws Exception {
    String list = selectSql(
        "findVisibleMessages",
        UUID.class,
        Instant.class,
        boolean.class,
        int.class,
        long.class);
    String count = selectSql(
        "countVisibleMessages",
        UUID.class,
        Instant.class,
        boolean.class);

    for (String sql : List.of(list, count)) {
      assertThat(sql)
          .contains("publication.lifecycle_status = 'sent'")
          .contains("publication.deleted_at is null")
          .contains("publication.sent_at <= #{now}")
          .contains("recipient.role = 'user'")
          .contains("recipient.created_at <= publication.audience_cutoff_at")
          .contains("publication.audience_type = 'all'")
          .contains("publication.audience_type = 'selected'")
          .contains("from content.message_targets target")
          .contains("target.user_id = #{userid}")
          .contains("content_item.current_revision_id")
          .contains("revision.id = content_item.current_revision_id")
          .contains("receipt.hidden_at is null")
          .contains("receipt.read_at is null")
          .doesNotContain("insert into content.message_receipts");
    }
    assertThat(list)
        .contains("revision.sanitized_html")
        .contains("order by publication.sent_at desc, publication.id desc")
        .contains("limit #{limit}")
        .contains("offset #{offset}");
  }

  @Test
  void repositoryMutationSqlPreservesReceiptSemantics() throws Exception {
    String read = selectSql(
        "markReadIfVisible", UUID.class, UUID.class, Instant.class);
    String unread = selectSql(
        "markUnreadIfVisible", UUID.class, UUID.class, Instant.class);
    String hide = selectSql(
        "hideIfEligible", UUID.class, UUID.class, Instant.class);
    String readAll = insertSql("markAllVisibleRead", UUID.class, Instant.class);
    String popup = insertSql(
        "markReadFromPopup", UUID.class, UUID.class, Instant.class, Instant.class);
    String linked = selectSql(
        "findLinkedSentPublication", UUID.class, Instant.class);

    assertThat(read)
        .contains("insert into content.message_receipts")
        .contains("publication.sent_at")
        .contains("'user'")
        .contains("on conflict (publication_id, user_id) do update")
        .contains("existing.read_at is null")
        .contains("existing.hidden_at is null");
    assertThat(unread)
        .contains("update content.message_receipts")
        .contains("read_at = null")
        .contains("read_source = null")
        .doesNotContain("insert into content.message_receipts");
    assertThat(hide)
        .contains("hidden_at")
        .doesNotContain("read_at =")
        .doesNotContain("read_source =");
    assertThat(readAll)
        .contains("insert into content.message_receipts")
        .contains("select")
        .contains("on conflict (publication_id, user_id) do update")
        .contains("receipt.hidden_at is null")
        .contains("'read_all'");
    assertThat(popup)
        .contains("'popup'")
        .contains("existing.read_at is null")
        .doesNotContain("hidden_at =");
    assertThat(linked)
        .contains("source_campaign_id = #{campaignid}")
        .contains("lifecycle_status = 'sent'")
        .contains("deleted_at is null")
        .contains("sent_at <= #{shownat}");

    for (String method : List.of(
        "markReadIfVisible", "markUnreadIfVisible", "hideIfEligible")) {
      Select mutation = MessageReceiptRepository.class
          .getMethod(method, UUID.class, UUID.class, Instant.class)
          .getAnnotation(Select.class);
      assertThat(mutation.affectData()).isTrue();
    }
  }

  @Test
  void publicCommandsAndPopupHookAreTransactional() throws Exception {
    for (Method method : List.of(
        MessageReceiptService.class.getMethod("markRead", UUID.class, UUID.class),
        MessageReceiptService.class.getMethod("markUnread", UUID.class, UUID.class),
        MessageReceiptService.class.getMethod("markAllRead", UUID.class),
        MessageReceiptService.class.getMethod("hide", UUID.class, UUID.class),
        MessageReceiptService.class.getMethod(
            "onShown", UUID.class, UUID.class, UUID.class, Instant.class))) {
      assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }
  }

  private void stubActiveUser() {
    when(userLockRepository.findActiveBusinessUserForUpdate(USER_ID))
        .thenReturn(Optional.of(new UserEntity()));
  }

  private static UserMessageRow messageRow() {
    return new UserMessageRow(
        PUBLICATION_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        MessageSourceType.MANUAL,
        "SYSTEM",
        SENT_AT,
        SENT_AT,
        "Title",
        "<p>Safe</p>",
        null,
        "Open",
        "WALLET",
        "{}",
        null,
        null);
  }

  private static String selectSql(String methodName, Class<?>... parameterTypes)
      throws Exception {
    Select select = MessageReceiptRepository.class
        .getMethod(methodName, parameterTypes)
        .getAnnotation(Select.class);
    assertThat(select).isNotNull();
    return normalize(String.join(" ", select.value()));
  }

  private static String insertSql(String methodName, Class<?>... parameterTypes)
      throws Exception {
    Insert insert = MessageReceiptRepository.class
        .getMethod(methodName, parameterTypes)
        .getAnnotation(Insert.class);
    assertThat(insert).isNotNull();
    return normalize(String.join(" ", insert.value()));
  }

  private static String normalize(String sql) {
    return sql.toLowerCase().replaceAll("\\s+", " ").trim();
  }
}
