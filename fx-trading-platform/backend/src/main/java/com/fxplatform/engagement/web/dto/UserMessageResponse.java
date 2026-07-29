package com.fxplatform.engagement.web.dto;

import com.fxplatform.engagement.persistence.enums.MessageReadSource;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.query.UserMessageRow;
import java.time.Instant;
import java.util.UUID;

public record UserMessageResponse(
    UUID publicationId,
    UUID contentItemId,
    UUID revisionId,
    MessageSourceType sourceType,
    String category,
    Instant sentAt,
    Instant deliveredAt,
    String title,
    String sanitizedHtml,
    UUID coverAssetId,
    String ctaLabel,
    String ctaRouteKey,
    String ctaParams,
    boolean unread,
    Instant readAt,
    MessageReadSource readSource
) {

  public static UserMessageResponse from(UserMessageRow row) {
    return new UserMessageResponse(
        row.publicationId(),
        row.contentItemId(),
        row.revisionId(),
        row.sourceType(),
        row.category(),
        row.sentAt(),
        row.deliveredAt(),
        row.title(),
        row.sanitizedHtml(),
        row.coverAssetId(),
        row.ctaLabel(),
        row.ctaRouteKey(),
        row.ctaParams(),
        row.readAt() == null,
        row.readAt(),
        row.readSource());
  }
}
