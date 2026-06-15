package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 会员银行卡或钱包保存请求。
 */
public record AdminMemberPaymentAccountRequest(
    @NotBlank String accountType,
    @NotBlank String currency,
    String network,
    String holderName,
    String bankName,
    String branchName,
    String bankCode,
    @NotBlank String accountNo,
    @NotNull Boolean enabled
) {
}
