package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * KYC 申请资料请求。
 */
public record AdminKycApplicationRequest(
    @NotBlank String realName,
    @NotBlank String documentType,
    @NotBlank String documentNo,
    String frontImageUrl,
    String backImageUrl
) {
}
