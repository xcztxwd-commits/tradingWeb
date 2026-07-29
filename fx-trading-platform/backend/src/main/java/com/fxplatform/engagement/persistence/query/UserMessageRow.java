package com.fxplatform.engagement.persistence.query;

import com.fxplatform.engagement.persistence.enums.MessageReadSource;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import java.time.Instant;
import java.util.UUID;

/** Current sanitized content plus the calling user's receipt projection. */
public record UserMessageRow(
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
    Instant readAt,
    MessageReadSource readSource
) {
}
