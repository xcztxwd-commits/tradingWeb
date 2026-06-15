package com.fxplatform.admin.dto.response;

import com.fxplatform.audit.entity.AuditLogEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminAuditLogResponse 是后台审计日志的只读响应 DTO。
 *
 * @param id 审计日志 ID。
 * @param actorUserId 执行操作的管理员用户 ID。
 * @param action 审计动作编码，例如 ADMIN_USER_STATUS_UPDATE。
 * @param targetType 被操作目标类型，例如 USER、ORDER、POSITION。
 * @param targetId 被操作目标 ID，保留字符串类型以兼容不同目标主键。
 * @param requestId 请求链路 ID，用于串联接口日志和审计日志。
 * @param details 结构化 JSON 明细，记录原因、前后值和执行结果。
 * @param createdAt 审计日志创建时间。
 */
public record AdminAuditLogResponse(
    UUID id,
    UUID actorUserId,
    String action,
    String targetType,
    String targetId,
    String requestId,
    String details,
    Instant createdAt
) {

  /**
   * 将审计日志实体映射为后台 DTO，避免 Controller 直接返回实体。
   */
  public static AdminAuditLogResponse from(AuditLogEntity entity) {
    return new AdminAuditLogResponse(
        entity.getId(),
        entity.getActorUserId(),
        entity.getAction(),
        entity.getTargetType(),
        entity.getTargetId(),
        entity.getRequestId(),
        entity.getDetails(),
        entity.getCreatedAt());
  }
}
