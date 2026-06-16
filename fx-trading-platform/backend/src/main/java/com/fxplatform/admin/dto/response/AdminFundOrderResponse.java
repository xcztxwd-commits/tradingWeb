package com.fxplatform.admin.dto.response;

import com.fxplatform.finance.entity.FundOrderEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 资金审核订单响应。
 */
public record AdminFundOrderResponse(
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

  public static AdminFundOrderResponse from(FundOrderEntity entity) {
    return new AdminFundOrderResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getAccountId(),
        entity.getOrderType() == null ? null : entity.getOrderType().code(),
        entity.getAmount(),
        entity.getCurrency(),
        entity.getStatus() == null ? null : entity.getStatus().code(),
        entity.getPaymentMethodId(),
        entity.getApplicantNote(),
        entity.getReviewReason(),
        entity.getReviewedBy(),
        entity.getReviewedAt(),
        entity.getFundOperationId(),
        entity.getCreatedAt());
  }
}
