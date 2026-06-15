package com.fxplatform.admin.dto.request;

import java.util.Map;

/**
 * 后台通用页面动作请求。
 */
public record AdminFeatureOperationRequest(
    String action,
    String rowId,
    String reason,
    Map<String, Object> payload
) {
}
