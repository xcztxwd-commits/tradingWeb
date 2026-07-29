package com.fxplatform.engagement.admin.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PopupPolicyAdminDtos {

  private PopupPolicyAdminDtos() {
  }

  public record PopupPolicyUpdateRequest(
      @Min(1) @Max(100) int maxSequentialPopups,
      @Min(1) @Max(3650) int deliveryRetentionDays,
      @NotBlank @Size(max = 500) String reason
  ) {
  }

  public record PopupPolicyResponse(
      int maxSequentialPopups,
      int deliveryRetentionDays
  ) {
  }
}
