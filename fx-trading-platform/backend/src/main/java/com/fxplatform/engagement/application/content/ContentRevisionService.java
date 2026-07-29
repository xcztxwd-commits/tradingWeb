package com.fxplatform.engagement.application.content;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.domain.content.ContentDocumentSanitizer;
import com.fxplatform.engagement.domain.content.ContentDocumentSanitizer.SanitizedDocument;
import com.fxplatform.engagement.persistence.entity.ContentAssetEntity;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import com.fxplatform.engagement.persistence.entity.ContentRevisionEntity;
import com.fxplatform.engagement.persistence.enums.ContentAssetStatus;
import com.fxplatform.engagement.persistence.enums.ContentKind;
import com.fxplatform.engagement.persistence.repository.ContentAssetRepository;
import com.fxplatform.engagement.persistence.repository.ContentItemRepository;
import com.fxplatform.engagement.persistence.repository.ContentRevisionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates immutable content revisions and switches the owning item in one transaction. */
@Service
public class ContentRevisionService {

  private static final Set<String> PLATFORM_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");

  private final ContentItemRepository contentItemRepository;
  private final ContentRevisionRepository contentRevisionRepository;
  private final ContentAssetRepository contentAssetRepository;
  private final ContentDocumentSanitizer sanitizer;
  private final Clock clock;

  public ContentRevisionService(
      ContentItemRepository contentItemRepository,
      ContentRevisionRepository contentRevisionRepository,
      ContentAssetRepository contentAssetRepository,
      ContentDocumentSanitizer sanitizer,
      Clock clock
  ) {
    this.contentItemRepository = Objects.requireNonNull(contentItemRepository);
    this.contentRevisionRepository = Objects.requireNonNull(contentRevisionRepository);
    this.contentAssetRepository = Objects.requireNonNull(contentAssetRepository);
    this.sanitizer = Objects.requireNonNull(sanitizer);
    this.clock = Objects.requireNonNull(clock);
  }

  @Transactional
  public SavedContentRevision create(
      ContentKind contentKind,
      UUID actorUserId,
      ContentDraft draft
  ) {
    Objects.requireNonNull(contentKind, "contentKind");
    Objects.requireNonNull(actorUserId, "actorUserId");
    PreparedDraft prepared = prepare(draft);
    Instant now = clock.instant();

    ContentItemEntity item = new ContentItemEntity();
    item.setId(UUID.randomUUID());
    item.setContentKind(contentKind);
    item.setCreatedAt(now);
    item.setUpdatedAt(now);
    requireSingleWrite(contentItemRepository.insert(item));

    ContentRevisionEntity revision = revision(item.getId(), 1, actorUserId, prepared, now);
    requireSingleWrite(contentRevisionRepository.insert(revision));

    item.setCurrentRevisionId(revision.getId());
    item.setUpdatedAt(now);
    requireSingleWrite(contentItemRepository.updateById(item));
    return saved(item, revision);
  }

  @Transactional
  public SavedContentRevision revise(
      UUID contentItemId,
      UUID actorUserId,
      ContentDraft draft
  ) {
    Objects.requireNonNull(contentItemId, "contentItemId");
    Objects.requireNonNull(actorUserId, "actorUserId");
    PreparedDraft prepared = prepare(draft);
    ContentItemEntity item = contentItemRepository.selectByIdForUpdate(contentItemId);
    if (item == null) {
      throw new BusinessException("CONTENT_ITEM_NOT_FOUND", "Content item not found");
    }
    UUID currentRevisionId = item.getCurrentRevisionId();
    ContentRevisionEntity current = currentRevisionId == null
        ? null
        : contentRevisionRepository.selectById(currentRevisionId);
    if (current == null || !contentItemId.equals(current.getContentItemId())) {
      throw new BusinessException(
          "CONTENT_REVISION_INVALID", "Current revision does not belong to the content item");
    }

    Instant now = clock.instant();
    ContentRevisionEntity revision = revision(
        contentItemId,
        current.getRevisionNo() + 1,
        actorUserId,
        prepared,
        now);
    requireSingleWrite(contentRevisionRepository.insert(revision));

    item.setCurrentRevisionId(revision.getId());
    item.setUpdatedAt(now);
    requireSingleWrite(contentItemRepository.updateById(item));
    return saved(item, revision);
  }

