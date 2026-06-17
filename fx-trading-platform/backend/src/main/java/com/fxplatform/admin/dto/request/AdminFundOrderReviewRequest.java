package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 资金审核订单审核请求。
 */
public record AdminFundOrderReviewRequest(
    @NotBlank String status,
    @NotBlank String reason,
    String confirmationText
) {
  public AdminFundOrderReviewRequest(String status, String reason) {
    this(status, reason, null);
  }
}
