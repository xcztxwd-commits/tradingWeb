package com.fxplatform.admin.dto.response;

import com.fxplatform.finance.entity.MemberPaymentAccountEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台会员银行卡/钱包响应。
 */
public record AdminMemberPaymentAccountResponse(
    UUID id,
    UUID userId,
    String accountType,
    String currency,
    String network,
    String holderName,
    String bankName,
    String branchName,
    String bankCode,
    String accountNo,
    Boolean enabled,
    Instant createdAt
) {

  public static AdminMemberPaymentAccountResponse from(MemberPaymentAccountEntity entity) {
    return new AdminMemberPaymentAccountResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getAccountType(),
        entity.getCurrency(),
        entity.getNetwork(),
        entity.getHolderName(),
        entity.getBankName(),
        entity.getBranchName(),
        entity.getBankCode(),
        entity.getAccountNo(),
        entity.getEnabled(),
        entity.getCreatedAt());
  }
}
