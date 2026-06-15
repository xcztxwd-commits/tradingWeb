package com.fxplatform.admin.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;

/**
 * AdminPageResponse 是后台管理列表接口的统一分页响应。
 *
 * @param items 当前页 DTO 数据，不直接暴露数据库实体
 * @param page 当前页码，从 0 开始
 * @param size 当前页请求大小
 * @param total 符合条件的总记录数
 * @param totalPages 符合条件的总页数
 * @param <T> 当前页 DTO 类型
 */
public record AdminPageResponse<T>(
    List<T> items,
    int page,
    int size,
    long total,
    int totalPages
) {

  /**
   * 从 MyBatis-Plus 分页结果构造后台分页响应。
   */
  public static <T> AdminPageResponse<T> from(IPage<T> page) {
    return new AdminPageResponse<>(
        page.getRecords(),
        Math.max(0, (int) page.getCurrent() - 1),
        (int) page.getSize(),
        page.getTotal(),
        (int) page.getPages());
  }
}
