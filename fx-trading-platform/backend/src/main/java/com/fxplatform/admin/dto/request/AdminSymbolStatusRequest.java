package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AdminSymbolStatusRequest 是后台启停产品品种的请求。
 *
 * @param enabled 是否启用该品种。
 * @param reason 启停原因，用于审计和产品事件记录。
 */
public record AdminSymbolStatusRequest(
    @NotNull Boolean enabled,
    @NotBlank String reason,
    String confirmationText
) {
  public AdminSymbolStatusRequest(Boolean enabled, String reason) {
    this(enabled, reason, null);
  }
}