  private PreparedDraft prepare(ContentDraft draft) {
    if (draft == null || draft.title() == null || draft.title().isBlank()) {
      throw new BusinessException("CONTENT_TITLE_INVALID", "Content title is required");
    }
    String title = draft.title().trim();
    if (title.length() > 200) {
      throw new BusinessException("CONTENT_TITLE_INVALID", "Content title is too long");
    }
    SanitizedDocument body = sanitizer.sanitize(draft.bodyDocument());
    PreparedCta cta = prepareCta(draft.cta());
    validateAssets(body.assetIds(), draft.coverAssetId());
    return new PreparedDraft(title, body, draft.coverAssetId(), cta);
  }

  private void validateAssets(Set<UUID> bodyAssetIds, UUID coverAssetId) {
    Set<UUID> assetIds = new HashSet<>(bodyAssetIds);
    if (coverAssetId != null) {
      assetIds.add(coverAssetId);
    }
    for (UUID assetId : assetIds) {
      ContentAssetEntity asset = contentAssetRepository.selectById(assetId);
      if (asset == null || asset.getStatus() != ContentAssetStatus.ACTIVE
          || !PLATFORM_IMAGE_TYPES.contains(asset.getMimeType())) {
        throw new BusinessException(
            "CONTENT_ASSET_UNAVAILABLE", "Content image asset is not active");
      }
    }
  }

  private PreparedCta prepareCta(ContentCta cta) {
    if (cta == null) {
      return null;
    }
    if (cta.label() == null || cta.label().isBlank() || cta.label().trim().length() > 80
        || cta.label().indexOf('<') >= 0 || cta.label().indexOf('>') >= 0) {
      throw new BusinessException("CONTENT_CTA_INVALID", "CTA label is invalid");
    }
    String params = sanitizer.sanitizeRouteParams(
        cta.routeKey(), cta.paramsJson(), "CONTENT_CTA_INVALID");
    return new PreparedCta(cta.label().trim(), cta.routeKey(), params);
  }

  private static ContentRevisionEntity revision(
      UUID contentItemId,
      int revisionNo,
      UUID actorUserId,
      PreparedDraft draft,
      Instant now
  ) {
    ContentRevisionEntity revision = new ContentRevisionEntity();
    revision.setId(UUID.randomUUID());
    revision.setContentItemId(contentItemId);
    revision.setRevisionNo(revisionNo);
    revision.setTitle(draft.title());
    revision.setBodyDocument(draft.body().documentJson());
    revision.setSanitizedHtml(draft.body().html());
    revision.setCoverAssetId(draft.coverAssetId());
    if (draft.cta() != null) {
      revision.setCtaLabel(draft.cta().label());
      revision.setCtaRouteKey(draft.cta().routeKey());
      revision.setCtaParams(draft.cta().paramsJson());
    }
    revision.setCreatedBy(actorUserId);
    revision.setCreatedAt(now);
    return revision;
  }

  private static SavedContentRevision saved(
      ContentItemEntity item,
      ContentRevisionEntity revision
  ) {
    return new SavedContentRevision(
        item.getId(),
        revision.getId(),
        revision.getRevisionNo(),
        revision.getTitle(),
        revision.getSanitizedHtml());
  }

  private static void requireSingleWrite(int affectedRows) {
    if (affectedRows != 1) {
      throw new BusinessException("CONTENT_WRITE_CONFLICT", "Content write did not affect one row");
    }
  }

  public record ContentDraft(
      String title,
      String bodyDocument,
      UUID coverAssetId,
      ContentCta cta
  ) {
  }

  public record ContentCta(String label, String routeKey, String paramsJson) {
  }

  public record SavedContentRevision(
      UUID contentItemId,
      UUID revisionId,
      int revisionNo,
      String title,
      String sanitizedHtml
  ) {
  }

  private record PreparedDraft(
      String title,
      SanitizedDocument body,
      UUID coverAssetId,
      PreparedCta cta
  ) {
  }

  private record PreparedCta(String label, String routeKey, String paramsJson) {
  }
}
