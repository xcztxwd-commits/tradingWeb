package com.fxplatform.engagement.admin.message;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageActionRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageContentRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageCtaRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageDetailResponse;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSaveRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSendRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSummaryResponse;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageUpdateRequest;
import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository;
import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository.MessageDetailRow;
import com.fxplatform.engagement.admin.message.repository.MessageAdminQueryRepository.MessageSummaryRow;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentCta;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentDraft;
import com.fxplatform.engagement.application.content.ContentRevisionService.SavedContentRevision;
import com.fxplatform.engagement.application.message.MessagePublicationService;
import com.fxplatform.engagement.application.message.MessagePublicationService.CreateCommand;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.repository.MessageTargetRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EngagementMessageAdminService {

  private static final int MAX_PAGE_SIZE = 100;

  private final MessagePublicationService publicationService;
  private final MessageTargetRepository targetRepository;
  private final MessageAdminQueryRepository queryRepository;
  private final EngagementAuditService auditService;

  public EngagementMessageAdminService(
      MessagePublicationService publicationService,
      MessageTargetRepository targetRepository,
      MessageAdminQueryRepository queryRepository,
      EngagementAuditService auditService) {
    this.publicationService = Objects.requireNonNull(publicationService);
    this.targetRepository = Objects.requireNonNull(targetRepository);
    this.queryRepository = Objects.requireNonNull(queryRepository);
    this.auditService = Objects.requireNonNull(auditService);
  }

  @Transactional(readOnly = true)
  public AdminPageResponse<MessageSummaryResponse> messages(
      int page,
      int size,
      MessageLifecycleStatus lifecycleStatus,
      String titleQuery) {
    PageWindow window = pageWindow(page, size);
    String lifecycle = lifecycleStatus == null ? null : lifecycleStatus.name();
    String title = normalizeFilter(titleQuery);
    List<MessageSummaryResponse> items = queryRepository.findMessages(
            lifecycle, title, window.size(), window.offset()).stream()
        .map(EngagementMessageAdminService::summary)
        .toList();
    return page(items, window, queryRepository.countMessages(lifecycle, title));
  }

  @Transactional(readOnly = true)
  public MessageDetailResponse message(UUID publicationId) {
    return detail(requireRow(publicationId));
  }

  public MessageDetailResponse create(UUID actorId, MessageSaveRequest request) {
    UUID actor = requireId(actorId, "actorId");
    MessageSaveRequest command = requireSave(request);
    MessagePublicationEntity created = publicationService.create(
        actor,
        new CreateCommand(
            command.category(),
            command.audienceType(),
            normalizeTargets(command.targetUserIds()),
            content(command.content())));
    MessageDetailResponse after = message(required(created.getId(), "id"));
    record(actor, Action.MESSAGE_CREATE, after, null, after.lifecycleStatus(), command.reason());
    return after;
  }

  public MessageDetailResponse update(
      UUID actorId,
      UUID publicationId,
      MessageUpdateRequest request) {
    UUID actor = requireId(actorId, "actorId");
    UUID id = requireId(publicationId, "publicationId");
    MessageUpdateRequest command = requireUpdate(request);
    MessageDetailResponse before = message(id);
    AudienceType desiredAudience = command.audienceType() == null
        ? before.audienceType()
        : command.audienceType();
    Set<UUID> desiredTargets = desiredAudience == AudienceType.SELECTED
        ? command.audienceType() == null && command.targetUserIds() == null
            ? Set.copyOf(before.targetUserIds())
            : normalizeTargets(command.targetUserIds())
        : Set.of();
    boolean audienceChanged = desiredAudience != before.audienceType()
        || !desiredTargets.equals(Set.copyOf(before.targetUserIds()));

    if (before.lifecycleStatus() == MessageLifecycleStatus.SENT) {
      if (audienceChanged) {
        throw invalid("Sent message audience is frozen");
      }
    } else if (audienceChanged) {
      publicationService.replaceAudience(id, desiredAudience, desiredTargets, actor);
    }

    SavedContentRevision revised = publicationService.reviseContent(
        id, content(command.content()), actor);
    MessageDetailResponse after = message(id);
    if (!required(revised.revisionId(), "revisionId").equals(after.revisionId())) {
      throw dataInvalid("Message current revision did not advance to the saved revision");
    }
    auditService.record(
        actor,
        Action.MESSAGE_EDIT,
        TargetType.MESSAGE,
        after.id().toString(),
        new Metadata(
            after.revisionId(),
            after.audienceType(),
            Math.toIntExact(after.targetCount()),
            revisionSnapshot(before),
            revisionSnapshot(after),
            command.reason()));
    return after;
  }

  public MessageDetailResponse send(
      UUID actorId,
      UUID publicationId,
      MessageSendRequest request) {
    UUID actor = requireId(actorId, "actorId");
    UUID id = requireId(publicationId, "publicationId");
    MessageSendRequest command = requireSend(request);
    MessageDetailResponse before = message(id);
    MessagePublicationEntity mutated = publicationService.send(id, command.sendAt(), actor);
    MessageDetailResponse after = message(id);
    MessageLifecycleStatus result = required(mutated.getLifecycleStatus(), "lifecycleStatus");
    if (result != after.lifecycleStatus()) {
      throw dataInvalid("Message lifecycle result is inconsistent");
    }
    Action action = result == MessageLifecycleStatus.SCHEDULED
        ? Action.MESSAGE_SCHEDULE
        : Action.MESSAGE_SEND;
    record(actor, action, after, before.lifecycleStatus(), result, command.reason());
    return after;
  }

  public MessageDetailResponse cancelSchedule(
      UUID actorId,
      UUID publicationId,
      MessageActionRequest request) {
    return mutate(
        actorId,
        publicationId,
        request,
        Action.MESSAGE_CANCEL_SCHEDULE,
        publicationService::cancelSchedule);
  }

  public MessageDetailResponse delete(
      UUID actorId,
      UUID publicationId,
      MessageActionRequest request) {
    return mutate(
        actorId,
        publicationId,
        request,
        Action.MESSAGE_DELETE,
        publicationService::delete);
  }

  public MessageDetailResponse restore(
      UUID actorId,
      UUID publicationId,
      MessageActionRequest request) {
    return mutate(
        actorId,
        publicationId,
        request,
        Action.MESSAGE_RESTORE,
        publicationService::restore);
  }

  private MessageDetailResponse mutate(
      UUID actorId,
      UUID publicationId,
      MessageActionRequest request,
      Action action,
      MessageMutation mutation) {
    UUID actor = requireId(actorId, "actorId");
    UUID id = requireId(publicationId, "publicationId");
    MessageActionRequest command = requireAction(request);
    MessageDetailResponse before = message(id);
    MessagePublicationEntity mutated = mutation.apply(id, actor);
    MessageDetailResponse after = message(id);
    if (required(mutated.getLifecycleStatus(), "lifecycleStatus") != after.lifecycleStatus()) {
      throw dataInvalid("Message lifecycle result is inconsistent");
    }
    record(
        actor,
        action,
        after,
        before.lifecycleStatus(),
        after.lifecycleStatus(),
        command.reason());
    return after;
  }

  private void record(
      UUID actorId,
      Action action,
      MessageDetailResponse message,
      MessageLifecycleStatus before,
      MessageLifecycleStatus after,
      String reason) {
    auditService.record(
        actorId,
        action,
        TargetType.MESSAGE,
        message.id().toString(),
        new Metadata(
            message.revisionId(),
            message.audienceType(),
            Math.toIntExact(message.targetCount()),
            before == null ? null : before.name(),
            after.name(),
            reason));
  }

  private MessageDetailResponse detail(MessageDetailRow row) {
    if (row.sourceType() != MessageSourceType.MANUAL) {
      throw dataInvalid("Campaign messages are not managed by the manual message API");
    }
    AudienceType audience = required(row.audienceType(), "audienceType");
    int rawTargetCount = targetRepository.countByPublicationId(row.id());
    List<UUID> targets = audience == AudienceType.SELECTED
        ? List.copyOf(targetRepository.findUserIdsByPublicationId(row.id()))
        : List.of();
    if (rawTargetCount != targets.size()
        || (audience == AudienceType.ALL && rawTargetCount != 0)
        || (audience == AudienceType.SELECTED && number(row.targetCount()) != targets.size())) {
      throw dataInvalid("Message targets are inconsistent");
    }
    return new MessageDetailResponse(
        required(row.id(), "id"),
        required(row.contentItemId(), "contentItemId"),
        required(row.lifecycleStatus(), "lifecycleStatus"),
        audience,
        required(row.category(), "category"),
        targets,
        row.scheduledAt(),
        row.sentAt(),
        row.audienceCutoffAt(),
        row.deletedAt(),
        required(row.revisionId(), "revisionId"),
        required(row.revisionNo(), "revisionNo"),
        required(row.title(), "title"),
        required(row.bodyDocument(), "bodyDocument"),
        required(row.sanitizedHtml(), "sanitizedHtml"),
        row.coverAssetId(),
        row.ctaLabel(),
        row.ctaRouteKey(),
        row.ctaParams(),
        number(row.targetCount()),
        required(row.createdAt(), "createdAt"),
        required(row.updatedAt(), "updatedAt"));
  }

  private MessageDetailRow requireRow(UUID publicationId) {
    UUID id = requireId(publicationId, "publicationId");
    return queryRepository.findMessage(id).orElseThrow(
        () -> new BusinessException("MESSAGE_NOT_FOUND", "Manual message not found: " + id));
  }

  private static MessageSummaryResponse summary(MessageSummaryRow row) {
    return new MessageSummaryResponse(
        required(row.id(), "id"),
        required(row.lifecycleStatus(), "lifecycleStatus"),
        required(row.audienceType(), "audienceType"),
        required(row.category(), "category"),
        row.scheduledAt(),
        row.sentAt(),
        required(row.revisionId(), "revisionId"),
        required(row.title(), "title"),
        number(row.targetCount()),
        required(row.updatedAt(), "updatedAt"));
  }

  private static String revisionSnapshot(MessageDetailResponse message) {
    return "status=" + message.lifecycleStatus() + ",revisionId=" + message.revisionId();
  }

  private static ContentDraft content(MessageContentRequest request) {
    MessageContentRequest content = required(request, "content");
    MessageCtaRequest cta = content.cta();
    return new ContentDraft(
        content.title(),
        content.bodyDocument(),
        content.coverAssetId(),
        cta == null ? null : new ContentCta(cta.label(), cta.routeKey(), cta.paramsJson()));
  }

  private static MessageSaveRequest requireSave(MessageSaveRequest request) {
    if (request == null || request.audienceType() == null || request.content() == null) {
      throw invalid("Message create request is invalid");
    }
    requireReason(request.reason());
    return request;
  }

  private static MessageUpdateRequest requireUpdate(MessageUpdateRequest request) {
    if (request == null || request.content() == null) {
      throw invalid("Message update request is invalid");
    }
    requireReason(request.reason());
    return request;
  }

  private static MessageSendRequest requireSend(MessageSendRequest request) {
    if (request == null) {
      throw invalid("Message send request is required");
    }
    requireReason(request.reason());
    return request;
  }

  private static MessageActionRequest requireAction(MessageActionRequest request) {
    if (request == null) {
      throw invalid("Message action request is required");
    }
    requireReason(request.reason());
    return request;
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
      throw invalid("Message audit reason is required");
    }
    return reason.trim();
  }

  private static Set<UUID> normalizeTargets(Collection<UUID> userIds) {
    if (userIds == null) {
      return Set.of();
    }
    Set<UUID> targets = new LinkedHashSet<>();
    for (UUID userId : userIds) {
      if (userId == null) {
        throw invalid("Message target user id is required");
      }
      targets.add(userId);
    }
    return Set.copyOf(targets);
  }

  private static String normalizeFilter(String filter) {
    if (filter == null || filter.isBlank()) {
      return null;
    }
    String normalized = filter.trim();
    if (normalized.length() > 200) {
      throw invalid("Message title filter is too long");
    }
    return normalized;
  }

  private static PageWindow pageWindow(int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw invalid("Message pagination is invalid");
    }
    return new PageWindow(page, size, Math.multiplyExact((long) page, size));
  }

  private static <T> AdminPageResponse<T> page(
      List<T> items,
      PageWindow window,
      long total) {
    int totalPages = total == 0
        ? 0
        : Math.toIntExact((total + window.size() - 1) / window.size());
    return new AdminPageResponse<>(items, window.page(), window.size(), total, totalPages);
  }

  private static int number(Long value) {
    return Math.toIntExact(required(value, "targetCount"));
  }

  private static UUID requireId(UUID value, String field) {
    return required(value, field);
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("MESSAGE_COMMAND_INVALID", message);
  }

  private static BusinessException dataInvalid(String message) {
    return new BusinessException("MESSAGE_DATA_INVALID", message);
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw dataInvalid("Message " + field + " is invalid");
    }
    return value;
  }

  @FunctionalInterface
  private interface MessageMutation {
    MessagePublicationEntity apply(UUID publicationId, UUID actorId);
  }

  private record PageWindow(int page, int size, long offset) {
  }
}
