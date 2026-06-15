package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AdminKycReviewRequest 是后台 KYC 审核请求。
 *
 * @param kycStatus 审核后的 KYC 状态。
 * @param reason 执行审核的原因。
 * @param reviewNote 审核备注，记录证据或驳回说明。
 */
public record AdminKycReviewRequest(
    @NotBlank String kycStatus,
    @NotBlank String reason,
    @NotBlank String reviewNote
) {
}
