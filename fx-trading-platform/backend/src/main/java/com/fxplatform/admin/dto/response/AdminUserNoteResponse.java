package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminUserNoteEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminUserNoteResponse 是后台用户备注响应 DTO。
 *
 * @param id 备注 ID。
 * @param userId 被备注的用户 ID。
 * @param adminUserId 添加备注的管理员 ID。
 * @param note 备注正文。
 * @param createdAt 创建时间。
 */
public record AdminUserNoteResponse(
    UUID id,
    UUID userId,
    UUID adminUserId,
    String note,
    Instant createdAt
) {

  /**
   * 将备注实体映射为后台备注 DTO。
   */
  public static AdminUserNoteResponse from(AdminUserNoteEntity entity) {
    return new AdminUserNoteResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getAdminUserId(),
        entity.getNote(),
        entity.getCreatedAt());
  }
}
