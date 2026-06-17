package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * AdminBalanceAdjustmentRequest 是后台余额调整请求 DTO。
 *
 * @param delta 调整金额，正数增加余额，负数减少余额。
 * @param reason 管理员调整余额的业务原因。
 * @param note 补充说明或凭证摘要，可为空。
 * @param idempotencyKey 前端生成的幂等键，用于后续接入命令幂等表。
 */
public record AdminBalanceAdjustmentRequest(
    @NotNull BigDecimal delta,
    @NotBlank String reason,
    String note,
    String idempotencyKey,
    String confirmationText
) {
  public AdminBalanceAdjustmentRequest(
      BigDecimal delta,
      String reason,
      String note,
      String idempotencyKey
  ) {
    this(delta, reason, note, idempotencyKey, null);
  }
}
