package com.fxplatform.admin.dto.response;

/**
 * 后台通用页面动作执行结果。
 */
public record AdminFeatureOperationResponse(
    String pageKey,
    String action,
    String targetId,
    boolean success,
    String message
) {
}
