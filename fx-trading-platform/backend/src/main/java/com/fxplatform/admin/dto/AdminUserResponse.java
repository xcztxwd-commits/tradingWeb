package com.fxplatform.admin.dto;

import com.fxplatform.auth.entity.UserEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminUserResponse 承载后台管理模块的数据结构。
 */
public record AdminUserResponse(
    UUID id,
    String email,
    String phone,
    String status,
    String role,
    String kycStatus,
    String riskLevel,
    Instant createdAt
) {

  /**
   * 执行 from 方法逻辑。
   */
  public static AdminUserResponse from(UserEntity user) {
    return new AdminUserResponse(
        user.getId(),
        user.getEmail(),
        user.getPhone(),
        user.getStatus().name(),
        user.getRole().name(),
        user.getKycStatus() == null ? null : user.getKycStatus().code(),
        user.getRiskLevel(),
        user.getCreatedAt());
  }
}
