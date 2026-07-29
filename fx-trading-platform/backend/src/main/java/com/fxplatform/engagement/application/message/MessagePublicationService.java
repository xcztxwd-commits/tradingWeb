package com.fxplatform.engagement.application.message;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.engagement.application.content.ContentRevisionService;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentDraft;
import com.fxplatform.engagement.application.content.ContentRevisionService.SavedContentRevision;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional lifecycle authority for manually authored inbox messages. */
@Service
@Transactional
public class MessagePublicationService {

  private final ContentRevisionService contentRevisionService;
  private final MessagePublicationRepository publicationRepository;
  private final MessageTargetRepository targetRepository;
  private final UserRepository userRepository;
  private final Clock clock;
  private final EngagementOutboxService outboxService;

  public MessagePublicationService(
      ContentRevisionService contentRevisionService,
      MessagePublicationRepository publicationRepository,
      MessageTargetRepository targetRepository,
      UserRepository userRepository,
      Clock clock,
      EngagementOutboxService outboxService) {
    this.contentRevisionService = Objects.requireNonNull(contentRevisionService);
    this.publicationRepository = Objects.requireNonNull(publicationRepository);
    this.targetRepository = Objects.requireNonNull(targetRepository);
    this.userRepository = Objects.requireNonNull(userRepository);
    this.clock = Objects.requireNonNull(clock);
    this.outboxService = Objects.requireNonNull(outboxService);
  }

  public MessagePublicationEntity create(UUID actorId, CreateCommand command) {
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(command, "command");
    AudienceType audience = Objects.requireNonNull(command.audienceType(), "audienceType");
    String category = category(command.category());
    Set<UUID> targets = audience == AudienceType.SELECTED
        ? normalize(command.targetUserIds())
        : Set.of();
    validateBusinessUsers(targets);

    SavedContentRevision content = contentRevisionService.create(
        ContentKind.MESSAGE,
        actor,
        Objects.requireNonNull(command.content(), "content"));
    Instant now = clock.instant();
    MessagePublicationEntity publication = new MessagePublicationEntity();
    publication.setId(UUID.randomUUID());
    publication.setContentItemId(content.contentItemId());
    publication.setSourceType(MessageSourceType.MANUAL);
    publication.setAudienceType(audience);
    publication.setLifecycleStatus(MessageLifecycleStatus.DRAFT);
    publication.setCategory(category);
    publication.setCreatedBy(actor);
    publication.setUpdatedBy(actor);
    publication.setCreatedAt(now);
    publication.setUpdatedAt(now);
    requireSingleWrite(publicationRepository.insert(publication), "publication");
    insertTargets(publication.getId(), targets);
    return publication;
  }

  public MessagePublicationEntity replaceAudience(
      UUID publicationId,
      AudienceType audienceType,
      Collection<UUID> targetUserIds,
      UUID actorId) {
    MessagePublicationEntity publication = lockedManual(publicationId);
    if (publication.getLifecycleStatus() != MessageLifecycleStatus.DRAFT
        && publication.getLifecycleStatus() != MessageLifecycleStatus.SCHEDULED) {
      throw new IllegalStateException("Sent or deleted message audience is frozen");
    }
    AudienceType audience = Objects.requireNonNull(audienceType, "audienceType");
    Set<UUID> targets = audience == AudienceType.SELECTED
        ? normalize(targetUserIds)
        : Set.of();
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.SCHEDULED
        && audience == AudienceType.SELECTED
        && targets.isEmpty()) {
      throw new IllegalStateException("Scheduled selected message requires at least one target");
    }
    validateBusinessUsers(targets);
    replaceTargets(publication.getId(), targets);

