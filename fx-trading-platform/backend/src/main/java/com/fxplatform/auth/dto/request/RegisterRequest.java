package com.fxplatform.auth.dto.request;

/**
 * RegisterRequest 承载认证授权模块的数据结构。
 */
public record RegisterRequest(
    String email,
    String phone,
    String password
) {
}
