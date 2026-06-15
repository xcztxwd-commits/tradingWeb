package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 后台批量操作任务创建请求。
 */
public record AdminBatchOperationRequest(
    @NotBlank String pageKey,
    @NotBlank String operation,
    List<String> rowIds,
    String reason
) {
}