    publication.setAudienceType(audience);
    touch(publication, actorId, clock.instant());
    requireSingleWrite(publicationRepository.updateById(publication), "publication");
    return publication;
  }

  public SavedContentRevision reviseContent(
      UUID publicationId,
      ContentDraft content,
      UUID actorId) {
    MessagePublicationEntity publication = lockedManual(publicationId);
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.DELETED) {
      throw new IllegalStateException("Deleted message content cannot be revised");
    }
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    SavedContentRevision revised = contentRevisionService.revise(
        required(publication.getContentItemId(), "contentItemId"),
        actor,
        Objects.requireNonNull(content, "content"));
    touch(publication, actor, clock.instant());
    requireSingleWrite(publicationRepository.updateById(publication), "publication");
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.SENT) {
      appendSent(publication, publication.getUpdatedAt());
    }
    return revised;
  }

  public MessagePublicationEntity send(
      UUID publicationId,
      Instant requestedSendAt,
      UUID actorId) {
    MessagePublicationEntity publication = lockedManual(publicationId);
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.SENT) {
      return publication;
    }
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.DELETED) {
      throw new IllegalStateException("Deleted message cannot be sent");
    }
    Instant now = clock.instant();
    Instant sendAt = requestedSendAt == null ? now : requestedSendAt;
    requireSendableAudience(publication);
    if (now.isBefore(sendAt)) {
      if (publication.getLifecycleStatus() == MessageLifecycleStatus.SCHEDULED
          && sendAt.equals(publication.getScheduledAt())) {
        return publication;
      }
      publication.setLifecycleStatus(MessageLifecycleStatus.SCHEDULED);
      publication.setScheduledAt(sendAt);
      publication.setSentAt(null);
      publication.setAudienceCutoffAt(null);
    } else {
      publication.setLifecycleStatus(MessageLifecycleStatus.SENT);
      if (requestedSendAt != null) {
        publication.setScheduledAt(requestedSendAt);
      }
      publication.setSentAt(now);
      publication.setAudienceCutoffAt(now);
    }
    touch(publication, actorId, now);
    requireSingleWrite(publicationRepository.updateById(publication), "publication");
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.SENT) {
      appendSent(publication, now);
    }
    return publication;
  }

  public int dispatchDue() {
    Instant now = clock.instant();
    List<MessagePublicationEntity> due = publicationRepository.findDueForUpdate(now);
    for (MessagePublicationEntity publication : due) {
      requireManual(publication);
      if (publication.getLifecycleStatus() != MessageLifecycleStatus.SCHEDULED
          || publication.getScheduledAt() == null
          || publication.getScheduledAt().isAfter(now)) {
        throw new IllegalStateException("Due message query returned a non-due publication");
      }
      requireSendableAudience(publication);
      publication.setLifecycleStatus(MessageLifecycleStatus.SENT);
      publication.setSentAt(now);
      publication.setAudienceCutoffAt(now);
      publication.setUpdatedAt(now);
      requireSingleWrite(publicationRepository.updateById(publication), "publication");
      appendSent(publication, now);
    }
    return due.size();
  }

  public MessagePublicationEntity cancelSchedule(UUID publicationId, UUID actorId) {
    MessagePublicationEntity publication = lockedManual(publicationId);
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.DRAFT) {
      return publication;
    }
    if (publication.getLifecycleStatus() != MessageLifecycleStatus.SCHEDULED) {
      throw new IllegalStateException("Only a scheduled message can be cancelled");
    }
    publication.setLifecycleStatus(MessageLifecycleStatus.DRAFT);
    publication.setScheduledAt(null);
    touch(publication, actorId, clock.instant());
    requireSingleWrite(publicationRepository.updateById(publication), "publication");
    return publication;
  }

  public MessagePublicationEntity delete(UUID publicationId, UUID actorId) {
    MessagePublicationEntity publication = lockedManual(publicationId);
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.DELETED) {
      return publication;
    }
    boolean wasSent = publication.getLifecycleStatus() == MessageLifecycleStatus.SENT;
    Instant now = clock.instant();
    publication.setLifecycleStatus(MessageLifecycleStatus.DELETED);
    publication.setDeletedAt(now);
    touch(publication, actorId, now);
    requireSingleWrite(publicationRepository.updateById(publication), "publication");
    if (wasSent) {
      appendSent(publication, now);
    }
    return publication;
  }

  public MessagePublicationEntity restore(UUID publicationId, UUID actorId) {
    MessagePublicationEntity publication = lockedManual(publicationId);
    if (publication.getLifecycleStatus() != MessageLifecycleStatus.DELETED) {
      return publication;
    }
    Instant now = clock.instant();
    if (publication.getSentAt() != null) {
      required(publication.getAudienceCutoffAt(), "audienceCutoffAt");
      publication.setLifecycleStatus(MessageLifecycleStatus.SENT);
    } else if (publication.getScheduledAt() != null
        && publication.getScheduledAt().isAfter(now)) {
      publication.setLifecycleStatus(MessageLifecycleStatus.SCHEDULED);
    } else {
      publication.setLifecycleStatus(MessageLifecycleStatus.DRAFT);
    }
    publication.setDeletedAt(null);
    touch(publication, actorId, now);
    requireSingleWrite(publicationRepository.updateById(publication), "publication");
    if (publication.getLifecycleStatus() == MessageLifecycleStatus.SENT) {
      appendSent(publication, now);
    }
    return publication;
  }

  private MessagePublicationEntity lockedManual(UUID publicationId) {
    MessagePublicationEntity publication = publicationRepository.selectByIdForUpdate(
        Objects.requireNonNull(publicationId, "publicationId"));
    if (publication == null) {
      throw new NoSuchElementException("Message publication not found");
    }
    requireManual(publication);
    return publication;
  }

  private void appendSent(MessagePublicationEntity publication, Instant now) {
    outboxService.append(
        UpdateType.MESSAGE_UPDATED,
        AggregateType.MESSAGE,
        required(publication.getId(), "id"),
        required(publication.getAudienceType(), "audienceType"),
        now);
  }

  private static void requireManual(MessagePublicationEntity publication) {
    if (publication.getSourceType() != MessageSourceType.MANUAL
        || publication.getSourceCampaignId() != null) {
      throw new IllegalStateException("Campaign message is owned by campaign synchronization");
    }
  }

  private void requireSendableAudience(MessagePublicationEntity publication) {
    if (publication.getAudienceType() == AudienceType.SELECTED
        && targetRepository.countByPublicationId(publication.getId()) == 0) {
      throw new IllegalStateException("Selected message requires at least one target");
    }
  }

  private void replaceTargets(UUID publicationId, Set<UUID> targets) {
    int expectedDeletes = targetRepository.countByPublicationId(publicationId);
    if (targetRepository.deleteByPublicationId(publicationId) != expectedDeletes) {
      throw new IllegalStateException("Message target delete conflict");
    }
    insertTargets(publicationId, targets);
  }

  private void insertTargets(UUID publicationId, Set<UUID> targets) {
    for (UUID userId : targets) {
      requireSingleWrite(
          targetRepository.insertIfAbsent(publicationId, userId),
          "message target");
    }
  }

  private void validateBusinessUsers(Set<UUID> targetIds) {
    if (targetIds.isEmpty()) {
      return;
    }
    Collection<UserEntity> users = userRepository.selectBatchIds(targetIds);
    Set<UUID> loadedIds = new LinkedHashSet<>();
    for (UserEntity user : users) {
      if (user == null
          || user.getId() == null
          || !targetIds.contains(user.getId())
          || user.getRole() != UserRole.USER) {
        throw new IllegalArgumentException("Message targets must be existing business users");
      }
      loadedIds.add(user.getId());
    }
    if (!loadedIds.equals(targetIds)) {
      throw new IllegalArgumentException("Message target user not found");
    }
  }

  private static Set<UUID> normalize(Collection<UUID> userIds) {
    if (userIds == null) {
      return Set.of();
    }
    Set<UUID> normalized = new LinkedHashSet<>();
    for (UUID userId : userIds) {
      if (userId == null) {
        throw new IllegalArgumentException("Message target user id is required");
      }
      normalized.add(userId);
    }
    return normalized;
  }

  private static void touch(
      MessagePublicationEntity publication,
      UUID actorId,
      Instant now) {
    publication.setUpdatedBy(Objects.requireNonNull(actorId, "actorId"));
    publication.setUpdatedAt(now);
  }

  private static String category(String category) {
    if (category == null || category.isBlank() || category.trim().length() > 64) {
      throw new IllegalArgumentException("Message category is invalid");
    }
    return category.trim();
  }

  private static void requireSingleWrite(int rows, String target) {
    if (rows != 1) {
      throw new IllegalStateException("Message " + target + " write conflict");
    }
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalStateException("Message publication " + field + " is required");
    }
    return value;
  }

  public record CreateCommand(
      String category,
      AudienceType audienceType,
      Collection<UUID> targetUserIds,
      ContentDraft content) {
  }
}
