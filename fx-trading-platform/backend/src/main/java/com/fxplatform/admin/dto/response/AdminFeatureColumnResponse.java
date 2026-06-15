package com.fxplatform.admin.dto.response;

/**
 * 后台页面表格列定义。
 */
public record AdminFeatureColumnResponse(
    String key,
    String label,
    boolean sortable
) {
}
