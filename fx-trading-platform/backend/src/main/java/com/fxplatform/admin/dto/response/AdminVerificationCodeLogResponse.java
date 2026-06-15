package com.fxplatform.admin.dto.response;

import com.fxplatform.audit.entity.VerificationCodeLogEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台验证码发送记录响应。
 */
public record AdminVerificationCodeLogResponse(
    UUID id,
    String scene,
    String account,
    String channel,
    String code,
    String status,
    String errorMessage,
    Instant createdAt
) {

  public static AdminVerificationCodeLogResponse from(VerificationCodeLogEntity entity) {
    return new AdminVerificationCodeLogResponse(
        entity.getId(),
        entity.getScene(),
        entity.getAccount(),
        entity.getChannel(),
        entity.getCode(),
        entity.getStatus(),
        entity.getErrorMessage(),
        entity.getCreatedAt());
  }
}
