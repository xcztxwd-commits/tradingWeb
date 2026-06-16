package com.fxplatform.admin.dto.response;

import com.fxplatform.auth.entity.KycApplicationEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台 KYC 申请响应。
 */
public record AdminKycApplicationResponse(
    UUID id,
    UUID userId,
    String realName,
    String documentType,
    String documentNo,
    String frontImageUrl,
    String backImageUrl,
    String status,
    String reviewReason,
    UUID reviewedBy,
    Instant reviewedAt,
    Instant createdAt
) {

  public static AdminKycApplicationResponse from(KycApplicationEntity entity) {
    return new AdminKycApplicationResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getRealName(),
        entity.getDocumentType(),
        entity.getDocumentNo(),
        entity.getFrontImageUrl(),
        entity.getBackImageUrl(),
        entity.getStatus() == null ? null : entity.getStatus().code(),
        entity.getReviewReason(),
        entity.getReviewedBy(),
        entity.getReviewedAt(),
        entity.getCreatedAt());
  }
}
