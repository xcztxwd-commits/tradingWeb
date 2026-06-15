package com.fxplatform.admin.service;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import java.util.HashMap;
import java.util.Map;

/**
 * 后台功能页统一查询参数。
 *
 * <p>前端通用列表页会以 page/size/sortField/sortDirection/filter.xxx 形式提交查询条件；
 * 这里统一做边界归一、空值过滤和排序方向解析，避免各个 Controller/Service 重复处理。</p>
 */
public record AdminFeaturePageQuery(
    int page,
    int size,
    Map<String, String> filters,
    String sortField,
    boolean ascending
) {

  private static final String FILTER_PREFIX = "filter.";

  public AdminFeaturePageQuery {
    page = AdminPageRequests.normalizePage(page);
    size = AdminPageRequests.normalizeSize(size);
    filters = Map.copyOf(filters == null ? Map.of() : filters);
    sortField = StrUtil.trimToEmpty(sortField);
  }

  /**
   * 从 HTTP 查询参数中提取后台列表协议。
   */
  public static AdminFeaturePageQuery from(
      int page,
      int size,
      String sortField,
      String sortDirection,
      Map<String, String> requestParams
  ) {
    Map<String, String> filters = new HashMap<>();
    for (Map.Entry<String, String> entry : (requestParams == null ? Map.<String, String>of() : requestParams).entrySet()) {
      String key = entry.getKey();
      String value = StrUtil.trimToEmpty(entry.getValue());
      if (StrUtil.startWith(key, FILTER_PREFIX) && StrUtil.isNotBlank(value)) {
        filters.put(StrUtil.removePrefix(key, FILTER_PREFIX), value);
      }
    }
    boolean ascending = !StrUtil.equalsIgnoreCase(StrUtil.trimToEmpty(sortDirection), "desc");
    return new AdminFeaturePageQuery(page, size, filters, sortField, ascending);
  }

  /**
   * 读取单个过滤条件，未传入时返回空字符串，方便 Service 层直接做空值判断。
   */
  public String filter(String key) {
    return StrUtil.trimToEmpty(MapUtil.getStr(filters, key));
  }
}
