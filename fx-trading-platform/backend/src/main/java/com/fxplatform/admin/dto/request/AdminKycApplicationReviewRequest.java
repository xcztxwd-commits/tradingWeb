package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * KYC 申请审核请求。
 */
public record AdminKycApplicationReviewRequest(
    @NotBlank String status,
    @NotBlank String reason
) {
}
