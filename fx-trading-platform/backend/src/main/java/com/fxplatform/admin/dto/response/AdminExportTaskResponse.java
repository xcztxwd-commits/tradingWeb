package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminExportTaskEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台导出任务响应。
 */
public record AdminExportTaskResponse(
    UUID id,
    String pageKey,
    String status,
    String filterJson,
    String fileUrl,
    UUID createdBy,
    Instant createdAt
) {

  public static AdminExportTaskResponse from(AdminExportTaskEntity entity) {
    return new AdminExportTaskResponse(
        entity.getId(),
        entity.getPageKey(),
        entity.getStatus() == null ? null : entity.getStatus().code(),
        entity.getFilterJson(),
        entity.getFileUrl(),
        entity.getCreatedBy(),
        entity.getCreatedAt());
  }
}
