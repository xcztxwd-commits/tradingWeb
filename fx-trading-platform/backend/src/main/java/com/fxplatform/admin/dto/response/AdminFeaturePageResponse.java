package com.fxplatform.admin.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 后台管理页面的完整渲染配置。
 */
public record AdminFeaturePageResponse(
    String key,
    String title,
    String group,
    List<AdminFeatureFieldResponse> fields,
    List<AdminFeatureColumnResponse> columns,
    List<AdminFeatureActionResponse> toolbarActions,
    List<AdminFeatureActionResponse> rowActions,
    List<Map<String, Object>> rows
) {
}
