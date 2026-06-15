package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 后台导出任务创建请求。
 */
public record AdminExportTaskRequest(
    @NotBlank String pageKey,
    String filterJson
) {
}
