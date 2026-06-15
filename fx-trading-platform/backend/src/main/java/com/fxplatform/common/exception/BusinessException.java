package com.fxplatform.common.exception;

/**
 * BusinessException 是通用基础设施模块的异常处理组件。
 */
public class BusinessException extends RuntimeException {

  private final String code;

  /**
   * 创建 BusinessException 实例。
   */
  public BusinessException(String code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * 处理 getCode 异常相关逻辑。
   */
  public String getCode() {
    return code;
  }
}
