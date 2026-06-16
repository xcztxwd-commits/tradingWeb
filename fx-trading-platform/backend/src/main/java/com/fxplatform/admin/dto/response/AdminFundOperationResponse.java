package com.fxplatform.admin.dto.response;

import com.fxplatform.finance.entity.AdminFundOperationEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminFundOperationResponse 是后台资金操作响应 DTO。
 *
 * @param id 操作记录 ID。
 * @param accountId 交易账户 ID。
 * @param userId 账户所属用户 ID。
 * @param operationType 操作类型，例如 DEPOSIT、WITHDRAWAL、ADJUSTMENT。
 * @param amount 签名金额，正数增加余额，负数减少余额。
 * @param currency 币种。
 * @param beforeBalance 操作前余额。
 * @param afterBalance 操作后余额。
 * @param status 操作状态。
 * @param adminUserId 执行操作的管理员 ID。
 * @param reason 操作原因。
 * @param paymentMethodId 关联支付方式 ID。
 * @param note 审核备注。
 * @param idempotencyKey 幂等键。
 * @param createdAt 创建时间。
 */
public record AdminFundOperationResponse(
    UUID id,
    UUID accountId,
    UUID userId,
    String operationType,
    BigDecimal amount,
    String currency,
    BigDecimal beforeBalance,
    BigDecimal afterBalance,
    String status,
    UUID adminUserId,
    String reason,
    UUID paymentMethodId,
    String note,
    String idempotencyKey,
    Instant createdAt
) {

  /**
   * 将资金操作实体映射为后台 DTO。
   */
  public static AdminFundOperationResponse from(AdminFundOperationEntity entity) {
    return new AdminFundOperationResponse(
        entity.getId(),
        entity.getAccountId(),
        entity.getUserId(),
        entity.getOperationType() == null ? null : entity.getOperationType().code(),
        entity.getAmount(),
        entity.getCurrency(),
        entity.getBeforeBalance(),
        entity.getAfterBalance(),
        entity.getStatus() == null ? null : entity.getStatus().code(),
        entity.getAdminUserId(),
        entity.getReason(),
        entity.getPaymentMethodId(),
        entity.getNote(),
        entity.getIdempotencyKey(),
        entity.getCreatedAt());
  }
}
