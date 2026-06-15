package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * AdminPageRequests 统一创建后台分页请求，避免各个查询服务重复处理页码边界。
 */
final class AdminPageRequests {

  /** 后台列表允许的最大单页数量，防止管理表格一次加载过多数据。 */
  private static final int MAX_PAGE_SIZE = 100;

  private AdminPageRequests() {
  }

  /**
   * 创建 MyBatis-Plus 分页对象。
   *
   * <p>外部 API 的 page 从 0 开始，MyBatis-Plus 的 current 从 1 开始，这里统一做转换。</p>
   */
  static <T> Page<T> page(int page, int size) {
    return Page.of((long) normalizePage(page) + 1, normalizeSize(size));
  }

  /** 归一化页码，避免负数页码穿透到 Mapper。 */
  static int normalizePage(int page) {
    return Math.max(0, page);
  }

  /** 归一化页大小，限制在 1 到 100。 */
  static int normalizeSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }
}
