package com.fxplatform.common.response;
import cn.hutool.core.date.DateUtil;

import java.time.Instant;

/**
 * ApiResponse 承载通用基础设施模块的数据结构。
 */
public record ApiResponse<T>(
    boolean success,
    String code,
    String message,
    T data,
    Instant timestamp
) {

  /**
   * 执行 success 方法逻辑。
   */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, "OK", "success", data, DateUtil.date().toInstant());
  }

  /**
   * 执行 fail 方法逻辑。
   */
  public static <T> ApiResponse<T> fail(String code, String message) {
    return new ApiResponse<>(false, code, message, null, DateUtil.date().toInstant());
  }
}
