package com.fxplatform.engagement.application.message;

import com.fxplatform.engagement.application.popup.PopupShownHandler;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.MessageReceiptRepository;
import com.fxplatform.engagement.persistence.repository.MessageReceiptRepository.ReceiptMutation;
import com.fxplatform.engagement.web.dto.UserMessagePageResponse;
import com.fxplatform.engagement.web.dto.UserMessageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-authoritative user inbox queries and receipt mutations. */
@Service
public class MessageReceiptService implements PopupShownHandler {

  private static final int MAX_PAGE_SIZE = 100;

  private final EngagementUserLockRepository userLockRepository;
  private final MessageReceiptRepository receiptRepository;
  private final Clock clock;

  public MessageReceiptService(
      EngagementUserLockRepository userLockRepository,
      MessageReceiptRepository receiptRepository,
      Clock clock) {
    this.userLockRepository = Objects.requireNonNull(userLockRepository, "userLockRepository");
    this.receiptRepository = Objects.requireNonNull(receiptRepository, "receiptRepository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Transactional(readOnly = true)
  public UserMessagePageResponse listMessages(
      UUID userId,
      int page,
      int size,
      boolean unreadOnly) {
    UUID canonicalUserId = Objects.requireNonNull(userId, "userId");
    int boundedPage = Math.max(0, page);
    int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    long offset = (long) boundedPage * boundedSize;
    Instant now = clock.instant();

    List<UserMessageResponse> items = receiptRepository.findVisibleMessages(
            canonicalUserId, now, unreadOnly, boundedSize, offset)
        .stream()
        .map(UserMessageResponse::from)
        .toList();
    long total = receiptRepository.countVisibleMessages(canonicalUserId, now, unreadOnly);
    long pages = total == 0 ? 0 : 1 + (total - 1) / boundedSize;
    return new UserMessagePageResponse(
        items,
        boundedPage,
        boundedSize,
        total,
        (int) Math.min(Integer.MAX_VALUE, pages));
  }

  @Transactional(readOnly = true)
  public long unreadCount(UUID userId) {
    return receiptRepository.countVisibleMessages(
        Objects.requireNonNull(userId, "userId"), clock.instant(), true);
  }

  @Transactional
  public void markRead(UUID userId, UUID publicationId) {
    UUID canonicalUserId = lockUser(userId);
    requireVisible(receiptRepository.markReadIfVisible(
        canonicalUserId,
        Objects.requireNonNull(publicationId, "publicationId"),
        clock.instant()));
  }

  @Transactional
  public void markUnread(UUID userId, UUID publicationId) {
    UUID canonicalUserId = lockUser(userId);
    requireVisible(receiptRepository.markUnreadIfVisible(
        canonicalUserId,
        Objects.requireNonNull(publicationId, "publicationId"),
        clock.instant()));
  }

  @Transactional
  public int markAllRead(UUID userId) {
    UUID canonicalUserId = lockUser(userId);
    int affected = receiptRepository.markAllVisibleRead(canonicalUserId, clock.instant());
    if (affected < 0) {
      throw new IllegalStateException("Message read-all write conflicted");
    }
    return affected;
  }

  @Transactional
  public void hide(UUID userId, UUID publicationId) {
    UUID canonicalUserId = lockUser(userId);
    requireVisible(receiptRepository.hideIfEligible(
        canonicalUserId,
        Objects.requireNonNull(publicationId, "publicationId"),
        clock.instant()));
  }

  @Override
  @Transactional
  public void onShown(
      UUID userId,
      UUID campaignId,
      UUID deliveryId,
      Instant shownAt) {
    UUID canonicalUserId = lockUser(userId);
    UUID canonicalCampaignId = Objects.requireNonNull(campaignId, "campaignId");
    Objects.requireNonNull(deliveryId, "deliveryId");
    Instant canonicalShownAt = Objects.requireNonNull(shownAt, "shownAt");
    MessagePublicationEntity publication = receiptRepository
        .findLinkedSentPublication(canonicalCampaignId, canonicalShownAt)
        .orElse(null);
    if (publication == null) {
      return;
    }
    UUID publicationId = Objects.requireNonNull(publication.getId(), "publication.id");
    Instant deliveredAt = Objects.requireNonNull(publication.getSentAt(), "publication.sentAt");
    int affected = receiptRepository.markReadFromPopup(
        publicationId, canonicalUserId, deliveredAt, canonicalShownAt);
    if (affected < 0 || affected > 1) {
      throw new IllegalStateException("Popup message receipt write conflicted");
    }
  }

  private UUID lockUser(UUID userId) {
    UUID canonicalUserId = Objects.requireNonNull(userId, "userId");
    if (userLockRepository.findActiveBusinessUserForUpdate(canonicalUserId).isEmpty()) {
      throw new IllegalArgumentException("Message recipient is invalid");
    }
    return canonicalUserId;
  }

  private static void requireVisible(ReceiptMutation mutation) {
    if (mutation == null) {
      throw new IllegalStateException("Message receipt write conflicted");
    }
    if (!mutation.visible()) {
      throw new IllegalArgumentException("Message publication is not visible to this user");
    }
  }
}
