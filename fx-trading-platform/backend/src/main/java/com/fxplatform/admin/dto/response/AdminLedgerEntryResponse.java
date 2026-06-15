package com.fxplatform.admin.dto.response;

import com.fxplatform.ledger.entity.LedgerEntryEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminLedgerEntryResponse 是后台资金流水响应 DTO。
 *
 * @param id 流水 ID。
 * @param accountId 账户 ID。
 * @param entryType 流水类型。
 * @param amount 流水金额。
 * @param balanceAfter 记账后的账户余额。
 * @param currency 币种。
 * @param referenceType 关联业务类型。
 * @param referenceId 关联业务 ID。
 * @param description 流水说明。
 * @param createdAt 创建时间。
 */
public record AdminLedgerEntryResponse(
    UUID id,
    UUID accountId,
    String entryType,
    BigDecimal amount,
    BigDecimal balanceAfter,
    String currency,
    String referenceType,
    UUID referenceId,
    String description,
    Instant createdAt
) {

  /**
   * 将资金流水实体映射为后台 DTO。
   */
  public static AdminLedgerEntryResponse from(LedgerEntryEntity entity) {
    return new AdminLedgerEntryResponse(
        entity.getId(),
        entity.getAccountId(),
        entity.getEntryType().name(),
        entity.getAmount(),
        entity.getBalanceAfter(),
        entity.getCurrency(),
        entity.getReferenceType(),
        entity.getReferenceId(),
        entity.getDescription(),
        entity.getCreatedAt());
  }
}
