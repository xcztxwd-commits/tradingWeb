package com.fxplatform.admin.dto.response;

import com.fxplatform.content.entity.ContentMessageEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminMessageResponse 是后台站内消息响应 DTO。
 *
 * @param id 消息 ID。
 * @param targetUserId 目标用户 ID，空值表示全站消息。
 * @param title 消息标题。
 * @param body 消息正文。
 * @param messageType 消息类型。
 * @param status 消息状态。
 * @param sentBy 发送管理员 ID。
 * @param publishedAt 发布时间。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record AdminMessageResponse(
    UUID id,
    UUID targetUserId,
    String title,
    String body,
    String messageType,
    String status,
    UUID sentBy,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt
) {

  /**
   * 将站内消息实体映射为后台 DTO。
   */
  public static AdminMessageResponse from(ContentMessageEntity entity) {
    return new AdminMessageResponse(
        entity.getId(),
        entity.getTargetUserId(),
        entity.getTitle(),
        entity.getBody(),
        entity.getMessageType(),
        entity.getStatus(),
        entity.getSentBy(),
        entity.getPublishedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
