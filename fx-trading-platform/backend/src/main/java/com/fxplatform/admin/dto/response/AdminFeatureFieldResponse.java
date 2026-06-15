package com.fxplatform.admin.dto.response;

import java.util.List;

/**
 * 后台页面筛选或表单字段定义。
 */
public record AdminFeatureFieldResponse(
    String key,
    String label,
    String component,
    List<AdminFeatureOptionResponse> options
) {
}
