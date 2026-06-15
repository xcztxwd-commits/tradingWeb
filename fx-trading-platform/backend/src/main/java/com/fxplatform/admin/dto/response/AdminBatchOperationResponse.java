package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminBatchOperationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 后台批量操作任务响应。
 */
public record AdminBatchOperationResponse(
    UUID id,
    String pageKey,
    String operation,
    List<String> rowIds,
    String reason,
    String status,
    UUID createdBy,
    Instant createdAt
) {

  public static AdminBatchOperationResponse from(AdminBatchOperationEntity entity) {
    return new AdminBatchOperationResponse(
        entity.getId(),
        entity.getPageKey(),
        entity.getOperation(),
        AdminTableColumnPreferenceResponse.parseStringList(entity.getRowIds()),
        entity.getReason(),
        entity.getStatus(),
        entity.getCreatedBy(),
        entity.getCreatedAt());
  }
}
