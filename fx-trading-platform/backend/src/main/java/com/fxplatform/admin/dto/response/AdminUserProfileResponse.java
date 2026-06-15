package com.fxplatform.admin.dto.response;

import com.fxplatform.auth.entity.UserProfileEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台会员详情资料响应。
 */
public record AdminUserProfileResponse(
    UUID id,
    UUID userId,
    String realName,
    String phone,
    String address,
    String remark,
    Instant updatedAt
) {

  public static AdminUserProfileResponse from(UserProfileEntity entity) {
    if (entity == null) {
      return null;
    }
    return new AdminUserProfileResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getRealName(),
        entity.getPhone(),
        entity.getAddress(),
        entity.getRemark(),
        entity.getUpdatedAt());
  }
}
