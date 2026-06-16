package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminImportTaskEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台导入任务响应。
 */
public record AdminImportTaskResponse(
    UUID id,
    String pageKey,
    String status,
    String fileName,
    Integer totalRows,
    Integer successRows,
    Integer failedRows,
    UUID createdBy,
    Instant createdAt
) {

  public static AdminImportTaskResponse from(AdminImportTaskEntity entity) {
    return new AdminImportTaskResponse(
        entity.getId(),
        entity.getPageKey(),
        entity.getStatus() == null ? null : entity.getStatus().code(),
        entity.getFileName(),
        entity.getTotalRows(),
        entity.getSuccessRows(),
        entity.getFailedRows(),
        entity.getCreatedBy(),
        entity.getCreatedAt());
  }
}
