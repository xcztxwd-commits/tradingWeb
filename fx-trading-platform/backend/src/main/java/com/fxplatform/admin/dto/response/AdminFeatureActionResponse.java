package com.fxplatform.admin.dto.response;

/**
 * 后台页面按钮或行操作定义。
 */
public record AdminFeatureActionResponse(
    String key,
    String label,
    String type
) {
}
