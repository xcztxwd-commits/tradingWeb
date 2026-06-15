package com.fxplatform.admin.dto.response;

import com.fxplatform.account.entity.TradingAccountEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminAccountResponse 是后台账户列表和详情的响应 DTO。
 *
 * @param id 账户 ID。
 * @param userId 账户所属用户 ID。
 * @param accountType 账户类型，例如 DEMO 或 LIVE。
 * @param baseCurrency 账户基础币种。
 * @param balance 账户余额。
 * @param equity 账户权益。
 * @param usedMargin 已用保证金。
 * @param freeMargin 可用保证金。
 * @param marginLevel 保证金比例。
 * @param leverage 账户杠杆。
 * @param status 账户状态。
 * @param createdAt 账户创建时间。
 * @param updatedAt 账户更新时间。
 */
public record AdminAccountResponse(
    UUID id,
    UUID userId,
    String accountType,
    String baseCurrency,
    BigDecimal balance,
    BigDecimal equity,
    BigDecimal usedMargin,
    BigDecimal freeMargin,
    BigDecimal marginLevel,
    Integer leverage,
    String status,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将账户实体映射为后台账户 DTO。
   */
  public static AdminAccountResponse from(TradingAccountEntity entity) {
    return new AdminAccountResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getAccountType().name(),
        entity.getBaseCurrency(),
        entity.getBalance(),
        entity.getEquity(),
        entity.getUsedMargin(),
        entity.getFreeMargin(),
        entity.getMarginLevel(),
        entity.getLeverage(),
        entity.getStatus().name(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
