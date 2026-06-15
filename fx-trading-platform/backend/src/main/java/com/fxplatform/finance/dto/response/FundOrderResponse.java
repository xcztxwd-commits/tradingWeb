package com.fxplatform.finance.dto.response;

import com.fxplatform.finance.entity.FundOrderEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * FundOrderResponse exposes user-visible fund application status.
 */
public record FundOrderResponse(
    UUID id,
    UUID userId,
    UUID accountId,
    String orderType,
    BigDecimal amount,
    String currency,
    String status,
    UUID paymentMethodId,
    String note,
    String reviewReason,
    UUID reviewedBy,
    Instant reviewedAt,
    UUID fundOperationId,
    Instant createdAt
) {

  public static FundOrderResponse from(FundOrderEntity entity) {
    return new FundOrderResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getAccountId(),
        entity.getOrderType(),
        entity.getAmount(),
        entity.getCurrency(),
        entity.getStatus(),
        entity.getPaymentMethodId(),
        entity.getApplicantNote(),
        entity.getReviewReason(),
        entity.getReviewedBy(),
        entity.getReviewedAt(),
        entity.getFundOperationId(),
        entity.getCreatedAt());
  }
}
