package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 后台导入任务创建请求。
 */
public record AdminImportTaskRequest(
    @NotBlank String pageKey,
    @NotBlank String fileName
) {
}
