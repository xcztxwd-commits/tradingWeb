package com.fxplatform.auth.dto.request;

/**
 * LoginRequest 承载认证授权模块的数据结构。
 */
public record LoginRequest(
    String email,
    String password
) {
}
