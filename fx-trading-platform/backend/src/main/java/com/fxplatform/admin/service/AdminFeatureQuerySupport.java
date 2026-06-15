package com.fxplatform.admin.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Map;
import java.util.UUID;

/**
 * 后台通用列表查询工具，集中处理截图式表格的筛选、分页和排序。
 *
 * <p>所有排序字段必须来自白名单，避免前端透传任意列名进入 SQL。</p>
 */
final class AdminFeatureQuerySupport {

  private AdminFeatureQuerySupport() {
  }

  static <T> Page<T> page(AdminFeaturePageQuery query) {
    return AdminPageRequests.page(query.page(), query.size());
  }

  static <T> void applyOrder(
      QueryWrapper<T> wrapper,
      AdminFeaturePageQuery query,
      Map<String, String> sortableColumns,
      String defaultColumn,
      boolean defaultAscending
  ) {
    String column = sortableColumns.get(query.sortField());
    boolean ascending = query.ascending();
    if (StrUtil.isBlank(column)) {
      column = defaultColumn;
      ascending = defaultAscending;
    }
    if (StrUtil.isNotBlank(column)) {
      wrapper.orderBy(true, ascending, column);
    }
  }

  static <T> void likeIfPresent(QueryWrapper<T> wrapper, AdminFeaturePageQuery query, String field, String column) {
    String value = query.filter(field);
    if (StrUtil.isNotBlank(value)) {
      wrapper.like(column, value);
    }
  }

  static <T> void eqIfPresent(QueryWrapper<T> wrapper, AdminFeaturePageQuery query, String field, String column) {
    String value = query.filter(field);
    if (StrUtil.isNotBlank(value)) {
      wrapper.eq(column, value);
    }
  }

  static <T> void eqBooleanIfPresent(QueryWrapper<T> wrapper, AdminFeaturePageQuery query, String field, String column) {
    String value = query.filter(field);
    if (StrUtil.isBlank(value)) {
      return;
    }
    if (StrUtil.equalsAnyIgnoreCase(value, "true", "1", "enabled", "active")) {
      wrapper.eq(column, true);
    } else if (StrUtil.equalsAnyIgnoreCase(value, "false", "0", "disabled", "inactive")) {
      wrapper.eq(column, false);
    }
  }

  static <T> void eqUuidIfPresent(QueryWrapper<T> wrapper, AdminFeaturePageQuery query, String field, String column) {
    String value = query.filter(field);
    if (StrUtil.isBlank(value)) {
      return;
    }
    try {
      wrapper.eq(column, UUID.fromString(value));
    } catch (IllegalArgumentException ignored) {
      // 非法 UUID 不下推到数据库，避免截图搜索框误输入导致整页报错。
    }
  }
}
