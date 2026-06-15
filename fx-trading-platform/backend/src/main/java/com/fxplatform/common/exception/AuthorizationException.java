package com.fxplatform.common.exception;

/**
 * AuthorizationException 表示当前用户无权访问目标资源。
 */
public class AuthorizationException extends BusinessException {

  public AuthorizationException(String code, String message) {
    super(code, message);
  }
}
